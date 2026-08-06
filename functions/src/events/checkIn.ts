/**
 * events.checkIn — callable (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-checkIn`.
 *
 * "HOW DO YOU KNOW SOMEONE IS AT THE MEETING?" — GEOFENCE + DWELL.
 *
 * A member proves attendance by being measurably present, twice, ten minutes
 * apart:
 *
 *   - each sample must be within EVENT_GEOFENCE_RADIUS_METERS (150 m) of the
 *     event's own coordinates, with the distance computed SERVER-SIDE via the
 *     shared haversine (crownHunt/crown-hunt-geo.ts — imported, never forked)
 *     and the reported GPS accuracy buffered conservatively;
 *   - each sample must fall inside [startsAt - 30 min, endsAt + 30 min];
 *   - attendance needs >= 2 qualifying samples at least 10 minutes apart and
 *     >= 10 minutes of cumulative dwell.
 *
 * WHY TWO EXPLICIT TAPS, NOT BACKGROUND SAMPLING.
 *
 * The client calls this once when the member arrives and once when the app
 * asks "still here?" about ten minutes later. It is not a background
 * position stream, and the callable does not require one:
 *
 *   - BATTERY: two one-shot GPS fixes versus a continuous fix for the length
 *     of a car meet. At a three-hour Sunday meet that difference is the whole
 *     ballgame;
 *   - PERMISSIONS: no background-location permission is needed, so members
 *     are not asked for the scariest permission Android has in order to
 *     collect 50 points;
 *   - PRIVACY: two coordinates, both next to a published event, versus a
 *     continuous trail of where someone was. The samples are nonetheless real
 *     positions of a real member, so they are also BOUNDED IN TIME: the
 *     attendance record carries an `expireAt` and a Firestore TTL policy
 *     deletes it after ATTENDANCE_EVIDENCE_RETENTION_MS (90 days), which is
 *     long enough to settle a dispute about the award and no longer. Scoped
 *     to one event, readable by the owning member AND by admins (the audit
 *     path — see the firestore.rules block for eventAttendance), and not
 *     kept forever;
 *   - HONESTY: a tap is a deliberate statement ("I am here"), which is what
 *     is being rewarded. Silent background credit for leaving an app open in
 *     a nearby car park is not.
 *
 * The evaluation is nonetheless written against a LIST of samples, so a
 * client that does post periodically while the event screen is foregrounded
 * gets the same answer with no server change.
 *
 * ANTI-FRAUD. Every sample runs through the SAME risk pipeline as a Kronjakt
 * claim (crownHunt/crown-hunt-risk.ts evaluateClaimRisk): position freshness,
 * implausible jump against the last trusted RTDB position, GPS accuracy,
 * attempts per minute. A score at or above RISK_REVIEW_THRESHOLD records the
 * sample as rejected and it never counts toward dwell. The award itself is
 * NOT made here — the callable only records evidence and flips `verified`;
 * points are credited by the points-onAttendanceVerified trigger.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { adminRtdb, db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import {
  isPlausibleJump,
  isPositionFresh,
  isValidCoordinate,
} from '../crownHunt/crown-hunt-geo';
import { evaluateClaimRisk } from '../crownHunt/crown-hunt-risk';
import {
  ECONOMY_RATE_LIMIT_COLLECTION,
  EVENT_ATTENDANCE_COLLECTION,
  EVENT_ATTENDANCE_RISK_COLLECTION,
  MAX_ATTENDANCE_SAMPLES,
  REQUIRED_DWELL_MS,
  attendanceDocId,
  attendanceEvidenceExpiry,
  attendanceWindow,
  economyRateLimitDocId,
  economyRateLimitExpiry,
  evaluateAttendance,
  isSampleInsideFence,
  isUnderEconomyRateLimit,
  parseCheckInInput,
  parseStoredAttendanceSamples,
  readCount,
  type AttendanceSample,
} from '../points/points-economy-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

const RATE_LIMIT_ACTION = 'eventCheckIn';

export type CheckInResult =
  | 'recorded'
  | 'verified'
  | 'already_verified'
  | 'outside_geofence'
  | 'outside_window'
  | 'position_too_old'
  | 'risk_review'
  | 'event_not_checkinable';

export interface CheckInResponse {
  result: CheckInResult;
  /** True once the geofence + dwell evidence is complete. */
  verified: boolean;
  /** Cumulative server-measured dwell so far, in seconds. */
  dwellSeconds: number;
  /** Dwell still required before the next sample can verify, in seconds. */
  remainingDwellSeconds: number;
  /** Qualifying samples recorded so far. */
  sampleCount: number;
}

/**
 * Per-minute ceiling — same limiter shape as points.recordDailyOpen.
 *
 * Returns the attempt number THIS call represents (the stored count plus its
 * own increment), which is exactly what the risk pipeline's
 * `attemptsInLastMinute` signal needs. Returning it instead of re-reading the
 * same document a few lines later saves one Firestore read on every check-in,
 * and is if anything more truthful: a second read could observe a concurrent
 * caller's increment and attribute it to this attempt.
 */
async function enforceRateLimit(uid: string, nowMs: number): Promise<number> {
  const ref = db
    .collection(ECONOMY_RATE_LIMIT_COLLECTION)
    .doc(economyRateLimitDocId(uid, RATE_LIMIT_ACTION, nowMs));
  const snap = await ref.get();
  // Degrades a corrupt counter to 0. That matters more here than at any other
  // counter read: `stored + 1` is returned as the `attemptsInLastMinute` fed
  // to the risk pipeline below, and a fractional or non-finite attempt rate
  // would score as silently as a real one.
  const stored = readCount(snap.get('count'));
  if (!isUnderEconomyRateLimit(stored)) {
    throw new HttpsError('resource-exhausted', 'Too many check-in attempts — try again shortly.');
  }
  await ref.set(
    {
      uid,
      action: RATE_LIMIT_ACTION,
      count: FieldValue.increment(1),
      expireAt: Timestamp.fromDate(economyRateLimitExpiry(nowMs)),
    },
    { merge: true },
  );
  return stored + 1;
}

interface EventLocation {
  latitude: number;
  longitude: number;
  startsAtMs: number;
  endsAtMs: number | null;
}

const toMillis = (value: unknown): number | null =>
  value instanceof Timestamp ? value.toMillis() : null;

const toNumber = (value: unknown): number | null =>
  typeof value === 'number' && Number.isFinite(value) ? value : null;

/**
 * Loads the event's coordinates and time window.
 *
 * Coordinates are read from the teaser document FIRST and fall back to the
 * member-gated `details/private` document. On this branch latitude/longitude
 * live on `details/private`; a concurrent change moves (or mirrors) them onto
 * the teaser. Reading both, teaser-first, makes this callable correct under
 * either layout and during the migration between them — the Admin SDK
 * bypasses rules, so no privacy boundary is crossed either way.
 */
async function loadEventLocation(eventId: string): Promise<EventLocation | null> {
  const eventRef = db.collection('events').doc(eventId);
  const [eventSnap, detailSnap] = await Promise.all([
    eventRef.get(),
    eventRef.collection('details').doc('private').get(),
  ]);
  const event = eventSnap.data();
  if (!event) {
    return null;
  }
  // A cancelled or draft event is not a meet anyone can attend. `completed`
  // is allowed: the hourly autoClose sweep can complete an event while people
  // are still standing in the car park.
  if (event.status !== 'published' && event.status !== 'completed') {
    return null;
  }
  const detail = detailSnap.data();
  const latitude = toNumber(event.latitude) ?? toNumber(detail?.latitude);
  const longitude = toNumber(event.longitude) ?? toNumber(detail?.longitude);
  const startsAtMs = toMillis(event.startsAt);
  if (latitude === null || longitude === null || startsAtMs === null) {
    return null;
  }
  if (!isValidCoordinate(latitude, longitude)) {
    return null;
  }
  return {
    latitude,
    longitude,
    startsAtMs,
    endsAtMs: toMillis(event.endsAt),
  };
}

/** Latest trusted RTDB position for the implausible-jump signal; null when absent. */
async function readLatestTrustedPosition(
  uid: string,
): Promise<{ latitude: number; longitude: number; recordedAt: string } | null> {
  try {
    const value = (await adminRtdb.ref(`liveLocation/${uid}/latest`).get()).val() as {
      latitude?: unknown;
      longitude?: unknown;
      recordedAt?: unknown;
    } | null;
    if (
      value &&
      typeof value.latitude === 'number' &&
      typeof value.longitude === 'number' &&
      typeof value.recordedAt === 'string'
    ) {
      return {
        latitude: value.latitude,
        longitude: value.longitude,
        recordedAt: value.recordedAt,
      };
    }
    return null;
  } catch (error) {
    logger.warn('Check-in jump check skipped: latest position unreadable', {
      uid,
      error: String(error),
    });
    return null;
  }
}

export const checkIn = onCall(CALLABLE_OPTS, async (request): Promise<CheckInResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseCheckInInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  const now = new Date();
  const attemptsInLastMinute = await enforceRateLimit(actor.uid, now.getTime());

  const location = await loadEventLocation(input.eventId);
  if (!location) {
    return respond('event_not_checkinable', false, 0, 0);
  }

  // The client's `capturedAt` is an ASSERTION, and it is checked twice: for
  // freshness against the server clock (a back-dated or replayed fix is
  // stale) and for membership of the event window. It is never trusted as a
  // dwell measurement on its own — dwell is derived from the set of stored,
  // server-validated samples.
  const positionStale = !isPositionFresh(input.capturedAt, now.getTime());
  if (positionStale) {
    return respond('position_too_old', false, 0, 0);
  }
  const capturedAtMs = new Date(input.capturedAt).getTime();

  const window = attendanceWindow({
    startsAtMs: location.startsAtMs,
    endsAtMs: location.endsAtMs,
  });
  if (capturedAtMs < window.fromMs || capturedAtMs > window.toMs) {
    return respond('outside_window', false, 0, 0);
  }

  const sample: AttendanceSample = {
    latitude: input.latitude,
    longitude: input.longitude,
    accuracyMeters: input.accuracyMeters ?? null,
    capturedAtMs,
  };
  if (!isSampleInsideFence(sample, location.latitude, location.longitude)) {
    return respond('outside_geofence', false, 0, 0);
  }

  // Risk pipeline — identical thresholds to a Kronjakt claim. The attempts
  // signal is the value enforceRateLimit already established above; it is not
  // re-read (one fewer Firestore read per check-in).
  const latestPosition = await readLatestTrustedPosition(actor.uid);
  const impossibleJump = latestPosition
    ? !isPlausibleJump(
        latestPosition.latitude,
        latestPosition.longitude,
        latestPosition.recordedAt,
        input.latitude,
        input.longitude,
        now.getTime(),
      )
    : false;
  const risk = evaluateClaimRisk({
    positionStale,
    poorAccuracy: (input.accuracyMeters ?? 0) > 50,
    impossibleJump,
    duplicateIdempotencyKey: false,
    attemptsInLastMinute,
    successfulClaimsInVelocityWindow: 0,
    geofenceEdgeAttempts: 0,
    accuracyMeters: input.accuracyMeters ?? null,
    // A self-reported mock location is the strongest spoofing signal available
    // without an attestation API, and — exactly as for a Kronjakt claim — it
    // scores at the review threshold ON ITS OWN (MOCK_LOCATION_SCORE), so a
    // mocked fix is recorded as rejected and can never contribute dwell. This
    // is why checkIn accepts `isMockLocation` at all: platformIntegrityPassed
    // alone (a weaker +40 signal) would let a mocked position through.
    mockLocationReported: input.isMockLocation ?? null,
    platformIntegrityPassed: input.platformIntegrityPassed ?? null,
  });

  const attendanceRef = db
    .collection(EVENT_ATTENDANCE_COLLECTION)
    .doc(attendanceDocId(input.eventId, actor.uid));

  if (risk.isHighRisk) {
    // The sample never joins `samples`, so it can contribute no dwell. The
    // score and reasons go to the backend-only eventAttendanceRisk collection
    // — NOT onto the owner-readable attendance record — exactly as
    // crownHuntClaimRisk is kept apart from crownHuntClaims: telling a client
    // which signal tripped tells them what to change next time.
    await Promise.all([
      attendanceRef.set(
        {
          eventId: input.eventId,
          userId: actor.uid,
          rejectedSampleCount: FieldValue.increment(1),
          updatedAt: FieldValue.serverTimestamp(),
          expireAt: Timestamp.fromDate(attendanceEvidenceExpiry(now.getTime())),
        },
        { merge: true },
      ),
      db
        .collection(EVENT_ATTENDANCE_RISK_COLLECTION)
        .doc(attendanceDocId(input.eventId, actor.uid))
        .set(
          {
            eventId: input.eventId,
            userId: actor.uid,
            lastRiskScore: risk.riskScore,
            lastRiskReasons: risk.riskReasons,
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        ),
    ]);
    return respond('risk_review', false, 0, 0);
  }

  // Append + evaluate in ONE transaction so two concurrent taps cannot both
  // read a pre-verification state and race the `verified` flip.
  const outcome = await db.runTransaction(async (tx) => {
    const snap = await tx.get(attendanceRef);
    const data = snap.data();
    const alreadyVerified = data?.verified === true;
    // The stored list is re-written below, so a malformed entry is DROPPED
    // here rather than carried forward — see parseStoredAttendanceSamples.
    const existing = parseStoredAttendanceSamples(data?.samples);

    // Drop an exact-timestamp duplicate: a double-tap must not become two
    // samples (it would still fail the 10-minute spacing rule, but keeping
    // the record honest matters more).
    const samples = existing.some((entry) => entry.capturedAtMs === capturedAtMs)
      ? existing
      : [...existing, sample];
    // Newest-wins truncation keeps the record bounded; the first sample is
    // always retained because it anchors the dwell span.
    const bounded: AttendanceSample[] =
      samples.length > MAX_ATTENDANCE_SAMPLES
        ? samples
            .slice(0, 1)
            .concat(samples.slice(samples.length - (MAX_ATTENDANCE_SAMPLES - 1)))
        : samples;

    const decision = evaluateAttendance(bounded, location.latitude, location.longitude, {
      startsAtMs: location.startsAtMs,
      endsAtMs: location.endsAtMs,
    });

    tx.set(
      attendanceRef,
      {
        eventId: input.eventId,
        userId: actor.uid,
        samples: bounded.map((entry) => ({
          latitude: entry.latitude,
          longitude: entry.longitude,
          accuracyMeters: entry.accuracyMeters,
          capturedAtMs: entry.capturedAtMs,
        })),
        sampleCount: bounded.length,
        dwellMs: decision.dwellMs,
        // `verified` only ever goes false -> true; the points trigger fires on
        // that edge and the award is idempotent on (eventId, uid).
        verified: alreadyVerified || decision.attended,
        ...(!alreadyVerified && decision.attended
          ? { verifiedAt: FieldValue.serverTimestamp() }
          : {}),
        updatedAt: FieldValue.serverTimestamp(),
        // Retention deadline on the raw coordinates — pushed out by each new
        // sample so the record survives the event it documents, then reaped
        // by the TTL policy. See ATTENDANCE_EVIDENCE_RETENTION_MS for why
        // deleting this record cannot re-open the award.
        expireAt: Timestamp.fromDate(attendanceEvidenceExpiry(now.getTime())),
        ...(snap.exists ? {} : { createdAt: FieldValue.serverTimestamp() }),
      },
      { merge: true },
    );

    return { decision, alreadyVerified };
  });

  const dwellSeconds = Math.floor(outcome.decision.dwellMs / 1000);
  const remaining = Math.max(0, Math.ceil((REQUIRED_DWELL_MS - outcome.decision.dwellMs) / 1000));
  if (outcome.alreadyVerified) {
    return respond('already_verified', true, dwellSeconds, 0, outcome.decision.qualifyingSampleCount);
  }
  if (outcome.decision.attended) {
    return respond('verified', true, dwellSeconds, 0, outcome.decision.qualifyingSampleCount);
  }
  return respond(
    'recorded',
    false,
    dwellSeconds,
    remaining,
    outcome.decision.qualifyingSampleCount,
  );
});

function respond(
  result: CheckInResult,
  verified: boolean,
  dwellSeconds: number,
  remainingDwellSeconds: number,
  sampleCount = 0,
): CheckInResponse {
  return { result, verified, dwellSeconds, remainingDwellSeconds, sampleCount };
}
