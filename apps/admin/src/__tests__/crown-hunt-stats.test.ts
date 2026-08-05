import { describe, it, expect, vi } from 'vitest';

// Stub the firebase seams the barrel imports; these tests exercise the PURE
// mappers + ranking only.
vi.mock('../lib/callables', () => ({ callAdmin: vi.fn() }));
vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('firebase/firestore', () => ({
  collection: (_db: unknown, path: string) => ({ path }),
  doc: (_db: unknown, path: string, id?: string) => ({ path, id }),
  getDoc: vi.fn(),
  getDocs: vi.fn(),
  query: (t: unknown, ...c: unknown[]) => ({ t, c }),
  where: (f: string, op: string, v: unknown) => ({ f, op, v }),
  orderBy: (f: string, d: string) => ({ f, d }),
  limit: (n: number) => ({ n }),
}));

import {
  toAdminSpawnStatsView,
  toCellStat,
  toSeason,
  toLeaderboardCounter,
  rankLeaderboardCounters,
  toRarityCounts,
  sumRarityCounts,
  cellKeyCenter,
  currentSeasonId,
} from '@/features/crown-hunt';

describe('toAdminSpawnStatsView', () => {
  it('derives collectionRate and the hand-placed remainder', () => {
    const view = toAdminSpawnStatsView('alltime', {
      spawnedTotal: 100,
      collectedTotal: 40,
      spawnedByRarity: { common: 70, rare: 30 },
      collectedByRarity: { common: 20, rare: 8 },
    });
    expect(view.collectionRate).toBeCloseTo(0.4);
    // 40 collected total - (20 + 8) auto-spawned by rarity = 12 hand-placed.
    expect(view.handPlacedCollected).toBe(12);
  });

  it('reports a 0 collection rate when nothing has spawned (no divide-by-zero)', () => {
    expect(toAdminSpawnStatsView('alltime', undefined).collectionRate).toBe(0);
  });

  it('reports activePlayers as null when the aggregate does not carry them', () => {
    const view = toAdminSpawnStatsView('2026-08', { spawnedTotal: 1, collectedTotal: 0 });
    expect(view.activePlayers7d).toBeNull();
    expect(view.activePlayers30d).toBeNull();
  });

  it('surfaces activePlayers when the aggregate DOES carry them', () => {
    const view = toAdminSpawnStatsView('2026-08', {
      spawnedTotal: 1,
      collectedTotal: 0,
      activePlayers7d: 12,
      activePlayers30d: 40,
    });
    expect(view.activePlayers7d).toBe(12);
    expect(view.activePlayers30d).toBe(40);
  });
});

describe('rarity helpers', () => {
  it('reads sparse rarity maps, treating absent buckets as absent', () => {
    expect(toRarityCounts({ common: 3, bogus: 9 })).toEqual({ common: 3 });
  });

  it('sums all four tiers with absent buckets as 0', () => {
    expect(sumRarityCounts({ common: 3, legendary: 1 })).toBe(4);
  });
});

describe('toCellStat + cellKeyCenter', () => {
  it('maps a cell document and resolves the cell centre from its key', () => {
    const stat = toCellStat('5748_1207', { spawned: 5, collected: 2 });
    expect(stat.cellKey).toBe('5748_1207');
    expect(stat.spawned).toBe(5);
    const center = cellKeyCenter('5748_1207');
    expect(center?.lat).toBeCloseTo(57.485);
    expect(center?.lon).toBeCloseTo(12.075);
  });

  it('returns null centre for a malformed key', () => {
    expect(cellKeyCenter('nope')).toBeNull();
  });
});

describe('toSeason', () => {
  it('maps an ended season with winners', () => {
    const season = toSeason('2026-07', {
      status: 'ended',
      startAt: '2026-07-01T00:00:00Z',
      endAt: '2026-08-01T00:00:00Z',
      participantCount: 3,
      winners: [{ rank: 1, uid: 'u1', displayName: 'Ada', points: 100, crownsCollected: 5 }],
    });
    expect(season.status).toBe('ended');
    expect(season.participantCount).toBe(3);
    expect(season.winners?.[0]?.displayName).toBe('Ada');
  });

  it('defaults an active season to no winners', () => {
    const season = toSeason('2026-08', { status: 'active', startAt: '', endAt: '' });
    expect(season.status).toBe('active');
    expect(season.winners).toBeUndefined();
  });
});

describe('rankLeaderboardCounters — canonical three-key order', () => {
  it('orders points desc, then crowns desc, then uid asc; drops zero rows', () => {
    const ranked = rankLeaderboardCounters([
      { uid: 'zeta', points: 50, crownsCollected: 2 },
      { uid: 'beta', points: 100, crownsCollected: 3 },
      { uid: 'alpha', points: 100, crownsCollected: 3 }, // ties beta on points+crowns → uid asc wins
      { uid: 'gamma', points: 100, crownsCollected: 5 }, // more crowns → outranks the 3-crown pair
      { uid: 'empty', points: 0, crownsCollected: 0 }, // dropped
    ]);
    expect(ranked.map((r) => r.uid)).toEqual(['gamma', 'alpha', 'beta', 'zeta']);
    expect(ranked.map((r) => r.rank)).toEqual([1, 2, 3, 4]);
  });

  it('keeps a zero-point row that still collected a crown', () => {
    const ranked = rankLeaderboardCounters([{ uid: 'x', points: 0, crownsCollected: 1 }]);
    expect(ranked).toHaveLength(1);
  });
});

describe('toLeaderboardCounter', () => {
  it('coerces a leaderboard entry document', () => {
    expect(toLeaderboardCounter({ uid: 'u1', points: 10, crownsCollected: 2 })).toEqual({
      uid: 'u1',
      points: 10,
      crownsCollected: 2,
    });
  });
});

describe('currentSeasonId', () => {
  it('produces a YYYY-MM id in Europe/Stockholm', () => {
    expect(currentSeasonId(new Date('2026-08-05T09:00:00Z'))).toBe('2026-08');
  });
});
