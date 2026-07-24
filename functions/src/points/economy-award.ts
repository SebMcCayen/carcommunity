/**
 * The points-economy AWARD ENGINE — the single door every earning rule goes
 * through.
 *
 * One function, `awardEconomyPoints`, and every caller (triggers, the two
 * callables, the live-distance tracker) hands it a rule key plus the
 * identifiers of the thing that happened. It then, in ONE Firestore
 * transaction (the ledger's own):
 *
 *   1. short-circuits on the deterministic idempotency key — a replayed
 *      trigger, a retried callable or a double-tap returns the original entry
 *      and writes nothing;
 *   2. reads the per-rule limit counter (1/day, 2/day, 3/day, 1/event, once
 *      ever) and refuses when it is spent;
 *   3. reads the daily-total and weekly-driving counters and CLIPS the award
 *      to whatever headroom is left (partial award — see applyEconomyCaps);
 *   4. appends the ledger entry with a description that names the cap when
 *      one bit; and
 *   5. increments all three counters atomically with the entry.
 *
 * Because steps 2-5 happen inside the transaction, two concurrent awards for
 * the same uid serialise on the ledger balance document and cannot race each
 * other past a limit or a cap.
 *
 * FORGERY: nothing here accepts a point value, a distance or a duration from
 * a caller — `points` may only be supplied for `daily_open` and is itself
 * derived from the server clock plus the stored streak, and it is clamped to
 * the rule's base x the maximum streak multiplier before it is used.
 */

import { HttpsError } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { creditPointsResolved, type AtomicExtraWrites } from './ledger';
import {
  DAILY_OPEN_BASE_POINTS,
  MAX_STREAK_FOR_MULTIPLIER,
  POINTS_DAILY_TOTALS_COLLECTION,
  POINTS_RULE_COUNTERS_COLLECTION,
  POINTS_WEEKLY_DRIVING_COLLECTION,
  applyEconomyCaps,
  buildAwardDescription,
  dailyTotalDocId,
  economyRule,
  ruleCounterDocId,
  ruleLimitWindowKey,
  stockholmDayKey,
  stockholmWeekKey,
  weeklyDrivingDocId,
  type EconomyCapClip,
  type EconomyRuleKey,
} from './points-economy-core';

export interface EconomyAwardRequest {
  /** Who earns the points. */
  uid: string;
  rule: EconomyRuleKey;
  /** Server clock for the award — decides the local day and week. */
  now: Date;
  /**
   * Deterministic ledger idempotency key, built with
   * {@link economyIdempotencyKey} from the thing that happened.
   */
  idempotencyKey: string;
  /**
   * ONLY for `daily_open`, whose award is base x streak multiplier. Ignored
   * for every other rule (which pay their table value) and hard-clamped to
   * the multiplier's maximum, so even a bug upstream cannot inflate it.
   */
  points?: number;
  /** eventId for the `event`-windowed rules; ignored otherwise. */
  limitWindowKey?: string | null;
  relatedEntityType?: string | null;
  relatedEntityId?: string | null;
  /** Appended to the Swedish ledger description after a colon. */
  detail?: string | null;
  /** Extra writes committed atomically with the award. */
  extraWrites?: AtomicExtraWrites;
}

export type EconomyAwardStatus =
  | 'awarded'
  | 'already_awarded'
  | 'limit_reached'
  | 'cap_reached'
  | 'blocked';

export interface EconomyAwardOutcome {
  status: EconomyAwardStatus;
  /** Points credited by THIS call (0 unless status is 'awarded'). */
  points: number;
  balanceAfter: number | null;
  entryId: string | null;
  clippedBy: EconomyCapClip;
  /** Populated when status is 'blocked'. */
  blockedReason?: string;
}

/**
 * Thrown from inside the award transaction to abort it with an authoritative
 * outcome (limit spent, or no headroom left under a cap).
 */
class EconomyRejection extends Error {
  constructor(readonly status: Extract<EconomyAwardStatus, 'limit_reached' | 'cap_reached'>) {
    super(status);
    this.name = 'EconomyRejection';
  }
}

const readCount = (value: unknown): number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : 0;

/**
 * The most a `daily_open` may ever pay: base x the capped multiplier. Any
 * larger `points` is clamped here, so a miscomputed or tampered streak can
 * never turn the daily open into a jackpot.
 */
const MAX_DAILY_OPEN_POINTS = Math.round(
  (DAILY_OPEN_BASE_POINTS * (10 + MAX_STREAK_FOR_MULTIPLIER)) / 10,
);

/**
 * Resolves the requested (pre-cap) amount for a rule. Every rule except
 * `daily_open` pays exactly its table value — the `points` field is not even
 * consulted.
 */
function requestedPoints(request: EconomyAwardRequest): number {
  const rule = economyRule(request.rule);
  if (request.rule !== 'daily_open') {
    return rule.basePoints;
  }
  const supplied = request.points;
  if (typeof supplied !== 'number' || !Number.isInteger(supplied) || supplied <= 0) {
    return rule.basePoints;
  }
  return Math.min(supplied, MAX_DAILY_OPEN_POINTS);
}

/**
 * Awards a rule's points to a member, idempotently, under the per-rule limit
 * and the global/driving caps. Never throws for an ordinary refusal — the
 * outcome carries the reason so trigger callers can log and move on.
 */
export async function awardEconomyPoints(
  request: EconomyAwardRequest,
): Promise<EconomyAwardOutcome> {
  const rule = economyRule(request.rule);
  const dayKey = stockholmDayKey(request.now);
  const weekKey = stockholmWeekKey(request.now);
  const windowKey = ruleLimitWindowKey(rule, dayKey, request.limitWindowKey);

  const requested = requestedPoints(request);
  const dailyRef = db
    .collection(POINTS_DAILY_TOTALS_COLLECTION)
    .doc(dailyTotalDocId(request.uid, dayKey));
  const weeklyRef = db
    .collection(POINTS_WEEKLY_DRIVING_COLLECTION)
    .doc(weeklyDrivingDocId(request.uid, weekKey));
  const counterRef = db
    .collection(POINTS_RULE_COUNTERS_COLLECTION)
    .doc(ruleCounterDocId(request.uid, rule.key, windowKey));

  // Captured by the read phase, consumed by the write phase and the caller.
  let clippedBy: EconomyCapClip = 'none';

  try {
    const result = await creditPointsResolved(
      {
        targetUid: request.uid,
        // The CEILING for this award; the resolver may only clip it down.
        amount: requested,
        transactionType: 'earn',
        source: rule.source,
        description: rule.label,
        idempotencyKey: request.idempotencyKey,
        relatedEntityType: request.relatedEntityType ?? null,
        relatedEntityId: request.relatedEntityId ?? null,
      },
      // READ PHASE: limit counter + both cap counters, transactionally.
      async (tx) => {
        const [counterSnap, dailySnap, weeklySnap] = await Promise.all([
          tx.get(counterRef),
          tx.get(dailyRef),
          rule.driving ? tx.get(weeklyRef) : Promise.resolve(null),
        ]);

        if (readCount(counterSnap.data()?.count) >= rule.limit) {
          throw new EconomyRejection('limit_reached');
        }

        const decision = applyEconomyCaps(requested, rule.driving, {
          dailyAwarded: readCount(dailySnap.data()?.total),
          weeklyDrivingAwarded: readCount(weeklySnap?.data()?.total),
        });
        if (decision.awarded <= 0) {
          throw new EconomyRejection('cap_reached');
        }
        clippedBy = decision.clippedBy;
        return {
          amount: decision.awarded,
          description: buildAwardDescription(rule, decision, request.detail),
        };
      },
      // WRITE PHASE: counters move with the entry or not at all. Skipped
      // entirely on an idempotent replay, so a retry cannot double-count.
      (tx, mutation) => {
        const stamp = FieldValue.serverTimestamp();
        tx.set(
          counterRef,
          {
            userId: request.uid,
            ruleKey: rule.key,
            windowKey,
            count: FieldValue.increment(1),
            updatedAt: stamp,
          },
          { merge: true },
        );
        tx.set(
          dailyRef,
          {
            userId: request.uid,
            day: dayKey,
            total: FieldValue.increment(mutation.amount),
            updatedAt: stamp,
          },
          { merge: true },
        );
        if (rule.driving) {
          tx.set(
            weeklyRef,
            {
              userId: request.uid,
              week: weekKey,
              total: FieldValue.increment(mutation.amount),
              updatedAt: stamp,
            },
            { merge: true },
          );
        }
        request.extraWrites?.(tx, mutation);
      },
    );

    return {
      status: result.alreadyApplied ? 'already_awarded' : 'awarded',
      points: result.alreadyApplied ? 0 : result.amount,
      balanceAfter: result.balanceAfter,
      entryId: result.entryId,
      clippedBy: result.alreadyApplied ? 'none' : clippedBy,
    };
  } catch (error) {
    if (error instanceof EconomyRejection) {
      return {
        status: error.status,
        points: 0,
        balanceAfter: null,
        entryId: null,
        clippedBy: error.status === 'cap_reached' ? 'daily' : 'none',
      };
    }
    // The ledger refuses suspended/deleted accounts and unknown users with
    // HttpsError. That is a legitimate "no award", not a bug — surface it as
    // `blocked` rather than exploding a Firestore trigger into a retry loop.
    if (error instanceof HttpsError) {
      return {
        status: 'blocked',
        points: 0,
        balanceAfter: null,
        entryId: null,
        clippedBy: 'none',
        blockedReason: error.code,
      };
    }
    throw error;
  }
}

/**
 * Fire-and-forget wrapper for trigger/best-effort call sites: awards the
 * points and swallows infrastructure failures into a warning.
 *
 * Deliberately never rethrows. These awards ride along with a user action
 * that has ALREADY succeeded (a drive was saved, a car was added, an incident
 * was confirmed) — failing the trigger would retry the whole thing for a
 * gamification side effect, and the idempotency key means a genuinely lost
 * award is re-awardable by replaying the source event rather than by
 * hammering.
 */
export async function tryAwardEconomyPoints(
  request: EconomyAwardRequest,
): Promise<EconomyAwardOutcome | null> {
  try {
    const outcome = await awardEconomyPoints(request);
    if (outcome.status !== 'awarded' && outcome.status !== 'already_awarded') {
      logger.info('Economy award not granted', {
        rule: request.rule,
        status: outcome.status,
        uid: request.uid,
      });
    }
    return outcome;
  } catch (error) {
    logger.error('Economy award failed', {
      rule: request.rule,
      uid: request.uid,
      error: String(error),
    });
    return null;
  }
}
