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
import { writeInAppNotification } from '../notifications/deliver';
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
// crownHuntPerks flag — module-level TTL cache with ASYMMETRIC TTL (HOT-PATH ONLY)
//
// processTrapDrains runs on live.updatePosition (every accepted position
// sample), and readFeatureFlag has no cache — each call is a fresh
// config/featureFlags Firestore read. Without this, every live position update
// would pay that read forever, even with the feature OFF (the flag is read
// before the early-return). So the gate is cached in-memory per warm instance.
//
// The TTL is ASYMMETRIC, keyed on the cached VALUE, so the flag stays a fast
// KILL-SWITCH while still cheap when off:
//   - FALSE (feature off/disabled — the steady state pre-launch and after a
//     kill): cached for the LONG TTL, so the hot path pays ~no reads while off.
//   - TRUE (feature live): cached for a SHORT TTL, so if Seb emergency-disables
//     crownHuntPerks, warm instances stop draining within a few seconds rather
//     than up to a minute — the whole point of the flag as a kill-switch — while
//     still avoiding a per-sample read.
//
// This cache is DELIBERATELY LOCAL to the drain hot path — it does NOT touch
// the shared featureFlags infra (a global flag cache would add staleness to
// every flag-gated behaviour and needs separate sign-off). A failed read caches
// the contract default (false) for the long TTL, which is the correct fail-safe.
// ---------------------------------------------------------------------------

/** TTL for a cached ENABLED gate — short, so a disable propagates in seconds. */
const PERKS_FLAG_ENABLED_TTL_MS = 5_000;
/** TTL for a cached DISABLED gate — long, so the off steady-state is cheap. */
const PERKS_FLAG_DISABLED_TTL_MS = 60_000;
let cachedPerksFlag: { value: boolean; expiresAtMs: number } | null = null;

/**
 * The crownHuntPerks gate, cached with the asymmetric TTL above. Exported for a
 * PURE unit test (inject the clock via `nowMs`, the flag via a mocked
 * readFeatureFlag) so the TTL logic has coverage that does not depend on the
 * emulator exercising it.
 *
 * UNDER THE EMULATOR (FUNCTIONS_EMULATOR === 'true') the cache is BYPASSED — a
 * fresh read every time. The module cache lives in the functions-emulator
 * process and survives across test files there; a stale value once leaked
 * between files and failed CI. Bypassing under the emulator makes every
 * flag-toggle test deterministic regardless of code path, while prod keeps the
 * cache (the kill-switch + cheap-when-off behaviour the asymmetric TTL exists
 * for).
 */
export async function crownHuntPerksEnabled(nowMs: number): Promise<boolean> {
  if (process.env.FUNCTIONS_EMULATOR === 'true') {
    return readFeatureFlag(CROWN_HUNT_PERKS_FLAG_KEY);
  }
  if (cachedPerksFlag && nowMs < cachedPerksFlag.expiresAtMs) {
    return cachedPerksFlag.value;
  }
  // readFeatureFlag already returns the contract default (false) on a read
  // error, so a failed read simply caches false for the long (disabled) TTL.
  const value = await readFeatureFlag(CROWN_HUNT_PERKS_FLAG_KEY);
  const ttlMs = value ? PERKS_FLAG_ENABLED_TTL_MS : PERKS_FLAG_DISABLED_TTL_MS;
  cachedPerksFlag = { value, expiresAtMs: nowMs + ttlMs };
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

/**
 * How long a per-victim real-time drain-event doc lives before the TTL sweep
 * removes it. Short: the doc's ONLY job is to fire the on-screen text + vibration
 * on the victim's map the moment they drive onto a trap. A victim listening at
 * drain time pops it once (the client filters to docs newer than its subscribe
 * instant, exactly like the wave inbox); one that is not listening (app
 * backgrounded / not on the map) still gets the durable in-app notification +
 * push instead, so the ephemeral event is pure best-effort live feedback and 10
 * minutes is ample. Requires a Firestore TTL policy on collection-group `events`
 * (field `expireAt`) — an OPERATOR step, like every other perk TTL.
 */
const DRAIN_EVENT_TTL_MS = 10 * 60 * 1000;

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
    // DELIBERATELY an UNCACHED read (unlike the drain hot path). The boost
    // multiplier is a direct, per-crown-claim reward the member expects the
    // instant they deploy a boost — the crownHuntPerksEnabled cache holds a
    // stale FALSE for up to 60s (its long off-TTL), which is fine for the
    // background drain but would deny 2x on the very next crown after a member
    // enables PvP / deploys a boost. A crown claim is also far lower frequency
    // than a position sample, so a per-claim flag read costs little.
    if (!(await readFeatureFlag(CROWN_HUNT_PERKS_FLAG_KEY))) {
      return 1;
    }
    const snap = await db.collection('perkBoost').doc(uid).get();
    const expiresAt = snap.data()?.expiresAt as Timestamp | undefined;
    const expiresMs = expiresAt instanceof Timestamp ? expiresAt.toMillis() : null;
    return isTimestampActive(expiresMs, now.getTime()) ? BOOST_MULTIPLIER : 1;
  } catch (error) {
    // No PII: the uid is deliberately NOT logged (hard no-identifiers-in-logs
    // rule); "boost read failed" plus the error is enough to diagnose.
    logger.warn('Boost multiplier read failed; awarding unboosted', {
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
 * True for a Firestore/gRPC ALREADY_EXISTS error — the expected outcome when two
 * concurrent drains race the once-per-(trap, victim) marker `create`. The Admin
 * SDK surfaces it three ways across versions/transports (gRPC code 6, the
 * 'already-exists' string code, or only in the message text), so all three are
 * matched — mirroring functions/src/chatchannels/chat-core.ts (itself a mirror
 * of crownHunt/submitClaim.ts). Missing the text form lets the expected race be
 * misclassified and re-spam the logs the earlier fix quieted.
 */
function isAlreadyExistsError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const code = (error as { code?: unknown }).code;
  if (code === 6 || code === 'already-exists') {
    return true;
  }
  return String((error as { message?: unknown }).message ?? '').includes('ALREADY_EXISTS');
}

/**
 * Transfers the drain for ONE trap in a single transaction. All reads first
 * (both ledgers, the once-per-trap marker, the victim cooldown, both daily
 * counters, the trap doc), then the guards, then the paired ledger writes and
 * the anti-abuse bookkeeping. The marker `create` is the idempotency guard:
 * two concurrent samples for the same (trap, victim) serialise on it and the
 * loser aborts, so a victim sitting in a trap is drained exactly once.
 *
 * The victim's REAL-TIME trap-trigger signal (perkDrainEvents/{victim}/events)
 * is written INSIDE the transaction, so it is atomic with the KP move — if the
 * drain commits the signal is guaranteed, and if it aborts no phantom signal is
 * left behind. The durable in-app notifications (victim + placer) and their
 * pushes are emitted AFTER commit (see notifyDrainParticipants): they call the
 * shared notification writer, which does its own reads and cannot be nested in
 * this transaction, and — being best-effort — must never be able to fail or
 * roll back a committed drain.
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
  const victimUserRef = db.collection('users').doc(victimUid);
  const victimShieldRef = db.collection('perkShield').doc(victimUid);
  const victimLedgerRef = db.collection('pointsLedger').doc(victimUid);
  const placerLedgerRef = db.collection('pointsLedger').doc(placerUid);
  const cooldownRef = db.collection('perkDrainCooldowns').doc(victimCooldownDocId(victimUid));
  const lossRef = db.collection('perkTrapLoss').doc(trapLossCounterDocId(victimUid, dayKey));
  const earnRef = db.collection('perkTrapEarn').doc(trapEarnCounterDocId(placerUid, dayKey));

  const drainedAmount = await db.runTransaction(async (tx): Promise<number> => {
    const trapSnap = await tx.get(trapRef);
    const trap = trapSnap.data();
    if (!trapSnap.exists || !trap) {
      return 0;
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
      return 0;
    }
    if (!trapHasVictimRoom((trap.victimCount as number | undefined) ?? 0)) {
      return 0; // trap full (10 distinct victims)
    }

    const markerRef = db
      .collection('perkTrapVictims')
      .doc(trapVictimMarkerId(trapRef.id, victimUid));

    const [
      placerUserSnap,
      victimUserSnap,
      victimShieldSnap,
      markerSnap,
      cooldownSnap,
      victimLedgerSnap,
      placerLedgerSnap,
      lossSnap,
      earnSnap,
    ] = await Promise.all([
      tx.get(placerUserRef),
      tx.get(victimUserRef),
      tx.get(victimShieldRef),
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
      return 0;
    }

    // Victim eligibility RE-CHECKED inside the transaction (runTrapDrains already
    // pre-checked it, but that read is outside this txn). A victim who became
    // restricted, aged into immunity terms, OR raised a SHIELD between the
    // pre-check and commit must not still be drained — this upholds the
    // server-enforced shield/immunity guarantee under concurrency.
    const victimData = victimUserSnap.data();
    if (!victimData || !memberGateAllows(toUserAccessState(victimData))) {
      return 0; // victim now restricted/deleted/non-member
    }
    const victimCreatedAt = victimData.createdAt as Timestamp | undefined;
    if (
      isNewAccountImmune(
        victimCreatedAt instanceof Timestamp ? victimCreatedAt.toMillis() : null,
        nowMs,
      )
    ) {
      return 0; // new-account immunity
    }
    const victimShieldExp = victimShieldSnap.data()?.expiresAt as Timestamp | undefined;
    if (isTimestampActive(victimShieldExp instanceof Timestamp ? victimShieldExp.toMillis() : null, nowMs)) {
      return 0; // victim raised a shield before commit
    }

    if (markerSnap.exists) {
      return 0; // already caught by THIS trap
    }
    const lastDrainAt = cooldownSnap.data()?.lastDrainAt as Timestamp | undefined;
    if (
      isWithinVictimCooldown(lastDrainAt instanceof Timestamp ? lastDrainAt.toMillis() : null, nowMs)
    ) {
      return 0; // victim in 2h cooldown
    }

    const victimBalance = toStoredBalance(victimLedgerSnap.data()?.balance);
    const victimLossToday = (lossSnap.data()?.total as number | undefined) ?? 0;
    const placerEarnToday = (earnSnap.data()?.total as number | undefined) ?? 0;
    const drain = resolveDrainAmount({ victimBalance, victimLossToday, placerEarnToday });
    if (drain <= 0) {
      return 0; // broke or a daily cap already spent
    }

    // Paired ledger writes — victim debited, placer credited, both serialised on
    // their own balance docs (append-only entries + denormalised balance).
    const victimCheck = applyDelta(victimBalance, -drain);
    if (!victimCheck.ok) {
      return 0; // cannot go negative (belt-and-braces; resolveDrainAmount clamps)
    }
    const placerBalance = toStoredBalance(placerLedgerSnap.data()?.balance);
    const placerCheck = applyDelta(placerBalance, drain);
    if (!placerCheck.ok) {
      return 0; // unreachable for a positive credit; keeps the type narrowed
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

    // VICTIM real-time trap-trigger signal — per-victim inbox
    // perkDrainEvents/{victim}/events (owner-read, backend-write; same shape as
    // the wave inbox liveWaves/{uid}/waves). Written IN-TXN so it is atomic with
    // the KP move: the Android map listens here and, on a new doc, fires the
    // phone vibration + the on-screen "Du körde på en Spikmatta! −N KP" pop.
    // ANONYMOUS by design — it never carries the placer's uid, so the client
    // signal can never expose who owns the trap (mirrors perkDrains staying
    // backend-only). A short expireAt TTL sweeps it (operator TTL policy).
    tx.set(db.collection('perkDrainEvents').doc(victimUid).collection('events').doc(), {
      amount: drain,
      trapId: trapRef.id,
      createdAt: Timestamp.fromDate(now),
      expireAt: Timestamp.fromMillis(nowMs + DRAIN_EVENT_TTL_MS),
    });

    return drain; // KP moved — the caller early-exits (one trap per update)
  });

  if (drainedAmount <= 0) {
    return false;
  }

  // Durable in-app notifications + push for BOTH sides, AFTER commit and fully
  // best-effort: the shared writer does its own reads (it cannot run inside the
  // drain transaction), and a notification failure must never roll back a
  // committed KP move. A throw here is swallowed by runTrapDrains' per-trap
  // catch, but notifyDrainParticipants also never throws on its own.
  await notifyDrainParticipants({
    victimUid,
    placerUid,
    trapId: trapRef.id,
    amount: drainedAmount,
  });
  return true;
}

/**
 * Writes the durable in-app notification (which the notifications-onNotification
 * Created trigger then turns into an FCM push, inheriting the recipient's
 * eligibility + opt-outs) for both sides of a committed drain:
 *   - the VICTIM: "Du körde på en Spikmatta! — Du förlorade N KP." Kept ANONYMOUS
 *     (never names the placer) for the same reason the drain audit is backend-
 *     only.
 *   - the PLACER: "Någon körde på din Spikmatta! — Du fick N KP." The shared
 *     writer skips a deleted/suspended placer on its own, and the drain never
 *     reaches here for a self-trap (placer === victim is refused in-txn), so no
 *     extra guard is needed.
 * Best-effort throughout: each write is caught independently so one failing does
 * not suppress the other, and the function never throws — a notification is a
 * courtesy on top of an already-committed drain.
 */
async function notifyDrainParticipants(args: {
  victimUid: string;
  placerUid: string;
  trapId: string;
  amount: number;
}): Promise<void> {
  const { victimUid, placerUid, amount } = args;
  await writeInAppNotification(victimUid, {
    category: 'system_notice',
    title: 'Du körde på en Spikmatta!',
    previewText: `Du förlorade ${amount} KP.`,
    actionType: 'open_notifications',
    relatedEntityId: null,
  }).catch((error) => {
    logger.warn('Victim drain notification failed; drain unaffected', {
      error: error instanceof Error ? error.message : String(error),
    });
    return undefined;
  });
  await writeInAppNotification(placerUid, {
    category: 'system_notice',
    title: 'Någon körde på din Spikmatta!',
    previewText: `Du fick ${amount} KP.`,
    actionType: 'open_notifications',
    relatedEntityId: null,
  }).catch((error) => {
    logger.warn('Placer drain notification failed; drain unaffected', {
      error: error instanceof Error ? error.message : String(error),
    });
    return undefined;
  });
}
