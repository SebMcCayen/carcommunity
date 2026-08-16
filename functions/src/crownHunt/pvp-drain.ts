/**
 * Kronjakt PvP — the trap DRAIN processor + the boost-multiplier reader.
 *
 * ## Where the drain runs, and why INLINE
 * `processTrapDrains` is called from `live.updatePosition` on every accepted
 * position sample — the ONE place the backend knows "a member is at this
 * coordinate right now", which is exactly what a proximity trap needs. It is
 * NOT a scheduled job: a trap must fire the moment a rival arrives, and the
 * position stream already carries that event. It mirrors the crown-activity
 * signal that rides the same hook: best-effort, never able to fail live
 * sharing (a game must not break a safety feature), separately gated on the
 * contract-default-OFF `crownHuntPerks` flag so it is completely dark until the
 * shop is switched on.
 *
 * ## What a drain is
 * A trap drains ANY presence inside its radius (not just moving vehicles) —
 * once per trap per victim. When an eligible victim is found, KP moves from the
 * victim to the placer in a SINGLE transaction (mirroring claimSpawn's award
 * transaction: all reads before all writes, both ledger balances serialised),
 * with `source: 'perk_trap'` — a source deliberately excluded from the Kronjakt
 * leaderboard and the daily-economy fold (both key on `source: 'crown_hunt'`),
 * so PvP can never farm standings or the 300/day cap.
 *
 * ## Anti-abuse (every guard enforced here or in deployPerk.ts)
 *   - once per trap per victim   — perkTrapVictims/{marker} create-if-absent
 *   - <=10 distinct victims/trap  — trap.victimCount, checked + incremented in-txn
 *   - victim 2h cooldown          — perkDrainCooldowns/{victim}.lastDrainAt
 *   - victim <=45 KP lost/day      — perkTrapLoss/{victim__day}
 *   - placer <=150 KP earned/day   — perkTrapEarn/{placer__day}
 *   - new accounts immune 7 days   — users/{victim}.createdAt
 *   - shielded victims skipped     — perkShield/{victim}.expiresAt
 *   - suspended/deleted skipped    — users access state (never earn/spend)
 *   - can't drain yourself         — trap.placedByUid !== victim
 * The drain amount is {@link resolveDrainAmount}, clamped so the ledger can
 * never go negative and no daily cap is breached (partial or zero drain).
 */

import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import { toUserAccessState } from '../shared/access';
import { memberGateAllows } from '../shared/memberGating';
import {
  applyDelta,
  buildLedgerEntry,
  toStoredBalance,
} from '../points/points-core';
import { haversineDistanceMeters } from './crown-hunt-geo';
import { crownCellKey, neighbourCrownCells, utcDayKey } from './crown-spawn-core';
import {
  CROWN_HUNT_PERKS_FLAG_KEY,
  BOOST_MULTIPLIER,
  isNewAccountImmune,
  isTimestampActive,
  isWithinTrapRadius,
  isWithinVictimCooldown,
  resolveDrainAmount,
  trapEarnCounterDocId,
  trapLossCounterDocId,
  trapHasVictimRoom,
  trapVictimMarkerId,
  victimCooldownDocId,
} from './perks-core';

/**
 * Hard ceiling on how many candidate traps a single position update will run a
 * drain transaction against before the first drain lands. Bounds the hot-path
 * cost when many traps overlap one spot; the first success early-exits well
 * inside this in the common case.
 */
const MAX_TRAP_SCAN_BUDGET = 5;

// ---------------------------------------------------------------------------
// crownHuntPerks flag — short module-level TTL cache (HOT-PATH ONLY)
//
// processTrapDrains runs on live.updatePosition (every accepted position
// sample), and readFeatureFlag has no cache — each call is a fresh
// config/featureFlags Firestore read. Without this, every live position update
// would pay that read forever, even with the feature OFF (the flag is read
// before the early-return). So the gate is cached in-memory per warm instance
// for CACHE_TTL_MS.
//
// This cache is DELIBERATELY LOCAL to the drain hot path — it does NOT touch
// the shared featureFlags infra (a global flag cache would add staleness to
// every flag-gated behaviour and needs separate sign-off). Bounded staleness:
// when the flag is flipped ON at launch, drains begin within <= CACHE_TTL_MS
// per warm instance — fine for a game feature. A failed read caches the
// contract default (false) briefly, which is the correct fail-safe.
// ---------------------------------------------------------------------------

const PERKS_FLAG_CACHE_TTL_MS = 60_000;
let cachedPerksFlag: { value: boolean; expiresAtMs: number } | null = null;

async function crownHuntPerksEnabled(nowMs: number): Promise<boolean> {
  if (cachedPerksFlag && nowMs < cachedPerksFlag.expiresAtMs) {
    return cachedPerksFlag.value;
  }
  // readFeatureFlag already returns the contract default (false) on a read
  // error, so a failed read simply caches false for the TTL.
  const value = await readFeatureFlag(CROWN_HUNT_PERKS_FLAG_KEY);
  cachedPerksFlag = { value, expiresAtMs: nowMs + PERKS_FLAG_CACHE_TTL_MS };
  return value;
}

/** Test-only: clears the module-level flag cache between cases. */
export function __resetPerksFlagCacheForTest(): void {
  cachedPerksFlag = null;
}

/** TTL horizon for the ephemeral counter/cooldown/marker docs. */
function ttlDaysFromNow(now: Date, days: number): Timestamp {
  return Timestamp.fromMillis(now.getTime() + days * 24 * 60 * 60 * 1000);
}

interface DrainContext {
  victimUid: string;
  latitude: number;
  longitude: number;
  now: Date;
}

/**
 * The crown-award boost multiplier for `uid` at `now` — 2 while a boost is
 * active, else 1. Best-effort and flag-gated: any error, a missing perk system,
 * or the flag being OFF all return 1 (no change to the award), so the crown
 * paths stay correct whether or not PvP is enabled. The doubled award still
 * folds into the 300/day economy cap because it is credited with the unchanged
 * `source: 'crown_hunt'` and the existing daily fold charges the full amount.
 */
export async function resolveActiveBoostMultiplier(uid: string, now: Date): Promise<number> {
  try {
    if (!(await readFeatureFlag(CROWN_HUNT_PERKS_FLAG_KEY))) {
      return 1;
    }
    const snap = await db.collection('perkBoost').doc(uid).get();
    const expiresAt = snap.data()?.expiresAt as Timestamp | undefined;
    const expiresMs = expiresAt instanceof Timestamp ? expiresAt.toMillis() : null;
    return isTimestampActive(expiresMs, now.getTime()) ? BOOST_MULTIPLIER : 1;
  } catch (error) {
    logger.warn('Boost multiplier read failed; awarding unboosted', {
      uid,
      error: error instanceof Error ? error.message : String(error),
    });
    return 1;
  }
}

/**
 * Runs the trap drain for a member's new position. Best-effort: swallows every
 * error and never throws, so it can be awaited on the live-sharing hot path
 * without any chance of failing a position update. A no-op when the
 * `crownHuntPerks` flag is off.
 */
export async function processTrapDrains(ctx: DrainContext): Promise<void> {
  try {
    // Cached gate (see crownHuntPerksEnabled) so the hot path does not pay a
    // fresh config/featureFlags read on every position sample.
    if (!(await crownHuntPerksEnabled(ctx.now.getTime()))) {
      return;
    }
    await runTrapDrains(ctx);
  } catch (error) {
    logger.warn('Trap drain processing failed; live sharing unaffected', {
      error: error instanceof Error ? error.message : String(error),
    });
  }
}

async function runTrapDrains(ctx: DrainContext): Promise<void> {
  const { victimUid, latitude, longitude, now } = ctx;
  const nowMs = now.getTime();

  // Candidate armed traps in the mover's 3x3 cell block (the ~1.1 km cell is
  // coarser than the 100 m radius, so neighbours are needed to catch a trap
  // just across a cell boundary). `in` takes <= 10 values; a 3x3 block is 9.
  const cells = neighbourCrownCells(crownCellKey(latitude, longitude));
  const snap = await db
    .collection('activePerks')
    .where('cellKey', 'in', cells)
    .where('status', '==', 'armed')
    .where('expiresAt', '>', Timestamp.fromDate(now))
    .limit(50)
    .get();
  if (snap.empty) {
    return;
  }

  // Victim eligibility that is independent of any single trap — read ONCE.
  // Gated with memberGateAllows (parity with buyPerk/claimSpawn): today, with
  // member gating disabled repo-wide, this excludes only suspended/deleted/
  // absent accounts; when gating is re-locked it also skips non-members, so a
  // non-member never loses points to a trap either.
  const victimSnap = await db.collection('users').doc(victimUid).get();
  const victimData = victimSnap.data();
  if (!victimData || !memberGateAllows(toUserAccessState(victimData))) {
    return; // ineligible (suspended/deleted/absent, or non-member once re-locked)
  }
  const createdAt = victimData.createdAt as Timestamp | undefined;
  if (isNewAccountImmune(createdAt instanceof Timestamp ? createdAt.toMillis() : null, nowMs)) {
    return; // new-account immunity
  }
  const shieldSnap = await db.collection('perkShield').doc(victimUid).get();
  const shieldExp = shieldSnap.data()?.expiresAt as Timestamp | undefined;
  if (isTimestampActive(shieldExp instanceof Timestamp ? shieldExp.toMillis() : null, nowMs)) {
    return; // shielded
  }

  // A victim can lose to AT MOST ONE trap per position update: the moment one
  // trap drains them it sets the 2h victim cooldown (perkDrainCooldowns), which
  // makes every OTHER trap on this same sample a guaranteed no-op. So we
  // EARLY-EXIT on the first successful drain rather than run a full transaction
  // against each remaining trap only to rediscover the cooldown — this is the
  // hot path (live.updatePosition), and the invariant is unchanged (each trap
  // still drains a given victim at most once, and the per-victim caps hold).
  // A scan budget bounds the worst case (many overlapping traps in one cell)
  // before the first drain lands.
  let scanned = 0;
  for (const doc of snap.docs) {
    if (scanned >= MAX_TRAP_SCAN_BUDGET) {
      break;
    }
    const trap = doc.data();
    const placerUid = trap.placedByUid as string | undefined;
    if (!placerUid || placerUid === victimUid) {
      continue; // can't be caught by your own trap (no transaction spent)
    }
    const tLat = trap.lat as number | undefined;
    const tLng = trap.lng as number | undefined;
    if (typeof tLat !== 'number' || typeof tLng !== 'number') {
      continue;
    }
    if (!isWithinTrapRadius(haversineDistanceMeters(latitude, longitude, tLat, tLng))) {
      continue; // outside the 100 m radius (no transaction spent)
    }
    scanned += 1;
    // Apply this trap's drain (its own transaction). A skip (already caught by
    // this trap, cooldown, cap spent, race) returns false — try the next trap;
    // a real drain returns true and we STOP (one trap per update, see above).
    const drained = await applyTrapDrain(doc.ref, placerUid, ctx).catch((error) => {
      // The marker `create` is the once-per-(trap, victim) idempotency guard, so
      // two concurrent samples racing the SAME marker have one loser throw
      // Firestore ALREADY_EXISTS — a NORMAL no-op, not a failure. Skip it
      // silently (matching the gRPC status code, not message text) so it cannot
      // spam prod logs under load; only genuinely unexpected errors warn.
      if (!isAlreadyExistsError(error)) {
        logger.warn('Single trap drain failed; skipping', {
          trapId: doc.id,
          error: error instanceof Error ? error.message : String(error),
        });
      }
      return false;
    });
    if (drained) {
      return;
    }
  }
}

/**
 * True for a Firestore/gRPC ALREADY_EXISTS error (status code 6) — the expected
 * outcome when two concurrent drains race the once-per-(trap, victim) marker
 * `create`. Matched on the numeric/string status code, never the message text.
 */
function isAlreadyExistsError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null || !('code' in error)) {
    return false;
  }
  const code = (error as { code: unknown }).code;
  return code === 6 || code === 'already-exists';
}

/**
 * Transfers the drain for ONE trap in a single transaction. All reads first
 * (both ledgers, the once-per-trap marker, the victim cooldown, both daily
 * counters, the trap doc), then the guards, then the paired ledger writes and
 * the anti-abuse bookkeeping. The marker `create` is the idempotency guard:
 * two concurrent samples for the same (trap, victim) serialise on it and the
 * loser aborts, so a victim sitting in a trap is drained exactly once.
 *
 * Returns TRUE when it actually moved KP (so the caller can early-exit — a
 * drained victim is now on cooldown and no other trap can catch them this
 * update), FALSE for every skip (placer restricted, trap gone/full, already
 * caught, cooldown, cap spent, broke).
 */
async function applyTrapDrain(
  trapRef: FirebaseFirestore.DocumentReference,
  placerUid: string,
  ctx: DrainContext,
): Promise<boolean> {
  const { victimUid, now } = ctx;
  const nowMs = now.getTime();
  const dayKey = utcDayKey(now);

  const placerUserRef = db.collection('users').doc(placerUid);
  const victimLedgerRef = db.collection('pointsLedger').doc(victimUid);
  const placerLedgerRef = db.collection('pointsLedger').doc(placerUid);
  const cooldownRef = db.collection('perkDrainCooldowns').doc(victimCooldownDocId(victimUid));
  const lossRef = db.collection('perkTrapLoss').doc(trapLossCounterDocId(victimUid, dayKey));
  const earnRef = db.collection('perkTrapEarn').doc(trapEarnCounterDocId(placerUid, dayKey));

  return db.runTransaction(async (tx) => {
    const trapSnap = await tx.get(trapRef);
    const trap = trapSnap.data();
    if (!trapSnap.exists || !trap) {
      return false;
    }
    // Re-validate the trap inside the transaction.
    const expiresAt = trap.expiresAt as Timestamp | undefined;
    if (
      trap.status !== 'armed' ||
      !(expiresAt instanceof Timestamp) ||
      expiresAt.toMillis() <= nowMs ||
      trap.placedByUid !== placerUid ||
      trap.placedByUid === victimUid
    ) {
      return false;
    }
    if (!trapHasVictimRoom((trap.victimCount as number | undefined) ?? 0)) {
      return false; // trap full (10 distinct victims)
    }

    const markerRef = db
      .collection('perkTrapVictims')
      .doc(trapVictimMarkerId(trapRef.id, victimUid));

    const [
      placerUserSnap,
      markerSnap,
      cooldownSnap,
      victimLedgerSnap,
      placerLedgerSnap,
      lossSnap,
      earnSnap,
    ] = await Promise.all([
      tx.get(placerUserRef),
      tx.get(markerRef),
      tx.get(cooldownRef),
      tx.get(victimLedgerRef),
      tx.get(placerLedgerRef),
      tx.get(lossRef),
      tx.get(earnRef),
    ]);

    // Placer must be eligible to earn — checked INSIDE the transaction so a
    // placer suspended/deleted in the window before commit is not still
    // credited (a suspended user must never earn new points). Gated with
    // memberGateAllows (parity with buyPerk/claimSpawn): today it excludes only
    // suspended/deleted/absent; when gating is re-locked a non-member placer
    // earns nothing from a trap either. Read here rather than pre-transaction,
    // so it is re-validated at commit time.
    if (!placerUserSnap.exists || !memberGateAllows(toUserAccessState(placerUserSnap.data()))) {
      return false;
    }

    if (markerSnap.exists) {
      return false; // already caught by THIS trap
    }
    const lastDrainAt = cooldownSnap.data()?.lastDrainAt as Timestamp | undefined;
    if (
      isWithinVictimCooldown(lastDrainAt instanceof Timestamp ? lastDrainAt.toMillis() : null, nowMs)
    ) {
      return false; // victim in 2h cooldown
    }

    const victimBalance = toStoredBalance(victimLedgerSnap.data()?.balance);
    const victimLossToday = (lossSnap.data()?.total as number | undefined) ?? 0;
    const placerEarnToday = (earnSnap.data()?.total as number | undefined) ?? 0;
    const drain = resolveDrainAmount({ victimBalance, victimLossToday, placerEarnToday });
    if (drain <= 0) {
      return false; // broke or a daily cap already spent
    }

    // Paired ledger writes — victim debited, placer credited, both serialised on
    // their own balance docs (append-only entries + denormalised balance).
    const victimCheck = applyDelta(victimBalance, -drain);
    if (!victimCheck.ok) {
      return false; // cannot go negative (belt-and-braces; resolveDrainAmount clamps)
    }
    const placerBalance = toStoredBalance(placerLedgerSnap.data()?.balance);
    const placerCheck = applyDelta(placerBalance, drain);
    if (!placerCheck.ok) {
      return false; // unreachable for a positive credit; keeps the type narrowed
    }
    const ts = () => FieldValue.serverTimestamp();

    tx.set(
      victimLedgerRef.collection('entries').doc(),
      buildLedgerEntry(
        {
          transactionType: 'spend',
          source: 'perk_trap',
          amount: -drain,
          balanceAfter: victimCheck.balanceAfter,
          description: 'Kronjakt: fångad i en fälla',
          idempotencyKey: null,
          relatedEntityType: 'perk_trap',
          relatedEntityId: trapRef.id,
          createdByUserId: null,
        },
        ts,
      ),
    );
    tx.set(victimLedgerRef, { balance: victimCheck.balanceAfter, updatedAt: ts() }, { merge: true });

    tx.set(
      placerLedgerRef.collection('entries').doc(),
      buildLedgerEntry(
        {
          transactionType: 'earn',
          source: 'perk_trap',
          amount: drain,
          balanceAfter: placerCheck.balanceAfter,
          description: 'Kronjakt: en rival gick i din fälla',
          idempotencyKey: null,
          relatedEntityType: 'perk_trap',
          relatedEntityId: trapRef.id,
          createdByUserId: null,
        },
        ts,
      ),
    );
    tx.set(placerLedgerRef, { balance: placerCheck.balanceAfter, updatedAt: ts() }, { merge: true });

    // Once-per-trap marker (idempotency guard) + trap victim count.
    tx.create(markerRef, {
      trapId: trapRef.id,
      victimUid,
      placerUid,
      amount: drain,
      drainedAt: Timestamp.fromDate(now),
      expireAt: expiresAt,
      createdAt: ts(),
    });
    tx.update(trapRef, { victimCount: FieldValue.increment(1), updatedAt: ts() });

    // Victim cooldown + daily caps.
    tx.set(
      cooldownRef,
      {
        victimUid,
        lastDrainAt: Timestamp.fromDate(now),
        expireAt: ttlDaysFromNow(now, 1),
        updatedAt: ts(),
      },
      { merge: true },
    );
    tx.set(
      lossRef,
      {
        userId: victimUid,
        day: dayKey,
        total: FieldValue.increment(drain),
        expireAt: ttlDaysFromNow(now, 3),
        updatedAt: ts(),
      },
      { merge: true },
    );
    tx.set(
      earnRef,
      {
        userId: placerUid,
        day: dayKey,
        total: FieldValue.increment(drain),
        expireAt: ttlDaysFromNow(now, 3),
        updatedAt: ts(),
      },
      { merge: true },
    );

    // Audit record of the drain (backend-only).
    tx.set(db.collection('perkDrains').doc(), {
      trapId: trapRef.id,
      placerUid,
      victimUid,
      amount: drain,
      cellKey: trap.cellKey ?? null,
      drainedAt: Timestamp.fromDate(now),
      expireAt: ttlDaysFromNow(now, 30),
      createdAt: ts(),
    });

    return true; // KP moved — the caller early-exits (one trap per update)
  });
}
