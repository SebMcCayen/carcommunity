/**
 * crownHunt.claimSpawn — callable (contracts/functions/functions.json).
 *
 * Collects an AUTO-SPAWNED crown (`crownSpawns`). The sibling of
 * `crownHunt.submitClaim`, which collects a hand-placed admin point
 * (`crownHuntPoints`); the two share every geo and risk primitive
 * (crown-hunt-geo.ts, crown-hunt-risk.ts) and the same points ledger, and
 * differ only where the two kinds of crown genuinely differ.
 *
 * ## What is different from submitClaim, and why
 *
 * ONCE GLOBALLY, NOT ONCE PER USER. A spawned crown is claimed by the first
 * member to reach it and then it is gone, for everyone. Per-user claiming was
 * the alternative and is rejected: it turns a hunt into a checklist. If every
 * crown is waiting for you personally, arriving first means nothing, there is
 * no reason to go now rather than tomorrow, and a coordinate leaked to a group
 * chat pays out once per member instead of once. First-come is also the only
 * variant where the density and separation rules do real work — with per-user
 * claiming a cell's five crowns are five rewards for every member forever, and
 * the map would need no replenishment at all. The cost is that a member can
 * arrive to find a crown taken; that is the game.
 *
 * (Per-user repetition still exists in Kronjakt — it is what the hand-placed
 * points' `repeatRule: daily|weekly` is for. Curated points are destinations
 * you return to; spawns are opportunities you catch.)
 *
 * STATIONARY, NOT JUST SLOW. `submitClaim` checks one reported speed against
 * 1.4 m/s. That is a single number the client chose. Here a claim must carry
 * TWO position fixes, and the server checks that both are inside the radius,
 * that they are 4–300 s apart, that both reported speeds are ≤ 2.0 m/s, AND
 * that the speed it DERIVES from the two coordinates itself is ≤ 2.0 m/s. The
 * derived check is the one that cannot be talked out of.
 *
 * A failed stationary check is a PLAIN REFUSAL with its own result code and a
 * Swedish message that says "stop safely and try again". It is deliberately not
 * routed through the risk score: the overwhelmingly common case is an honest
 * member rolling through a car park, and telling them they look like a cheat
 * would be both wrong and useless. Nothing in this file rewards speed — there
 * is no time bonus, no arrival streak, no fastest-collector anything.
 *
 * MOCK LOCATION. The claim may carry Android's `Location.isMock`. Reported
 * `true` scores at the review threshold on its own (crown-hunt-risk.ts), so it
 * alone sends the claim to `risk_review` with no award. It is one-way: absent
 * and `false` are treated identically, because a spoofing client simply would
 * not set it.
 *
 * ## Everything else is the ported chain
 * Feature flags, entitlement, idempotent replay (document ID = a namespaced
 * SHA-256 scoping of the client key), position freshness, server-computed
 * Haversine distance (a client-supplied distance is never read), impossible-jump
 * detection against the RTDB trusted position, the shared `evaluateClaimRisk`
 * scorer with its 60-point review threshold, a per-user daily cap, and an award
 * that commits atomically with the claim record through the ledger's
 * transaction primitives. Risk scores and reasons go to a backend-only
 * collection and never reach a client.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { adminRtdb, db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import { toUserAccessState } from '../shared/access';
import { memberGateAllows } from '../shared/memberGating';
import { creditPoints } from '../points/ledger';
import {
  haversineDistanceMeters,
  isPlausibleJump,
  isPositionFresh,
  isValidCoordinate,
} from './crown-hunt-geo';
import { HIGH_VELOCITY_WINDOW_SECONDS, evaluateClaimRisk } from './crown-hunt-risk';
import { CROWN_HUNT_FLAG_KEY } from './crownhunt-core';
import {
  CROWN_SPAWN_FLAG_KEY,
  MAX_DAILY_SPAWN_CLAIMS,
  evaluateStationaryCollection,
  getSpawnClaimMessage,
  parseClaimSpawnInput,
  resolveCollectMode,
  resolveCollectRadiusMeters,
  scopeSpawnClaimKey,
  spawnClaimLedgerIdempotencyKey,
  spawnCollectorDocId,
  spawnDailyCounterDocId,
  utcDayKey,
  type CrownCollectMode,
  type CrownRarity,
  type CrownSpawnClaimResult,
} from './crown-spawn-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface ClaimSpawnResponse {
  result: CrownSpawnClaimResult;
  pointsAwarded: number | null;
  newBalance: number | null;
  /** The crown's rarity, echoed so the client can play the right reveal. */
  rarity: string | null;
  message: string;
}

function respond(result: CrownSpawnClaimResult, rarity: string | null = null): ClaimSpawnResponse {
  return {
    result,
    pointsAwarded: null,
    newBalance: null,
    rarity,
    message: getSpawnClaimMessage(result),
  };
}

/**
 * Thrown inside the award transaction's read guard when the crown was taken,
 * expired, or the caller is over their daily cap. Carries the authoritative
 * result code the caller replies with.
 */
class SpawnClaimRejection extends Error {
  constructor(readonly result: CrownSpawnClaimResult) {
    super(result);
    this.name = 'SpawnClaimRejection';
  }
}

/** Replays a stored claim for the same idempotency key. */
function replayStoredClaim(
  existing: FirebaseFirestore.DocumentData,
  requestedSpawnId: string,
): ClaimSpawnResponse {
  // A key reused against a DIFFERENT crown is a client bug or a replay attempt,
  // never a legitimate retry — answer with the taken result rather than leaking
  // the other crown's outcome (mirrors submitClaim's parity behaviour).
  if (existing.spawnId !== requestedSpawnId) {
    return respond('already_taken');
  }
  const result = existing.result as CrownSpawnClaimResult;
  const rarity = (existing.rarity as string | null) ?? null;
  if (result === 'awarded') {
    return {
      result,
      pointsAwarded: (existing.pointsAwarded as number | null) ?? null,
      newBalance: (existing.balanceAfter as number | null) ?? null,
      rarity,
      message: getSpawnClaimMessage(result),
    };
  }
  return respond(result, rarity);
}

/**
 * Records a non-awarded attempt WITHOUT overwriting an existing claim: two
 * concurrent requests with the same key serialize in a transaction and the
 * loser replays the stored result instead of clobbering it. Returns null when
 * this attempt was recorded, or the existing document.
 */
async function recordAttempt(
  scopedKey: string,
  data: Record<string, unknown>,
  riskData?: Record<string, unknown>,
): Promise<FirebaseFirestore.DocumentData | null> {
  const claimRef = db.collection('crownSpawnClaims').doc(scopedKey);
  return db.runTransaction(async (tx) => {
    const existing = await tx.get(claimRef);
    if (existing.exists) {
      return existing.data()!;
    }
    tx.set(claimRef, { ...data, createdAt: FieldValue.serverTimestamp() });
    if (riskData) {
      tx.set(db.collection('crownSpawnClaimRisk').doc(scopedKey), {
        ...riskData,
        createdAt: FieldValue.serverTimestamp(),
      });
    }
    return null;
  });
}

/** Latest trusted position from RTDB for jump detection; null when absent. */
async function readLatestTrustedPosition(
  uid: string,
): Promise<{ latitude: number; longitude: number; recordedAt: string } | null> {
  try {
    const snap = await adminRtdb.ref(`liveLocation/${uid}/latest`).get();
    const value = snap.val() as {
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
    // No trusted position → no jump signal. Never fail a claim over a
    // best-effort risk read.
    logger.warn('Latest position read failed; skipping jump check', { uid, error: String(error) });
    return null;
  }
}

export const claimSpawn = onCall(CALLABLE_OPTS, async (request): Promise<ClaimSpawnResponse> => {
  const auth = request.auth;
  if (!auth) {
    throw new HttpsError('unauthenticated', 'Sign in to collect a Kronjakt crown.');
  }
  const uid = auth.uid;

  const parsed = parseClaimSpawnInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  const now = new Date();
  const scopedKey = scopeSpawnClaimKey(uid, input.idempotencyKey);
  const claimsRef = db.collection('crownSpawnClaims');

  // 1. Feature flags. BOTH must be on: `crownHunt` is the domain switch, and
  // `crownHuntSpawn` is the auto-spawn switch that also gates the spawner — a
  // member must never be able to collect from a system that is officially off.
  const [huntEnabled, spawnEnabled] = await Promise.all([
    readFeatureFlag(CROWN_HUNT_FLAG_KEY),
    readFeatureFlag(CROWN_SPAWN_FLAG_KEY),
  ]);
  if (!huntEnabled || !spawnEnabled) {
    return respond('feature_disabled');
  }

  // 2. Account status and entitlement (result codes, not errors — parity with
  // submitClaim). Entitlement is currently bypassed repo-wide
  // (shared/memberGating.ts); suspended and deleted accounts still fail.
  const userSnap = await db.collection('users').doc(uid).get();
  if (!memberGateAllows(toUserAccessState(userSnap.data()))) {
    return respond('not_eligible');
  }

  // 3. Idempotent replay.
  const existingClaim = await claimsRef.doc(scopedKey).get();
  if (existingClaim.exists) {
    return replayStoredClaim(existingClaim.data()!, input.spawnId);
  }

  // 4. Load the crown. Gone, taken, or expired are all reported honestly —
  // there is no reason to hide from a member that someone beat them to it.
  const spawnRef = db.collection('crownSpawns').doc(input.spawnId);
  const spawnSnap = await spawnRef.get();
  const spawn = spawnSnap.data();
  if (!spawnSnap.exists) {
    return respond('crown_expired');
  }
  const rarity = (spawn!.rarity as string | undefined) ?? null;
  if (spawn!.status !== 'live') {
    return respond('already_taken', rarity);
  }
  const expiresAt = spawn!.expiresAt as Timestamp | undefined;
  if (!expiresAt || expiresAt.toMillis() <= now.getTime()) {
    return respond('crown_expired', rarity);
  }

  // 4b. Collection mode. SHARED crowns are collectable ONCE PER DISTINCT MEMBER
  // and stay on the map to their TTL; EXCLUSIVE crowns are first-come and
  // removed on the first claim. The per-(crown, user) collector record is what
  // makes "once per member" hold across a member's separate requests (each with
  // a fresh idempotency key), so it is checked here as a fast path AND re-checked
  // transactionally in the award below, where it is the authority — exactly as
  // the crown's own status is the authority for an exclusive crown.
  const rarityForMode = (spawn!.rarity as CrownRarity | undefined) ?? 'common';
  const collectMode: CrownCollectMode = resolveCollectMode(spawn!.collectMode, rarityForMode);
  const collectorRef = db
    .collection('crownSpawnCollectors')
    .doc(spawnCollectorDocId(input.spawnId, uid));
  if (collectMode === 'shared') {
    const alreadyCollected = await collectorRef.get();
    if (alreadyCollected.exists) {
      return respond('already_collected', rarity);
    }
  }

  // 5. Coordinate validation (malformed input is an ERROR, not a result code).
  if (
    !isValidCoordinate(input.latitude, input.longitude) ||
    !isValidCoordinate(input.previousFix.latitude, input.previousFix.longitude)
  ) {
    throw new HttpsError('invalid-argument', 'Invalid coordinates provided.');
  }
  const recordedAtDate = new Date(input.recordedAt);
  const previousRecordedAt = new Date(input.previousFix.recordedAt);

  const attemptBase = {
    spawnId: input.spawnId,
    userId: uid,
    rarity,
    claimedAt: Timestamp.fromDate(now),
    positionRecordedAt: Timestamp.fromDate(recordedAtDate),
    reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
  };

  // 6. Freshness of the CURRENT fix (the previous one is allowed to be older —
  // that is the whole point of a dwell window; MAX_DWELL_SECONDS bounds it).
  const positionStale = !isPositionFresh(input.recordedAt, now.getTime());
  if (positionStale) {
    const existing = await recordAttempt(scopedKey, {
      ...attemptBase,
      result: 'position_too_old',
    });
    return existing
      ? replayStoredClaim(existing, input.spawnId)
      : respond('position_too_old', rarity);
  }

  // 7. Server-computed distances. Every distance in this flow is computed here
  // from coordinates; the client never supplies one.
  const crownLat = spawn!.latitude as number;
  const crownLon = spawn!.longitude as number;
  // The stored radius is validated, not cast. A bad latitude/longitude on the
  // crown already fails closed (a non-numeric one makes the distance NaN, and
  // `NaN <= radius` is false, so the claim is refused), but an oversized radius
  // is the one corruption that would fail OPEN — a wider geofence pays out to
  // someone who was never there. Anything not a sane positive number falls back
  // to the 75 m default.
  const collectRadius = resolveCollectRadiusMeters(spawn!.collectRadiusMeters);
  const distanceMeters = haversineDistanceMeters(
    input.latitude,
    input.longitude,
    crownLat,
    crownLon,
  );
  const previousDistanceMeters = haversineDistanceMeters(
    input.previousFix.latitude,
    input.previousFix.longitude,
    crownLat,
    crownLon,
  );
  const movedMeters = haversineDistanceMeters(
    input.previousFix.latitude,
    input.previousFix.longitude,
    input.latitude,
    input.longitude,
  );

  // 8. Radius + the stationary rule, in one evaluation so the two cannot drift.
  const stationary = evaluateStationaryCollection({
    current: {
      distanceMeters,
      speedMetersPerSecond: input.speedMetersPerSecond ?? null,
      accuracyMeters: input.accuracyMeters ?? null,
      recordedAtMs: recordedAtDate.getTime(),
    },
    previous: {
      distanceMeters: previousDistanceMeters,
      speedMetersPerSecond: input.previousFix.speedMetersPerSecond ?? null,
      accuracyMeters: input.previousFix.accuracyMeters ?? null,
      recordedAtMs: previousRecordedAt.getTime(),
    },
    movedMeters,
    collectRadiusMeters: collectRadius,
  });
  if (!stationary.ok) {
    const existing = await recordAttempt(scopedKey, {
      ...attemptBase,
      result: stationary.result,
      distanceMeters,
    });
    return existing
      ? replayStoredClaim(existing, input.spawnId)
      : respond(stationary.result, rarity);
  }

  // 9. Risk signals.
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

  const riskEval = evaluateClaimRisk({
    positionStale,
    poorAccuracy: (input.accuracyMeters ?? 0) > 50,
    impossibleJump,
    duplicateIdempotencyKey: false, // handled in step 3
    attemptsInLastMinute: attemptsSnap.data().count,
    successfulClaimsInVelocityWindow: successesSnap.data().count,
    geofenceEdgeAttempts: 0,
    accuracyMeters: input.accuracyMeters ?? null,
    platformIntegrityPassed: input.platformIntegrityPassed ?? null,
    mockLocationReported: input.isMockLocation ?? null,
  });

  if (riskEval.isHighRisk) {
    const existing = await recordAttempt(
      scopedKey,
      { ...attemptBase, result: 'risk_review', distanceMeters },
      {
        userId: uid,
        spawnId: input.spawnId,
        riskScore: riskEval.riskScore,
        riskReasons: riskEval.riskReasons,
      },
    );
    return existing ? replayStoredClaim(existing, input.spawnId) : respond('risk_review', rarity);
  }

  // 10. Award. The daily cap and the crown's collection rule are enforced INSIDE
  // the ledger transaction, on documents whose IDs do not derive from the client
  // idempotency key: the per-day counter, and — depending on mode — the crown
  // document itself (EXCLUSIVE: read in the guard, flipped to `claimed` in the
  // writes, so two members tapping the same jackpot serialize on it and exactly
  // one wins) or the per-(crown, user) collector record (SHARED: created in the
  // writes so the SAME member cannot double-collect, while two DIFFERENT members
  // touch different collector docs and so never contend). The pre-transaction
  // reads in step 4/4b are only fast paths, never the authority.
  const rewardPoints = spawn!.rewardPoints as number;
  const dailyCounterRef = db
    .collection('crownSpawnDailyClaims')
    .doc(spawnDailyCounterDocId(uid, now));
  let nextDailyCount = 1;
  // The AUTHORITATIVE collection mode, resolved from the crown READ INSIDE the
  // transaction's read phase (below) — the `collectMode` computed pre-transaction
  // in step 4b is only a fast path. Every write-phase branch (shared once-per-
  // member vs exclusive remove-on-first-claim) reads THIS, so a transaction retry
  // re-derives it from that attempt's own read rather than trusting a value read
  // before the transaction began. Seeded with the fast-path value only so the
  // type is non-null; the read phase always overwrites it before any write runs.
  let txCollectMode: CrownCollectMode = collectMode;

  try {
    const ledgerResult = await creditPoints(
      {
        targetUid: uid,
        amount: rewardPoints,
        transactionType: 'earn',
        source: 'crown_hunt',
        description: `Kronjakt: ${rarity ?? 'krona'}`,
        idempotencyKey: spawnClaimLedgerIdempotencyKey(scopedKey),
        relatedEntityType: 'crown_spawn',
        relatedEntityId: input.spawnId,
      },
      (tx, mutation) => {
        // Write phase. Branches on txCollectMode (resolved from the in-transaction
        // crown read below), never the pre-transaction collectMode.
        if (txCollectMode === 'exclusive') {
          // EXCLUSIVE: the first taker removes it for everyone. Flip to claimed
          // and expire AT the claim instant so the existing sweep reaps it and
          // the client read rule (status live + unexpired) hides it immediately —
          // a claimed jackpot must leave the map at once, not linger to its TTL.
          tx.update(spawnRef, {
            status: 'claimed',
            claimedByUid: uid,
            claimedAt: Timestamp.fromDate(now),
            expiresAt: Timestamp.fromDate(now),
          });
        } else {
          // SHARED: the crown is UNTOUCHED — it stays live on the map until its
          // TTL for the next member. This member's one pickup is recorded by the
          // collector doc, which the read guard proved absent and which its
          // create makes present, so a repeat (any future request) is refused.
          tx.set(collectorRef, {
            spawnId: input.spawnId,
            userId: uid,
            // rarityForMode, not the raw nullable `rarity`: it is the
            // resolveCollectMode-normalised value (a guaranteed CrownRarity,
            // falling back to 'common'), so a collector record is never written
            // with a null/invalid rarity even if the crown document's field is.
            rarity: rarityForMode,
            collectedAt: Timestamp.fromDate(now),
            // Bounds the collection's growth: a collector record is only useful
            // while its crown is still live, so it expires WITH the crown. A
            // Firestore TTL policy on `expireAt` (a manual operator step, like
            // crownCellActivity/messages) reaps these alongside the swept crown;
            // until then the sweeper never touches them, so the field is the only
            // thing keeping the collection from growing without bound.
            expireAt: expiresAt,
            createdAt: FieldValue.serverTimestamp(),
          });
        }
        tx.set(
          dailyCounterRef,
          {
            userId: uid,
            day: utcDayKey(now),
            count: nextDailyCount,
            // Lets a TTL policy reap spent counters; nothing reads a past day.
            expireAt: Timestamp.fromMillis(now.getTime() + 3 * 24 * 60 * 60 * 1000),
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
        tx.set(claimsRef.doc(scopedKey), {
          ...attemptBase,
          result: 'awarded',
          distanceMeters,
          dwellSeconds: (recordedAtDate.getTime() - previousRecordedAt.getTime()) / 1000,
          pointsAwarded: mutation.amount,
          balanceAfter: mutation.balanceAfter,
          pointsLedgerEntryId: mutation.entryId,
          createdAt: FieldValue.serverTimestamp(),
        });
        if (riskEval.riskScore > 0) {
          tx.set(db.collection('crownSpawnClaimRisk').doc(scopedKey), {
            userId: uid,
            spawnId: input.spawnId,
            riskScore: riskEval.riskScore,
            riskReasons: riskEval.riskReasons,
            createdAt: FieldValue.serverTimestamp(),
          });
        }
      },
      // Read phase (all reads before any write, per Firestore's rule).
      async (tx) => {
        const [crownSnap, counterSnap] = await Promise.all([
          tx.get(spawnRef),
          tx.get(dailyCounterRef),
        ]);
        const crown = crownSnap.data();
        // A MISSING document and a non-live one are different answers, and the
        // fast path in step 4 already distinguishes them. Gone means gone: the
        // sweeper reaped it after expiry, or a cell revocation deleted it out
        // from under this call because an admin had just declared that area
        // unsafe. Neither of those is "someone beat you to it", and saying so
        // would send a member looking for a winner who does not exist.
        // `already_taken` is reserved for a crown that still EXISTS and whose
        // status says another member has it.
        if (!crownSnap.exists) {
          throw new SpawnClaimRejection('crown_expired');
        }
        if (crown!.status !== 'live') {
          throw new SpawnClaimRejection('already_taken');
        }
        const crownExpiry = crown!.expiresAt as Timestamp | undefined;
        if (!crownExpiry || crownExpiry.toMillis() <= now.getTime()) {
          throw new SpawnClaimRejection('crown_expired');
        }
        // Resolve the collection mode from THIS transaction's crown read, so the
        // write phase and the collector check below both branch on a value read
        // inside the current attempt (retry-safe), not the pre-transaction one.
        txCollectMode = resolveCollectMode(
          crown!.collectMode,
          (crown!.rarity as CrownRarity | undefined) ?? 'common',
        );
        // SHARED: this member's prior pickup is the authority for "once per
        // member", re-checked here so a race between two of the same member's
        // requests (different idempotency keys) cannot double-award — the loser
        // re-reads the collector doc its rival created and rejects. Read AFTER the
        // crown, so the decision to read it uses the in-transaction mode; still a
        // read, so it precedes every write.
        if (txCollectMode === 'shared') {
          const collectorSnap = await tx.get(collectorRef);
          if (collectorSnap.exists) {
            throw new SpawnClaimRejection('already_collected');
          }
        }
        const currentDaily = (counterSnap.data()?.count as number | undefined) ?? 0;
        if (currentDaily >= MAX_DAILY_SPAWN_CLAIMS) {
          throw new SpawnClaimRejection('daily_limit_reached');
        }
        nextDailyCount = currentDaily + 1;
      },
    );

    return {
      result: 'awarded',
      pointsAwarded: ledgerResult.amount,
      newBalance: ledgerResult.balanceAfter,
      rarity,
      message: getSpawnClaimMessage('awarded'),
    };
  } catch (error) {
    if (!(error instanceof SpawnClaimRejection)) {
      throw error;
    }
    // Lost the race (or hit the cap): record the authoritative result and reply
    // with it, exactly as the fast-path checks above would have.
    const existing = await recordAttempt(scopedKey, {
      ...attemptBase,
      result: error.result,
      distanceMeters,
    });
    return existing ? replayStoredClaim(existing, input.spawnId) : respond(error.result, rarity);
  }
});
