/**
 * Public LEADERBOARD web JSON — pure core.
 *
 * PR3 of the social leaderboard: publish the precomputed board to the community
 * homepage (SebMcCayen/kungsbacka-car-community-homepage, a static site the
 * owner asked to "show the leaderboard on our webpage"). The generator
 * (functions/src/leaderboard/generator.ts) already writes the authoritative
 * client-readable documents `leaderboards/{scope}` — this module turns those
 * documents into the compact PUBLIC `data/leaderboard.json` the site renders,
 * mirroring how events/publicSite-core.ts turns the events feed into
 * `data/app-events.json`.
 *
 * SHRINK + STRIP. The in-app board publishes each category's top-10 as
 * `[{rank, uid, displayName, avatarPath, value}]`. The public web page shows a
 * shallower board and must carry NO more than what the app already shows
 * publicly:
 *  - depth is TOP-3 (a podium), not the in-app top-10;
 *  - `uid` is DROPPED — the public rows are `{rank, displayName, avatarPath,
 *    value}`, exactly the display name + avatar the app already surfaces
 *    publicly, and nothing else (no uid, no counters beyond the ranked value).
 *  The opt-out and deleted-member filtering already happened server-side in the
 *  generator before `leaderboards/{scope}` was written, so a member who opted
 *  out is never in the source document and therefore never in this file.
 *
 * SCOPES + CATEGORIES.
 *  - `alltime` publishes all five categories: crownPoints, distance, events,
 *    convoys, streak.
 *  - `month` publishes the four monthly categories: crownPoints, distance,
 *    events, convoys — NO streak (a monthly "longest streak" is not a category
 *    the monthly board tracks). The month block is OMITTED entirely (null) when
 *    no `leaderboards/{YYYY-MM}` document exists yet — the monthly board is a
 *    separate PR (#887); until it merges and runs, the site simply shows the
 *    all-time board.
 *
 * Everything here is pure (no Firestore/HTTP imports) so the truncation, the
 * PII stripping, the per-scope category selection and the change-detection that
 * decides whether a GitHub commit is needed are all unit-testable
 * (publicLeaderboard-core.test.ts). The GitHub write path lives in
 * leaderboardRepo.ts; the Firestore reads + orchestration in publicLeaderboard.ts.
 */

/** How many ranked rows per category the PUBLIC web page shows (a podium). */
export const PUBLIC_LEADERBOARD_TOP_N = 3;

/** All-time public categories, in render order. Includes `streak`. */
export const PUBLIC_ALLTIME_CATEGORIES = [
  'crownPoints',
  'distance',
  'events',
  'convoys',
  'streak',
] as const;

/** Monthly public categories, in render order. NO `streak`. */
export const PUBLIC_MONTH_CATEGORIES = [
  'crownPoints',
  'distance',
  'events',
  'convoys',
] as const;

export type PublicAlltimeCategoryKey = (typeof PUBLIC_ALLTIME_CATEGORIES)[number];
export type PublicMonthCategoryKey = (typeof PUBLIC_MONTH_CATEGORIES)[number];

/**
 * One row as STORED in `leaderboards/{scope}.categories[key]` by the generator.
 * `uid` is present in the stored document but deliberately NOT copied into the
 * public file.
 */
export interface StoredLeaderboardRow {
  rank?: number;
  uid?: string;
  displayName?: unknown;
  avatarPath?: unknown;
  value?: unknown;
}

/** The `categories` map of one `leaderboards/{scope}` document. */
export type StoredCategories = Record<string, StoredLeaderboardRow[] | undefined>;

/** One PUBLIC row — the display name + avatar the app already shows publicly, plus the ranked value. NO uid. */
export interface PublicLeaderboardRow {
  rank: number;
  displayName: string;
  avatarPath: string | null;
  value: number;
}

/** All-time public block: every category as a (possibly empty) top-3 array. */
export type PublicAlltimeBlock = Record<PublicAlltimeCategoryKey, PublicLeaderboardRow[]>;

/** Monthly public block: the four monthly categories plus the `YYYY-MM` id. */
export type PublicMonthBlock = { yyyymm: string } & Record<
  PublicMonthCategoryKey,
  PublicLeaderboardRow[]
>;

/**
 * Maps one stored row to a public row, or null when it is not publishable.
 * A row without a non-empty string `displayName` is dropped (the generator
 * guarantees one, so this only guards against a mangled document); `value` is
 * coerced defensively to a finite non-negative number (0 otherwise), matching
 * `readCandidateValue` in leaderboard-core.ts. `rank` is intentionally NOT
 * trusted from the stored row — it is re-derived from the published position so
 * the podium is always 1..N contiguous even if the source array is odd.
 */
function toPublicRow(row: StoredLeaderboardRow | undefined, position: number): PublicLeaderboardRow | null {
  if (!row || typeof row.displayName !== 'string' || row.displayName.length === 0) {
    return null;
  }
  const value =
    typeof row.value === 'number' && Number.isFinite(row.value) && row.value >= 0 ? row.value : 0;
  const avatarPath =
    typeof row.avatarPath === 'string' && row.avatarPath.length > 0 ? row.avatarPath : null;
  return {
    rank: position,
    displayName: row.displayName,
    avatarPath,
    value,
  };
}

/**
 * The top-`PUBLIC_LEADERBOARD_TOP_N` publishable rows of one stored category,
 * PII-stripped and re-ranked 1..N contiguously. A missing category array, or one
 * whose rows are all unpublishable, yields an empty array (a category nobody has
 * scored in simply renders empty — never an error).
 */
export function publicCategoryRows(rows: StoredLeaderboardRow[] | undefined): PublicLeaderboardRow[] {
  // A malformed `leaderboards/{scope}` document could store a category as an
  // object/null/scalar rather than the expected array. Publishing is
  // best-effort and must NEVER throw (a bad doc must not break the site sync),
  // so a non-array category reads as empty rather than crashing the for-of.
  const safeRows = Array.isArray(rows) ? rows : [];
  const out: PublicLeaderboardRow[] = [];
  for (const row of safeRows) {
    if (out.length >= PUBLIC_LEADERBOARD_TOP_N) {
      break;
    }
    const publicRow = toPublicRow(row, out.length + 1);
    if (publicRow) {
      out.push(publicRow);
    }
  }
  return out;
}

/** Builds the all-time public block from a stored `categories` map (null → all empty). */
export function buildPublicAlltimeBlock(categories: StoredCategories | null): PublicAlltimeBlock {
  const block = {} as PublicAlltimeBlock;
  for (const key of PUBLIC_ALLTIME_CATEGORIES) {
    block[key] = publicCategoryRows(categories?.[key]);
  }
  return block;
}

/** Builds the monthly public block (four categories, no streak) tagged with its `YYYY-MM` id. */
export function buildPublicMonthBlock(
  yyyymm: string,
  categories: StoredCategories | null,
): PublicMonthBlock {
  const block = { yyyymm } as PublicMonthBlock;
  for (const key of PUBLIC_MONTH_CATEGORIES) {
    block[key] = publicCategoryRows(categories?.[key]);
  }
  return block;
}

/** Human warning stored in the file so nobody hand-edits generated content. */
export const PUBLIC_LEADERBOARD_FILE_NOTICE =
  'Denna fil skrivs automatiskt av appens backend. Redigera INTE för hand — ändringar skrivs över.';

/** The public file's parsed shape. */
export interface PublicLeaderboardFile {
  _generated: string;
  generatedAt: string;
  alltime: PublicAlltimeBlock;
  /** The current month's podium, or null when no monthly board exists yet. */
  month: PublicMonthBlock | null;
}

/**
 * Serializes the full `data/leaderboard.json` content. Key order and 2-space
 * indentation are fixed so the same board always produces byte-identical output
 * (bar generatedAt) and diffs in the homepage repo stay readable.
 *
 * @param alltime   the `leaderboards/alltime.categories` map, or null if absent.
 * @param month     the current month's block (buildPublicMonthBlock) or null.
 * @param generatedAt the stamp (ignored by homepageLeaderboardEquivalent).
 */
export function buildPublicLeaderboardFile(
  alltime: StoredCategories | null,
  month: PublicMonthBlock | null,
  generatedAt: Date,
): string {
  const file: PublicLeaderboardFile = {
    _generated: PUBLIC_LEADERBOARD_FILE_NOTICE,
    generatedAt: generatedAt.toISOString(),
    alltime: buildPublicAlltimeBlock(alltime),
    month,
  };
  return `${JSON.stringify(file, null, 2)}\n`;
}

/**
 * Whether two file contents describe the SAME board — equal apart from the
 * `generatedAt` stamp. This is what lets the publisher skip a commit when the
 * board has not changed: generatedAt differs on every run by construction, so a
 * byte compare would commit hourly forever even with zero rank changes.
 *
 * A missing/unparseable existing file is never equivalent (the first
 * generation, or a hand-mangled file, must be [re]written).
 */
export function homepageLeaderboardEquivalent(existing: string | null, next: string): boolean {
  if (existing === null) {
    return false;
  }
  let existingParsed: unknown;
  try {
    existingParsed = JSON.parse(existing);
  } catch {
    return false;
  }
  if (typeof existingParsed !== 'object' || existingParsed === null) {
    return false;
  }
  const nextParsed = JSON.parse(next) as Record<string, unknown>;
  const a = { ...(existingParsed as Record<string, unknown>) };
  const b = { ...nextParsed };
  delete a.generatedAt;
  delete b.generatedAt;
  return JSON.stringify(a) === JSON.stringify(b);
}
