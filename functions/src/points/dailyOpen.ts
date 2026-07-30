/**
 * points.recordDailyOpen — callable (contracts/functions/functions.json).
 *
 * Deployed via the `points` export group as `points-recordDailyOpen`.
 *
 * THE ONE CLIENT-TRIGGERED AWARD. Every other rule in the economy hangs off
 * something the backend already observes (a ride document, a confirmation
 * document, a verified attendance record), so it cannot be forged. "Opened
 * the app today" has no such server-side footprint, so the client has to say
 * it — which makes this the one surface that needs its own defences:
 *
 *  - it takes NO arguments (strict empty schema): no day, no streak, no point
 *    value. The award is derived from the SERVER clock and the STORED streak;
 *  - the ledger idempotency key is `pe__daily_open__{uid}__{local day}`, so
 *    calling it a thousand times a day produces exactly one entry;
 *  - a per-minute rate limiter refuses a client that spams it anyway, so the
 *    hot path cannot be used to burn Firestore reads;
 *  - App Check is enforced (as on every callable but diagnostics).
 *
 * The streak is advanced in its OWN transaction before the award, not inside
 * the ledger transaction. That ordering is deliberate: if a member has
 * already hit DAILY_POINTS_CAP (e.g. from a big Kronjakt crown), the award
 * aborts with `cap_reached` — and the streak must survive that. A member who
 * opened the app kept their streak whether or not there was headroom left to
 * pay them for it. Both steps are individually idempotent per local day, so
 * a crash between them self-heals on the next call.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { awardEconomyPoints } from './economy-award';
import {
  ECONOMY_RATE_LIMIT_COLLECTION,
  POINTS_STREAKS_COLLECTION,
  decideDailyOpen,
  economyIdempotencyKey,
  economyRateLimitDocId,
  economyRateLimitExpiry,
  isUnderEconomyRateLimit,
  parseRecordDailyOpenInput,
  readCount,
  toStoredStreak,
  type DailyOpenDecision,
} from './points-economy-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

const RATE_LIMIT_ACTION = 'dailyOpen';

export interface RecordDailyOpenResponse {
  /** Points credited by THIS call. 0 on a repeat open or when capped out. */
  pointsAwarded: number;
  /** Consecutive Europe/Stockholm days with an open, including today. */
  streak: number;
  /** The streak multiplier this open was paid at (1.0 … 1.7). */
  multiplier: number;
  /** The Europe/Stockholm day this open counted for (`YYYY-MM-DD`). */
  day: string;
  /** True when today's open had already been counted. */
  alreadyCountedToday: boolean;
  /** True when the daily cap left no headroom to pay for the open. */
  dailyCapReached: boolean;
  balance: number | null;
}

/**
 * Per-minute ceiling, same shape as the incidents.listNearby limiter: read a
 * deterministic `{uid}__{action}__{epochMinute}` counter BY ID (no query, no
 * index), refuse over the ceiling, otherwise bump it with a commutative
 * increment. Exactness at the boundary does not matter — the goal is to stop
 * a runaway client, and the idempotency key already caps the AWARD at one.
 */
async function enforceRateLimit(uid: string, nowMs: number): Promise<void> {
  const ref = db
    .collection(ECONOMY_RATE_LIMIT_COLLECTION)
    .doc(economyRateLimitDocId(uid, RATE_LIMIT_ACTION, nowMs));
  const snap = await ref.get();
  // Degrades a corrupt counter to 0. A bare `typeof === 'number'` would let a
  // stored NaN or Infinity through, and both compare false against the
  // ceiling — locking the member out of their own daily open with a
  // `resource-exhausted` indistinguishable from genuine abuse.
  if (!isUnderEconomyRateLimit(readCount(snap.get('count')))) {
    throw new HttpsError('resource-exhausted', 'Too many requests — try again shortly.');
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
}

/**
 * Advances `pointsStreaks/{uid}` for today, transactionally, and returns the
 * decision. Re-running on the same local day is a no-op that returns the
 * stored decision — so the caller can always (re-)attempt the award with the
 * same numbers, and the ledger's idempotency key decides whether it lands.
 */
async function advanceStreak(uid: string, now: Date): Promise<DailyOpenDecision> {
  const ref = db.collection(POINTS_STREAKS_COLLECTION).doc(uid);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const data = snap.data();
    const decision = decideDailyOpen(
      {
        lastOpenDay: (data?.lastOpenDay as string | undefined) ?? null,
        streak: toStoredStreak(data?.streak),
      },
      now,
    );
    if (decision.alreadyOpenedToday) {
      return {
        ...decision,
        // Replay the award value stored with the day so a retry after a
        // failed credit re-attempts the SAME amount, not a recomputed one.
        points: toStoredStreak(data?.lastAwardPoints),
      };
    }
    tx.set(
      ref,
      {
        userId: uid,
        lastOpenDay: decision.day,
        streak: decision.newStreak,
        longestStreak: Math.max(toStoredStreak(data?.longestStreak), decision.newStreak),
        lastAwardPoints: decision.points,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    return decision;
  });
}

export const recordDailyOpen = onCall(
  CALLABLE_OPTS,
  async (request): Promise<RecordDailyOpenResponse> => {
    const actor = await requireActiveActor(request);

    // Strict empty schema: a client that tries to send `{ points: 9999 }`,
    // `{ streak: 7 }` or `{ day: '2026-01-01' }` gets invalid-argument, not a
    // silently-ignored field that some future refactor starts trusting.
    const parsed = parseRecordDailyOpenInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }

    const now = new Date();
    await enforceRateLimit(actor.uid, now.getTime());

    const decision = await advanceStreak(actor.uid, now);
    const idempotencyKey = economyIdempotencyKey('daily_open', actor.uid, decision.day);
    if (!idempotencyKey) {
      throw new HttpsError('internal', 'Could not derive an award key.');
    }

    const outcome =
      decision.points > 0
        ? await awardEconomyPoints({
            uid: actor.uid,
            rule: 'daily_open',
            now,
            idempotencyKey,
            points: decision.points,
            relatedEntityType: 'daily_open',
            relatedEntityId: decision.day,
            detail:
              decision.priorStreak > 0
                ? `dagsserie ${decision.newStreak} (×${decision.multiplier.toFixed(1)})`
                : null,
          })
        : null;

    return {
      pointsAwarded: outcome?.points ?? 0,
      streak: decision.newStreak,
      multiplier: decision.multiplier,
      day: decision.day,
      alreadyCountedToday: decision.alreadyOpenedToday || outcome?.status === 'already_awarded',
      dailyCapReached: outcome?.status === 'cap_reached',
      balance: outcome?.balanceAfter ?? null,
    };
  },
);
