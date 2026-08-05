/**
 * Kronjakt STATISTICS + LEADERBOARD — pure core.
 *
 * This module is the single home for every decision the stats layer makes that
 * does not need Firestore: season bucketing, the daily-collection streak,
 * leaderboard ranking, rarity ordering, and the collection/document-id naming.
 * It imports no Firebase Admin SDK and reads no ambient clock, so every edge is
 * unit-testable without an emulator (crown-hunt-stats-core.test.ts).
 *
 * It is deliberately DECOUPLED from the spawn engine: the parallel agent owns
 * `crown-spawn-core.ts` / the spawner, so nothing here imports from those files.
 * The rarity tiers are re-declared locally (CROWN_STATS_RARITIES) and read
 * defensively — an unknown rarity still counts toward totals, it just lands in
 * no rarity bucket — and the grid cell key is taken straight off the
 * `crownSpawns` document (`cellKey`), never recomputed. That keeps this slice
 * merge-trivial against the spawn-area work happening at the same time.
 *
 * DESIGN NOTES that the two consuming UI slices (admin dashboard, Android
 * social screen) depend on:
 *  - SEASONS. A season is one calendar MONTH in Europe/Stockholm. The period is
 *    a single named constant (SEASON_PERIOD) so it can be changed later, but it
 *    is implemented monthly. The season id is `YYYY-MM`. The competitive board
 *    resets every season; the all-time board never resets. Points are BUCKETED
 *    by season (one leaderboard entry document per (scope, uid)) rather than
 *    destructively reset, so rolling a season over never touches the ledger or
 *    the all-time totals.
 *  - AUTHORITY SPLIT. Leaderboard points/crowns/streak/rank come from the
 *    Kronpoäng LEDGER (both crown paths write it), so hand-placed points and
 *    auto-spawned crowns both count. The rarity breakdown and the spawn/collect
 *    heat-map come from the `crownSpawns` documents, which is the ONLY place a
 *    rarity and a cell exist — so those cover auto-spawned crowns only, and
 *    `crownsCollected - sum(byRarity)` is the hand-placed remainder.
 */

// ---------------------------------------------------------------------------
// Seasons
// ---------------------------------------------------------------------------

/**
 * The season period. A single named constant so the cadence can be changed in
 * one place later; the implementation is monthly and the rest of this module is
 * written against `'month'`.
 */
export const SEASON_PERIOD = 'month' as const;
export type SeasonPeriod = typeof SEASON_PERIOD;

/** Everything user-visible is bucketed in Swedish civil time, like the economy. */
export const CROWN_STATS_TIME_ZONE = 'Europe/Stockholm';

/** The reserved scope id for the never-resetting all-time board. */
export const ALL_TIME_SCOPE = 'alltime';

const seasonMonthFormatter = new Intl.DateTimeFormat('en-US', {
  timeZone: CROWN_STATS_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
});

const dayFormatter = new Intl.DateTimeFormat('en-US', {
  timeZone: CROWN_STATS_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

function part(parts: Intl.DateTimeFormatPart[], type: Intl.DateTimeFormatPartTypes): string {
  return parts.find((p) => p.type === type)?.value ?? '';
}

/** The Europe/Stockholm season id (`YYYY-MM`) an instant falls in. */
export function seasonIdForInstant(instant: Date): string {
  const parts = seasonMonthFormatter.formatToParts(instant);
  return `${part(parts, 'year')}-${part(parts, 'month')}`;
}

/** The Europe/Stockholm civil day (`YYYY-MM-DD`) an instant falls on. */
export function stockholmDayKey(instant: Date): string {
  const parts = dayFormatter.formatToParts(instant);
  return `${part(parts, 'year')}-${part(parts, 'month')}-${part(parts, 'day')}`;
}

const SEASON_ID_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

/** True for a well-formed `YYYY-MM` season id. */
export function isSeasonId(value: unknown): value is string {
  return typeof value === 'string' && SEASON_ID_PATTERN.test(value);
}

/** True for a valid leaderboard/spawn-stats scope id (`alltime` or a season). */
export function isScopeId(value: unknown): value is string {
  return value === ALL_TIME_SCOPE || isSeasonId(value);
}

function parseSeason(seasonId: string): { year: number; month: number } {
  const [y, m] = seasonId.split('-');
  return { year: Number(y), month: Number(m) };
}

/** The season id of the month after `seasonId`. */
export function nextSeasonId(seasonId: string): string {
  const { year, month } = parseSeason(seasonId);
  const y = month === 12 ? year + 1 : year;
  const m = month === 12 ? 1 : month + 1;
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}`;
}

/** The season id of the month before `seasonId`. */
export function previousSeasonId(seasonId: string): string {
  const { year, month } = parseSeason(seasonId);
  const y = month === 1 ? year - 1 : year;
  const m = month === 1 ? 12 : month - 1;
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}`;
}

/**
 * The zone offset (ms east of UTC) at an instant, via the standard "format the
 * same instant in the zone and in UTC and difference the wall clocks" trick.
 * Used only to place month boundaries; it is never used for bucketing (that is
 * `seasonIdForInstant`).
 */
function stockholmOffsetMs(instant: Date): number {
  const inZone = new Date(instant.toLocaleString('en-US', { timeZone: CROWN_STATS_TIME_ZONE }));
  const inUtc = new Date(instant.toLocaleString('en-US', { timeZone: 'UTC' }));
  return inZone.getTime() - inUtc.getTime();
}

/** The UTC instant of Swedish local midnight on the first of `seasonId`. */
export function seasonStart(seasonId: string): Date {
  const { year, month } = parseSeason(seasonId);
  const guess = Date.UTC(year, month - 1, 1, 0, 0, 0, 0);
  // Month boundaries are never at a DST transition (those fall on a Sunday at
  // 01:00-03:00, never on the 1st at midnight), so evaluating the offset at the
  // UTC guess is exact.
  return new Date(guess - stockholmOffsetMs(new Date(guess)));
}

/**
 * The inclusive-start / exclusive-end UTC bounds of a season. `endAt` is the
 * start of the next season, so `[startAt, endAt)` tiles the timeline with no
 * gaps or overlaps.
 */
export function seasonBounds(seasonId: string): { startAt: Date; endAt: Date } {
  return { startAt: seasonStart(seasonId), endAt: seasonStart(nextSeasonId(seasonId)) };
}

// ---------------------------------------------------------------------------
// Rarity
// ---------------------------------------------------------------------------

/**
 * The crown rarity tiers, re-declared here so this slice never imports from the
 * spawn engine (owned by a parallel agent). Kept in ascending rarity order so
 * `rarerThan` is an index comparison.
 */
export const CROWN_STATS_RARITIES = ['common', 'uncommon', 'rare', 'legendary'] as const;
export type CrownStatsRarity = (typeof CROWN_STATS_RARITIES)[number];

/** True for a rarity this slice knows how to bucket. */
export function isCrownStatsRarity(value: unknown): value is CrownStatsRarity {
  return typeof value === 'string' && (CROWN_STATS_RARITIES as readonly string[]).includes(value);
}

/** A zeroed rarity histogram — the shape every `byRarity` map takes. */
export function zeroRarityCounts(): Record<CrownStatsRarity, number> {
  return { common: 0, uncommon: 0, rare: 0, legendary: 0 };
}

/**
 * True when `candidate` is strictly rarer than `incumbent`. A null incumbent
 * ("nothing found yet") is always beaten. Used to maintain "rarest crown found"
 * without re-scanning history.
 */
export function rarerThan(candidate: CrownStatsRarity, incumbent: CrownStatsRarity | null): boolean {
  if (incumbent === null) {
    return true;
  }
  return CROWN_STATS_RARITIES.indexOf(candidate) > CROWN_STATS_RARITIES.indexOf(incumbent);
}

// ---------------------------------------------------------------------------
// Daily-collection streak
// ---------------------------------------------------------------------------

export interface CollectionStreakState {
  /** Days in the run that is currently alive. */
  current: number;
  /** Best run ever — never decreases, so a lapsed streak keeps the record. */
  best: number;
  /** Local Swedish day key of the most recent collection, or null. */
  lastDayKey: string | null;
}

export const EMPTY_COLLECTION_STREAK: CollectionStreakState = {
  current: 0,
  best: 0,
  lastDayKey: null,
};

const DAY_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** Defensive read of a stored streak state off a Firestore document. */
export function readCollectionStreak(
  data: Record<string, unknown> | undefined,
): CollectionStreakState {
  const toCount = (v: unknown): number =>
    typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 ? v : 0;
  const lastKey = data?.lastCollectionDayKey;
  return {
    current: toCount(data?.collectionStreakCurrent),
    best: toCount(data?.collectionStreakBest),
    lastDayKey: typeof lastKey === 'string' && DAY_KEY_PATTERN.test(lastKey) ? lastKey : null,
  };
}

function isNextDay(previousDayKey: string, dayKey: string): boolean {
  const previous = Date.parse(`${previousDayKey}T00:00:00Z`);
  const current = Date.parse(`${dayKey}T00:00:00Z`);
  if (Number.isNaN(previous) || Number.isNaN(current)) {
    return false;
  }
  return current - previous === 24 * 60 * 60 * 1000;
}

/**
 * Advances the daily-collection streak for a collection on `dayKey`.
 *
 *  - same day as the last collection → unchanged (`changed: false`, so the
 *    trigger skips the streak write and only bumps points/crowns);
 *  - the day after                   → the run grows by one;
 *  - any gap (or a clock that went backwards) → a new run starts at one.
 *
 * `best` is a running maximum, so lapsing costs the current run, never the
 * record.
 */
export function advanceCollectionStreak(
  state: CollectionStreakState,
  dayKey: string,
): { state: CollectionStreakState; changed: boolean } {
  if (state.lastDayKey === dayKey && state.current > 0) {
    return { state, changed: false };
  }
  const current =
    state.lastDayKey && isNextDay(state.lastDayKey, dayKey) ? state.current + 1 : 1;
  return {
    state: {
      current,
      best: Math.max(state.best, current),
      lastDayKey: dayKey,
    },
    changed: true,
  };
}

// ---------------------------------------------------------------------------
// Leaderboard ranking
// ---------------------------------------------------------------------------

export interface LeaderboardCounter {
  uid: string;
  points: number;
  crownsCollected: number;
}

export interface RankedLeaderboardRow extends LeaderboardCounter {
  /** 1-based ordinal rank after the deterministic sort. */
  rank: number;
}

/**
 * Ranks leaderboard entries: highest Kronpoäng first, then most crowns, then
 * uid ascending as a stable final tiebreak. Strict ordinal ranking (1, 2, 3…)
 * with a deterministic order so "top 3" is unambiguous at season rollover and
 * a client computing a rank from a `points > mine` count agrees with it.
 *
 * A zero-point entry is dropped: a member who has never collected a crown is
 * not "last on the leaderboard", they are simply not on it.
 */
export function rankLeaderboard(entries: readonly LeaderboardCounter[]): RankedLeaderboardRow[] {
  return [...entries]
    .filter((e) => e.points > 0 || e.crownsCollected > 0)
    .sort(
      (a, b) =>
        b.points - a.points ||
        b.crownsCollected - a.crownsCollected ||
        (a.uid < b.uid ? -1 : a.uid > b.uid ? 1 : 0),
    )
    .map((e, index) => ({ ...e, rank: index + 1 }));
}

/**
 * The rank a member holds given how many OTHER members strictly outrank them
 * (more points, or equal points and more crowns). This is what a client derives
 * from an aggregation `count()` query, so the definition lives here to keep the
 * client and the server in agreement.
 */
export function rankFromBetterCount(betterCount: number): number {
  return Math.max(0, betterCount) + 1;
}

// ---------------------------------------------------------------------------
// Collection names + document ids
// ---------------------------------------------------------------------------

/** Season metadata: `crownHuntSeasons/{seasonId}`. Member-readable. */
export const CROWN_SEASONS_COLLECTION = 'crownHuntSeasons';
/**
 * Per-scope, per-user leaderboard counters, as a FLAT collection keyed
 * `{scope}__{uid}` with a `scope` field, so one composite index (scope, points
 * desc, crownsCollected desc) serves both the ranked read and the rank count.
 * A flat collection avoids sharing the generic `entries` collection-group id
 * with `pointsLedger/{uid}/entries`.
 */
export const CROWN_LEADERBOARD_COLLECTION = 'crownHuntLeaderboardEntries';
/** Per-user rich stats: `crownHuntUserStats/{uid}`. Owner + admin readable. */
export const CROWN_USER_STATS_COLLECTION = 'crownHuntUserStats';
/** Per-scope admin spawn/collect totals: `crownHuntSpawnStats/{scope}`. Admin. */
export const CROWN_SPAWN_STATS_COLLECTION = 'crownHuntSpawnStats';
/** Per-cell spawn/collect counts for the heat-map: `crownHuntCellStats/{cellKey}`. */
export const CROWN_CELL_STATS_COLLECTION = 'crownHuntCellStats';

/** Backend-only exactly-once markers. */
export const CROWN_STAT_LEDGER_FOLDS_COLLECTION = 'crownHuntStatFolds';
export const CROWN_STAT_SPAWN_FOLDS_COLLECTION = 'crownHuntSpawnFolds';
export const CROWN_SEASON_WIN_CREDITS_COLLECTION = 'crownHuntSeasonWinCredits';

/** `crownHuntLeaderboardEntries/{scope}__{uid}` — one counter per (scope, uid). */
export function leaderboardEntryDocId(scope: string, uid: string): string {
  return `${scope}__${uid}`;
}

/** `crownHuntStatFolds/{uid}__{entryId}` — ledger fold-in guard. */
export function ledgerStatFoldId(uid: string, entryId: string): string {
  return `${uid}__${entryId}`;
}

/** `crownHuntSpawnFolds/{spawnId}__{phase}` — spawn/collect fold-in guard. */
export function spawnStatFoldId(spawnId: string, phase: 'spawn' | 'collect'): string {
  return `${spawnId}__${phase}`;
}

/**
 * The season a Kronpoäng ledger entry / crown collection belongs to. Prefers a
 * real timestamp; falls back to the supplied `fallback` (the event delivery
 * time) so a document missing its own timestamp still lands in a bucket.
 */
export function scopeSeasonFor(instant: Date): string {
  return seasonIdForInstant(instant);
}
