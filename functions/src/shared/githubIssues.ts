/**
 * Shared GitHub REST helper — creates issues on the PUBLIC repo
 * SebMcCayen/carcommunity via the Node global `fetch` (functions run on
 * Node 22 — pinned by firebase.json functions[].runtime; no octokit dependency).
 *
 * Extracted so BOTH the authenticated feedback.reportIssue callable and the
 * unauthenticated-diagnostics-driven diagnostics-onSignInFailure trigger share
 * one hardened issue-creation path:
 *
 * - the caller-supplied `labels` array is threaded through verbatim (feedback
 *   uses `android-issue`; the sign-in trigger uses `sign-in-failure` +
 *   `auto-generated`), so labelling stays configurable per source;
 * - the token is passed as an argument (resolved from the GITHUB_ISSUE_TOKEN
 *   Secret Manager secret at the call site) and is never logged;
 * - every failure (network, auth, rate limit, missing token, unexpected shape)
 *   resolves to `null` — the function NEVER throws, so callers can decide how
 *   to degrade without a crash-loop.
 *
 * Pure-ish: the only side effect is the outbound HTTPS request.
 */

import { logger } from 'firebase-functions';

/**
 * Neutralizes GitHub's live-reference syntax in caller-controlled text destined
 * for a public issue. A zero-width space (U+200B) is inserted immediately after
 * every `@` and `#`, so `@maintainer` → `@​maintainer` and `#123` → `#​123`:
 * they render visually identical but are no longer a live @mention (which would
 * notify a maintainer) or `#` issue cross-reference. This blocks any flow that
 * feeds user/client-controlled strings into an issue from being used to
 * spam/ping maintainers or auto-link arbitrary issues.
 *
 * More robust than wrapping in a fenced code block (a caller can break out with
 * their own ```), and applied ONLY to the strings sent to GitHub.
 */
export function neutralizeMentions(text: string): string {
  return text.replace(/[@#]/g, '$&\u200b');
}

/** `POST /issues` request body for the public repo. */
export interface GitHubIssuePayload {
  title: string;
  body: string;
  labels: string[];
}

/** The created issue's stable identifiers. */
export interface GitHubIssueResult {
  number: number;
  url: string;
}

/** REST base for the public repo (issues live under `/issues`). */
export const GITHUB_REPO_URL = 'https://api.github.com/repos/SebMcCayen/carcommunity';

/** REST endpoint for issues on the public repo. */
export const GITHUB_ISSUES_URL = `${GITHUB_REPO_URL}/issues`;

/**
 * Common request headers for the GitHub REST v3 JSON API: the bearer
 * `Authorization` built from the token argument plus the standard Accept /
 * API-version / User-Agent. POST callers add their own `Content-Type`.
 */
function githubHeaders(token: string, userAgent: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': userAgent,
  };
}

/**
 * Files a public issue. Returns the created issue's number/url, or `null` on
 * any failure. Never throws.
 *
 * @param payload   title/body/labels — already sanitized by the caller.
 * @param token     the GITHUB_ISSUE_TOKEN secret value; empty → logged + null.
 * @param userAgent required by the GitHub API; identifies the caller.
 * @param logContext scalar context merged into failure logs (never the token).
 */
export async function createGitHubIssue(
  payload: GitHubIssuePayload,
  token: string,
  userAgent: string,
  logContext: Record<string, string | number> = {},
): Promise<GitHubIssueResult | null> {
  // Never reach api.github.com from the Firebase emulator. The emulator injects
  // a non-empty placeholder GITHUB_ISSUE_TOKEN (functions/.secret.local) purely
  // so the Functions emulator does not block on Google Secret Manager at each
  // secret-declaring function's cold start (a CI-hanging network call). That
  // placeholder must not turn into real GitHub traffic — every error-report
  // trigger and feedback submission in the ~900-test emulator suite calls this.
  // Treated exactly like an absent token: the caller records
  // githubIssueStatus:'failed' and moves on, keeping the suite hermetic.
  if (process.env.FUNCTIONS_EMULATOR === 'true') {
    return null;
  }
  if (!token) {
    logger.error('createGitHubIssue: GITHUB_ISSUE_TOKEN is empty', logContext);
    return null;
  }

  try {
    const response = await fetch(GITHUB_ISSUES_URL, {
      method: 'POST',
      headers: { ...githubHeaders(token, userAgent), 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      // The body may carry the GitHub error message; log the status only,
      // never the token, and never surface it to the caller.
      logger.error('createGitHubIssue: GitHub issue creation failed', {
        ...logContext,
        status: response.status,
      });
      return null;
    }

    const body = (await response.json()) as { number?: number; html_url?: string };
    if (typeof body.number !== 'number' || typeof body.html_url !== 'string') {
      logger.error('createGitHubIssue: unexpected GitHub response shape', logContext);
      return null;
    }
    return { number: body.number, url: body.html_url };
  } catch (error) {
    logger.error('createGitHubIssue: GitHub request threw', {
      ...logContext,
      error: String(error),
    });
    return null;
  }
}

/**
 * A single OPEN issue as returned by `GET /issues`, reduced to the fields the
 * `openTickets` mirror needs. `pull_request` is present only on rows that are
 * actually pull requests (the issues endpoint returns both) — callers filter it
 * out. `body` may be null on a bodyless issue.
 */
export interface GitHubOpenIssue {
  number: number;
  title: string;
  body: string | null;
  html_url: string;
  created_at: string;
  state: string;
  comments: number;
  pull_request?: unknown;
}

/**
 * Validates EVERY field the openTickets pipeline consumes and NORMALIZES each
 * row, so a downstream consumer never sees an `undefined`. A row missing any
 * required scalar (number/title/html_url/created_at/state) is DROPPED rather
 * than allowed to poison the mirror; the optional `body`/`comments`/
 * `pull_request` are coerced to safe defaults.
 */
function normalizeIssueRows(rows: unknown[]): GitHubOpenIssue[] {
  const normalized: GitHubOpenIssue[] = [];
  for (const row of rows) {
    if (!row || typeof row !== 'object') continue;
    const r = row as Record<string, unknown>;
    if (
      typeof r.number !== 'number' ||
      typeof r.title !== 'string' ||
      typeof r.html_url !== 'string' ||
      typeof r.created_at !== 'string' ||
      typeof r.state !== 'string'
    ) {
      continue;
    }
    normalized.push({
      number: r.number,
      title: r.title,
      body: typeof r.body === 'string' ? r.body : null,
      html_url: r.html_url,
      created_at: r.created_at,
      state: r.state,
      comments: typeof r.comments === 'number' ? r.comments : 0,
      // Preserve the PR marker so isMirrorableIssue can drop pull requests.
      ...(r.pull_request !== undefined ? { pull_request: r.pull_request } : {}),
    });
  }
  return normalized;
}

/**
 * A successful list result. `complete` is true when the FULL open set was
 * fetched (the final page was short, so nothing follows); it is false when the
 * page cap was hit while pages were still full — meaning the open set may be
 * TRUNCATED. The scheduled sync must NOT run its delete/reconcile pass on a
 * truncated set (it would delete still-open tickets it never saw), so this flag
 * is load-bearing, not cosmetic.
 */
export interface OpenIssuesResult {
  issues: GitHubOpenIssue[];
  complete: boolean;
}

/** Max pages fetched per list call (100/page → up to 1000 open issues). */
export const OPEN_ISSUES_MAX_PAGES = 10;

/**
 * Lists OPEN issues carrying the given label on the public repo, newest-updated
 * first, PAGINATING through all pages (per_page clamped to GitHub's 1..100)
 * until a short page ends the set or [OPEN_ISSUES_MAX_PAGES] is hit. `perPage`
 * is clamped so an out-of-range value can never turn a successful list into a
 * 4xx.
 *
 * Returns `null` on ANY failure (network, non-2xx, unexpected shape, missing
 * token, or a failure on ANY page mid-pagination — so a partial set is never
 * returned) and in the emulator. On success returns `{ issues, complete }`:
 * `complete` is false only when the page cap was reached with full pages, i.e.
 * the set may be truncated. NEVER throws — the scheduled sync is best-effort and
 * must not crash-loop on a GitHub blip.
 *
 * The null-vs-result and complete-vs-truncated distinctions are load-bearing:
 * they let the sync reconcile stale docs out on a genuine COMPLETE result
 * (including a zero-open-issues one) WITHOUT wiping the mirror on a transient
 * outage (null) or deleting unseen open tickets from a truncated set.
 *
 * PRs (which the issues endpoint also returns) are NOT filtered here — the
 * caller drops any row with a `pull_request` field so the rule lives with the
 * mapping.
 */
export async function listOpenIssues(
  label: string,
  token: string,
  userAgent: string,
  perPage = 100,
): Promise<OpenIssuesResult | null> {
  if (process.env.FUNCTIONS_EMULATOR === 'true') {
    return null;
  }
  if (!token) {
    logger.error('listOpenIssues: GITHUB_ISSUE_TOKEN is empty');
    return null;
  }

  const boundedPerPage = Math.min(100, Math.max(1, Math.trunc(perPage)));
  const issues: GitHubOpenIssue[] = [];

  for (let page = 1; page <= OPEN_ISSUES_MAX_PAGES; page += 1) {
    const url =
      `${GITHUB_ISSUES_URL}?state=open&labels=${encodeURIComponent(label)}` +
      `&per_page=${boundedPerPage}&page=${page}&sort=updated&direction=desc`;
    try {
      const response = await fetch(url, { method: 'GET', headers: githubHeaders(token, userAgent) });
      if (!response.ok) {
        // A failure on ANY page aborts the WHOLE call to null — never return a
        // partial set, which would make the sync delete the pages it never saw.
        logger.error('listOpenIssues: GitHub list failed', { status: response.status, page });
        return null;
      }
      const body = (await response.json()) as unknown;
      if (!Array.isArray(body)) {
        logger.error('listOpenIssues: unexpected GitHub response shape', { page });
        return null;
      }
      issues.push(...normalizeIssueRows(body));
      // A short RAW page (fewer than the page size) is the last page. Use the
      // raw length, not the normalized count, so dropped malformed rows can't
      // be mistaken for the end of the set.
      if (body.length < boundedPerPage) {
        return { issues, complete: true };
      }
    } catch (error) {
      logger.error('listOpenIssues: GitHub request threw', { error: String(error), page });
      return null;
    }
  }

  // The page cap was hit with full pages throughout: the open set may extend
  // beyond what we fetched, so mark it potentially-truncated.
  logger.warn('listOpenIssues: page cap reached; open set may be truncated', {
    pages: OPEN_ISSUES_MAX_PAGES,
    fetched: issues.length,
  });
  return { issues, complete: false };
}

/**
 * Posts a comment to an existing issue. Returns `true` on success, `false` on
 * ANY failure or in the emulator; NEVER throws. `body` MUST already be
 * sanitized by the caller (neutralizeMentions + length bound) — this helper
 * does no sanitization, it only transports.
 */
export async function createIssueComment(
  issueNumber: number,
  body: string,
  token: string,
  userAgent: string,
  logContext: Record<string, string | number> = {},
): Promise<boolean> {
  if (process.env.FUNCTIONS_EMULATOR === 'true') {
    return false;
  }
  if (!token) {
    logger.error('createIssueComment: GITHUB_ISSUE_TOKEN is empty', logContext);
    return false;
  }

  try {
    const response = await fetch(`${GITHUB_ISSUES_URL}/${issueNumber}/comments`, {
      method: 'POST',
      headers: { ...githubHeaders(token, userAgent), 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    });
    if (!response.ok) {
      logger.error('createIssueComment: GitHub comment failed', {
        ...logContext,
        status: response.status,
      });
      return false;
    }
    return true;
  } catch (error) {
    logger.error('createIssueComment: GitHub request threw', {
      ...logContext,
      error: String(error),
    });
    return false;
  }
}
