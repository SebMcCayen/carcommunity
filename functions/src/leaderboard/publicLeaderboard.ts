/**
 * Public leaderboard publish — reads the precomputed `leaderboards/{scope}`
 * documents and syncs a compact `data/leaderboard.json` to the community
 * homepage repo (PR3 of the social leaderboard).
 *
 * This is the leaderboard analogue of events' `regenerateHomepageEvents`
 * (events/publicSite.ts): it does the Firestore reads + the pure build
 * (publicLeaderboard-core.ts) + the GitHub sync (leaderboardRepo.ts), and it is
 * BEST-EFFORT — a publish failure never breaks leaderboard GENERATION. The
 * caller (the scheduled generator) swallows the result; the next hourly run
 * self-heals a transient GitHub outage, and the skip-when-unchanged check keeps
 * a quiet board from committing hourly.
 *
 * It publishes WHATEVER `leaderboards/{scope}` documents exist:
 *  - `leaderboards/alltime` — written by the all-time generator (PR1, merged);
 *  - `leaderboards/{YYYY-MM}` — the current and immediately previous calendar
 *    month. Either block is null when its source document is absent.
 */

import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { seasonIdForInstant } from '../crownHunt/crown-hunt-stats-core';
import { LEADERBOARD_ALL_TIME_SCOPE } from './leaderboard-core';
import {
  buildPublicLeaderboardFile,
  buildPublicMonthBlock,
  type StoredCategories,
} from './publicLeaderboard-core';
import { syncHomepageLeaderboardFile, type HomepageSyncStatus } from './leaderboardRepo';

// The leaderboard collection name, inlined here rather than imported from
// ./generator to avoid a generator <-> publicLeaderboard import cycle
// (generator.ts imports publishPublicLeaderboard from this module). Under the
// functions' CommonJS build a cycle risks partially-initialized exports.
const LEADERBOARD_COLLECTION = 'leaderboards';

/** Reads one `leaderboards/{scope}` document's `categories` map, or null when absent. */
async function readScopeCategories(scope: string): Promise<StoredCategories | null> {
  const snap = await db.collection(LEADERBOARD_COLLECTION).doc(scope).get();
  if (!snap.exists) {
    return null;
  }
  const categories = snap.data()?.categories;
  if (typeof categories !== 'object' || categories === null) {
    return null;
  }
  return categories as StoredCategories;
}

/** What one publish did — the sync outcome plus whether a monthly board was found. */
export interface PublicLeaderboardPublishResult {
  status: HomepageSyncStatus;
  hasMonth: boolean;
  hasPreviousMonth: boolean;
}

/** Returns the calendar month immediately before a canonical `YYYY-MM` id. */
export function previousMonthId(monthId: string): string {
  const [yearText, monthText] = monthId.split('-');
  const year = Number(yearText);
  const month = Number(monthText);
  const previousYear = month === 1 ? year - 1 : year;
  const previousMonth = month === 1 ? 12 : month - 1;
  return `${previousYear}-${String(previousMonth).padStart(2, '0')}`;
}

/**
 * Reads the published `leaderboards/{scope}` documents (all-time + current and
 * previous month), builds the public top-3 JSON and syncs it to the homepage repo. Never
 * throws — every failure resolves to a `status` of 'failed'.
 *
 * @param token the HOMEPAGE_REPO_TOKEN secret value.
 * @param now   the clock (the current month's `YYYY-MM` doc id is derived from it).
 */
export async function publishPublicLeaderboard(
  token: string,
  now: Date = new Date(),
): Promise<PublicLeaderboardPublishResult> {
  // The WHOLE body is wrapped so the documented "never throws" contract is real
  // rather than relying on the caller. syncHomepageLeaderboardFile already
  // swallows its own errors, but the Firestore reads (readScopeCategories) and
  // the pure build could still throw before it — a bad `leaderboards/{scope}`
  // read or an unexpected builder fault must degrade to 'failed', never take
  // down the scheduled generator run that invoked us.
  try {
    const monthId = seasonIdForInstant(now);
    const previousId = previousMonthId(monthId);
    const [alltime, monthCategories, previousMonthCategories] = await Promise.all([
      readScopeCategories(LEADERBOARD_ALL_TIME_SCOPE),
      readScopeCategories(monthId),
      readScopeCategories(previousId),
    ]);

    const month = monthCategories ? buildPublicMonthBlock(monthId, monthCategories) : null;
    const previousMonth = previousMonthCategories
      ? buildPublicMonthBlock(previousId, previousMonthCategories)
      : null;
    const content = buildPublicLeaderboardFile(alltime, month, now, previousMonth);
    const status = await syncHomepageLeaderboardFile(content, token, {
      month: monthId,
      hasMonth: month ? 1 : 0,
      previousMonth: previousId,
      hasPreviousMonth: previousMonth ? 1 : 0,
    });
    // One summary line per publish (matches the events publisher); 'unchanged'
    // runs stay quiet so a stable board is not hourly log noise.
    if (status !== 'unchanged') {
      logger.info('Public leaderboard sync', {
        status,
        month: monthId,
        hasMonth: month !== null,
        previousMonth: previousId,
        hasPreviousMonth: previousMonth !== null,
      });
    }
    return {
      status,
      hasMonth: month !== null,
      hasPreviousMonth: previousMonth !== null,
    };
  } catch (error) {
    // Non-identifying context only (never the token or member data). Consistent
    // with the 'failed' shape syncHomepageLeaderboardFile itself returns.
    logger.error('Public leaderboard publish failed', { error: String(error) });
    return { status: 'failed', hasMonth: false, hasPreviousMonth: false };
  }
}
