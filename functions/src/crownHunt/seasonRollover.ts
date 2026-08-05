/**
 * Kronjakt SEASON rollover — the scheduled aggregator that closes a finished
 * season and opens the next one.
 *
 * A season is one Europe/Stockholm calendar MONTH. This runs DAILY (not only on
 * the 1st) and is self-healing: each run makes sure the current month's season
 * document exists and is active, then FINALIZES any still-active season whose id
 * is older than the current one. Running daily means a single missed
 * month-boundary tick is caught the next day rather than stranding a season
 * un-finalized.
 *
 * FINALIZATION, per season, is idempotent in layers so a re-run is safe:
 *  1. the season document flips `active -> ended` inside a transaction, and the
 *     transition is the guard — a second run sees `ended` and does the
 *     already-finalized branch;
 *  2. the podium badges are create-if-absent writes (badges/awards.ts), so
 *     re-awarding is a no-op;
 *  3. the lifetime-championship credit is guarded by a per-season marker
 *     (`crownHuntSeasonWinCredits/{seasonId}`) claimed with `create`, so the
 *     winner's `seasonsWon` counter is incremented exactly once even if
 *     finalization runs twice.
 *
 * NON-DESTRUCTIVE. Nothing here wipes points, crowns or the ledger. The season
 * board is a separate `crownHuntLeaderboard/{seasonId}/entries` bucket that
 * simply stops being written once the season ends; the new month starts
 * accumulating in its own bucket from zero. The all-time board is never touched.
 *
 * BADGES (two distinct achievements, by explicit product decision):
 *  - PODIUM (`sasong_guld` / `sasong_silver` / `sasong_brons`): a single
 *    season's top three. Rank-specific standalone badges, permanent. The
 *    one-doc-per-key badge model is create-if-absent, so a member who podiums in
 *    several seasons keeps ONE badge per rank ("I reached the podium"); which
 *    seasons is preserved in each season's stored standings.
 *  - CHAMPION LADDER (`sasongsmastare_*`, metric `seasonsWon`): a LIFETIME count
 *    of first-place finishes, surfaced exactly (×N) in the read contract so the
 *    UI can show "N-time champion". Reuses the standard tier machinery: the win
 *    credit bumps `badgeProgress/{uid}.seasonsWon`, and the existing
 *    badges-onBadgeProgressWritten trigger evaluates and awards the rungs.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { withServerErrorReporting } from '../errors/serverErrors';
import { awardBadge } from '../badges/awards';
import { BADGE_METRIC_FIELD } from '../badges/badge-tiers';
import { tryEvaluateBadgeTiers } from '../badges/tierAwards';
import {
  CROWN_LEADERBOARD_COLLECTION,
  CROWN_SEASONS_COLLECTION,
  CROWN_SEASON_WIN_CREDITS_COLLECTION,
  CROWN_USER_STATS_COLLECTION,
  SEASON_PERIOD,
  isSeasonId,
  rankLeaderboard,
  seasonBounds,
  seasonIdForInstant,
  type LeaderboardCounter,
} from './crown-hunt-stats-core';
import { MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';

/** How many ranked members are stored on the finalized season document. */
export const SEASON_STANDINGS_LIMIT = 100;

/** Rank-specific podium badges, indexed by finishing position (1..3). */
const PODIUM_BADGE_BY_RANK = {
  1: 'sasong_guld',
  2: 'sasong_silver',
  3: 'sasong_brons',
} as const;

interface SeasonWinner {
  rank: number;
  uid: string;
  points: number;
  crownsCollected: number;
  displayName: string;
}

// ---------------------------------------------------------------------------
// Reads
// ---------------------------------------------------------------------------

/** Reads every leaderboard entry for a scope (a season is small enough to page). */
async function readScopeEntries(scope: string): Promise<LeaderboardCounter[]> {
  const snap = await db
    .collection(CROWN_LEADERBOARD_COLLECTION)
    .where('scope', '==', scope)
    .get();
  const entries: LeaderboardCounter[] = [];
  for (const doc of snap.docs) {
    const data = doc.data();
    const uid = typeof data.uid === 'string' && data.uid.length > 0 ? data.uid : null;
    if (!uid) {
      continue;
    }
    const points = typeof data.points === 'number' && Number.isFinite(data.points) ? data.points : 0;
    const crownsCollected =
      typeof data.crownsCollected === 'number' && Number.isFinite(data.crownsCollected)
        ? data.crownsCollected
        : 0;
    entries.push({ uid, points, crownsCollected });
  }
  return entries;
}

/** Best-effort public display name; falls back to a neutral label. */
async function resolveDisplayName(uid: string): Promise<string> {
  try {
    const snap = await db.collection('users').doc(uid).get();
    const name = snap.data()?.displayName;
    return typeof name === 'string' && name.length > 0 ? name : 'Kronjägare';
  } catch {
    return 'Kronjägare';
  }
}

// ---------------------------------------------------------------------------
// Season lifecycle
// ---------------------------------------------------------------------------

/** Creates the season document for `seasonId` as `active` if it does not exist. */
export async function ensureSeasonActive(seasonId: string): Promise<void> {
  const ref = db.collection(CROWN_SEASONS_COLLECTION).doc(seasonId);
  const { startAt, endAt } = seasonBounds(seasonId);
  await db.runTransaction(async (tx) => {
    if ((await tx.get(ref)).exists) {
      return;
    }
    tx.create(ref, {
      seasonId,
      period: SEASON_PERIOD,
      status: 'active',
      startAt: Timestamp.fromDate(startAt),
      endAt: Timestamp.fromDate(endAt),
      createdAt: FieldValue.serverTimestamp(),
    });
  });
}

/**
 * Credits one lifetime championship to `uid`. Exactly-once per season via the
 * win-credit marker; bumps both the authoritative `badgeProgress.seasonsWon`
 * (which drives the champion ladder) and the read-facing
 * `crownHuntUserStats.seasonsWon` (surfaced in personal stats + the leaderboard
 * contract). Returns true when the credit was newly applied.
 */
async function creditSeasonWin(uid: string, seasonId: string): Promise<boolean> {
  const markerRef = db.collection(CROWN_SEASON_WIN_CREDITS_COLLECTION).doc(seasonId);
  const progressRef = db.collection('badgeProgress').doc(uid);
  const statsRef = db.collection(CROWN_USER_STATS_COLLECTION).doc(uid);
  const credited = await db.runTransaction(async (tx) => {
    if ((await tx.get(markerRef)).exists) {
      return false;
    }
    tx.create(markerRef, { seasonId, uid, createdAt: FieldValue.serverTimestamp() });
    tx.set(
      progressRef,
      {
        [BADGE_METRIC_FIELD.seasonsWon]: FieldValue.increment(1),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    tx.set(
      statsRef,
      { uid, seasonsWon: FieldValue.increment(1), updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    );
    return true;
  });
  if (credited) {
    // The badgeProgress write fires badges-onBadgeProgressWritten, which awards
    // the newly reached champion rungs. Evaluating directly too makes the test
    // path (and a missed trigger) self-heal without waiting on the cascade.
    await tryEvaluateBadgeTiers(uid, 'crownHunt.seasonWin');
  }
  return credited;
}

/**
 * Finalizes one season: records the ranked standings + top-3 winners on the
 * season document, flips it to `ended`, then awards the podium badges and
 * credits the champion. Safe to run more than once.
 */
export async function finalizeSeason(seasonId: string): Promise<void> {
  const seasonRef = db.collection(CROWN_SEASONS_COLLECTION).doc(seasonId);

  const ranked = rankLeaderboard(await readScopeEntries(seasonId));
  const topRanked = ranked.slice(0, SEASON_STANDINGS_LIMIT);
  const podium = ranked.slice(0, 3);
  const winners: SeasonWinner[] = [];
  for (const row of podium) {
    winners.push({
      rank: row.rank,
      uid: row.uid,
      points: row.points,
      crownsCollected: row.crownsCollected,
      displayName: await resolveDisplayName(row.uid),
    });
  }

  // Flip active -> ended and store standings in one transaction; the transition
  // is the idempotency guard.
  const didFinalize = await db.runTransaction(async (tx) => {
    const snap = await tx.get(seasonRef);
    if (!snap.exists) {
      // A season nobody ever collected in has no leaderboard writes and so no
      // document. Create it already-ended with empty standings so the history
      // read is complete.
      const { startAt, endAt } = seasonBounds(seasonId);
      tx.create(seasonRef, {
        seasonId,
        period: SEASON_PERIOD,
        status: 'ended',
        startAt: Timestamp.fromDate(startAt),
        endAt: Timestamp.fromDate(endAt),
        createdAt: FieldValue.serverTimestamp(),
        finalizedAt: FieldValue.serverTimestamp(),
        participantCount: ranked.length,
        winners,
        topStandings: topRanked,
      });
      return true;
    }
    if (snap.data()?.status !== 'active') {
      return false; // already finalized
    }
    tx.set(
      seasonRef,
      {
        status: 'ended',
        finalizedAt: FieldValue.serverTimestamp(),
        participantCount: ranked.length,
        winners,
        topStandings: topRanked,
      },
      { merge: true },
    );
    return true;
  });

  if (!didFinalize) {
    return;
  }

  // Awards happen AFTER the finalize commit. Podium badges are idempotent
  // (create-if-absent); the champion credit is marker-guarded. Re-running a
  // finalized season skips this block via didFinalize=false, and the awards are
  // still individually idempotent if this run crashes partway and the next
  // day's run somehow re-enters (it will not, since status is ended — but the
  // guards make that safe regardless).
  for (const winner of winners) {
    const badgeKey = PODIUM_BADGE_BY_RANK[winner.rank as 1 | 2 | 3];
    if (badgeKey) {
      await awardBadge({ targetUid: winner.uid, badgeKey, source: 'automatic' });
    }
  }
  const champion = winners.find((w) => w.rank === 1);
  if (champion) {
    await creditSeasonWin(champion.uid, seasonId);
  }
  logger.info('Kronjakt season finalized', {
    seasonId,
    participantCount: ranked.length,
    winners: winners.map((w) => ({ rank: w.rank, uid: w.uid })),
  });
}

/**
 * One rollover pass. Ensures the current season is open, and finalizes every
 * active season older than it. Exported for deterministic emulator tests (which
 * inject `now`).
 */
export async function runSeasonRollover(now: Date = new Date()): Promise<void> {
  const currentSeasonId = seasonIdForInstant(now);
  await ensureSeasonActive(currentSeasonId);

  const activeSnap = await db
    .collection(CROWN_SEASONS_COLLECTION)
    .where('status', '==', 'active')
    .get();
  for (const doc of activeSnap.docs) {
    const seasonId = doc.id;
    if (isSeasonId(seasonId) && seasonId < currentSeasonId) {
      await finalizeSeason(seasonId);
    }
  }
}

/**
 * Scheduled daily at 00:15 Europe/Stockholm — just after a month boundary, with
 * the whole rest of the day as a self-healing retry window.
 */
export const rolloverSeason = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    schedule: '15 0 * * *',
    timeZone: 'Europe/Stockholm',
    memory: '512MiB',
    timeoutSeconds: 540,
  },
  withServerErrorReporting('crownHunt.rolloverSeason', async () => {
    await runSeasonRollover();
  }),
);
