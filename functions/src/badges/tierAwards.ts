/**
 * Tiered-badge awarding — the Admin SDK side of badge-tiers.ts.
 *
 * Server-authoritative by construction: there is NO callable that awards a
 * tier. The only entry points are the Firestore triggers in
 * progressTriggers.ts (each fired by a write the backend itself made or
 * validated) and the bounded scheduled sweep in scheduled.ts. A client cannot
 * ask for a badge, and cannot write any counter — `badgeProgress/{uid}` denies
 * all client access in firestore.rules.
 *
 * IDEMPOTENCY, in three layers, so re-evaluation is always safe:
 *   1. The badge document ID equals the badge key and the write is a
 *      transactional create-if-absent (awards.ts::awardBadge), so a badge is
 *      never awarded twice however many times evaluation runs.
 *   2. The Kronpoäng credit uses a deterministic ledger idempotency key
 *      (`badge_award_{key}`), which IS the ledger entry document ID, so a
 *      replayed award is a transactional no-op in the ledger too.
 *   3. Qualification is a pure `>=` test over the current counters
 *      (badge-tiers.ts), so it depends on no history and cannot drift.
 *
 * ORDERING: points are credited BEFORE the badge document is written, and the
 * badge is written ONLY if the credit succeeded. The badge document's absence
 * is what marks an award as "not yet processed", so both a crash between the
 * two writes and a failed credit leave the tier unprocessed; the next
 * evaluation replays the credit (a ledger no-op if it did land) and then writes
 * the badge. Writing the badge after a failed credit would be unrecoverable —
 * the key would be excluded from `missing` forever and the member would hold
 * the badge with the Kronpoäng silently lost.
 *
 * COST: evaluation is O(qualified tiers) — one BATCHED `getAll` of the
 * qualified badge documents (at most 22), plus one `badgeProgress` read ONLY
 * when the caller did not already have that document (both real callers do, so
 * on the hot path it is zero). The `getAll` is a single RPC but is BILLED PER
 * DOCUMENT, so a steady-state no-op costs as many reads as the member holds
 * tiers; a member qualifying for nothing returns without any `getAll` at all.
 * It never scans users and never scans a member's full badge subcollection.
 */

import { FieldValue } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { isRestricted, toUserAccessState } from '../shared/access';
import { MAX_VEHICLES_PER_USER } from '../garage/garage-core';
import {
  ALL_TIME_SCOPE,
  CROWN_LEADERBOARD_COLLECTION,
  leaderboardEntryDocId,
} from '../crownHunt/crown-hunt-stats-core';
import { creditPoints } from '../points/ledger';
import { type BadgeMetric, type TierBadgeKey } from './badge-core';
import {
  BADGE_METRIC_FIELD,
  badgeAwardIdempotencyKey,
  badgeAwardPointsDescription,
  qualifiedTierBadges,
  readBadgeCounters,
  tierPointsReward,
  toCounter,
} from './badge-tiers';
import { awardBadge } from './awards';

/** Backend-only counter document feeding every ladder. */
export function badgeProgressRef(uid: string): FirebaseFirestore.DocumentReference {
  return db.collection('badgeProgress').doc(uid);
}

/**
 * Adds `delta` to one server-verified counter. `FieldValue.increment` is
 * commutative, so concurrent source events (two crown claims, two saved
 * drives) cannot race the counter — no transaction is needed for the bump
 * itself.
 *
 * A non-finite or zero delta is dropped rather than written: a corrupt source
 * value must not be able to poison a counter that badges are derived from.
 *
 * NOT IDEMPOTENT UNDER REDELIVERY, deliberately. Firestore triggers are
 * at-least-once, so a redelivered source event increments twice; the guards in
 * badge-tiers.ts are transition tests on the source document, which reject a
 * *rewrite* but cannot detect a *replay* of the same write. This is the same
 * property the pre-existing Phase 9f `completedEventsAttended` counter has, and
 * the bounded consequence is a counter drifting slightly high — a member
 * reaching a rung marginally early. It is NOT an economy exploit: the Kronpoäng
 * for a tier are keyed on `badge_award_{key}` and can only ever be credited
 * once, and redelivery is a platform event, not something a client can induce.
 *
 * The three counters where correctness mattered more than write cost do NOT go
 * through this function and are idempotent by construction: `vehiclesInGarage`
 * and (via the sweep) any snapshot counter use `raiseBadgeCounter` over a
 * re-derived `count()`, and `bestDayStreak` is a transactional day-key
 * comparison. Making `crownsCollected` / `lifetimeDistanceMeters` /
 * `convoysLed` exact would mean re-deriving each from its source on every
 * source event, or a per-event dedupe document — a cost and surface trade-off
 * that is a deliberate product decision, not an oversight.
 */
export async function bumpBadgeCounter(
  uid: string,
  metric: BadgeMetric,
  delta: number,
): Promise<boolean> {
  if (!Number.isFinite(delta) || delta <= 0) {
    return false;
  }
  await badgeProgressRef(uid).set(
    {
      [BADGE_METRIC_FIELD[metric]]: FieldValue.increment(delta),
      updatedAt: FieldValue.serverTimestamp(),
    },
    { merge: true },
  );
  return true;
}

/**
 * Raises a counter to `value` if `value` is higher, leaving it alone
 * otherwise. Used for counters that are RE-DERIVED rather than accumulated
 * (the garage vehicle count), where the ladder measures the peak the member
 * ever reached — deleting a car must not strip a Samlare tier they already
 * hold, and re-adding it must not award a second time.
 */
export async function raiseBadgeCounter(
  uid: string,
  metric: BadgeMetric,
  value: number,
): Promise<boolean> {
  if (!Number.isFinite(value) || value <= 0) {
    return false;
  }
  const field = BADGE_METRIC_FIELD[metric];
  const ref = badgeProgressRef(uid);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const stored = snap.data()?.[field];
    const current = typeof stored === 'number' && Number.isFinite(stored) ? stored : 0;
    if (current >= value) {
      return false;
    }
    tx.set(ref, { [field]: value, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    return true;
  });
}

/**
 * Re-derives the counters that a stream of increments cannot reconstruct on its
 * own and raises the stored running maximum to match. Two counters need this,
 * for two different reasons, and both are reconciled on every sweep pass:
 *
 *  - `vehiclesInGarage` is a SNAPSHOT of current state. `onVehicleCreated` fires
 *    on a vehicle CREATE, so a member whose garage predates the ladders — above
 *    all one at the MAX_VEHICLES_PER_USER cap, who can never create another and
 *    so can never fire that trigger again — would otherwise never earn a Samlare
 *    tier however full their garage is. Re-deriving it on the sweep is what
 *    makes the Samlare ladder reachable for existing members.
 *  - `crownsCollected` is an ACCUMULATED total maintained by the live
 *    crown-claim triggers, but those triggers only started counting auto-spawn
 *    crowns when issue #793 was fixed. Reconciling against the authoritative
 *    all-time Kronjakt leaderboard (`crownHuntLeaderboardEntries/alltime__{uid}`,
 *    which has always counted BOTH hand-placed and auto-spawn collections via
 *    the shared pointsLedger) back-fills every collector who predates the fix,
 *    within one sweep cycle.
 *
 * Both use `raiseBadgeCounter` (a running MAXIMUM), which is why the backfill
 * cannot double-count against the live increments: it lifts the counter TO the
 * leaderboard value, it never adds on top, so once the two agree the sweep is a
 * no-op. A member who has since deleted cars keeps the Samlare tier they earned,
 * and the `risk_review` invariant is preserved for free — a risk_review claim
 * writes no pointsLedger entry, so the leaderboard already excludes it.
 *
 * `knownProgress` is the member's already-loaded `badgeProgress` data, passed by
 * callers that have it in hand so the vehicle-cap shortcut costs no extra read.
 *
 * RETURNS the progress data to evaluate from. When this function raises a
 * counter it patches the new value into the returned snapshot, because the
 * caller's copy predates that write — evaluating from the stale copy would miss
 * the very tier this reconciliation just made reachable. Returns `undefined`
 * when the caller supplied nothing, so the evaluator falls back to reading the
 * document itself.
 *
 * This full reconciliation runs ONLY on the 6-hour sweep. The `onVehicleCreated`
 * trigger deliberately calls `reconcileVehiclesInGarage` directly instead, so a
 * vehicle create pays for only the garage `count()` and never the unrelated
 * leaderboard read — crowns are already handled instantly by
 * `onSpawnClaimWritten`/`onCrownClaimWritten` and, for pre-fix collectors, by
 * this sweep within one cycle.
 */
export async function reconcileDerivedBadgeCounters(
  uid: string,
  knownProgress?: Record<string, unknown>,
): Promise<Record<string, unknown> | undefined> {
  let progress = await reconcileVehiclesInGarage(uid, knownProgress);
  progress = await reconcileCrownsCollected(uid, progress);
  return progress;
}

/**
 * Raises `vehiclesInGarage` to the member's real `count()` of vehicles. Costs
 * one aggregation read per member per sweep, EXCEPT for a member already
 * recorded at the vehicle cap, where it is skipped entirely: the counter is a
 * running maximum and the garage is transactionally capped, so once the stored
 * value reaches MAX_VEHICLES_PER_USER the `count()` can never return anything
 * higher and is guaranteed wasted. Only callers that ALREADY hold the
 * badgeProgress document can use that shortcut — the sweep does, and that is
 * exactly where the read would otherwise repeat every cycle for every maxed-out
 * member.
 *
 * Exported so the `onVehicleCreated` trigger can reconcile ONLY the vehicle
 * counter: a vehicle create has nothing to do with crowns, so it must not pay
 * for the leaderboard read (or trigger a Kronjägare backfill) that the full
 * `reconcileDerivedBadgeCounters` does on the 6-hour sweep.
 */
export async function reconcileVehiclesInGarage(
  uid: string,
  knownProgress?: Record<string, unknown>,
): Promise<Record<string, unknown> | undefined> {
  const field = BADGE_METRIC_FIELD.vehiclesInGarage;
  if (knownProgress !== undefined && toCounter(knownProgress[field]) >= MAX_VEHICLES_PER_USER) {
    return knownProgress;
  }
  const countSnap = await db.collection('vehicles').where('userId', '==', uid).count().get();
  const count = countSnap.data().count;
  await raiseBadgeCounter(uid, 'vehiclesInGarage', count);
  if (knownProgress === undefined) {
    return undefined;
  }
  return toCounter(knownProgress[field]) >= count
    ? knownProgress
    : { ...knownProgress, [field]: count };
}

/**
 * Raises `crownsCollected` to the member's all-time Kronjakt leaderboard total,
 * back-filling collectors whose auto-spawn crowns never reached the badge
 * counter before issue #793 was fixed. Reads one leaderboard document per
 * member per sweep; a member with no crowns (no leaderboard entry) is a no-op.
 */
async function reconcileCrownsCollected(
  uid: string,
  knownProgress: Record<string, unknown> | undefined,
): Promise<Record<string, unknown> | undefined> {
  const field = BADGE_METRIC_FIELD.crownsCollected;
  const entrySnap = await db
    .collection(CROWN_LEADERBOARD_COLLECTION)
    .doc(leaderboardEntryDocId(ALL_TIME_SCOPE, uid))
    .get();
  const collected = toCounter(entrySnap.data()?.crownsCollected);
  if (collected <= 0) {
    return knownProgress;
  }
  await raiseBadgeCounter(uid, 'crownsCollected', collected);
  if (knownProgress === undefined) {
    return undefined;
  }
  return toCounter(knownProgress[field]) >= collected
    ? knownProgress
    : { ...knownProgress, [field]: collected };
}

async function isEligibleForAwards(uid: string): Promise<boolean> {
  const snap = await db.collection('users').doc(uid).get();
  return snap.exists && !isRestricted(toUserAccessState(snap.data()));
}

/**
 * Evaluates every ladder for one member and awards the tiers they have newly
 * reached, crediting the Kronpoäng milestone for each.
 *
 * Returns the keys awarded by THIS call (empty on a no-op re-run). Never
 * throws for an expected condition — a restricted or missing member is a
 * silent no-op, and a failed points credit is logged and DEFERS the badge:
 * awarding it would mark the tier processed forever and strand the Kronpoäng,
 * so the loop stops and the next trigger (or the 6h sweep) retries both.
 */
export async function evaluateAndAwardBadgeTiers(
  uid: string,
  knownProgress?: Record<string, unknown>,
): Promise<TierBadgeKey[]> {
  // Callers that were handed the document already (the onBadgeProgressWritten
  // trigger gets it as the event payload; the sweep gets it in its page) pass
  // it in rather than making this re-read it. Evaluating from that snapshot is
  // safe because qualification is a pure `>=` test and awards are monotonic
  // create-if-absent writes: a snapshot that is momentarily behind can only
  // award FEWER tiers, never a wrong one, and the write that superseded it
  // fires its own evaluation.
  const progress =
    knownProgress ?? (await badgeProgressRef(uid).get()).data();
  const counters = readBadgeCounters(progress);
  const qualified = qualifiedTierBadges(counters);
  if (qualified.length === 0) {
    return [];
  }

  const badgesCollection = db.collection('users').doc(uid).collection('badges');
  const existing = await db.getAll(...qualified.map((key) => badgesCollection.doc(key)));
  const missing = qualified.filter((_key, index) => existing[index]?.exists !== true);
  if (missing.length === 0) {
    return [];
  }

  // Cheap gate AFTER the "is anything new?" test, so the common no-op path
  // never pays for it. Suspended and deleted members earn nothing new; badges
  // they already hold are untouched (legacy parity, awards.ts).
  if (!(await isEligibleForAwards(uid))) {
    return [];
  }

  const awarded: TierBadgeKey[] = [];
  for (const key of missing) {
    // Points first — see the ORDERING note in the module header.
    const reward = tierPointsReward(key);
    if (reward > 0) {
      try {
        await creditPoints({
          targetUid: uid,
          amount: reward,
          transactionType: 'earn',
          source: 'badge',
          description: badgeAwardPointsDescription(key),
          idempotencyKey: badgeAwardIdempotencyKey(key),
          relatedEntityType: 'badge',
          relatedEntityId: key,
        });
      } catch (error) {
        // STOP — do not award the badge. The badge document's absence is the
        // ONLY marker that this tier is unprocessed, so writing it now would
        // permanently exclude the key from `missing` on every later evaluation
        // and the member would keep the badge with the Kronpoäng silently lost.
        // Breaking (rather than skipping to the next tier) also keeps a
        // member's holdings a PREFIX of each ladder, never a gap. The next
        // trigger or the 6h sweep retries the whole tail.
        logger.error('Badge tier points credit failed — deferring award', {
          uid,
          badgeKey: key,
          error: String(error),
        });
        break;
      }
    }
    const result = await awardBadge({ targetUid: uid, badgeKey: key, source: 'automatic' });
    if (result === null) {
      // Became restricted mid-loop — stop rather than keep crediting.
      break;
    }
    if (!result.alreadyAwarded) {
      awarded.push(key);
    }
  }

  if (awarded.length > 0) {
    logger.info('Badge tiers awarded', { uid, badgeKeys: awarded });
  }
  return awarded;
}

/**
 * Fire-and-log wrapper for the triggers: a badge evaluation failure must never
 * fail (and therefore never retry-storm) the source write it hangs off. The
 * scheduled sweep re-evaluates anything a swallowed failure missed.
 */
export async function tryEvaluateBadgeTiers(
  uid: string,
  context: string,
  knownProgress?: Record<string, unknown>,
): Promise<void> {
  try {
    await evaluateAndAwardBadgeTiers(uid, knownProgress);
  } catch (error) {
    logger.error('Badge tier evaluation failed', { uid, context, error: String(error) });
  }
}
