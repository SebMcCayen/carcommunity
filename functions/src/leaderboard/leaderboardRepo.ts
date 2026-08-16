/**
 * GitHub Contents API sync for the community homepage's data/leaderboard.json.
 *
 * PR3 of the social leaderboard. The homepage
 * (SebMcCayen/kungsbacka-car-community-homepage) is a static site deployed from
 * its main branch via cPanel sync, so "showing the leaderboard on our webpage"
 * means committing a generated JSON file to that repo. This module owns exactly
 * that write path and is a deliberate MIRROR of events/homepageRepo.ts (the
 * events→homepage publisher): same GET→compare→PUT shape, same
 * skip-when-unchanged, same one-retry-on-409, same never-throw posture, and the
 * SAME secret.
 *
 *   GET  the current file (for its blob sha and content)
 *   →    SKIP entirely when the content is unchanged bar the generatedAt stamp
 *        (homepageLeaderboardEquivalent) — the hourly generator run must not
 *        create a commit per hour when no rank changed
 *   PUT  the new content with the sha (or shaless when the file is new)
 *
 * SECRET: reuses HOMEPAGE_REPO_TOKEN — the SAME fine-grained PAT (contents:
 * write on ONLY the homepage repo) the events publisher already uses. No new
 * secret is provisioned for the leaderboard; both generated files live in the
 * same repo, so one `contents: write` token covers both.
 *
 * The token is passed in (resolved from the HOMEPAGE_REPO_TOKEN secret at the
 * call site) and never logged; every failure resolves to 'failed' — the
 * function NEVER throws, so a GitHub outage degrades to a log line and the next
 * generator run self-heals.
 */

import { logger } from 'firebase-functions';
import { homepageLeaderboardEquivalent } from './publicLeaderboard-core';

/** Contents endpoint of the generated leaderboard file on the homepage repo. */
export const HOMEPAGE_LEADERBOARD_FILE_URL =
  'https://api.github.com/repos/SebMcCayen/kungsbacka-car-community-homepage/contents/data/leaderboard.json';

/**
 * The branch the homepage deploys from (cPanel syncs main). Pinned explicitly
 * on BOTH the read (?ref=) and the write (body.branch) rather than relying on
 * the repo's default branch — a default-branch change must never silently
 * redirect the sync. Same value the events publisher pins.
 */
export const HOMEPAGE_REPO_BRANCH = 'main';

/** Commit message used for every leaderboard sync commit on the homepage repo. */
export const HOMEPAGE_LEADERBOARD_COMMIT_MESSAGE = 'chore: sync leaderboard to homepage';

const USER_AGENT = 'carcommunity-homepage-sync';

export type HomepageSyncStatus =
  /** New content committed to the homepage repo. */
  | 'committed'
  /** Existing file already equivalent — no commit made. */
  | 'unchanged'
  /** Firebase emulator run — never reaches api.github.com (hermetic tests). */
  | 'skipped-emulator'
  /** Any failure (missing token, network, auth, conflict after retry). Logged, never thrown. */
  | 'failed';

function githubHeaders(token: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': USER_AGENT,
  };
}

/** The current file, or null when it does not exist yet, or 'error'. */
async function getCurrentFile(
  token: string,
  logContext: Record<string, string | number>,
): Promise<{ sha: string; content: string } | null | 'error'> {
  const response = await fetch(`${HOMEPAGE_LEADERBOARD_FILE_URL}?ref=${HOMEPAGE_REPO_BRANCH}`, {
    headers: githubHeaders(token),
  });
  if (response.status === 404) {
    return null;
  }
  if (!response.ok) {
    logger.error('leaderboardRepo: reading data/leaderboard.json failed', {
      ...logContext,
      status: response.status,
    });
    return 'error';
  }
  const body = (await response.json()) as { sha?: string; content?: string };
  if (typeof body.sha !== 'string' || typeof body.content !== 'string') {
    logger.error('leaderboardRepo: unexpected GitHub contents response shape', logContext);
    return 'error';
  }
  // GitHub base64-encodes with embedded newlines; Buffer tolerates them.
  return { sha: body.sha, content: Buffer.from(body.content, 'base64').toString('utf8') };
}

async function putFile(
  token: string,
  content: string,
  sha: string | null,
  logContext: Record<string, string | number>,
): Promise<'committed' | 'conflict' | 'error'> {
  const response = await fetch(HOMEPAGE_LEADERBOARD_FILE_URL, {
    method: 'PUT',
    headers: { ...githubHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message: HOMEPAGE_LEADERBOARD_COMMIT_MESSAGE,
      branch: HOMEPAGE_REPO_BRANCH,
      content: Buffer.from(content, 'utf8').toString('base64'),
      ...(sha ? { sha } : {}),
    }),
  });
  if (response.ok) {
    return 'committed';
  }
  if (response.status === 409) {
    return 'conflict';
  }
  logger.error('leaderboardRepo: writing data/leaderboard.json failed', {
    ...logContext,
    status: response.status,
  });
  return 'error';
}

/**
 * Syncs `content` to data/leaderboard.json on the homepage repo's main branch.
 * Skips the commit entirely when the stored file is already equivalent
 * (ignoring generatedAt). Never throws.
 *
 * @param content    the full new file content (buildPublicLeaderboardFile).
 * @param token      the HOMEPAGE_REPO_TOKEN secret value; empty → logged + 'failed'.
 * @param logContext scalar context merged into logs (never the token or content).
 */
export async function syncHomepageLeaderboardFile(
  content: string,
  token: string,
  logContext: Record<string, string | number> = {},
): Promise<HomepageSyncStatus> {
  // Never reach api.github.com from the Firebase emulator — same hermeticity
  // rule as events/homepageRepo.ts and shared/githubIssues.ts: the emulator
  // injects a placeholder secret so cold starts do not block on Secret Manager,
  // and that placeholder must not turn into real GitHub commits from tests.
  if (process.env.FUNCTIONS_EMULATOR === 'true') {
    return 'skipped-emulator';
  }
  if (!token) {
    logger.error('leaderboardRepo: HOMEPAGE_REPO_TOKEN is empty', logContext);
    return 'failed';
  }

  try {
    // One initial attempt + one bounded retry after a 409 sha conflict (a
    // concurrent generation committed between our GET and PUT).
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const current = await getCurrentFile(token, logContext);
      if (current === 'error') {
        return 'failed';
      }
      if (homepageLeaderboardEquivalent(current?.content ?? null, content)) {
        return 'unchanged';
      }
      const put = await putFile(token, content, current?.sha ?? null, logContext);
      if (put === 'committed') {
        return 'committed';
      }
      if (put === 'error') {
        return 'failed';
      }
      // 409 → loop once more with a fresh sha.
    }
    logger.error('leaderboardRepo: still conflicting after retry', logContext);
    return 'failed';
  } catch (error) {
    logger.error('leaderboardRepo: GitHub request threw', {
      ...logContext,
      error: String(error),
    });
    return 'failed';
  }
}
