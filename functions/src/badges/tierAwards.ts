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
 * ORDERING: points are credited BEFORE the badge document is written. The
 * badge document's absence is what marks an award as "not yet processed", so
 * if the process dies between the two writes the next evaluation replays the
 * credit (a ledger no-op) and then writes the badge. Writing the badge first
 * would make a failed credit permanently invisible.
 *
 * COST: evaluation is O(qualified tiers) — one `badgeProgress` read, one
 * batched `getAll` of at most 23 badge documents, and writes only for tiers
 * that are actually new. It never scans users, never scans a member's full
 * badge subcollection, and returns after two reads for the overwhelmingly
 * common case of "nothing new".
 */

import { FieldValue } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { isRestricted, toUserAccessState } from '../shared/access';
import { creditPoints } from '../points/ledger';
import { type BadgeMetric, type TierBadgeKey } from './badge-core';
import {
  BADGE_METRIC_FIELD,
  badgeAwardIdempotencyKey,
  badgeAwardPointsDescription,
  qualifiedTierBadges,
  readBadgeCounters,
  tierPointsReward,
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
 * silent no-op, and a failed points credit is logged and does not block the
 * badge, because the credit will be replayed on the next evaluation.
 */
export async function evaluateAndAwardBadgeTiers(uid: string): Promise<TierBadgeKey[]> {
  const progressSnap = await badgeProgressRef(uid).get();
  const counters = readBadgeCounters(progressSnap.data());
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
        // The badge is still worth awarding; the credit replays next time.
        logger.error('Badge tier points credit failed', {
          uid,
          badgeKey: key,
          error: String(error),
        });
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
export async function tryEvaluateBadgeTiers(uid: string, context: string): Promise<void> {
  try {
    await evaluateAndAwardBadgeTiers(uid);
  } catch (error) {
    logger.error('Badge tier evaluation failed', { uid, context, error: String(error) });
  }
}
