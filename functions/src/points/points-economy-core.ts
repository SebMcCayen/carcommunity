/**
 * Kronpoäng ECONOMY — the earning rules on top of the Phase 9g ledger.
 *
 * This module is PURE (no Firebase Admin SDK, no clock reads that are not
 * injected): the rule table, the Europe/Stockholm local-day + streak maths,
 * the cap arithmetic, the deterministic idempotency-key derivation and the
 * event-attendance (geofence + dwell) decision all live here so every edge
 * can be unit-tested without an emulator.
 *
 * DESIGN RULES (Seb's standing constraints — do not relax without asking):
 *
 *  1. NO SPEED GAMIFICATION. Nothing in this table rewards or ranks speed,
 *     and no rule may ever be added that does. Distance rules reward
 *     PARTICIPATION (that you drove and shared it), never how fast or how
 *     far beyond a small threshold.
 *  2. DISTANCE IS CAPPED. `live_session_1km` and `drive_5km` are worth points
 *     at most twice a day each and are additionally bounded by
 *     WEEKLY_DRIVING_POINTS_CAP, so nobody is ever incentivised to drive
 *     pointlessly (or unsafely) to farm points. The thresholds are LOW
 *     (1 km / 5 km) on purpose: crossing them is a by-product of normal use,
 *     not a target to chase.
 *  3. SERVER-AUTHORITATIVE. Every value in this file is a server constant.
 *     No caller may pass a point value, a distance or a duration that reaches
 *     the ledger — the award engine derives the amount from THIS table and
 *     from server-computed measurements only.
 *  4. IDEMPOTENT. Every award derives a deterministic idempotency key from
 *     the thing that happened (a rideId, an eventId+uid, a local day). The
 *     key IS the ledger entry document ID, so a retried trigger, a replayed
 *     callable or a double-tap can never double-award.
 *
 * Local days: everything user-visible (the daily open, the streak, the daily
 * cap, the per-day rule limits) is computed in Europe/Stockholm civil days,
 * never UTC. See stockholmDayKey for the DST reasoning.
 */

import { z } from 'zod';
import {
  haversineDistanceMeters,
  isValidCoordinate,
  isWithinGeofence,
} from '../crownHunt/crown-hunt-geo';
import { isFirestoreSafeId, type PointsTransactionSource } from './points-core';

// ---------------------------------------------------------------------------
// The rule table
// ---------------------------------------------------------------------------

export const ECONOMY_RULE_KEYS = [
  'daily_open',
  'live_session_1km',
  'drive_5km',
  'event_attend_verified',
  'event_host_success',
  'garage_first_car',
  'incident_report_confirmed',
] as const;
export type EconomyRuleKey = (typeof ECONOMY_RULE_KEYS)[number];

/**
 * Which window a rule's `limit` counts over:
 *  - `local_day`  — resets at Europe/Stockholm midnight;
 *  - `event`      — one counter per eventId, never resets;
 *  - `forever`    — one counter for the account's whole lifetime.
 */
export type EconomyLimitWindow = 'local_day' | 'event' | 'forever';

export interface EconomyRule {
  key: EconomyRuleKey;
  /** Server-authoritative base award before the streak multiplier and caps. */
  basePoints: number;
  /** Maximum number of awards of this rule inside `limitWindow`. */
  limit: number;
  limitWindow: EconomyLimitWindow;
  /**
   * True when the award is DRIVING-DERIVED and therefore also counts against
   * WEEKLY_DRIVING_POINTS_CAP. This is the anti-"drive around for points"
   * bound; it is a property of the RULE, not of the ledger source.
   */
  driving: boolean;
  /** Ledger source (existing Phase 9g enum — this table adds none). */
  source: PointsTransactionSource;
  /** Swedish description stem written to the ledger entry. */
  label: string;
}

/** Base award for the first app open of a local day, before the multiplier. */
export const DAILY_OPEN_BASE_POINTS = 5;

/**
 * THE ECONOMY. Canonical — the award engine reads amounts and limits from
 * here and nowhere else.
 */
export const POINTS_ECONOMY_RULES: Readonly<Record<EconomyRuleKey, EconomyRule>> = Object.freeze({
  daily_open: {
    key: 'daily_open',
    basePoints: DAILY_OPEN_BASE_POINTS,
    limit: 1,
    limitWindow: 'local_day',
    driving: false,
    source: 'system',
    label: 'Daglig inloggning',
  },
  live_session_1km: {
    key: 'live_session_1km',
    basePoints: 10,
    limit: 2,
    limitWindow: 'local_day',
    driving: true,
    source: 'system',
    label: 'Live-delning (minst 1 km)',
  },
  drive_5km: {
    key: 'drive_5km',
    basePoints: 15,
    limit: 2,
    limitWindow: 'local_day',
    driving: true,
    source: 'system',
    label: 'Sparad körning (minst 5 km)',
  },
  event_attend_verified: {
    key: 'event_attend_verified',
    basePoints: 50,
    limit: 1,
    limitWindow: 'event',
    driving: false,
    source: 'event',
    label: 'Verifierad närvaro på träff',
  },
  event_host_success: {
    key: 'event_host_success',
    basePoints: 75,
    limit: 1,
    limitWindow: 'event',
    driving: false,
    source: 'event',
    label: 'Lyckad träff som arrangör',
  },
  garage_first_car: {
    key: 'garage_first_car',
    basePoints: 25,
    limit: 1,
    limitWindow: 'forever',
    driving: false,
    source: 'garage',
    label: 'Första bilen i garaget',
  },
  incident_report_confirmed: {
    key: 'incident_report_confirmed',
    basePoints: 15,
    limit: 3,
    limitWindow: 'local_day',
    driving: false,
    source: 'system',
    label: 'Din rapport bekräftades',
  },
});

export function economyRule(key: EconomyRuleKey): EconomyRule {
  return POINTS_ECONOMY_RULES[key];
}

// ---------------------------------------------------------------------------
// Caps
// ---------------------------------------------------------------------------

/**
 * Global ceiling on NON-DRIVING points earned in one Europe/Stockholm day,
 * across the economy engine's non-driving rules plus Kronjakt crowns (see
 * onLedgerEntryCreated: crown awards are folded into this counter so the
 * ceiling cannot be side-stepped by crown farming).
 *
 * DRIVING-DERIVED rules (`driving: true` — drive_5km, live_session_1km) are
 * DELIBERATELY EXEMPT from this cap; they are bounded solely by
 * WEEKLY_DRIVING_POINTS_CAP (issue #861). The exemption runs in BOTH
 * directions: a driving award is neither clipped by this cap NOR added to the
 * counter it is measured against — `awardEconomyPoints` increments
 * `pointsDailyTotals` only for non-driving rules — so driving points also do
 * not eat the daily headroom the cap leaves for non-driving rules. The two
 * lanes never touch. See applyEconomyCaps for the full reasoning — in short,
 * the driving lane already carries its own dedicated ceiling AND tiny per-day
 * rule limits (2×/day each, ≤ 50 KP/day total), so it poses no daily-farming
 * risk, and subjecting it to a cap that CROWNS consume meant a member who
 * collected a few crowns earned nothing at all for a real saved drive — the
 * exact thing #861 reported. Crowns are already excluded from the weekly
 * driving cap for the same "a crown is a destination, not a distance" reason;
 * this closes the matching hole on the daily cap.
 *
 * Deliberately NOT applied to admin adjustments or reversals — a correction
 * by an admin is not a member "earning", and letting it eat the member's
 * daily headroom would punish them for a support ticket. The asymmetry runs
 * both ways and is intended: a reversal does not RELEASE the headroom the
 * reversed award consumed either. This counter records what was paid out
 * during the day; it is not a live mirror of the balance.
 *
 * CALIBRATION (raised 300 → 1500, issue: "legit grinders hit the daily cap").
 * The original 300 was calibrated for a 10-crown day: 10 hand-placed claims
 * (MAX_DAILY_SUCCESSFUL_CLAIMS) × ~24.5 KP expected value ≈ 245 KP, so an
 * honest maximum landed just under 300. That calibration PRE-DATES the
 * auto-spawn engine, which added a SECOND crown lane worth 20 more claims a day
 * (MAX_DAILY_SPAWN_CLAIMS). The realistic maximum legit grind is now the two
 * crown-count caps combined — 10 + 20 = 30 crowns — and crowns fold into this
 * counter uncapped, so a dedicated player reaches:
 *
 *   30 crowns × ~24.5 KP expected value  ≈  735 KP   (average rarity roll)
 *   735 KP × 2 (the boost perk doubles a collect)     ≈ 1470 KP
 *
 * i.e. the crown-count caps already let an honest, boost-using player earn
 * roughly 1470 KP/day from crowns alone — far above 300 — so the OLD cap
 * starved legit grinders the moment they collected ~12 average crowns
 * (300 / 24.5), or a single legendary (500). 1500 is set just above that
 * boosted-maximum ceiling (rounded up from ~1470, with headroom for the handful
 * of non-crown economy awards) so the daily points cap no longer bites honest
 * play: the crown-count caps (MAX_DAILY_SUCCESSFUL_CLAIMS / MAX_DAILY_SPAWN_CLAIMS)
 * remain the true anti-farm bound on crowns, and the per-rule limits bound the
 * rest. SINGLE TUNABLE — change this one number to retune; every doc, test and
 * the member-facing cap text derive from it. When a member IS refused an award
 * because this cap is spent, points-detectDailyCapReached auto-files one GitHub
 * issue per day so the ceiling can be observed and retuned (see dailyCapDetector.ts).
 */
export const DAILY_POINTS_CAP = 1500;

/**
 * Ceiling on DRIVING-derived points (rules with `driving: true`) in one
 * Europe/Stockholm week (Monday-anchored). The whole point of the economy is
 * "how active are you in the community", not "how many kilometres did you
 * burn", so the driving lane is bounded well below the daily cap × 7.
 */
export const WEEKLY_DRIVING_POINTS_CAP = 400;

export interface EconomyCapState {
  /** Points already credited to this uid on the current local day. */
  dailyAwarded: number;
  /** Driving-derived points already credited in the current local week. */
  weeklyDrivingAwarded: number;
}

export type EconomyCapClip = 'none' | 'daily' | 'weekly_driving';

export interface EconomyCapDecision {
  /** What the rule wanted to pay. */
  requested: number;
  /** What is actually paid — 0 means "award nothing at all". */
  awarded: number;
  /** Which ceiling bound the award (the tighter one when both bind). */
  clippedBy: EconomyCapClip;
}

const nonNegative = (value: number): number =>
  Number.isSafeInteger(value) && value > 0 ? value : 0;

/**
 * PARTIAL AWARD, not all-or-nothing.
 *
 * When a member has 10 points of headroom left and completes something worth
 * 15, they are paid 10 — not 0. Rationale: a cap is a ceiling on the day's
 * total, not a punishment for the action that happens to cross it. Paying 0
 * reads as a bug ("I did the thing and got nothing"), makes the ceiling
 * invisible, and would make the LAST few points of every day unearnable.
 * The clipped amount and the reason are written into the ledger entry's
 * description (see buildAwardDescription) so a member can open their history
 * and see exactly why they got 10 instead of 15.
 *
 * A remainder of 0 writes NO ledger entry at all (the ledger primitive
 * requires a positive amount, and a 0-point row is noise); the caller reports
 * `cap_reached` instead.
 *
 * TWO LANES, TWO CEILINGS (issue #861). A DRIVING-derived rule is bounded ONLY
 * by WEEKLY_DRIVING_POINTS_CAP; a NON-driving rule is bounded ONLY by
 * DAILY_POINTS_CAP. The daily cap deliberately does NOT touch driving rules:
 *  - the driving lane already has a dedicated ceiling AND tiny per-day rule
 *    limits (drive_5km and live_session_1km are 2×/day each, ≤ 50 KP/day), so
 *    it cannot be farmed into a leaderboard position regardless of the daily
 *    cap — the daily cap adds no anti-farm value on this lane; and
 *  - the daily cap is CONSUMED by Kronjakt crowns (onLedgerEntryCreated folds
 *    them in, uncapped, so a single legendary alone can push the day's total
 *    past the cap). Subjecting driving rules to it meant collecting a few crowns
 *    zeroed out the points for a genuine saved drive — reported as #861. Crowns
 *    are already excluded from the weekly driving cap ("a crown is a
 *    destination, not a distance"); this removes the same starvation on the
 *    daily cap.
 * A driving rule therefore never reports `clippedBy: 'daily'`, and a
 * non-driving rule never reports `clippedBy: 'weekly_driving'`.
 */
export function applyEconomyCaps(
  requested: number,
  driving: boolean,
  state: EconomyCapState,
): EconomyCapDecision {
  // Driving rules answer to the weekly driving cap alone; every other rule
  // answers to the global daily cap alone. Each lane's OTHER ceiling is
  // infinite, so exactly one can ever bind an award.
  const dailyRemaining = driving
    ? Number.POSITIVE_INFINITY
    : Math.max(0, DAILY_POINTS_CAP - nonNegative(state.dailyAwarded));
  const drivingRemaining = driving
    ? Math.max(0, WEEKLY_DRIVING_POINTS_CAP - nonNegative(state.weeklyDrivingAwarded))
    : Number.POSITIVE_INFINITY;

  const awarded = Math.max(0, Math.min(requested, dailyRemaining, drivingRemaining));
  if (awarded === requested) {
    return { requested, awarded, clippedBy: 'none' };
  }
  // Exactly one ceiling is finite for a given rule, so the binding one is
  // unambiguous: the daily cap for a non-driving rule, the weekly driving cap
  // for a driving rule.
  return {
    requested,
    awarded,
    clippedBy: dailyRemaining <= drivingRemaining ? 'daily' : 'weekly_driving',
  };
}

// ---------------------------------------------------------------------------
// Europe/Stockholm local days and weeks
// ---------------------------------------------------------------------------

export const POINTS_ECONOMY_TIME_ZONE = 'Europe/Stockholm';

// `formatToParts` (not `format`) so the output does not depend on any
// locale's date pattern — we assemble YYYY-MM-DD ourselves.
const dayPartsFormatter = new Intl.DateTimeFormat('en-US', {
  timeZone: POINTS_ECONOMY_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

/**
 * The Europe/Stockholm CIVIL day (`YYYY-MM-DD`) an instant falls on.
 *
 * Why not UTC: Sweden is UTC+1/+2, so a UTC day and a Swedish day never line
 * up. Using UTC days would break BOTH directions —
 *  - two opens at 01:30 and 02:30 local on the same Swedish day straddle UTC
 *    midnight in summer, so UTC would count them as TWO days and hand out two
 *    daily-open awards (and an inflated streak) for one day; and
 *  - two opens at 23:30 and 00:30 local are two consecutive Swedish days but
 *    land inside ONE UTC day, so UTC would merge them, pay once and silently
 *    break a streak the member actually kept.
 *
 * DST is handled by the IANA zone database via Intl, not by arithmetic: on
 * the spring-forward day (02:00 -> 03:00) the local day is 23 hours long and
 * on the autumn day it is 25, and `formatToParts` gets both right. No offset
 * is ever hard-coded here.
 */
export function stockholmDayKey(instant: Date): string {
  const parts = dayPartsFormatter.formatToParts(instant);
  const get = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((part) => part.type === type)?.value ?? '';
  return `${get('year')}-${get('month')}-${get('day')}`;
}

const DAY_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** True for a well-formed `YYYY-MM-DD` local-day key. */
export function isDayKey(value: unknown): value is string {
  return typeof value === 'string' && DAY_KEY_PATTERN.test(value);
}

function dayKeyToUtcNoon(dayKey: string): Date {
  const parts = dayKey.split('-');
  const year = Number(parts[0]);
  const month = Number(parts[1]);
  const day = Number(parts[2]);
  // Noon UTC: far enough from either midnight that no DST shift can move the
  // civil date. Only used for civil-calendar arithmetic on the KEY, never to
  // represent an instant.
  return new Date(Date.UTC(year, month - 1, day, 12));
}

function utcDateToDayKey(date: Date): string {
  const year = String(date.getUTCFullYear()).padStart(4, '0');
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const day = String(date.getUTCDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/**
 * The civil day before `dayKey`. Pure calendar arithmetic on the key (in UTC,
 * where every day is exactly 24 h), so the 23-hour and 25-hour Swedish DST
 * days cannot shift the answer — "the day before 2026-03-29" is 2026-03-28
 * regardless of how many hours either day contained.
 */
export function previousDayKey(dayKey: string): string {
  const noon = dayKeyToUtcNoon(dayKey);
  noon.setUTCDate(noon.getUTCDate() - 1);
  return utcDateToDayKey(noon);
}

/**
 * The Monday-anchored Europe/Stockholm week an instant falls in, keyed by the
 * Monday's civil date (`w2026-07-20`).
 *
 * A Monday date rather than an ISO week NUMBER on purpose: week numbering has
 * year-boundary edge cases (week 1 of 2027 starting in December 2026) that
 * would produce two different keys for one week if anything ever disagreed
 * about the convention. A date cannot be ambiguous.
 */
export function stockholmWeekKey(instant: Date): string {
  const dayKey = stockholmDayKey(instant);
  const noon = dayKeyToUtcNoon(dayKey);
  const mondayOffset = (noon.getUTCDay() + 6) % 7; // Mon = 0 … Sun = 6
  noon.setUTCDate(noon.getUTCDate() - mondayOffset);
  return `w${utcDateToDayKey(noon)}`;
}

// ---------------------------------------------------------------------------
// Daily-open streak
// ---------------------------------------------------------------------------

/** Streak length at which the multiplier stops growing (1.7x). */
export const MAX_STREAK_FOR_MULTIPLIER = 7;

export interface StreakState {
  /** Local day of the member's most recent counted open, or null. */
  lastOpenDay: string | null;
  /** Consecutive local days with an open, ending at lastOpenDay. */
  streak: number;
}

export interface DailyOpenDecision {
  /** The local day this open belongs to. */
  day: string;
  /** True when this uid already earned their open for `day` (no new award). */
  alreadyOpenedToday: boolean;
  /** Consecutive days ENDING YESTERDAY — the multiplier input. */
  priorStreak: number;
  /** Streak after counting today. */
  newStreak: number;
  /** 1.0 … 1.7 */
  multiplier: number;
  /** Server-computed award before caps. 0 when already opened today. */
  points: number;
}

/** Defensive read of a stored streak: anything odd counts as "no streak". */
export function toStoredStreak(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

/**
 * `x(1 + min(streak, 7) / 10)` — 1.0x at streak 0, 1.7x at streak >= 7.
 *
 * `streak` is the run ENDING YESTERDAY, so a member's very first open is
 * 1.0x (they have no prior run) and the ramp completes on their eighth
 * consecutive day. Missing a day resets the run to 0 and the multiplier
 * to 1.0.
 */
export function streakMultiplier(priorStreak: number): number {
  return 1 + Math.min(Math.max(0, priorStreak), MAX_STREAK_FOR_MULTIPLIER) / 10;
}

/**
 * Points for a daily open at `priorStreak`, computed in INTEGER arithmetic
 * (`base * (10 + n) / 10`, rounded half-up) so no binary-float wobble can
 * make 1.4x pay a different number on different days.
 */
export function dailyOpenPoints(priorStreak: number): number {
  const tenths = 10 + Math.min(Math.max(0, priorStreak), MAX_STREAK_FOR_MULTIPLIER);
  return Math.round((DAILY_OPEN_BASE_POINTS * tenths) / 10);
}

/** Decides what a daily open is worth and what the streak becomes. */
export function decideDailyOpen(state: StreakState, now: Date): DailyOpenDecision {
  const day = stockholmDayKey(now);
  const storedStreak = toStoredStreak(state.streak);
  const lastOpenDay = isDayKey(state.lastOpenDay) ? state.lastOpenDay : null;

  if (lastOpenDay === day) {
    return {
      day,
      alreadyOpenedToday: true,
      priorStreak: Math.max(0, storedStreak - 1),
      newStreak: storedStreak,
      multiplier: streakMultiplier(Math.max(0, storedStreak - 1)),
      points: 0,
    };
  }

  // A gap of any size (or no history at all) resets the run to 0.
  const priorStreak = lastOpenDay !== null && lastOpenDay === previousDayKey(day) ? storedStreak : 0;
  return {
    day,
    alreadyOpenedToday: false,
    priorStreak,
    newStreak: priorStreak + 1,
    multiplier: streakMultiplier(priorStreak),
    points: dailyOpenPoints(priorStreak),
  };
}

// ---------------------------------------------------------------------------
// Deterministic document IDs and idempotency keys
// ---------------------------------------------------------------------------

export const POINTS_DAILY_TOTALS_COLLECTION = 'pointsDailyTotals';
export const POINTS_WEEKLY_DRIVING_COLLECTION = 'pointsWeeklyDriving';
export const POINTS_RULE_COUNTERS_COLLECTION = 'pointsRuleCounters';
export const POINTS_STREAKS_COLLECTION = 'pointsStreaks';
export const POINTS_LEDGER_FOLDS_COLLECTION = 'pointsLedgerFolds';
export const EVENT_ATTENDANCE_COLLECTION = 'eventAttendance';
export const EVENT_ATTENDANCE_COUNTS_COLLECTION = 'eventAttendanceCounts';
/** Backend-only risk scores for rejected samples (never client-readable). */
export const EVENT_ATTENDANCE_RISK_COLLECTION = 'eventAttendanceRisk';

/** Prefix on every economy ledger entry ID — greppable in the ledger. */
const IDEMPOTENCY_PREFIX = 'pe';

/**
 * Builds the deterministic ledger idempotency key (= the ledger entry
 * document ID) for one award. The parts must identify the THING THAT
 * HAPPENED, never the request: a rideId, an eventId + uid, a local day. Two
 * calls describing the same happening produce the same key and therefore the
 * same single ledger row.
 *
 * Returns null when any part is not a Firestore-safe ID fragment, rather than
 * building a document path out of it.
 *
 * CALL-SITE CONTRACT: null must never be swallowed silently. A missing award
 * is close to undiagnosable after the fact — there is no failed row to find,
 * only a member reporting points that never arrived — so every caller either
 * throws (points.recordDailyOpen, which has a caller to tell) or logs a
 * warning naming the offending ids and skips the award (every trigger, and
 * the live-distance tracker, which have no caller and must not fail the user
 * action that already succeeded). Do not add a call site that just returns.
 */
export function economyIdempotencyKey(
  rule: EconomyRuleKey,
  ...parts: readonly string[]
): string | null {
  if (parts.length === 0 || parts.some((part) => !part || !/^[A-Za-z0-9._-]+$/.test(part))) {
    return null;
  }
  const key = [IDEMPOTENCY_PREFIX, rule, ...parts].join('__');
  return isFirestoreSafeId(key) ? key : null;
}

/** `pointsDailyTotals/{uid}__{YYYY-MM-DD}`. */
export function dailyTotalDocId(uid: string, dayKey: string): string {
  return `${uid}__${dayKey}`;
}

/** `pointsWeeklyDriving/{uid}__{w2026-07-20}`. */
export function weeklyDrivingDocId(uid: string, weekKey: string): string {
  return `${uid}__${weekKey}`;
}

/**
 * The window a rule's limit counter is keyed by:
 *  - `local_day` -> the local day key;
 *  - `event`     -> the eventId (supplied by the caller);
 *  - `forever`   -> the literal `all`.
 *
 * THROWS for an `event`-windowed rule called without a window key, instead of
 * substituting a placeholder. This is not defensive noise: the return value
 * becomes part of the limit counter's document ID, so a missing eventId would
 * point every event at ONE shared counter. `event_attend_verified` is 1/event,
 * so the first meet a member attended would spend that shared counter and
 * every later meet would be refused `limit_reached` — a silent, permanent loss
 * of a 50-point award that looks like a cap working correctly. A caller that
 * forgets the key is a bug, and it must surface as one.
 */
export function ruleLimitWindowKey(
  rule: EconomyRule,
  dayKey: string,
  explicitWindowKey?: string | null,
): string {
  switch (rule.limitWindow) {
    case 'local_day':
      return dayKey;
    case 'event':
      if (typeof explicitWindowKey !== 'string' || explicitWindowKey.length === 0) {
        throw new Error(`Rule ${rule.key} is event-windowed and needs an explicit window key.`);
      }
      return explicitWindowKey;
    case 'forever':
      return 'all';
  }
}

/** `pointsRuleCounters/{uid}__{ruleKey}__{windowKey}`. */
export function ruleCounterDocId(uid: string, rule: EconomyRuleKey, windowKey: string): string {
  return `${uid}__${rule}__${windowKey}`;
}

/** `pointsLedgerFolds/{uid}__{entryId}` — crown fold-in exactly-once guard. */
export function ledgerFoldDocId(uid: string, entryId: string): string {
  return `${uid}__${entryId}`;
}

/** `eventAttendance/{eventId}__{uid}` — one attendance record per pair. */
export function attendanceDocId(eventId: string, uid: string): string {
  return `${eventId}__${uid}`;
}

// ---------------------------------------------------------------------------
// Ledger descriptions — the member-visible "why"
// ---------------------------------------------------------------------------

const CAP_REASON_TEXT: Readonly<Record<Exclude<EconomyCapClip, 'none'>, string>> = {
  daily: `daglig gräns ${DAILY_POINTS_CAP} p nådd`,
  weekly_driving: `veckogräns för körpoäng ${WEEKLY_DRIVING_POINTS_CAP} p nådd`,
};

/**
 * The ledger entry description. When a cap clipped the award, the ORIGINAL
 * amount, the paid amount and the reason are all in the text — the member
 * opens their points history and reads "15 p -> 10 p (daglig gräns 1500 p
 * nådd)" instead of silently wondering where five points went.
 */
export function buildAwardDescription(
  rule: EconomyRule,
  decision: EconomyCapDecision,
  detail?: string | null,
): string {
  const head = detail ? `${rule.label}: ${detail}` : rule.label;
  if (decision.clippedBy === 'none') {
    return head;
  }
  return `${head} — ${decision.requested} p → ${decision.awarded} p (${CAP_REASON_TEXT[decision.clippedBy]})`;
}

// ---------------------------------------------------------------------------
// Event attendance: geofence + dwell
// ---------------------------------------------------------------------------

/** A position sample must be within this distance of the event coordinates. */
export const EVENT_GEOFENCE_RADIUS_METERS = 150;

/** Cumulative time inside the fence required to count as attendance. */
export const REQUIRED_DWELL_MS = 10 * 60_000;

/**
 * The two furthest-apart qualifying samples must be at least this far apart.
 * A single ping proves you drove past; two pings ten minutes apart prove you
 * stopped.
 */
export const MIN_SAMPLE_SPACING_MS = 10 * 60_000;

/** Attendance may be proven from 30 min before the start … */
export const ATTENDANCE_WINDOW_BEFORE_MS = 30 * 60_000;
/** … to 30 min after the end. */
export const ATTENDANCE_WINDOW_AFTER_MS = 30 * 60_000;

/**
 * The most dwell credit a single gap between two consecutive samples may
 * contribute. Two samples three hours apart prove you were inside the fence
 * twice, three hours apart — they do NOT prove you never left, so the gap is
 * worth 30 minutes of dwell, not 180.
 */
export const MAX_DWELL_GAP_CREDIT_MS = 30 * 60_000;

/** Fallback event length when an event carries no end time. */
export const DEFAULT_EVENT_DURATION_MS = 4 * 60 * 60_000;

/** Hard bound on stored samples per attendance record (abuse + doc size). */
export const MAX_ATTENDANCE_SAMPLES = 60;

/**
 * How long an attendance record — INCLUDING its raw position samples — is
 * kept before Firestore's TTL reaper deletes it.
 *
 * These samples are the most sensitive thing this feature stores: real
 * coordinates of a real member at a real time. They are few (two taps), they
 * are all next to a published event, and `eventAttendance` is readable ONLY
 * by the member the record belongs to and by admins — both rules are in
 * firebase/firestore.rules, and the admin read is stated here rather than
 * glossed as "private", because it is what makes the audit use below
 * possible. But "few and scoped" is not the same as "not retained", and
 * without an expiry they would sit in Firestore forever. So they get a
 * deadline.
 *
 * 90 days is chosen to outlive any dispute about the award they justify
 * (a member querying a missing 50 KP, an admin auditing a suspected forgery)
 * and nothing beyond that.
 *
 * DELETING THE RECORD CANNOT RE-OPEN THE AWARD. Three independent guards
 * outlive it, none of which carries a TTL:
 *  - the ledger entry itself, whose document ID IS the idempotency key
 *    `pe__event_attend_verified__{eventId}__{uid}`, so a replayed award is a
 *    transactional no-op;
 *  - `pointsRuleCounters/{uid}__event_attend_verified__{eventId}`, the
 *    1-per-event limit counter;
 *  - `eventAttendanceCounts/{eventId}/counted/{uid}`, so the host tally
 *    cannot count one attendee twice either.
 * A member who checks in again after expiry at a still-open event therefore
 * re-verifies and is paid nothing.
 *
 * ONE-TIME DEPLOY STEP (same pattern as the rate-limit counters below):
 *
 *   gcloud firestore fields ttls update expireAt \
 *     --collection-group=eventAttendance --enable-ttl
 */
export const ATTENDANCE_EVIDENCE_RETENTION_MS = 90 * 24 * 60 * 60_000;

/** When an attendance record touched at `nowMs` becomes reapable. */
export function attendanceEvidenceExpiry(nowMs: number): Date {
  return new Date(nowMs + ATTENDANCE_EVIDENCE_RETENTION_MS);
}

/**
 * A sample whose reported accuracy is worse than this cannot qualify at all.
 *
 * This is a DELIBERATE tightening on top of the shared isWithinGeofence,
 * which buffers the radius by `accuracy x 0.5` with NO upper bound on the
 * accuracy a client may report. That is fine for Kronjakt (a 20-150 m point
 * where a poor fix is separately risk-scored), but here it would be a hole:
 * a client claiming `accuracyMeters: 50000` would inflate a 150 m fence to
 * 25 km and "attend" a meet from the next county. WHAT "REFUSED" MEANS HERE:
 * the SAMPLE does not qualify — `isSampleInsideFence` returns false and the
 * call answers `outside_geofence`. It is deliberately NOT a schema bound: the
 * input schema still accepts a much larger `accuracyMeters`, because a poor
 * GPS fix is an ordinary field condition (a member standing in the right car
 * park, indoors or under trees), not a malformed request, and rejecting it
 * with `invalid-argument` would turn "your phone has not got a fix yet" into
 * an error the client cannot retry its way out of. The schema bound stays a
 * sanity bound on the number; this constant is the semantic one. A fix that
 * cannot place you
 * inside 100 m cannot prove you were inside 150 m, so it is refused outright
 * rather than buffered — which also bounds the effective fence at 150 + 50 =
 * 200 m.
 */
export const MAX_ATTENDANCE_ACCURACY_METERS = 100;

export interface AttendanceSample {
  latitude: number;
  longitude: number;
  /** Reported horizontal GPS accuracy in metres; null when not reported. */
  accuracyMeters: number | null;
  /** Device capture instant, epoch ms. */
  capturedAtMs: number;
}

/**
 * Defensive read of the stored `eventAttendance.samples` array — DROPS every
 * malformed entry instead of repairing it.
 *
 * Only `events.checkIn` ever writes this array, and only from a
 * schema-validated payload, so in practice every entry is well-formed. The
 * read side still refuses to trust that, and the reason it DROPS rather than
 * coerces is that the array is READ-MODIFY-WRITTEN on every check-in: the
 * transaction reads the stored samples, appends the new one and writes the
 * whole (truncated) list back. Coercing a bad entry to `NaN` and keeping it
 * would persist that `NaN` forever, one write at a time, and each junk entry
 * would also consume one of the MAX_ATTENDANCE_SAMPLES slots that a real
 * sample needs.
 *
 * NOTHING IS LOST BY DROPPING — an entry failing any of these checks could
 * never have qualified. `evaluateAttendance` is the predicate that settles
 * it, and it excludes them on two different grounds:
 *  - the COORDINATE and ACCURACY checks here are the geo subset of
 *    `isSampleInsideFence`, which `evaluateAttendance` requires;
 *  - the CAPTURE INSTANT is not something `isSampleInsideFence` looks at at
 *    all. `evaluateAttendance` is what requires `Number.isFinite`
 *    (and membership of the attendance window) on it.
 * So a timestamp-only corruption is excluded by `evaluateAttendance`, not by
 * the fence — worth stating precisely, because "the fence rejects it" would
 * be false for exactly that case.
 *
 * `accuracyMeters` is the one field where coercion would be UNSAFE rather
 * than merely untidy — a stored `"500"` coerced to `null` reads as "no
 * accuracy reported", which is treated as a perfect fix and skips the
 * MAX_ATTENDANCE_ACCURACY_METERS bound. A missing or explicitly null
 * accuracy is legitimate (it is what the client sends when the platform
 * reports none); anything else present but not a finite number drops the
 * whole entry.
 *
 * Accuracy is also held to the SEMANTIC bound, not just "is it a number":
 * negative, or worse than MAX_ATTENDANCE_ACCURACY_METERS, drops the entry
 * too. That is the same predicate `isSampleInsideFence` applies, so such an
 * entry could never qualify — and `events.checkIn` runs the fence check
 * BEFORE the transaction, so it can never have been written by the normal
 * path either. Keeping it would be keeping dead weight in a bounded array.
 *
 * ONE CONSEQUENCE WORTH KNOWING: this couples the stored evidence to a
 * POLICY constant. If MAX_ATTENDANCE_ACCURACY_METERS is ever TIGHTENED,
 * samples that were legitimate when captured stop being re-written on the
 * next check-in. No award moves — `verified` latches once true, and those
 * samples stop counting toward dwell the moment the constant changes
 * regardless — but the audit trail for them thins. Tighten the constant with
 * that in mind rather than discovering it later.
 */
export function parseStoredAttendanceSamples(raw: unknown): AttendanceSample[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  const samples: AttendanceSample[] = [];
  for (const entry of raw) {
    if (typeof entry !== 'object' || entry === null) {
      continue;
    }
    const record = entry as Record<string, unknown>;
    const { latitude, longitude, capturedAtMs, accuracyMeters } = record;
    if (
      typeof latitude !== 'number' ||
      typeof longitude !== 'number' ||
      !isValidCoordinate(latitude, longitude)
    ) {
      continue;
    }
    if (typeof capturedAtMs !== 'number' || !Number.isFinite(capturedAtMs)) {
      continue;
    }
    if (
      accuracyMeters !== undefined &&
      accuracyMeters !== null &&
      (typeof accuracyMeters !== 'number' ||
        !Number.isFinite(accuracyMeters) ||
        accuracyMeters < 0 ||
        accuracyMeters > MAX_ATTENDANCE_ACCURACY_METERS)
    ) {
      continue;
    }
    samples.push({
      latitude,
      longitude,
      accuracyMeters: typeof accuracyMeters === 'number' ? accuracyMeters : null,
      capturedAtMs,
    });
  }
  return samples;
}

export interface AttendanceWindowInput {
  startsAtMs: number;
  /** null -> DEFAULT_EVENT_DURATION_MS after the start. */
  endsAtMs: number | null;
}

export type AttendanceReason =
  | 'attended'
  | 'no_qualifying_samples'
  | 'need_second_sample'
  | 'samples_too_close'
  | 'dwell_too_short';

export interface AttendanceDecision {
  attended: boolean;
  /** Cumulative in-fence dwell, with each gap capped at MAX_DWELL_GAP_CREDIT_MS. */
  dwellMs: number;
  /** Samples that were inside BOTH the fence and the attendance window. */
  qualifyingSampleCount: number;
  /** Span between the first and last qualifying sample. */
  spanMs: number;
  reason: AttendanceReason;
}

/** The inclusive attendance window for an event. */
export function attendanceWindow(input: AttendanceWindowInput): { fromMs: number; toMs: number } {
  const endsAtMs =
    input.endsAtMs !== null && Number.isFinite(input.endsAtMs) && input.endsAtMs > input.startsAtMs
      ? input.endsAtMs
      : input.startsAtMs + DEFAULT_EVENT_DURATION_MS;
  return {
    fromMs: input.startsAtMs - ATTENDANCE_WINDOW_BEFORE_MS,
    toMs: endsAtMs + ATTENDANCE_WINDOW_AFTER_MS,
  };
}

/**
 * True when a single sample is inside the event geofence. Distance is
 * ALWAYS computed server-side from the event's own coordinates
 * (haversineDistanceMeters) — a client-reported distance is never accepted —
 * and the reported accuracy is buffered conservatively by the shared
 * isWithinGeofence (radius + accuracy x 0.5), so a member with a poor fix is
 * not rejected at the boundary. Accuracy is first hard-bounded by
 * MAX_ATTENDANCE_ACCURACY_METERS so it cannot be inflated into a check-in
 * from the next town.
 */
export function isSampleInsideFence(
  sample: AttendanceSample,
  eventLatitude: number,
  eventLongitude: number,
  radiusMeters: number = EVENT_GEOFENCE_RADIUS_METERS,
): boolean {
  if (!isValidCoordinate(sample.latitude, sample.longitude)) {
    return false;
  }
  if (
    sample.accuracyMeters !== null &&
    (!Number.isFinite(sample.accuracyMeters) ||
      sample.accuracyMeters < 0 ||
      sample.accuracyMeters > MAX_ATTENDANCE_ACCURACY_METERS)
  ) {
    return false;
  }
  const distance = haversineDistanceMeters(
    sample.latitude,
    sample.longitude,
    eventLatitude,
    eventLongitude,
  );
  return isWithinGeofence(distance, radiusMeters, sample.accuracyMeters);
}

/**
 * GEOFENCE + DWELL — "how do you know someone was actually at the meet?".
 *
 * A sample QUALIFIES when it is inside the 150 m fence AND inside
 * [startsAt - 30 min, endsAt + 30 min]. Attendance requires:
 *
 *   1. at least two qualifying samples,
 *   2. at least MIN_SAMPLE_SPACING_MS between the first and the last
 *      (one ping cannot prove attendance, and neither can two pings from the
 *      same traffic light), and
 *   3. at least REQUIRED_DWELL_MS of cumulative dwell, where each gap between
 *      consecutive qualifying samples contributes at most
 *      MAX_DWELL_GAP_CREDIT_MS.
 *
 * Pure: the caller supplies the samples it has already stored, so this same
 * function decides both "did this new sample tip them over" and "would this
 * set of samples have counted" in tests.
 */
export function evaluateAttendance(
  samples: readonly AttendanceSample[],
  eventLatitude: number,
  eventLongitude: number,
  window: AttendanceWindowInput,
  radiusMeters: number = EVENT_GEOFENCE_RADIUS_METERS,
): AttendanceDecision {
  const { fromMs, toMs } = attendanceWindow(window);

  const qualifying = samples
    .filter(
      (sample) =>
        Number.isFinite(sample.capturedAtMs) &&
        sample.capturedAtMs >= fromMs &&
        sample.capturedAtMs <= toMs &&
        isSampleInsideFence(sample, eventLatitude, eventLongitude, radiusMeters),
    )
    .map((sample) => sample.capturedAtMs)
    .sort((a, b) => a - b);

  if (qualifying.length === 0) {
    return {
      attended: false,
      dwellMs: 0,
      qualifyingSampleCount: 0,
      spanMs: 0,
      reason: 'no_qualifying_samples',
    };
  }
  if (qualifying.length === 1) {
    return {
      attended: false,
      dwellMs: 0,
      qualifyingSampleCount: 1,
      spanMs: 0,
      reason: 'need_second_sample',
    };
  }

  let dwellMs = 0;
  for (let i = 1; i < qualifying.length; i += 1) {
    dwellMs += Math.min((qualifying[i] ?? 0) - (qualifying[i - 1] ?? 0), MAX_DWELL_GAP_CREDIT_MS);
  }
  const spanMs = (qualifying[qualifying.length - 1] ?? 0) - (qualifying[0] ?? 0);

  if (spanMs < MIN_SAMPLE_SPACING_MS) {
    return {
      attended: false,
      dwellMs,
      qualifyingSampleCount: qualifying.length,
      spanMs,
      reason: 'samples_too_close',
    };
  }
  if (dwellMs < REQUIRED_DWELL_MS) {
    return {
      attended: false,
      dwellMs,
      qualifyingSampleCount: qualifying.length,
      spanMs,
      reason: 'dwell_too_short',
    };
  }
  return {
    attended: true,
    dwellMs,
    qualifyingSampleCount: qualifying.length,
    spanMs,
    reason: 'attended',
  };
}

/** Verified attendees an event needs before its host earns event_host_success. */
export const HOST_SUCCESS_MIN_VERIFIED_ATTENDEES = 3;

// ---------------------------------------------------------------------------
// Callable input schemas
// ---------------------------------------------------------------------------

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  return result.success ? { ok: true, input: result.data } : { ok: false, message: expected };
}

/**
 * points.recordDailyOpen takes NOTHING.
 *
 * `.strict()` on an empty object is load-bearing, not tidiness: it makes a
 * client-supplied `points`, `streak` or `day` a hard invalid-argument instead
 * of a field that is quietly ignored today and accidentally read tomorrow.
 * The award value is derived entirely from the server's clock and the stored
 * streak.
 */
const recordDailyOpenInputSchema = z.object({}).strict();

export type RecordDailyOpenInput = z.infer<typeof recordDailyOpenInputSchema>;

export function parseRecordDailyOpenInput(data: unknown): ParseResult<RecordDailyOpenInput> {
  return parse(
    recordDailyOpenInputSchema,
    data ?? {},
    'points.recordDailyOpen takes no arguments — the award is computed server-side.',
  );
}

const eventIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

/**
 * events.checkIn — one position sample.
 *
 * Note what is NOT here: no distance, no dwell, no "minutes present", no
 * point value. The client reports WHERE and WHEN it was, and the server
 * derives everything else. `capturedAt` is still validated for freshness
 * server-side, so back-dating it does not help either.
 */
const checkInInputSchema = z
  .object({
    eventId: eventIdSchema,
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    accuracyMeters: z.number().nonnegative().max(100_000).nullish(),
    capturedAt: z.string().datetime(),
    /**
     * The platform's mock-provider flag for this fix (Location.isMock). Reported
     * as observed, never suppressed — the SAME one-way signal a Kronjakt claim
     * sends: `true` is penalised at the review threshold on its own (see
     * MOCK_LOCATION_SCORE in crown-hunt-risk.ts), while `false` and absent are
     * identical, so an honest client gives nothing away and a dishonest one
     * gains nothing by omitting it. checkIn.ts maps this onto the risk
     * pipeline's `mockLocationReported`.
     */
    isMockLocation: z.boolean().nullish(),
    /** Play Integrity / App Attest verdict once those are wired up. */
    platformIntegrityPassed: z.boolean().nullish(),
  })
  .strict();

export type CheckInInput = z.infer<typeof checkInInputSchema>;

export function parseCheckInInput(data: unknown): ParseResult<CheckInInput> {
  return parse(
    checkInInputSchema,
    data,
    'Expected { eventId, latitude, longitude, capturedAt, accuracyMeters?, isMockLocation?, platformIntegrityPassed? }.',
  );
}

// ---------------------------------------------------------------------------
// Trigger-side thresholds
// ---------------------------------------------------------------------------

/** A saved drive must cover at least this to earn `drive_5km`. */
export const DRIVE_AWARD_MIN_DISTANCE_METERS = 5_000;

/** A live session must cover at least this to earn `live_session_1km`. */
export const LIVE_SESSION_AWARD_MIN_DISTANCE_METERS = 1_000;

/**
 * Live-distance accumulation ignores a step longer than this between two
 * consecutive samples: a 20 km jump between two pings is a GPS glitch, a
 * resumed session or a spoof, and must not be credited as distance covered.
 */
export const LIVE_STEP_MAX_METERS = 2_000;

/** Live samples worse than this accuracy do not contribute distance. */
export const LIVE_STEP_MAX_ACCURACY_METERS = 100;

export interface LiveDistanceStep {
  previous: { latitude: number; longitude: number } | null;
  next: { latitude: number; longitude: number; accuracyMeters: number | null };
}

/**
 * Metres to add to a live session's running total for one new sample.
 * Server-computed via the shared haversine — the client never reports a
 * distance. Returns 0 for the first sample, for an implausible step and for
 * a low-confidence fix.
 */
export function liveDistanceIncrementMeters(step: LiveDistanceStep): number {
  if (!step.previous) {
    return 0;
  }
  if (
    step.next.accuracyMeters !== null &&
    step.next.accuracyMeters > LIVE_STEP_MAX_ACCURACY_METERS
  ) {
    return 0;
  }
  if (
    !isValidCoordinate(step.previous.latitude, step.previous.longitude) ||
    !isValidCoordinate(step.next.latitude, step.next.longitude)
  ) {
    return 0;
  }
  const metres = haversineDistanceMeters(
    step.previous.latitude,
    step.previous.longitude,
    step.next.latitude,
    step.next.longitude,
  );
  return metres > LIVE_STEP_MAX_METERS ? 0 : metres;
}

/**
 * Per-minute call ceiling for the two client-facing economy callables
 * (points.recordDailyOpen and events.checkIn).
 *
 * ONE-TIME DEPLOY STEP — the rate-limit counters carry a Firestore TTL policy
 * so spent windows self-delete instead of accumulating forever:
 *
 *   gcloud firestore fields ttls update expireAt \
 *     --collection-group=pointsEconomyRateLimit --enable-ttl
 *
 * The collection is backend-only (written via the Admin SDK, denied to all
 * clients by firebase/firestore.rules) and is read by document id, so it needs
 * no composite index.
 */
export const ECONOMY_CALLABLE_RATE_LIMIT_PER_MINUTE = 10;
export const ECONOMY_RATE_LIMIT_COLLECTION = 'pointsEconomyRateLimit';

/** `pointsEconomyRateLimit/{uid}__{action}__{epochMinute}`. */
export function economyRateLimitDocId(uid: string, action: string, nowMs: number): string {
  return `${uid}__${action}__${Math.floor(nowMs / 60_000)}`;
}

/** Spent rate-limit windows are reaped by a Firestore TTL policy on expireAt. */
export function economyRateLimitExpiry(nowMs: number): Date {
  return new Date(nowMs + 5 * 60_000);
}

export function isUnderEconomyRateLimit(currentCount: number): boolean {
  return currentCount < ECONOMY_CALLABLE_RATE_LIMIT_PER_MINUTE;
}

/**
 * Reads a stored counter value — a rate-limit window, a rule limit, a daily or
 * weekly total — degrading anything that is not a non-negative safe integer
 * to 0.
 *
 * Every one of these counters is backend-only and only ever written with
 * `FieldValue.increment(1)` or `increment(points)`, so in practice the value
 * is always a non-negative integer. This is the read side refusing to TRUST
 * that, because the failure modes are asymmetric and silent:
 *
 *  - a corrupted `NaN` or `Infinity` compares false against every ceiling, so
 *    a naive read would REJECT every call for that window — a member locked
 *    out of their own daily open by a bad byte, with a `resource-exhausted`
 *    that looks exactly like genuine abuse;
 *  - a fractional value (1.5) passes a `Number.isFinite` check and then flows
 *    onward as an attempt count — `events.checkIn` feeds this number straight
 *    into the anti-fraud risk pipeline, where a fractional attempt rate is
 *    meaningless;
 *  - a negative value silently GRANTS extra headroom under every cap — and in
 *    the one place a counter is a THRESHOLD rather than a ceiling (the
 *    verified-attendee tally behind `event_host_success`) it does the
 *    opposite, holding the tally below the threshold forever.
 *
 * Treating a nonsense counter as 0 is the honest reading — "no attempts
 * recorded in this window" — and it fails in the direction that costs the
 * member nothing, since the ledger's idempotency key still caps the AWARD at
 * one regardless of what the rate limiter decides.
 *
 * This is the single implementation for the whole economy: `economy-award.ts`
 * (rule limit, daily total, weekly driving total), `events/checkIn.ts` and
 * `points/dailyOpen.ts` (rate-limit windows) and `economyTriggers.ts` (the
 * verified-attendee tally) all read through it, so no call site can drift on
 * what a corrupt counter means.
 */
export function readCount(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}
