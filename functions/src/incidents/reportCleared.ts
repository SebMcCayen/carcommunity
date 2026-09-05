/**
 * incidents.reportCleared — "no, it's gone" on a crowd-sourced incident
 * (contracts/functions/functions.json: incidents.reportCleared).
 *
 * Deployed via the `incidents` export group as `incidents-reportCleared`
 * (europe-west1). Active-account-gated (requireActiveActor), matching `incidents.report`
 * and `incidents.confirm`: it is a WRITE that changes what every user on the map
 * sees.
 *
 * ## The model, and why it is not a delete
 *
 * One tap deleting a marker for everyone would let a single mistaken — or
 * malicious — member erase a real accident or road closure from every other
 * driver's map. Wrongly REMOVING a live hazard is far worse than briefly showing
 * a stale one. So a clear vote WEAKENS the incident visibly instead of deleting
 * it:
 *
 *  - the vote is counted into `clearedCount`, alongside the existing
 *    `confirmationCount`, and BOTH travel to clients;
 *  - while clears lead but have not reached the threshold, the incident stays on
 *    the map with `reportedCleared: true` and is drawn faded ("reported gone by
 *    N") — an arriving member sees both signals and judges for themselves;
 *  - only at CLEAR_VOTES_TO_REMOVE (2) NET clear votes does it leave the map.
 *
 * The threshold maths live in `evaluateClearVote` (incidents-core.ts) — pure and
 * unit-tested, so the callable cannot quietly disagree with the tests about what
 * "2 net clear votes" means.
 *
 * ## Removal = expiry, not deletion
 *
 * Crossing the threshold sets `expiresAt` to NOW rather than deleting the
 * document. The read rule already gates on `expiresAt > request.time` and
 * `listNearby` re-applies the same bound, so the marker disappears for everyone
 * immediately; the existing `incidents-cleanupExpired` sweep then reclaims the
 * document AND its sub-collections 15 minutes later (recursiveDelete). Deleting
 * here instead would need its own orphan-sweeping dance (see incidents.remove's
 * best-effort recursiveDelete and the permanent orphan it documents) and would
 * throw away the vote ledger that explains WHY the incident went — the audit
 * trail is the whole point of preferring decay to deletion.
 *
 * ## Immediate removal for the reporter and for admins
 *
 * The person who reported it has the best information about their own report, so
 * their clear vote removes it at once with no threshold — the same authority
 * `incidents.remove` already gives them, expressed as a vote so the ledger
 * records it. Admins likewise (moderation).
 *
 * ## Vote switching
 *
 * A member who already CONFIRMED may switch to a clear vote, and a member who
 * already cleared may switch back to confirming (handled in confirm.ts). Both
 * switches move both counters in ONE transaction, so a switcher is never counted
 * on both sides.
 *
 * ALLOWING the switch is the deliberate choice. The alternative — first vote
 * wins forever — punishes the honest case that matters most: you confirm an
 * accident, drive back an hour later and it is cleared. Refusing to let that
 * member update their own vote leaves a stale confirmation propping up a marker
 * they now know is wrong, and the whole point of this feature is that the map
 * tracks reality. The abuse it opens is bounded and unattractive: a switch is
 * still one vote (never two), still needs the voter to be physically near the
 * spot, and still passes the risk pipeline and the rate limit, so flip-flopping
 * buys nothing beyond what a single vote already buys.
 *
 * ## Proximity + anti-abuse
 *
 * You must be NEAR the incident to vote it gone. The position is checked with the
 * SHARED crownHunt helpers — imported, never forked:
 *  - `haversineDistanceMeters` + `isWithinGeofence` against
 *    INCIDENT_CLEAR_GEOFENCE_RADIUS_METERS (300 m). The client-supplied accuracy
 *    that buffers that fence is bounded twice inside `isWithinGeofence`
 *    (clamped to MAX_GEOFENCE_ACCURACY_METERS, capped at 2x the radius, PR #573)
 *    AND once more at this callable's own input schema (MAX_REPORTED_ACCURACY_METERS)
 *    — see reportClearedInputSchema for why the overlap is intentional.
 *  - `isPositionFresh` on the fix's own `capturedAt`.
 *  - `evaluateClaimRisk` with the same RISK_REVIEW_THRESHOLD (60) as a Kronjakt
 *    claim: a high-risk sample counts NO vote.
 *  - a per-user fixed-window rate limit in its own collection.
 *
 * Risk scores and reasons are NEVER returned to the client — telling an abuser
 * which signal tripped tells them what to change next time. They go to the
 * backend-only `incidentClearVoteRisk` collection, exactly as
 * `eventAttendanceRisk` and `crownHuntClaimRisk` do.
 *
 * ## Imported (Trafikverket) incidents are rejected
 *
 * `runTrafikverketSync` rewrites every `tv_` document with a full `batch.set` —
 * no merge — every 30 minutes, so a vote written here would simply be erased,
 * and upstream is the authority on whether the situation is still live anyway.
 * Rejected with `failed-precondition` and `details.reason = 'imported_incident'`
 * so the client can say WHY rather than showing a generic failure.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import {
  haversineDistanceMeters,
  isPositionFresh,
  isWithinGeofence,
} from '../crownHunt/crown-hunt-geo';
import { evaluateClaimRisk } from '../crownHunt/crown-hunt-risk';
import {
  CLEAR_VOTES_SUBCOLLECTION,
  INCIDENT_CLEAR_GEOFENCE_RADIUS_METERS,
  INCIDENT_CLEAR_RATE_LIMIT_COLLECTION,
  evaluateClearVote,
  incidentClearRateLimitDocId,
  incidentListRateLimitExpiry,
  isIncidentLive,
  isUnderIncidentClearRateLimit,
  isValidClearedCount,
  isValidConfirmationCount,
  parseReportClearedInput,
  readClearedCount,
  readConfirmationCount,
} from './incidents-core';
import { CONFIRMATIONS_SUBCOLLECTION } from './confirm';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/** Backend-only collection holding risk scores for rejected clear votes. */
export const INCIDENT_CLEAR_RISK_COLLECTION = 'incidentClearVoteRisk';

/**
 * Machine-readable reasons attached to a rejection's `details`, so the Android
 * client can say "drive closer" or "this comes from Trafikverket" instead of one
 * generic failure message. Deliberately a small closed set that leaks nothing:
 * `vote_not_counted` in particular does NOT say which risk signal tripped.
 */
export type ReportClearedRejection =
  | 'imported_incident'
  | 'incident_inactive'
  | 'out_of_range'
  | 'position_too_old'
  | 'vote_not_counted';

export interface ReportClearedResponse {
  incidentId: string;
  /** Clear votes after this call. */
  clearedCount: number;
  /** Confirmations after this call (a switch decrements it). */
  confirmationCount: number;
  /** True when the incident is now faded — clears lead, below the threshold. */
  reportedCleared: boolean;
  /** True when this vote took the incident off the map. */
  removed: boolean;
  /** True when this caller had already voted it clear (no double count). */
  alreadyVoted: boolean;
  /** True when this vote replaced the caller's earlier confirmation. */
  switchedFromConfirmation: boolean;
}

function reject(
  code: 'failed-precondition' | 'permission-denied',
  reason: ReportClearedRejection,
  message: string,
): never {
  throw new HttpsError(code, message, { reason });
}

export const reportCleared = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ReportClearedResponse> => {
    const actor = await requireActiveActor(request);

    // Validate BEFORE the rate limit so a malformed call is rejected without
    // touching Firestore and without burning the caller's window — the same
    // ordering incidents.listNearby uses.
    const parsed = parseReportClearedInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const input = parsed.input;
    const incidentId = input.incidentId;

    const now = new Date();
    const attemptsInLastMinute = await enforceClearRateLimit(actor.uid, now.getTime());

    // The fix's own timestamp, checked against the server clock. A replayed or
    // back-dated sample is not evidence that anyone looked at the road.
    const positionStale = !isPositionFresh(input.capturedAt, now.getTime());
    if (positionStale) {
      reject(
        'failed-precondition',
        'position_too_old',
        'Your position is too old to use for this. Try again.',
      );
    }

    // Server-managed admin claim (set only by admin.setAdminRole); a suspended
    // admin is already rejected by requireActiveActor above.
    const isAdmin = request.auth?.token.admin === true;

    const ref = db.collection('incidents').doc(incidentId);
    const clearVoteRef = ref.collection(CLEAR_VOTES_SUBCOLLECTION).doc(actor.uid);
    const confirmationRef = ref.collection(CONFIRMATIONS_SUBCOLLECTION).doc(actor.uid);

    // ---- Pre-transaction: read the incident (and this caller's existing vote)
    // to run the proximity + risk gates. These need no write, and a rejected vote
    // must not pay for — or contend on — a transaction. The transaction below
    // re-reads everything, so nothing decided here is trusted as still-current
    // when the write lands.
    const [preSnap, preVoteSnap] = await Promise.all([ref.get(), clearVoteRef.get()]);
    if (!preSnap.exists) {
      throw new HttpsError('not-found', 'Incident not found.');
    }
    const preData = preSnap.data()!;

    // ALREADY VOTED short-circuits BEFORE the proximity gate, and that ordering
    // is the point rather than an optimisation. A repeat writes nothing, so the
    // gates have nothing to protect — and running them anyway would break the
    // idempotency promise in the most confusing possible way: a member who voted
    // at the scene, drove on, and tapped again would be told "drive closer"
    // about a vote they had already successfully cast. A repeat is a stable
    // success from anywhere.
    if (preVoteSnap.exists) {
      const tally = evaluateClearVote({
        clearedCount: readClearedCount(preData.clearedCount),
        confirmationCount: readConfirmationCount(preData.confirmationCount),
      });
      return {
        incidentId,
        clearedCount: tally.clearedCount,
        confirmationCount: tally.confirmationCount,
        reportedCleared: tally.reportedCleared,
        removed: false,
        alreadyVoted: true,
        switchedFromConfirmation: false,
      };
    }

    if (preData.source !== 'user') {
      reject(
        'failed-precondition',
        'imported_incident',
        'Imported incidents are kept up to date automatically and cannot be voted on.',
      );
    }

    const incidentLat = preData.latitude;
    const incidentLng = preData.longitude;
    if (typeof incidentLat !== 'number' || typeof incidentLng !== 'number') {
      logger.error('incidents.reportCleared: incident has missing/invalid coordinates', {
        incidentId,
      });
      throw new HttpsError('internal', 'This incident cannot be voted on right now.');
    }

    // Distance is ALWAYS server-computed from the stored incident position and
    // the reported fix. A client-supplied distance is never read.
    const distanceMeters = haversineDistanceMeters(
      incidentLat,
      incidentLng,
      input.latitude,
      input.longitude,
    );
    const accuracyMeters = input.accuracyMeters ?? null;
    if (
      !isWithinGeofence(distanceMeters, INCIDENT_CLEAR_GEOFENCE_RADIUS_METERS, accuracyMeters)
    ) {
      reject(
        'failed-precondition',
        'out_of_range',
        'You need to be near the incident to report it gone.',
      );
    }

    // The SAME risk pipeline and the SAME 60-point review threshold as a
    // Kronjakt claim / an event check-in. `attemptsInLastMinute` is the value the
    // rate limiter already established — not re-read.
    const risk = evaluateClaimRisk({
      positionStale,
      poorAccuracy: false,
      impossibleJump: false,
      duplicateIdempotencyKey: false,
      attemptsInLastMinute,
      successfulClaimsInVelocityWindow: 0,
      geofenceEdgeAttempts: 0,
      accuracyMeters,
      platformIntegrityPassed: null,
      mockLocationReported: input.mockLocationReported ?? null,
    });
    if (risk.isHighRisk) {
      // Backend-only, never returned: naming the signal that tripped tells an
      // abuser exactly what to change. Mirrors eventAttendanceRisk.
      await db
        .collection(INCIDENT_CLEAR_RISK_COLLECTION)
        .doc(`${incidentId}_${actor.uid}`)
        .set(
          {
            incidentId,
            userId: actor.uid,
            lastRiskScore: risk.riskScore,
            lastRiskReasons: risk.riskReasons,
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
      reject(
        'permission-denied',
        'vote_not_counted',
        'We could not accept that report. Try again from the scene.',
      );
    }

    return db.runTransaction(async (tx) => {
      // Every read before any write, as Firestore requires.
      const [snap, existingVote, existingConfirmation] = await Promise.all([
        tx.get(ref),
        tx.get(clearVoteRef),
        tx.get(confirmationRef),
      ]);
      if (!snap.exists) {
        throw new HttpsError('not-found', 'Incident not found.');
      }
      const data = snap.data()!;

      // Re-checked inside the transaction: the pre-read above is a fast path,
      // and an incident could have been re-imported or expired since.
      if (data.source !== 'user') {
        reject(
          'failed-precondition',
          'imported_incident',
          'Imported incidents are kept up to date automatically and cannot be voted on.',
        );
      }

      // ONE clock reading per ATTEMPT, taken INSIDE the transaction body — the
      // same rule incidents.confirm states, for the same reason: the liveness
      // check below and the removal expiry stamped further down must agree on
      // what "now" is.
      //
      // Reading it inside is load-bearing, not stylistic. Firestore re-runs this
      // body on contention, and the outer `now` (captured before the rate limit,
      // the pre-reads and the transaction's own reads) would be re-used unchanged
      // by every retry. The race that opens is exactly the one this feature
      // creates: two members vote to clear at once, the first crosses the
      // threshold and stamps `expiresAt` to ITS `now`, the second's transaction
      // retries on the conflict — and comparing the fresh `expiresAt` against a
      // clock captured at roughly the same instant is a coin flip, so the retry
      // could count a vote onto an incident that is already gone. A clock read
      // here is always after the conflicting commit, so the retry rejects.
      const txNow = new Date();

      const currentExpiresAt = data.expiresAt;
      if (
        !(currentExpiresAt instanceof Timestamp) ||
        !isIncidentLive(data.status, currentExpiresAt.toMillis(), txNow.getTime())
      ) {
        reject(
          'failed-precondition',
          'incident_inactive',
          'This incident is no longer active.',
        );
      }

      // FAIL FAST on corrupt counters rather than building a threshold decision
      // on a number we cannot trust. This path both READS and WRITES the counts,
      // and the removal decision turns on their difference — a NaN would make
      // `net >= 2` false forever and silently disable removal on that document.
      // (listNearby takes the opposite branch on purpose; see readClearedCount.)
      if (data.confirmationCount !== undefined && !isValidConfirmationCount(data.confirmationCount)) {
        logger.error('incidents.reportCleared: incident has a corrupt confirmationCount', {
          incidentId,
          confirmationCount: String(data.confirmationCount),
        });
        throw new HttpsError('internal', 'This incident cannot be voted on right now.');
      }
      if (data.clearedCount !== undefined && !isValidClearedCount(data.clearedCount)) {
        logger.error('incidents.reportCleared: incident has a corrupt clearedCount', {
          incidentId,
          clearedCount: String(data.clearedCount),
        });
        throw new HttpsError('internal', 'This incident cannot be voted on right now.');
      }

      const storedCleared = readClearedCount(data.clearedCount);
      const storedConfirmations = readConfirmationCount(data.confirmationCount);

      // Already voted → idempotent success, nothing written. A double-tap (or a
      // second device) settles the client's button into a stable state rather
      // than raising an error the user can do nothing about.
      if (existingVote.exists) {
        const tally = evaluateClearVote({
          clearedCount: storedCleared,
          confirmationCount: storedConfirmations,
        });
        return {
          incidentId,
          clearedCount: tally.clearedCount,
          confirmationCount: tally.confirmationCount,
          reportedCleared: tally.reportedCleared,
          removed: false,
          alreadyVoted: true,
          switchedFromConfirmation: false,
        };
      }

      // A switch moves BOTH counters in this one transaction, so the switcher is
      // never counted on both sides of the same incident.
      const switchedFromConfirmation = existingConfirmation.exists;
      const nextCleared = storedCleared + 1;
      const nextConfirmations = switchedFromConfirmation
        ? Math.max(0, storedConfirmations - 1)
        : storedConfirmations;

      const tally = evaluateClearVote({
        clearedCount: nextCleared,
        confirmationCount: nextConfirmations,
      });

      // The reporter knows their own report best, and an admin is moderating;
      // neither waits for a threshold.
      const isReporter =
        typeof data.reporterUid === 'string' &&
        data.reporterUid.length > 0 &&
        data.reporterUid === actor.uid;
      const removed = isReporter || isAdmin || tally.shouldRemove;

      // `create`, not `set`: if a concurrent call for the same uid slipped in
      // between the read above and this commit, the transaction aborts and
      // retries rather than double-counting.
      tx.create(clearVoteRef, {
        uid: actor.uid,
        createdAt: FieldValue.serverTimestamp(),
        // Kept for moderation/audit — this is the evidence that the voter was
        // actually at the scene, and it is what makes a disputed removal
        // reviewable. Never client-readable (the sub-collection is denied by the
        // rules' catch-all).
        latitude: input.latitude,
        longitude: input.longitude,
        accuracyMeters,
        distanceMeters,
        switchedFromConfirmation,
      });
      if (switchedFromConfirmation) {
        tx.delete(confirmationRef);
      }

      // ABSOLUTE values, not FieldValue.increment: the fade flag and the removal
      // decision are DERIVED from these exact numbers, so the document must end
      // up holding the numbers the decision was made on. An increment could
      // commit a count that disagrees with the `reportedCleared` written beside
      // it. This is safe precisely because it is a transaction that READ the
      // document: a concurrent write aborts and retries with fresh values.
      const update: Record<string, unknown> = {
        clearedCount: tally.clearedCount,
        confirmationCount: tally.confirmationCount,
        // False when removed: a removed incident is gone, not faded.
        reportedCleared: removed ? false : tally.reportedCleared,
      };
      if (removed) {
        // Expire rather than delete — the read rule and listNearby both gate on
        // `expiresAt`, so it leaves the map at once, and incidents-cleanupExpired
        // reclaims the document AND its vote ledgers 15 minutes later.
        //
        // `txNow` (this attempt's clock), so the stamp agrees with the liveness
        // check above and is still in the past at commit time — the property that
        // makes removal immediate.
        update.expiresAt = Timestamp.fromDate(txNow);
      }
      tx.update(ref, update);

      return {
        incidentId,
        clearedCount: tally.clearedCount,
        confirmationCount: tally.confirmationCount,
        reportedCleared: removed ? false : tally.reportedCleared,
        removed,
        alreadyVoted: false,
        switchedFromConfirmation,
      };
    });
  },
);

/**
 * Fixed-window per-user rate limit for `incidents.reportCleared`, and the source
 * of the `attemptsInLastMinute` risk signal.
 *
 * Same mechanism as the listNearby limiter (deterministic `{uid}_{epochMinute}`
 * counter read by id, bumped with FieldValue.increment, `expireAt` TTL-reaped) in
 * its OWN collection, so map refreshes and clear votes cannot starve each other.
 * Returns the count BEFORE this call so the risk evaluator can score attempt
 * velocity without a second read.
 */
async function enforceClearRateLimit(uid: string, nowMs: number): Promise<number> {
  const ref = db
    .collection(INCIDENT_CLEAR_RATE_LIMIT_COLLECTION)
    .doc(incidentClearRateLimitDocId(uid, nowMs));

  const snap = await ref.get();
  const raw = snap.get('count');
  const currentCount = typeof raw === 'number' ? raw : 0;
  if (!isUnderIncidentClearRateLimit(currentCount)) {
    throw new HttpsError(
      'resource-exhausted',
      'Too many reports in a short time — please wait a moment.',
    );
  }

  await ref.set(
    {
      count: FieldValue.increment(1),
      uid,
      expireAt: Timestamp.fromDate(incidentListRateLimitExpiry(nowMs)),
    },
    { merge: true },
  );
  // The value BEFORE this call is what "attempts already made this minute" means.
  return Number.isFinite(currentCount) ? currentCount : 0;
}

// One-time deploy step for the counter's TTL (spent windows self-delete):
//
//   gcloud firestore fields ttls update expireAt \
//     --collection-group=incidentClearRateLimits --enable-ttl
//
// Backend-only: written here via the Admin SDK and denied to all clients by
// firebase/firestore.rules. Needs no composite index (read by document id).
