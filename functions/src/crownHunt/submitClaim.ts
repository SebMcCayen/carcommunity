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
import { readFeatureFlag } from '../shared/featureFlags';
import { canAccessMemberFeatures, toUserAccessState } from '../shared/access';
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
  MAX_CLAIM_SPEED_MPS,
  MAX_DAILY_SUCCESSFUL_CLAIMS,
  claimLedgerIdempotencyKey,
  getClaimMessage,
  isPointCurrentlyAvailable,
  parseSubmitClaimInput,
  repeatRuleWindowStart,
  scopeClaimIdempotencyKey,
  startOfUtcDay,
  type CrownHuntClaimResult,
  type CrownHuntRepeatRule,
} from './crownhunt-core';

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

/** crownHunt feature flag via the shared reader (Phase 9m). */
async function isCrownHuntEnabled(): Promise<boolean> {
  return readFeatureFlag(CROWN_HUNT_FLAG_KEY);
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

    // 1. Feature flag (legacy step 1 — no attempt record).
    if (!(await isCrownHuntEnabled())) {
      return respond('feature_disabled');
    }

    // 2 + 3. Account status and entitlement (result codes, not errors).
    const userSnap = await db.collection('users').doc(uid).get();
    const state = toUserAccessState(userSnap.data());
    if (!canAccessMemberFeatures(state)) {
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
      const existing = await recordAttempt(scopedKey, {
        pointId: input.pointId,
        userId: uid,
        result: 'position_too_old',
        claimedAt: Timestamp.fromDate(now),
        positionRecordedAt: Timestamp.fromDate(recordedAtDate),
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
      const existing = await recordAttempt(scopedKey, {
        pointId: input.pointId,
        userId: uid,
        result: 'outside_geofence',
        claimedAt: Timestamp.fromDate(now),
        distanceMeters,
        positionRecordedAt: Timestamp.fromDate(recordedAtDate),
        reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
      });
      if (existing) {
        return replayStoredClaim(existing, input.pointId);
      }
      return respond('outside_geofence');
    }

    // 10. Speed check — claiming requires being safely stopped.
    if (!isSpeedSafe(input.speedMetersPerSecond, MAX_CLAIM_SPEED_MPS)) {
      const existing = await recordAttempt(scopedKey, {
        pointId: input.pointId,
        userId: uid,
        result: 'moving_too_fast',
        claimedAt: Timestamp.fromDate(now),
        distanceMeters,
        positionRecordedAt: Timestamp.fromDate(recordedAtDate),
        reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
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
    const rewardPoints = point!.rewardPoints as number;
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
      },
    );

    return {
      result: 'awarded',
      pointsAwarded: ledgerResult.amount,
      newBalance: ledgerResult.balanceAfter,
      message: getClaimMessage('awarded'),
    };
  },
);
