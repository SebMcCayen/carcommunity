/**
 * GLOBAL auto-issue creation budget — pure domain logic.
 *
 * Why a second limiter on top of per-fingerprint dedup: dedup bounds issues per
 * DISTINCT failure, and that is the wrong axis for the worst case. A bad deploy
 * can produce hundreds of genuinely distinct fingerprints in minutes (every
 * scheduled sweep failing in a different frame, a schema change breaking twenty
 * code paths), and each one legitimately passes the dedup gate. The repository is
 * PUBLIC, so the blast radius of that burst is a permanent, world-readable wall
 * of auto-filed issues — plus GitHub secondary-rate-limit rejections that would
 * make the whole pipeline unreliable exactly when it matters.
 *
 * So all auto-filing paths (server errors and client errors) additionally share
 * ONE hourly budget, tracked in a single counter document per UTC hour:
 * `githubIssueBudget/{YYYYMMDDHH}`. Over the cap → the GitHub create is SKIPPED
 * and nothing is lost: the private report document is still written, the
 * occurrence is still tallied on the link doc, and the skip is logged. The link
 * is left in the retriable `failed` state, so the next occurrence in a fresh
 * hourly bucket files the issue.
 *
 * Deliberately NOT a per-source budget: the point is to bound how much this
 * codebase can publish to a public repo per hour in total, whoever asks.
 *
 * Pure module — no Firebase Admin SDK and no network imports (the transactional
 * consumer lives in shared/issueBudget.ts).
 */

/** Backend-only counter collection (`allow read, write: if false`). */
export const GITHUB_ISSUE_BUDGET_COLLECTION = 'githubIssueBudget';

/**
 * Maximum auto-filed GitHub issues per UTC hour, across ALL auto-filing paths.
 *
 * 20/hour is chosen to be comfortably above normal operation and well below
 * anything abusive: in steady state this pipeline files roughly zero issues an
 * hour (per-fingerprint dedup means only genuinely NEW failures file), a bad
 * deploy realistically surfaces a handful of distinct fingerprints, and GitHub's
 * content-creation secondary rate limit is ~20 requests/minute — so even a
 * saturated hour cannot trip it. A regression that would have filed 400 issues
 * instead files the first 20, and the remaining fingerprints are still fully
 * recorded privately and retried in the next bucket.
 */
export const GITHUB_ISSUE_BUDGET_PER_HOUR = 20;

/**
 * Counter document id for `now`: the UTC hour bucket, `YYYYMMDDHH`. UTC (not
 * Europe/Stockholm, unlike the scheduled functions' timeZone) so the bucket
 * boundary is unambiguous across DST transitions — a duplicated or skipped local
 * hour must not double or erase the budget.
 */
export function issueBudgetBucketId(now: Date): string {
  const yyyy = now.getUTCFullYear().toString().padStart(4, '0');
  const mm = (now.getUTCMonth() + 1).toString().padStart(2, '0');
  const dd = now.getUTCDate().toString().padStart(2, '0');
  const hh = now.getUTCHours().toString().padStart(2, '0');
  return `${yyyy}${mm}${dd}${hh}`;
}

/**
 * Whether the bucket is already at/over the cap. `used` is the count of issues
 * ALREADY charged to this bucket, so the caller charging the (used + 1)-th issue
 * is allowed exactly while `used < cap`.
 */
export function isIssueBudgetExhausted(
  used: number,
  cap: number = GITHUB_ISSUE_BUDGET_PER_HOUR,
): boolean {
  return used >= cap;
}
