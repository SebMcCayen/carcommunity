/**
 * Shared GitHub REST helper — creates issues on the PUBLIC repo
 * SebMcCayen/carcommunity via the Node global `fetch` (functions run on
 * Node 22 — see functions/package.json engines; no octokit dependency).
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

/** REST endpoint for issues on the public repo. */
export const GITHUB_ISSUES_URL = 'https://api.github.com/repos/SebMcCayen/carcommunity/issues';

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
  if (!token) {
    logger.error('createGitHubIssue: GITHUB_ISSUE_TOKEN is empty', logContext);
    return null;
  }

  try {
    const response = await fetch(GITHUB_ISSUES_URL, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': userAgent,
        'Content-Type': 'application/json',
      },
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
