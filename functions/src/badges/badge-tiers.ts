/**
 * Tiered-badge evaluation — pure logic.
 *
 * Given a set of server-maintained counters, decides WHICH ladder rungs a
 * member qualifies for and which of those are NEW. Nothing here reads or
 * writes Firestore; badges/tierAwards.ts is the only module that does.
 *
 * The three properties the awarding layer depends on, all proven here by unit
 * tests rather than asserted in prose:
 *
 *  - MONOTONIC. `qualifiedTierBadges` returns EVERY rung whose threshold the
 *    counter meets, not just the highest one. A member who jumps 0 → 300 crowns
 *    in a single claim earns Brons, Silver AND Guld together, and a member on
 *    Guld never stops qualifying for Silver. Combined with a create-if-absent
 *    award write, that means a tier is never revoked.
 *  - IDEMPOTENT. Qualification is a pure `>=` test over the current counters,
 *    so evaluating the same member twice produces the same set;
 *    `newlyEarnedTierBadges` subtracts what is already held, so a re-run
 *    returns nothing new.
 *  - DEFENSIVE. Counters arrive from Firestore documents and are sanitised
 *    through `toCounter` — a missing, negative, fractional-NaN, string or
 *    Infinity value reads as 0 rather than silently qualifying for everything.
 *
 * Distances are handled in METRES end to end (the unit drives.save computes and
 * stores); kilometres exist only in the display strings built in badge-core.
 */

import {
  BADGE_CATALOG,
  BADGE_CATALOG_ORDER,
  BADGE_LADDERS,
  type BadgeKey,
  type BadgeLadderKey,
  type BadgeMetric,
  type TierBadgeKey,
} from './badge-core';

// ---------------------------------------------------------------------------
// Counters
// ---------------------------------------------------------------------------

/**
 * The server-verified counters every ladder is measured against, as stored on
 * the backend-only `badgeProgress/{uid}` document.
 */
export type BadgeCounters = Readonly<Record<BadgeMetric, number>>;

export const ZERO_BADGE_COUNTERS: BadgeCounters = {
  crownsCollected: 0,
  lifetimeDistanceMeters: 0,
  verifiedEventsAttended: 0,
  bestDayStreak: 0,
  convoysLed: 0,
  vehiclesInGarage: 0,
};

/**
 * Reads one stored counter defensively. Only a finite, non-negative number is
 * trusted; anything else (missing field, string, NaN, Infinity, negative)
 * reads as 0. Fractional values are floored — `lifetimeDistanceMeters` is a
 * running sum of computed distances and may legitimately be fractional, and
 * flooring keeps a `>=` threshold test exact.
 */
export function toCounter(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    return 0;
  }
  return Math.floor(value);
}

/** Projects a raw `badgeProgress/{uid}` document into sanitised counters. */
export function readBadgeCounters(data: Record<string, unknown> | undefined): BadgeCounters {
  return {
    crownsCollected: toCounter(data?.crownsCollected),
    lifetimeDistanceMeters: toCounter(data?.lifetimeDistanceMeters),
    // Historic field name kept: the completed-event counter predates the
    // ladders (Phase 9f, first_event / five_events) and is already populated
    // for existing members. Träffräv reads the same authoritative number
    // rather than starting a parallel one from zero.
    verifiedEventsAttended: toCounter(data?.completedEventsAttended),
    bestDayStreak: toCounter(data?.bestDayStreak),
    convoysLed: toCounter(data?.convoysLed),
    vehiclesInGarage: toCounter(data?.vehiclesInGarage),
  };
}

/**
 * The `badgeProgress` field name a metric is stored under. Diverges from the
 * metric name only for `verifiedEventsAttended` (see readBadgeCounters).
 */
export const BADGE_METRIC_FIELD: Readonly<Record<BadgeMetric, string>> = {
  crownsCollected: 'crownsCollected',
  lifetimeDistanceMeters: 'lifetimeDistanceMeters',
  verifiedEventsAttended: 'completedEventsAttended',
  bestDayStreak: 'bestDayStreak',
  convoysLed: 'convoysLed',
  vehiclesInGarage: 'vehiclesInGarage',
};

// ---------------------------------------------------------------------------
// Qualification
// ---------------------------------------------------------------------------

/**
 * EVERY tier badge the counters qualify for, in catalog order — not just the
 * highest rung per ladder. Returning the full set is what makes the ladders
 * monotonic and makes a multi-tier jump in one step award all the tiers it
 * crossed.
 */
export function qualifiedTierBadges(counters: BadgeCounters): TierBadgeKey[] {
  const qualified = new Set<string>();
  for (const ladder of BADGE_LADDERS) {
    const value = counters[ladder.metric];
    for (const spec of ladder.tiers) {
      if (value >= spec.threshold) {
        qualified.add(spec.key);
      }
    }
  }
  // Catalog order, so awards and points land bottom-tier first and a partial
  // failure leaves a member holding a prefix of a ladder, never a gap.
  return BADGE_CATALOG_ORDER.filter((key): key is TierBadgeKey =>
    qualified.has(key),
  );
}

/**
 * Tier badges the counters qualify for that the member does NOT already hold,
 * in catalog order. `alreadyHeld` is the set of badge keys with an existing
 * `users/{uid}/badges/{key}` document.
 */
export function newlyEarnedTierBadges(
  counters: BadgeCounters,
  alreadyHeld: Iterable<string>,
): TierBadgeKey[] {
  const held = new Set<string>(alreadyHeld);
  return qualifiedTierBadges(counters).filter((key) => !held.has(key));
}

/** Kronpoäng credited on first award of `key` (0 for the standalone badges). */
export function tierPointsReward(key: BadgeKey): number {
  return BADGE_CATALOG[key].pointsReward;
}

/**
 * Deterministic points-ledger idempotency key for a badge award. The ledger
 * entry ID IS this string (per-user `entries` subcollection), so replaying an
 * award can never double-credit. Firestore-safe by construction: badge keys
 * are `[a-z_]+`.
 */
export function badgeAwardIdempotencyKey(key: BadgeKey): string {
  return `badge_award_${key}`;
}

/** Swedish ledger description for a tier award. */
export function badgeAwardPointsDescription(key: BadgeKey): string {
  return `Märke upplåst: ${BADGE_CATALOG[key].name}`;
}

/**
 * The highest tier a member holds per ladder — the "current rank" view a
 * profile header shows. Lower tiers are still held; this is presentation only.
 */
export function highestHeldTierPerLadder(
  heldKeys: Iterable<string>,
): Partial<Record<BadgeLadderKey, TierBadgeKey>> {
  const held = new Set<string>(heldKeys);
  const result: Partial<Record<BadgeLadderKey, TierBadgeKey>> = {};
  for (const ladder of BADGE_LADDERS) {
    for (const spec of ladder.tiers) {
      if (held.has(spec.key)) {
        result[ladder.ladder] = spec.key;
      }
    }
  }
  return result;
}

// ---------------------------------------------------------------------------
// Source-event guards
//
// The pure "did this write earn credit?" decisions the Firestore triggers in
// progressTriggers.ts make. They live here so the anti-abuse rules — above
// all "a risk_review crown claim never counts" — are unit-tested logic rather
// than a condition buried in a trigger body.
// ---------------------------------------------------------------------------

type DocData = Record<string, unknown> | undefined;

/**
 * Crowns credited by a write to `crownHuntClaims/{claimId}`.
 *
 * ONLY the transition into `result === 'awarded'` counts. A claim that
 * resolved to `risk_review` (the anti-fraud path — it awards no Kronpoäng
 * either), `too_far`, `already_claimed`, `daily_limit_reached` or any other
 * result contributes NOTHING to Kronjägare, ever. A rewrite of a claim that
 * was already `awarded` also credits nothing, so a replayed claim write cannot
 * inflate the counter.
 */
export function crownClaimCrownDelta(before: DocData, after: DocData): number {
  const wasAwarded = before?.result === 'awarded';
  const isAwarded = after?.result === 'awarded';
  return !wasAwarded && isAwarded ? 1 : 0;
}

/** The claim owner, or null when the document carries no usable `userId`. */
export function claimUserId(data: DocData): string | null {
  const uid = data?.userId;
  return typeof uid === 'string' && uid.length > 0 ? uid : null;
}

/**
 * Metres credited by a saved drive. `distanceMeters` is computed SERVER-SIDE
 * by drives.save (drive-calculations.ts) from the submitted route; the client
 * never writes it. A drive with no computable distance (null) credits nothing.
 *
 * Deleting a drive does not subtract: Vägfarare measures LIFETIME distance
 * driven, and a member removing a saved drive from their list has still driven
 * it. That also keeps the ladder monotonic — no delete can strip a tier.
 */
export function rideDistanceDelta(data: DocData): number {
  const distance = data?.distanceMeters;
  if (typeof distance !== 'number' || !Number.isFinite(distance) || distance <= 0) {
    return 0;
  }
  return distance;
}

/**
 * The owner to credit with one led convoy, or null when this write is not a
 * convoy going live.
 *
 * "Led" means CREATED BY the member AND actually started — convoys are born
 * active with a `startedAt` (convoy-core.ts::buildConvoyDocument), and this
 * guard also covers a `forming → active` transition should the lifecycle ever
 * regain a separate start step. A convoy that never starts never counts, and
 * ending or re-writing an already-started convoy credits nothing more.
 */
export function convoyLedOwnerUid(before: DocData, after: DocData): string | null {
  const hadStarted = before != null && before.startedAt != null;
  const hasStarted = after != null && after.startedAt != null;
  if (hadStarted || !hasStarted) {
    return null;
  }
  const ownerUid = after?.ownerUid;
  return typeof ownerUid === 'string' && ownerUid.length > 0 ? ownerUid : null;
}

// ---------------------------------------------------------------------------
// Streak (Trogen)
// ---------------------------------------------------------------------------

/**
 * Streaks are counted in LOCAL Swedish calendar days, not UTC days: a member
 * opening the app at 00:30 Stockholm time has opened it "today" as they
 * understand it, and a UTC boundary would break their streak an hour before
 * midnight in summer.
 */
export const STREAK_TIME_ZONE = 'Europe/Stockholm';

const dayKeyFormatter = new Intl.DateTimeFormat('sv-SE', {
  timeZone: STREAK_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

/** Local Swedish calendar day as `YYYY-MM-DD` (the sv-SE date format). */
export function streakDayKey(instant: Date): string {
  return dayKeyFormatter.format(instant);
}

/**
 * True when `dayKey` is the calendar day immediately after `previousDayKey`.
 *
 * Compares LABELS, not instants: both keys are parsed as UTC midnight and
 * differenced, which is exact calendar arithmetic and therefore immune to DST
 * — the 23-hour and 25-hour Stockholm days still produce consecutive labels.
 */
export function isNextDay(previousDayKey: string, dayKey: string): boolean {
  const previous = Date.parse(`${previousDayKey}T00:00:00Z`);
  const current = Date.parse(`${dayKey}T00:00:00Z`);
  if (Number.isNaN(previous) || Number.isNaN(current)) {
    return false;
  }
  return current - previous === 24 * 60 * 60 * 1000;
}

export interface StreakState {
  /** Days in the run that is currently alive. */
  currentDayStreak: number;
  /** Best run ever — the value Trogen is measured against. Never decreases. */
  bestDayStreak: number;
  /** Local day key of the most recent app open, or null if never opened. */
  lastStreakDayKey: string | null;
}

export const EMPTY_STREAK_STATE: StreakState = {
  currentDayStreak: 0,
  bestDayStreak: 0,
  lastStreakDayKey: null,
};

/** Projects a raw `badgeProgress/{uid}` document into a streak state. */
export function readStreakState(data: Record<string, unknown> | undefined): StreakState {
  const lastKey = data?.lastStreakDayKey;
  return {
    currentDayStreak: toCounter(data?.currentDayStreak),
    bestDayStreak: toCounter(data?.bestDayStreak),
    lastStreakDayKey: typeof lastKey === 'string' && lastKey.length > 0 ? lastKey : null,
  };
}

/**
 * Advances a streak for an app open on `dayKey`.
 *
 *  - same day as the last open      → unchanged (opening ten times in a day is
 *                                     still one day; `changed` is false so the
 *                                     trigger skips its write entirely).
 *  - the day after the last open    → the run grows by one.
 *  - any other day (gap, or a clock
 *    that went backwards)           → a new run starts at one.
 *
 * `bestDayStreak` is a running maximum and NEVER decreases — breaking a streak
 * costs the current run, never an already-earned Trogen tier.
 */
export function advanceStreak(
  state: StreakState,
  dayKey: string,
): { state: StreakState; changed: boolean } {
  if (state.lastStreakDayKey === dayKey && state.currentDayStreak > 0) {
    return { state, changed: false };
  }
  const currentDayStreak =
    state.lastStreakDayKey && isNextDay(state.lastStreakDayKey, dayKey)
      ? state.currentDayStreak + 1
      : 1;
  return {
    state: {
      currentDayStreak,
      bestDayStreak: Math.max(state.bestDayStreak, currentDayStreak),
      lastStreakDayKey: dayKey,
    },
    changed: true,
  };
}
