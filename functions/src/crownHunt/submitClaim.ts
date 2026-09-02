/**
 * crownHunt.submitClaim — callable (contracts/functions/functions.json).
 *
 * Deployed via the `crownHunt` export group as `crownHunt-submitClaim`.
 *
 * Ports the legacy claimPoint flow step for step (backend-domain-mapping.md:
 * "Must preserve all validation logic"). Eligibility failures return RESULT
 * CODES with Swedish messages, not errors (legacy parity) — only malformed
 * input and unauthenticated calls throw. Every attempt is recorded in
 * crownHuntClaims (document ID = SHA-256-scoped idempotency key, so a
 * duplicate submission replays the stored result). Risk score/reasons go to
 * the backend-only crownHuntClaimRisk collection — never client-readable.
 *
 * The award commits atomically with the claim record via the 9g ledger
 * primitives' AtomicExtraWrites hook (mapping: "Claim creation + points
 * award in a single transaction"), replay-safe through the ledger
 * idempotency key derived from the scoped claim key.
 *
 * Jump detection reads the RTDB latest trusted position
 * (liveLocation/{uid}/latest — written by the Phase 10 live-location
 * domain); absent or unreadable positions skip the check, exactly like the
 * legacy behavior when no trusted position row existed.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { adminRtdb, db } from '../firebase';
import { flagFromSnapshot, readFeatureFlagsSnapshot } from '../shared/featureFlags';
import { toUserAccessState } from '../shared/access';
import { crownHuntGateAllows, memberGateAllows } from '../shared/memberGating';
import { creditPoints } from '../points/ledger';
import {
  haversineDistanceMeters,
  isPlausibleJump,
  isPositionFresh,
  isSpeedSafe,
  isValidCoordinate,
  isWithinGeofence,
} from './crown-hunt-geo';
import { HIGH_VELOCITY_WINDOW_SECONDS, evaluateClaimRisk } from './crown-hunt-risk';
import {
  CROWN_HUNT_FLAG_KEY,
  CROWN_HUNT_REQUIRE_PAID_FLAG_KEY,
  MAX_CLAIM_SPEED_MPS,
  MAX_DAILY_SUCCESSFUL_CLAIMS,
  awardGuardDocId,
  awardGuardWindowKey,
  claimLedgerIdempotencyKey,
  dailyClaimCounterDocId,
  getClaimMessage,
  isPointCurrentlyAvailable,
  parseSubmitClaimInput,
  pointCollectorDocId,
  repeatRuleWindowStart,
  scopeClaimIdempotencyKey,
  startOfUtcDay,
  utcDayKey,
  type CrownHuntClaimResult,
  type CrownHuntRepeatRule,
} from './crownhunt-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { resolveActiveBoostMultiplier } from './pvp-drain';
import { resolveLiveShareMultiplier } from './live-share-multiplier';

/**
 * Thrown inside the award transaction's read guard when a deterministic
 * guard/counter document shows the claim would breach the repeat rule or the
 * daily cap. Carries the authoritative result code the caller replies with.
 */
class ClaimGuardRejection extends Error {
  constructor(
    readonly result: Extract<
      CrownHuntClaimResult,
      'already_claimed' | 'daily_limit_reached' | 'point_inactive'
    >,
  ) {
    super(result);
    this.name = 'ClaimGuardRejection';
  }
}

/**
 * Maps a thrown award-transaction error to an authoritative claim result, or
 * null when it is not a guard rejection (rethrow). Handles both the explicit
 * read-guard throw and the Firestore ALREADY_EXISTS from the guard-document
 * `tx.create` when two claims race past the read check simultaneously.
 */
function classifyClaimGuardRejection(
  error: unknown,
): Extract<
  CrownHuntClaimResult,
  'already_claimed' | 'daily_limit_reached' | 'point_inactive'
> | null {
  if (error instanceof ClaimGuardRejection) {
    return error.result;
  }
  // Firestore create-on-existing → gRPC ALREADY_EXISTS: a concurrent award
  // won the guard document. The admin SDK surfaces this as numeric code 6, an
  // 'already-exists' string, or an ALREADY_EXISTS message depending on version.
  const code = (error as { code?: unknown })?.code;
  const message = String((error as { message?: unknown })?.message ?? '');
  if (code === 6 || code === 'already-exists' || message.includes('ALREADY_EXISTS')) {
    return 'already_claimed';
  }
  return null;
}

export interface SubmitClaimResponse {
  result: CrownHuntClaimResult;
  pointsAwarded: number | null;
  newBalance: number | null;
  message: string;
}

function respond(result: CrownHuntClaimResult): SubmitClaimResponse {
  return { result, pointsAwarded: null, newBalance: null, message: getClaimMessage(result) };
}

/**
 * Replays a stored claim for the same idempotency key — the single source of
 * truth for BOTH the step-4 duplicate guard and the recordAttempt race
 * guard: a key reused across a different pointId is treated as a duplicate
 * (legacy parity), and an awarded replay carries the stored award data.
 */
function replayStoredClaim(
  existing: FirebaseFirestore.DocumentData,
  requestedPointId: string,
): SubmitClaimResponse {
  if (existing.pointId !== requestedPointId) {
    return respond('already_claimed');
  }
  const result = existing.result as CrownHuntClaimResult;
  if (result === 'awarded') {
    return {
      result,
      pointsAwarded: (existing.pointsAwarded as number | null) ?? null,
      newBalance: (existing.balanceAfter as number | null) ?? null,
      message: getClaimMessage(result),
    };
  }
  return respond(result);
}


/** Latest trusted position from RTDB for jump detection; null when absent. */
async function readLatestTrustedPosition(
  uid: string,
): Promise<{ latitude: number; longitude: number; recordedAt: string } | null> {
  try {
    const snap = await adminRtdb.ref(`liveLocation/${uid}/latest`).get();
    const value = snap.val() as
      | { latitude?: unknown; longitude?: unknown; recordedAt?: unknown }
      | null;
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
    // No trusted position → no jump signal (legacy parity when the position
    // row was absent). Never fail the claim over a jump-signal read.
    logger.warn('Latest position read failed; skipping jump check', {
      uid,
      error: String(error),
    });
    return null;
  }
}

/**
 * Records a non-awarded attempt (document ID = scoped idempotency key)
 * WITHOUT ever overwriting an existing claim: two concurrent requests with
 * the same key serialize in a transaction, and the loser returns the stored
 * result as a replay instead of clobbering an awarded/risk_review record.
 * Returns null when this attempt was recorded, or the existing result.
 */
async function recordAttempt(
  scopedKey: string,
  data: Record<string, unknown>,
  riskData?: Record<string, unknown>,
): Promise<FirebaseFirestore.DocumentData | null> {
  const claimRef = db.collection('crownHuntClaims').doc(scopedKey);
  return db.runTransaction(async (tx) => {
    const existing = await tx.get(claimRef);
    if (existing.exists) {
      return existing.data()!;
    }
    tx.set(claimRef, { ...data, createdAt: FieldValue.serverTimestamp() });
    if (riskData) {
      tx.set(db.collection('crownHuntClaimRisk').doc(scopedKey), {
        ...riskData,
        createdAt: FieldValue.serverTimestamp(),
      });
    }
    return null;
  });
}

export const submitClaim = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<SubmitClaimResponse> => {
    const auth = request.auth;
    if (!auth) {
      throw new HttpsError('unauthenticated', 'Sign in to submit a Kronjakt claim.');
    }
    const uid = auth.uid;

    const parsed = parseSubmitClaimInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const input = parsed.input;
    const now = new Date();
    const scopedKey = scopeClaimIdempotencyKey(uid, input.idempotencyKey);
    const claimsRef = db.collection('crownHuntClaims');

    // Structured rejection log — ONE line per refusal (mirrors claimSpawn), so the
    // hand-placed collect path's edge-of-geofence / rolling-too-fast retry lag is
    // visible in Cloud Logging and to the scheduled crownHunt-detectClaimLag
    // detector. NO COORDINATES are ever logged (crown-hunt-geo.ts).
    const logRejection = (
      result: CrownHuntClaimResult,
      extra: { distanceMeters?: number | null } = {},
    ): void => {
      logger.info('crownHunt.submitClaim rejected', {
        uid,
        pointId: input.pointId,
        result,
        distanceMeters: extra.distanceMeters ?? null,
        accuracyMeters: input.accuracyMeters ?? null,
        reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
      });
    };

    // 1. Feature flags (legacy step 1 — no attempt record). Read
    // config/featureFlags ONCE (this is a hot collect path) and derive both
    // flags this invocation needs from that single snapshot via flagFromSnapshot
    // — same default-on-missing/unreadable rule as readFeatureFlag. Read AFTER
    // the auth guard above resolved `uid`, so an unauthenticated caller never
    // triggers it.
    //   • crownHunt            — the domain switch;
    //   • crownHuntRequirePaid — the paywall gate chooser, used at step 2+3.
    const flagsSnap = await readFeatureFlagsSnapshot();
    if (!flagFromSnapshot(flagsSnap, CROWN_HUNT_FLAG_KEY)) {
      logRejection('feature_disabled');
      return respond('feature_disabled');
    }
    const requirePaid = flagFromSnapshot(flagsSnap, CROWN_HUNT_REQUIRE_PAID_FLAG_KEY);

    // 2 + 3. Account status and entitlement (result codes, not errors).
    // Entitlement is currently bypassed (shared/memberGating.ts); suspended
    // and deleted accounts still resolve to not_eligible.
    //
    // Kronjakt PAYWALL: the dark `crownHuntRequirePaid` flag (contract default
    // OFF, derived from the single snapshot above) chooses the gate. While OFF
    // this is exactly today's relaxed `memberGateAllows`; while ON, collection
    // is a paid feature and a free member is refused with the SAME
    // `not_eligible` result code and Swedish message (no throw), via the narrow
    // `crownHuntGateAllows` (activeMember, independent of the global
    // MEMBER_GATING_ENABLED switch).
    const userSnap = await db.collection('users').doc(uid).get();
    const state = toUserAccessState(userSnap.data());
    const gateAllows = requirePaid ? crownHuntGateAllows(state) : memberGateAllows(state);
    if (!gateAllows) {
      logRejection('not_eligible');
      return respond('not_eligible');
    }

    // 4. Idempotency replay (duplicate submission guard).
    const existingClaim = await claimsRef.doc(scopedKey).get();
    if (existingClaim.exists) {
      return replayStoredClaim(existingClaim.data()!, input.pointId);
    }

    // 5. Load the point; must be active and inside its availability window.
    const pointSnap = await db.collection('crownHuntPoints').doc(input.pointId).get();
    const point = pointSnap.data();
    const availability = point && {
      availableFrom: point.availableFrom ? (point.availableFrom as Timestamp).toDate() : null,
      availableUntil: point.availableUntil ? (point.availableUntil as Timestamp).toDate() : null,
    };
    if (
      !pointSnap.exists ||
      point!.status !== 'active' ||
      !isPointCurrentlyAvailable(availability!, now)
    ) {
      logRejection('point_inactive');
      const existing = await recordAttempt(scopedKey, {
        pointId: input.pointId,
        userId: uid,
        result: 'point_inactive',
        claimedAt: Timestamp.fromDate(now),
      });
      if (existing) {
        return replayStoredClaim(existing, input.pointId);
      }
      return respond('point_inactive');
    }

    // 6. Coordinate validation (malformed input → error, legacy parity).
    if (!isValidCoordinate(input.latitude, input.longitude)) {
      throw new HttpsError('invalid-argument', 'Invalid coordinates provided.');
    }
    const recordedAtDate = new Date(input.recordedAt);

    // 7. Position freshness.
    const positionStale = !isPositionFresh(input.recordedAt, now.getTime());
    if (positionStale) {
      logRejection('position_too_old');
      const existing = await recordAttempt(scopedKey, {
        pointId: input.pointId,
        userId: uid,
        result: 'position_too_old',
        claimedAt: Timestamp.fromDate(now),
        positionRecordedAt: Timestamp.fromDate(recordedAtDate),
        accuracyMeters: input.accuracyMeters ?? null,
      });
      if (existing) {
        return replayStoredClaim(existing, input.pointId);
      }
      return respond('position_too_old');
    }

    // 8 + 9. Server-side distance + geofence (accuracy-buffered).
    const distanceMeters = haversineDistanceMeters(
      input.latitude,
      input.longitude,
      point!.latitude as number,
      point!.longitude as number,
    );
    if (
      !isWithinGeofence(distanceMeters, point!.geofenceRadiusMeters as number, input.accuracyMeters)
    ) {
      logRejection('outside_geofence', { distanceMeters });
      const existing = await recordAttempt(scopedKey, {
        pointId: input.pointId,
        userId: uid,
        result: 'outside_geofence',
        claimedAt: Timestamp.fromDate(now),
        distanceMeters,
        positionRecordedAt: Timestamp.fromDate(recordedAtDate),
        reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
        accuracyMeters: input.accuracyMeters ?? null,
      });
      if (existing) {
        return replayStoredClaim(existing, input.pointId);
      }
      return respond('outside_geofence');
    }

    // 10. Speed check — claiming requires being safely stopped.
    if (!isSpeedSafe(input.speedMetersPerSecond, MAX_CLAIM_SPEED_MPS)) {
      logRejection('moving_too_fast', { distanceMeters });
      const existing = await recordAttempt(scopedKey, {
        pointId: input.pointId,
        userId: uid,
        result: 'moving_too_fast',
        claimedAt: Timestamp.fromDate(now),
        distanceMeters,
        positionRecordedAt: Timestamp.fromDate(recordedAtDate),
        reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
        accuracyMeters: input.accuracyMeters ?? null,
      });
      if (existing) {
        return replayStoredClaim(existing, input.pointId);
      }
      return respond('moving_too_fast');
    }

    // 11. Repeat rule (once / daily / weekly per point).
    const windowStart = repeatRuleWindowStart(point!.repeatRule as CrownHuntRepeatRule, now);
    let repeatQuery = claimsRef
      .where('userId', '==', uid)
      .where('pointId', '==', input.pointId)
      .where('result', '==', 'awarded');
    if (windowStart) {
      repeatQuery = repeatQuery.where('claimedAt', '>=', Timestamp.fromDate(windowStart));
    }
    if ((await repeatQuery.limit(1).get()).size > 0) {
      logRejection('already_claimed', { distanceMeters });
      const existing = await recordAttempt(scopedKey, {
        pointId: input.pointId,
        userId: uid,
        result: 'already_claimed',
        claimedAt: Timestamp.fromDate(now),
        distanceMeters,
        positionRecordedAt: Timestamp.fromDate(recordedAtDate),
      });
      if (existing) {
        return replayStoredClaim(existing, input.pointId);
      }
      return respond('already_claimed');
    }

    // 12. Daily successful-claim limit.
    const dailyCount = await claimsRef
      .where('userId', '==', uid)
      .where('result', '==', 'awarded')
      .where('claimedAt', '>=', Timestamp.fromDate(startOfUtcDay(now)))
      .count()
      .get();
    if (dailyCount.data().count >= MAX_DAILY_SUCCESSFUL_CLAIMS) {
      logRejection('daily_limit_reached', { distanceMeters });
      const existing = await recordAttempt(scopedKey, {
        pointId: input.pointId,
        userId: uid,
        result: 'daily_limit_reached',
        claimedAt: Timestamp.fromDate(now),
        distanceMeters,
        positionRecordedAt: Timestamp.fromDate(recordedAtDate),
      });
      if (existing) {
        return replayStoredClaim(existing, input.pointId);
      }
      return respond('daily_limit_reached');
    }
    // Authoritative count of awarded claims already made today, used to SEED
    // the transactional daily counter when its document does not yet exist
    // (first claim of the day, a mid-day deploy of this change, or a deleted
    // counter). Without this seed the counter would restart at 0 and let the
    // day's cap be exceeded. Read non-transactionally here; the counter is the
    // serialising source once seeded.
    const priorAwardedToday = dailyCount.data().count;

    // 13. Rate/velocity signals for risk scoring.
    const [attemptsSnap, successesSnap, latestPosition] = await Promise.all([
      claimsRef
        .where('userId', '==', uid)
        .where('createdAt', '>=', Timestamp.fromMillis(now.getTime() - 60_000))
        .count()
        .get(),
      claimsRef
        .where('userId', '==', uid)
        .where('result', '==', 'awarded')
        .where(
          'claimedAt',
          '>=',
          Timestamp.fromMillis(now.getTime() - HIGH_VELOCITY_WINDOW_SECONDS * 1000),
        )
        .count()
        .get(),
      readLatestTrustedPosition(uid),
    ]);

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

    // 14. Risk evaluation (ported thresholds; reasons never reach clients).
    const riskEval = evaluateClaimRisk({
      positionStale,
      poorAccuracy: (input.accuracyMeters ?? 0) > 50,
      impossibleJump,
      duplicateIdempotencyKey: false, // handled in step 4
      attemptsInLastMinute: attemptsSnap.data().count,
      successfulClaimsInVelocityWindow: successesSnap.data().count,
      geofenceEdgeAttempts: 0, // legacy TODO: geofence-edge counting
      accuracyMeters: input.accuracyMeters ?? null,
      platformIntegrityPassed: input.platformIntegrityPassed ?? null,
    });

    if (riskEval.isHighRisk) {
      logRejection('risk_review', { distanceMeters });
      const existing = await recordAttempt(
        scopedKey,
        {
          pointId: input.pointId,
          userId: uid,
          result: 'risk_review',
          claimedAt: Timestamp.fromDate(now),
          distanceMeters,
          positionRecordedAt: Timestamp.fromDate(recordedAtDate),
          reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
        },
        {
          userId: uid,
          pointId: input.pointId,
          riskScore: riskEval.riskScore,
          riskReasons: riskEval.riskReasons,
        },
      );
      if (existing) {
        return replayStoredClaim(existing, input.pointId);
      }
      return respond('risk_review');
    }

    // 15. Award: ledger credit + claim record + risk record, atomically
    // (AtomicExtraWrites runs inside the ledger transaction; replays via the
    // derived ledger idempotency key add no extra writes).
    //
    // Steps 11/12 above are non-transactional fast-path reads; they cannot
    // stop concurrent claims that use DISTINCT client idempotency keys (each
    // is an independent transaction that serialises only on the shared
    // pointsLedger balance, which does not re-check the repeat rule or cap).
    // The authoritative enforcement lives HERE, inside the award
    // transaction, on documents whose IDs do NOT derive from the client key:
    //   - crownHuntAwardGuards/{uid__point__window}: a `tx.create` that a
    //     second concurrent award for the same window loses (already_claimed);
    //   - crownHuntDailyClaims/{uid__utcDay}: a counter read in the read guard
    //     and incremented here, so the daily cap holds under concurrency.
    // Kronjakt PvP BOOST (Dubbla Poäng): a hand-placed crown also pays 2x while
    // the collector's boost is active. Best-effort + flag-gated (returns 1 when
    // crownHuntPerks is OFF), so it is a no-op until PvP is enabled; the doubled
    // award keeps `source: 'crown_hunt'`, so the daily fold charges the full
    // boosted amount to the 300/day cap.
    const boostMultiplier = await resolveActiveBoostMultiplier(uid, now);
    // Kronjakt LIVE-SHARE scoring: a hand-placed crown collected while NOT
    // live-sharing pays half. Best-effort + flag-gated + FAIL-OPEN (returns 1
    // when crownHuntLiveShareScoring is OFF, when an active session is present,
    // or on any read error), so a sharer is never wrongly penalised and the
    // feature is a no-op until the flag is on. Composes with the boost
    // multiplier; the rounded amount keeps `source: 'crown_hunt'` so the daily
    // fold charges what was actually awarded.
    const liveShareMultiplier = await resolveLiveShareMultiplier(uid, now);
    const rewardPoints = Math.round(
      (point!.rewardPoints as number) * boostMultiplier * liveShareMultiplier,
    );
    const repeatRule = point!.repeatRule as CrownHuntRepeatRule;
    // Distinct-collector cap is read AUTHORITATIVELY from the in-transaction
    // point snapshot in the read guard below (not from the non-transactional
    // step-5 read), so an admin changing maxCollectors between the two cannot be
    // enforced stably. null/absent = unlimited (the whole collector-slot path is
    // skipped); a positive integer caps DISTINCT collectors.
    const awardGuardRef = db
      .collection('crownHuntAwardGuards')
      .doc(awardGuardDocId(uid, input.pointId, repeatRule, now));
    const dailyCounterRef = db
      .collection('crownHuntDailyClaims')
      .doc(dailyClaimCounterDocId(uid, now));
    // Per-(point,user) distinct-collector marker + the point doc itself, used
    // only for limited crowns. The point's `collectorCount` is the
    // authoritative tally, read and bumped INSIDE the award transaction so a
    // burst of concurrent collects (e.g. at an event) can never exceed the cap.
    const collectorMarkerRef = db
      .collection('crownHuntPointCollectors')
      .doc(pointCollectorDocId(input.pointId, uid));
    const pointRef = db.collection('crownHuntPoints').doc(input.pointId);
    // Computed in the read guard, written absolutely in the write phase so the
    // counter self-heals from the authoritative awarded-claims count when its
    // document is missing (mid-day deploy / deleted counter).
    let nextDailyCount = priorAwardedToday + 1;
    // Collector-slot bookkeeping resolved in the read guard, applied in the
    // write phase. `isNewCollector` is true only when this user has no marker
    // yet (so a daily/weekly re-collect does not consume a second slot).
    let isNewCollector = false;
    let nextCollectorCount = 0;
    let capReached = false;

    try {
      const ledgerResult = await creditPoints(
        {
          targetUid: uid,
          amount: rewardPoints,
          transactionType: 'earn',
          source: 'crown_hunt',
          description: `Kronjakt: ${point!.title as string}`,
          idempotencyKey: claimLedgerIdempotencyKey(scopedKey),
          relatedEntityType: 'crown_hunt_point',
          relatedEntityId: input.pointId,
        },
        (tx, mutation) => {
          // Write phase. The guard `create` fails with ALREADY_EXISTS if a
          // concurrent award already claimed this window; the counter
          // increment pairs with the read-guard check below.
          tx.create(awardGuardRef, {
            userId: uid,
            pointId: input.pointId,
            repeatRule,
            windowKey: awardGuardWindowKey(repeatRule, now),
            claimedAt: Timestamp.fromDate(now),
            createdAt: FieldValue.serverTimestamp(),
          });
          tx.set(
            dailyCounterRef,
            {
              userId: uid,
              day: utcDayKey(now),
              count: nextDailyCount,
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true },
          );
          tx.set(claimsRef.doc(scopedKey), {
            pointId: input.pointId,
            userId: uid,
            result: 'awarded',
            claimedAt: Timestamp.fromDate(now),
            distanceMeters,
            positionRecordedAt: Timestamp.fromDate(recordedAtDate),
            reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
            pointsAwarded: mutation.amount,
            balanceAfter: mutation.balanceAfter,
            pointsLedgerEntryId: mutation.entryId,
            createdAt: FieldValue.serverTimestamp(),
          });
          if (riskEval.riskScore > 0) {
            tx.set(db.collection('crownHuntClaimRisk').doc(scopedKey), {
              userId: uid,
              pointId: input.pointId,
              riskScore: riskEval.riskScore,
              riskReasons: riskEval.riskReasons,
              createdAt: FieldValue.serverTimestamp(),
            });
          }
          // Limited crown: this is a NEW distinct collector (isNewCollector is
          // only set inside the read guard's effectiveMaxCollectors!==null path,
          // so it already reflects the authoritative in-transaction cap) —
          // record the marker and bump the tally. When it reaches the cap the
          // crown is done: deactivate it (status 'ended') so no one else can
          // collect and it stops rendering on the map (members read only active
          // points). pointRef was read in the read guard, so this update is safe.
          if (isNewCollector) {
            tx.create(collectorMarkerRef, {
              pointId: input.pointId,
              userId: uid,
              collectedAt: Timestamp.fromDate(now),
              createdAt: FieldValue.serverTimestamp(),
            });
            const pointUpdate: Record<string, unknown> = {
              collectorCount: nextCollectorCount,
              updatedAt: FieldValue.serverTimestamp(),
            };
            if (capReached) {
              pointUpdate.status = 'ended';
            }
            tx.update(pointRef, pointUpdate);
          }
        },
        // Read phase (runs before the writes above): reject a duplicate or an
        // over-cap claim from inside the transaction. Reads only.
        async (tx) => {
          // creditPoints re-runs this whole callback on each Firestore retry
          // (contention on the point counter is expected at a busy event), so
          // RESET the collector-slot state every attempt and derive it ONLY from
          // this attempt's reads below — never let a value from a previous,
          // aborted attempt leak into the write phase.
          isNewCollector = false;
          nextCollectorCount = 0;
          capReached = false;
          const [guardSnap, counterSnap, pointTxSnap] = await Promise.all([
            tx.get(awardGuardRef),
            tx.get(dailyCounterRef),
            tx.get(pointRef),
          ]);
          // Re-check the point INSIDE the transaction for BOTH unlimited and
          // limited crowns: it may have been paused, ended, or deleted between
          // the step-5 read and here, and an award must never land on an
          // inactive point. (pointTxSnap is read in the same Promise.all.)
          if (!pointTxSnap.exists || pointTxSnap.data()?.status !== 'active') {
            throw new ClaimGuardRejection('point_inactive');
          }
          if (guardSnap.exists) {
            throw new ClaimGuardRejection('already_claimed');
          }
          // Trust the counter when present; otherwise seed from the
          // authoritative awarded-claims count so a missing counter cannot
          // reset the day's cap. Written back absolutely in the write phase.
          const currentDaily = counterSnap.exists
            ? ((counterSnap.data()?.count as number | undefined) ?? priorAwardedToday)
            : priorAwardedToday;
          if (currentDaily >= MAX_DAILY_SUCCESSFUL_CLAIMS) {
            throw new ClaimGuardRejection('daily_limit_reached');
          }
          nextDailyCount = currentDaily + 1;

          // Distinct-collector cap — AUTHORITATIVE from the in-transaction point
          // snapshot (pointTxSnap read above), NOT the non-transactional step-5
          // read: an admin may have changed maxCollectors (or cleared it to
          // unlimited) in between, and the same value must drive both this guard
          // and the writes above. (updatePoint additionally forbids turning an
          // already-collected unlimited crown limited, so a stale-null fast path
          // cannot under-enforce a newly-added cap.)
          const effectiveMaxCollectors =
            (pointTxSnap.data()?.maxCollectors as number | null | undefined) ?? null;
          if (effectiveMaxCollectors !== null) {
            // (Active-status was already re-checked above for all crowns, incl.
            // a concurrent Nth collect that just deactivated this one.)
            // Marker existence decides ONLY new-vs-repeat; the cap governs the
            // HEADCOUNT and must NOT override repeatRule. A marker-holder is an
            // EXISTING collector who already occupies a slot, so this claim
            // consumes none (isNewCollector stays false → no marker/count write)
            // and proceeds — the award guard above already enforced the repeat
            // window ('once'/too-soon re-collects rejected there; a daily/weekly
            // limited crown still lets an existing collector re-collect in a NEW
            // window). Only a NEW collector is subject to the cap.
            const markerSnap = await tx.get(collectorMarkerRef);
            if (!markerSnap.exists) {
              // New distinct collector. The crown is full when collectorCount
              // has reached the cap — including the edge case where an admin
              // lowered maxCollectors below collectorCount. Reject new
              // collectors once full.
              const currentCollectors =
                (pointTxSnap.data()?.collectorCount as number | undefined) ?? 0;
              if (currentCollectors >= effectiveMaxCollectors) {
                throw new ClaimGuardRejection('point_inactive');
              }
              isNewCollector = true;
              nextCollectorCount = currentCollectors + 1;
              capReached = nextCollectorCount >= effectiveMaxCollectors;
            }
          }
        },
      );

      return {
        result: 'awarded',
        pointsAwarded: ledgerResult.amount,
        newBalance: ledgerResult.balanceAfter,
        message: getClaimMessage('awarded'),
      };
    } catch (error) {
      const guarded = classifyClaimGuardRejection(error);
      if (!guarded) {
        throw error;
      }
      // Lost the race: record the attempt with the authoritative result and
      // replay it, mirroring the non-transactional steps 11/12.
      logRejection(guarded, { distanceMeters });
      const existing = await recordAttempt(scopedKey, {
        pointId: input.pointId,
        userId: uid,
        result: guarded,
        claimedAt: Timestamp.fromDate(now),
        distanceMeters,
        positionRecordedAt: Timestamp.fromDate(recordedAtDate),
      });
      if (existing) {
        return replayStoredClaim(existing, input.pointId);
      }
      return respond(guarded);
    }
  },
);
