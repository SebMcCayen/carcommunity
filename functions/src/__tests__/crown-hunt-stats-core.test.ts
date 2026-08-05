/**
 * Kronjakt stats/leaderboard/season PURE logic — unit tests.
 *
 * Everything the stats layer decides without Firestore: season bucketing and
 * month boundaries, the daily-collection streak, leaderboard ranking, rarity
 * ordering and the document-id helpers. The emulator integration
 * (crownhunt-stats.emulator.test.ts) proves the wiring; this proves the maths.
 */

import { describe, expect, it } from 'vitest';
import {
  ALL_TIME_SCOPE,
  CROWN_STATS_RARITIES,
  EMPTY_COLLECTION_STREAK,
  SEASON_PERIOD,
  advanceCollectionStreak,
  isCrownStatsRarity,
  isScopeId,
  isSeasonId,
  leaderboardEntryDocId,
  ledgerStatFoldId,
  nextSeasonId,
  previousSeasonId,
  rankFromBetterCount,
  rankLeaderboard,
  rarerThan,
  readCollectionStreak,
  seasonBounds,
  seasonIdForInstant,
  spawnStatFoldId,
  stockholmDayKey,
  zeroRarityCounts,
} from '../crownHunt/crown-hunt-stats-core';

describe('season bucketing', () => {
  it('is monthly', () => {
    expect(SEASON_PERIOD).toBe('month');
  });

  it('buckets an instant into its Europe/Stockholm calendar month', () => {
    // Mid-August in either offset.
    expect(seasonIdForInstant(new Date('2026-08-15T12:00:00Z'))).toBe('2026-08');
    // 2026-08-01 00:30 Stockholm (summer, UTC+2) is 2026-07-31T22:30Z but still
    // AUGUST locally — the bucket must follow the civil month, not UTC.
    expect(seasonIdForInstant(new Date('2026-07-31T22:30:00Z'))).toBe('2026-08');
    // 2026-01-31 23:30 Stockholm (winter, UTC+1) is 2026-01-31T22:30Z — January.
    expect(seasonIdForInstant(new Date('2026-01-31T22:30:00Z'))).toBe('2026-01');
  });

  it('validates season and scope ids', () => {
    expect(isSeasonId('2026-08')).toBe(true);
    expect(isSeasonId('2026-13')).toBe(false);
    expect(isSeasonId('2026-00')).toBe(false);
    expect(isSeasonId('alltime')).toBe(false);
    expect(isScopeId('alltime')).toBe(true);
    expect(isScopeId('2026-08')).toBe(true);
    expect(isScopeId('nope')).toBe(false);
  });

  it('steps months and wraps years', () => {
    expect(nextSeasonId('2026-08')).toBe('2026-09');
    expect(nextSeasonId('2026-12')).toBe('2027-01');
    expect(previousSeasonId('2026-01')).toBe('2025-12');
    expect(previousSeasonId('2026-09')).toBe('2026-08');
  });

  it('produces bounds that tile the timeline and contain their own month', () => {
    const { startAt, endAt } = seasonBounds('2026-08');
    expect(startAt.getTime()).toBeLessThan(endAt.getTime());
    // The start instant is inside its own season; the end instant is the next.
    expect(seasonIdForInstant(startAt)).toBe('2026-08');
    expect(seasonIdForInstant(new Date(endAt.getTime() - 1))).toBe('2026-08');
    expect(seasonIdForInstant(endAt)).toBe('2026-09');
    // endAt is exactly next season's start — no gap, no overlap.
    expect(endAt.getTime()).toBe(seasonBounds('2026-09').startAt.getTime());
  });

  it('handles a DST-spanning season without shifting the boundary', () => {
    // October 2026: DST ends 25 Oct. The month is still a single bucket.
    const { startAt, endAt } = seasonBounds('2026-10');
    expect(seasonIdForInstant(startAt)).toBe('2026-10');
    expect(seasonIdForInstant(new Date(endAt.getTime() - 1))).toBe('2026-10');
  });
});

describe('daily-collection streak', () => {
  it('starts a run on the first collection', () => {
    const { state, changed } = advanceCollectionStreak(EMPTY_COLLECTION_STREAK, '2026-08-01');
    expect(changed).toBe(true);
    expect(state).toEqual({ current: 1, best: 1, lastDayKey: '2026-08-01' });
  });

  it('does not advance twice on the same day', () => {
    const day1 = advanceCollectionStreak(EMPTY_COLLECTION_STREAK, '2026-08-01').state;
    const again = advanceCollectionStreak(day1, '2026-08-01');
    expect(again.changed).toBe(false);
    expect(again.state).toEqual(day1);
  });

  it('grows on a consecutive day', () => {
    const day1 = advanceCollectionStreak(EMPTY_COLLECTION_STREAK, '2026-08-01').state;
    const day2 = advanceCollectionStreak(day1, '2026-08-02');
    expect(day2.state).toEqual({ current: 2, best: 2, lastDayKey: '2026-08-02' });
  });

  it('resets the current run on a gap but keeps the best', () => {
    let state = advanceCollectionStreak(EMPTY_COLLECTION_STREAK, '2026-08-01').state;
    state = advanceCollectionStreak(state, '2026-08-02').state;
    state = advanceCollectionStreak(state, '2026-08-03').state; // best 3
    const afterGap = advanceCollectionStreak(state, '2026-08-10');
    expect(afterGap.state).toEqual({ current: 1, best: 3, lastDayKey: '2026-08-10' });
  });

  it('reads a stored streak defensively', () => {
    expect(readCollectionStreak(undefined)).toEqual(EMPTY_COLLECTION_STREAK);
    expect(
      readCollectionStreak({
        collectionStreakCurrent: 4,
        collectionStreakBest: 9,
        lastCollectionDayKey: '2026-08-02',
      }),
    ).toEqual({ current: 4, best: 9, lastDayKey: '2026-08-02' });
    // Garbage degrades to zero / null.
    expect(
      readCollectionStreak({
        collectionStreakCurrent: -2,
        collectionStreakBest: Number.NaN,
        lastCollectionDayKey: 'not-a-day',
      }),
    ).toEqual({ current: 0, best: 0, lastDayKey: null });
  });

  it('uses Europe/Stockholm civil days for the day key', () => {
    // 23:30 UTC on 31 Jan is 00:30 local on 1 Feb (winter, UTC+1).
    expect(stockholmDayKey(new Date('2026-01-31T23:30:00Z'))).toBe('2026-02-01');
  });
});

describe('leaderboard ranking', () => {
  it('ranks by points, then crowns, then uid, dropping zero-score members', () => {
    const ranked = rankLeaderboard([
      { uid: 'zoe', points: 100, crownsCollected: 4 },
      { uid: 'amy', points: 100, crownsCollected: 4 }, // tie -> uid ascending
      { uid: 'bob', points: 250, crownsCollected: 10 },
      { uid: 'cat', points: 100, crownsCollected: 9 }, // more crowns than the 100-tie
      { uid: 'dan', points: 0, crownsCollected: 0 }, // never collected -> off board
    ]);
    expect(ranked.map((r) => [r.uid, r.rank])).toEqual([
      ['bob', 1],
      ['cat', 2],
      ['amy', 3],
      ['zoe', 4],
    ]);
  });

  it('derives a rank from a better-count', () => {
    expect(rankFromBetterCount(0)).toBe(1);
    expect(rankFromBetterCount(4)).toBe(5);
    expect(rankFromBetterCount(-3)).toBe(1);
  });
});

describe('rarity', () => {
  it('knows the four tiers, ascending', () => {
    expect(CROWN_STATS_RARITIES).toEqual(['common', 'uncommon', 'rare', 'legendary']);
    expect(isCrownStatsRarity('legendary')).toBe(true);
    expect(isCrownStatsRarity('mythic')).toBe(false);
    expect(zeroRarityCounts()).toEqual({ common: 0, uncommon: 0, rare: 0, legendary: 0 });
  });

  it('compares rarity, beating a null incumbent', () => {
    expect(rarerThan('common', null)).toBe(true);
    expect(rarerThan('rare', 'uncommon')).toBe(true);
    expect(rarerThan('uncommon', 'rare')).toBe(false);
    expect(rarerThan('rare', 'rare')).toBe(false);
  });
});

describe('document ids', () => {
  it('composes deterministic ids', () => {
    expect(ALL_TIME_SCOPE).toBe('alltime');
    expect(leaderboardEntryDocId('2026-08', 'u1')).toBe('2026-08__u1');
    expect(leaderboardEntryDocId(ALL_TIME_SCOPE, 'u1')).toBe('alltime__u1');
    expect(ledgerStatFoldId('u1', 'e1')).toBe('u1__e1');
    expect(spawnStatFoldId('s1', 'collect')).toBe('s1__collect');
  });
});
