/**
 * Social LEADERBOARD — pure core.
 *
 * The precompute writes ONE client-readable document per scope,
 * `leaderboards/{scope}`, holding each competitive category's top-N as an
 * ordered array of `[{rank, uid, displayName, avatarPath, value}]`. A member's
 * social screen renders the whole board from a single cheap document read; the
 * opt-out filtering and the name/avatar resolution both happen server-side in
 * the generator (functions/src/leaderboard/generator.ts) so a client never sees
 * a raw counter, an opted-out member, or another member's private state.
 *
 * This module is the single home for every decision the leaderboard makes that
 * does NOT need Firestore: which categories exist, how a category's candidates
 * are ranked, and how the published rows are assembled after opt-outs and
 * deleted members drop off. It imports no Firebase Admin SDK and reads no
 * ambient clock, so every edge is unit-testable without an emulator
 * (leaderboard-core.test.ts). It mirrors the crown-hunt-stats-core.ts split:
 * the emulator test proves the wiring, this proves the assembly.
 *
 * SCOPES. This PR ships the ALL-TIME board only (`scope = 'alltime'`, the
 * reserved id reused from the Kronjakt stats layer). The monthly board is a
 * follow-up PR that reuses this same core and the same document shape with a
 * season scope id.
 *
 * RANKING. Every category ranks by `value` DESCENDING with a `uid`-ascending
 * final tiebreak — the deterministic order `rankLeaderboard` in
 * crown-hunt-stats-core.ts uses, minus the crowns tiebreak that only the
 * crown-points board has a second counter for. A non-positive value is dropped:
 * a member who has driven zero metres is not "last", they are simply not on the
 * board.
 */

/** The reserved scope id for the never-resetting all-time board. */
export const LEADERBOARD_ALL_TIME_SCOPE = 'alltime';

/** How many ranked rows each category publishes. */
export const LEADERBOARD_TOP_N = 10;

/**
 * How many top candidates per category the generator keeps in memory during its
 * scan before resolving identities. Comfortably larger than `LEADERBOARD_TOP_N`
 * so that opted-out members and deleted members (no `users` doc) dropping out of
 * the top slice still leave a full page of ranked survivors. Lives here so the
 * bound is defined next to the ranking it protects.
 */
export const LEADERBOARD_CANDIDATE_RETENTION = 50;

/**
 * The competitive categories of the ALL-TIME board (owner-approved set). The
 * order is the order the social screen renders them in.
 *
 * SOURCES (resolved by the generator, documented here so the mapping is visible
 * where the keys are declared):
 *  - `crownPoints`  crownHuntLeaderboardEntries/{alltime__uid}.points
 *  - `distance`     badgeProgress/{uid}.lifetimeDistanceMeters
 *  - `events`       badgeProgress/{uid}.completedEventsAttended
 *  - `convoys`      badgeProgress/{uid}.convoysLed
 *  - `waves`        badgeProgress/{uid}.wavesSent
 *  - `streak`       badgeProgress/{uid}.bestDayStreak
 *
 * NOTE ON `events`: the stored field is `completedEventsAttended`, the historic
 * name the Träffräv badge ladder already reads (badges/badge-tiers.ts) — NOT a
 * field literally named `events`. The five badgeProgress counters are running
 * MAXIMA / monotonic sums, which is exactly right for these all-time "most / best
 * ever" categories.
 */
export const LEADERBOARD_CATEGORIES = [
  'crownPoints',
  'distance',
  'events',
  'convoys',
  'waves',
  'streak',
] as const;

export type LeaderboardCategoryKey = (typeof LEADERBOARD_CATEGORIES)[number];

/**
 * The competitive categories of a MONTHLY (`YYYY-MM`) board — the same keys and
 * the same published order as the all-time board, MINUS `streak`.
 *
 * `streak` is deliberately ALL-TIME ONLY: the daily-collection streak
 * (`bestDayStreak`) is a run that spans months, so a "longest streak THIS month"
 * is neither meaningful nor cheaply derivable — the monthly doc simply omits the
 * key. Every other category has a natural per-month bucket:
 *  - `crownPoints`  crownHuntLeaderboardEntries where scope == the month id
 *    (`{YYYY-MM}__{uid}.points`) — the Kronjakt season counter, already
 *    maintained per season by the crown stats layer.
 *  - `distance` / `events` / `convoys` / `waves` — the per-month buckets in
 *    `memberMonthlyStats/{YYYY-MM}__{uid}` (distanceMeters / eventsAttended /
 *    convoysLed / waves), incremented additively by the same source paths that
 *    feed the all-time badgeProgress counters (waves by live.sendWave itself).
 */
export const LEADERBOARD_MONTHLY_CATEGORIES = [
  'crownPoints',
  'distance',
  'events',
  'convoys',
  'waves',
] as const satisfies readonly LeaderboardCategoryKey[];

export type LeaderboardMonthlyCategoryKey = (typeof LEADERBOARD_MONTHLY_CATEGORIES)[number];

/**
 * The backend-only per-month stat buckets, one document per (month, member) at
 * `memberMonthlyStats/{YYYY-MM}__{uid}`. DENIED to every client in
 * firestore.rules — only the Admin-SDK generator reads them. The month id is the
 * Kronjakt season id (`seasonIdForInstant`), so the monthly board reuses the
 * exact month boundaries the crown season already defines.
 */
export const MEMBER_MONTHLY_STATS_COLLECTION = 'memberMonthlyStats';

/**
 * The `memberMonthlyStats` document field each additive monthly category is read
 * from (generator) and incremented on (the source triggers). `crownPoints` is
 * NOT here — it comes from the crown season counters, not this collection.
 */
export const MEMBER_MONTHLY_STAT_FIELDS = {
  distance: 'distanceMeters',
  events: 'eventsAttended',
  convoys: 'convoysLed',
  waves: 'waves',
} as const satisfies Record<Exclude<LeaderboardMonthlyCategoryKey, 'crownPoints'>, string>;

/** `memberMonthlyStats/{scope}__{uid}` — one bucket per (month, member). */
export function memberMonthlyStatsDocId(scope: string, uid: string): string {
  return `${scope}__${uid}`;
}

/** A single (member, value) input to a category ranking, before identity resolution. */
export interface LeaderboardCandidate {
  uid: string;
  value: number;
}

/**
 * A member's resolved public identity, read from `users/{uid}` by the generator.
 * `null` avatarPath is a member with no profile picture. A member with NO
 * `users` document at all is represented by a missing map entry, not this shape,
 * and is skipped (deleted members drop off the board).
 */
export interface LeaderboardIdentity {
  displayName: string;
  avatarPath: string | null;
}

/** One published, ranked leaderboard row — the client-facing shape. */
export interface LeaderboardRow {
  /** 1-based ordinal after opt-outs and deleted members are removed (contiguous). */
  rank: number;
  uid: string;
  displayName: string;
  avatarPath: string | null;
  value: number;
}

/**
 * Reads one candidate value defensively. Only a finite, non-negative number is
 * trusted; anything else (missing field, string, NaN, Infinity, negative) reads
 * as 0 — matching `toCounter` in badges/badge-tiers.ts so the two layers agree.
 * Fractional values (e.g. `lifetimeDistanceMeters`) are preserved; ranking only
 * compares them.
 */
export function readCandidateValue(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    return 0;
  }
  return value;
}

/**
 * Orders candidates by value DESC, then uid ASC as the deterministic final
 * tiebreak, drops non-positive values, and returns at most `retain` of them.
 *
 * Used both to keep the generator's in-memory scan bounded (retain =
 * LEADERBOARD_CANDIDATE_RETENTION) and, indirectly, to compose the published
 * page (via `buildLeaderboardCategory`). A stable, total order means "top N" is
 * unambiguous and reproducible run to run.
 */
export function topCandidates(
  candidates: readonly LeaderboardCandidate[],
  retain: number,
): LeaderboardCandidate[] {
  return [...candidates]
    .filter((c) => c.value > 0)
    .sort((a, b) => b.value - a.value || (a.uid < b.uid ? -1 : a.uid > b.uid ? 1 : 0))
    .slice(0, Math.max(0, retain));
}

/**
 * Assembles one category's published rows from ranked candidates plus the
 * resolved identity/opt-out state.
 *
 *  - candidates are ordered by `topCandidates` (value DESC, uid ASC, positive);
 *  - a uid in `optedOut` is removed entirely (server-honoured opt-out);
 *  - a uid with no `identities` entry is removed (no `users` doc → deleted
 *    member drops off);
 *  - the survivors are numbered 1..N CONTIGUOUSLY (a removed member leaves no
 *    gap in the ranks a client renders) and the first `topN` are returned.
 *
 * Ranks are the member's position on the PUBLISHED board, not their global
 * standing — which is the number the social screen shows and the only number a
 * client can verify against the array it was handed.
 */
export function buildLeaderboardCategory(
  candidates: readonly LeaderboardCandidate[],
  identities: ReadonlyMap<string, LeaderboardIdentity>,
  optedOut: ReadonlySet<string>,
  topN: number = LEADERBOARD_TOP_N,
): LeaderboardRow[] {
  const rows: LeaderboardRow[] = [];
  for (const candidate of topCandidates(candidates, Number.MAX_SAFE_INTEGER)) {
    if (rows.length >= topN) {
      break;
    }
    if (optedOut.has(candidate.uid)) {
      continue;
    }
    const identity = identities.get(candidate.uid);
    if (!identity) {
      continue;
    }
    rows.push({
      rank: rows.length + 1,
      uid: candidate.uid,
      displayName: identity.displayName,
      avatarPath: identity.avatarPath,
      value: candidate.value,
    });
  }
  return rows;
}

/** The set of uids that survive into the top slice of ANY category — the
 * generator resolves identities/opt-outs for exactly these, no more. */
export function candidateUidsToResolve(
  perCategory: Readonly<Record<LeaderboardCategoryKey, readonly LeaderboardCandidate[]>>,
  retain: number = LEADERBOARD_CANDIDATE_RETENTION,
): string[] {
  const uids = new Set<string>();
  for (const key of LEADERBOARD_CATEGORIES) {
    for (const candidate of topCandidates(perCategory[key] ?? [], retain)) {
      uids.add(candidate.uid);
    }
  }
  return [...uids];
}
