/**
 * Unit tests for publishPublicLeaderboard (leaderboard/publicLeaderboard.ts).
 *
 * Proves the documented "never throws — every failure resolves to 'failed'"
 * contract is real: a Firestore read that REJECTS resolves to
 * { status: 'failed' } rather than propagating. No emulator, no network — the
 * Firestore db, the GitHub sync, and the (function-registering) generator module
 * are all mocked so this stays a pure logic test.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// vi.mock factories are hoisted above module-body consts, so the mock fns must
// be created via vi.hoisted() to be referenceable inside the factories.
const { docMock, getMock, syncMock } = vi.hoisted(() => {
  const get = vi.fn();
  return {
    docMock: vi.fn((_scope: string) => ({ get })),
    getMock: get,
    syncMock: vi.fn(),
  };
});

// db.collection(...).doc(...).get() — the single configurable mock drives both
// the all-time, current-month, and previous-month reads. docMock preserves the
// requested scope so orchestration tests can verify each document binding.
vi.mock('../firebase', () => ({
  db: { collection: () => ({ doc: docMock }) },
}));

// Break the generator↔publicLeaderboard import cycle AND avoid loading
// generator.ts's onSchedule/defineSecret at import time — we only need the
// collection name constant.
vi.mock('../leaderboard/generator', () => ({ LEADERBOARD_COLLECTION: 'leaderboards' }));

// Keep the GitHub write hermetic — never reach api.github.com from a unit test.
vi.mock('../leaderboard/leaderboardRepo', () => ({
  syncHomepageLeaderboardFile: syncMock,
}));

import { previousMonthId, publishPublicLeaderboard } from '../leaderboard/publicLeaderboard';

const NOW = new Date('2026-08-16T10:00:00Z');

beforeEach(() => {
  docMock.mockClear();
  getMock.mockReset();
  syncMock.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('publishPublicLeaderboard', () => {
  it('derives the previous calendar month across year boundaries', () => {
    expect(previousMonthId('2026-09')).toBe('2026-08');
    expect(previousMonthId('2026-01')).toBe('2025-12');
    expect(previousMonthId('0001-01')).toBe('0000-12');
  });

  it('resolves to { status: "failed" } (does NOT throw) when a Firestore read rejects', async () => {
    getMock.mockRejectedValue(new Error('firestore unavailable'));
    // Must not reject — the whole point of the never-throw contract.
    const result = await publishPublicLeaderboard('token', NOW);
    expect(result).toEqual({ status: 'failed', hasMonth: false, hasPreviousMonth: false });
    // A read blew up before the sync, so GitHub is never touched.
    expect(syncMock).not.toHaveBeenCalled();
  });

  it('resolves to "failed" when the pure build/sync path throws unexpectedly', async () => {
    // Both scope reads succeed (no doc), then the sync itself throws — still
    // swallowed into 'failed' rather than propagating.
    getMock.mockResolvedValue({ exists: false });
    syncMock.mockRejectedValue(new Error('unexpected'));
    const result = await publishPublicLeaderboard('token', NOW);
    expect(result).toEqual({ status: 'failed', hasMonth: false, hasPreviousMonth: false });
  });

  it('returns the sync status on the happy path (wrap does not change behaviour)', async () => {
    // First read = all-time doc with categories; second read = month doc absent.
    getMock
      .mockResolvedValueOnce({
        exists: true,
        data: () => ({
          categories: {
            crownPoints: [{ rank: 1, uid: 'u1', displayName: 'Anna', avatarPath: null, value: 100 }],
            distance: [],
            events: [],
            convoys: [],
            streak: [],
          },
        }),
      })
      .mockResolvedValueOnce({ exists: false })
      .mockResolvedValueOnce({ exists: false });
    syncMock.mockResolvedValue('committed');

    const result = await publishPublicLeaderboard('token', NOW);
    expect(result).toEqual({ status: 'committed', hasMonth: false, hasPreviousMonth: false });
    expect(syncMock).toHaveBeenCalledTimes(1);
    // The published content is the built public JSON string (top-3, no uid).
    const [content] = syncMock.mock.calls[0] as [string];
    expect(content).toContain('Anna');
    expect(content).not.toContain('"uid"');
  });

  it('reads and publishes distinct current and previous month documents', async () => {
    const categories = (displayName: string, value: number) => ({
      crownPoints: [{ rank: 1, uid: `uid-${displayName}`, displayName, avatarPath: null, value }],
      distance: [],
      events: [],
      convoys: [],
      streak: [],
    });
    getMock
      .mockResolvedValueOnce({ exists: false })
      .mockResolvedValueOnce({
        exists: true,
        data: () => ({ categories: categories('August leader', 80) }),
      })
      .mockResolvedValueOnce({
        exists: true,
        data: () => ({ categories: categories('July leader', 70) }),
      });
    syncMock.mockResolvedValue('committed');

    const result = await publishPublicLeaderboard('token', NOW);

    expect(result).toEqual({ status: 'committed', hasMonth: true, hasPreviousMonth: true });
    expect(docMock.mock.calls.map(([scope]) => scope)).toEqual(['alltime', '2026-08', '2026-07']);
    const [content] = syncMock.mock.calls[0] as [string];
    const published = JSON.parse(content) as {
      month: { yyyymm: string; crownPoints: Array<{ displayName: string }> };
      previousMonth: { yyyymm: string; crownPoints: Array<{ displayName: string }> };
    };
    expect(published.month.yyyymm).toBe('2026-08');
    expect(published.month.crownPoints[0]?.displayName).toBe('August leader');
    expect(published.previousMonth.yyyymm).toBe('2026-07');
    expect(published.previousMonth.crownPoints[0]?.displayName).toBe('July leader');
  });
});
