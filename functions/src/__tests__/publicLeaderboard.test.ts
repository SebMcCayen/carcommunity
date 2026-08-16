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
const { getMock, syncMock } = vi.hoisted(() => ({ getMock: vi.fn(), syncMock: vi.fn() }));

// db.collection(...).doc(...).get() — the single configurable mock drives both
// the all-time and the current-month reads (they hit the same collection/doc/get).
vi.mock('../firebase', () => ({
  db: { collection: () => ({ doc: () => ({ get: getMock }) }) },
}));

// Break the generator↔publicLeaderboard import cycle AND avoid loading
// generator.ts's onSchedule/defineSecret at import time — we only need the
// collection name constant.
vi.mock('../leaderboard/generator', () => ({ LEADERBOARD_COLLECTION: 'leaderboards' }));

// Keep the GitHub write hermetic — never reach api.github.com from a unit test.
vi.mock('../leaderboard/leaderboardRepo', () => ({
  syncHomepageLeaderboardFile: syncMock,
}));

import { publishPublicLeaderboard } from '../leaderboard/publicLeaderboard';

const NOW = new Date('2026-08-16T10:00:00Z');

beforeEach(() => {
  getMock.mockReset();
  syncMock.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('publishPublicLeaderboard', () => {
  it('resolves to { status: "failed" } (does NOT throw) when a Firestore read rejects', async () => {
    getMock.mockRejectedValue(new Error('firestore unavailable'));
    // Must not reject — the whole point of the never-throw contract.
    const result = await publishPublicLeaderboard('token', NOW);
    expect(result).toEqual({ status: 'failed', hasMonth: false });
    // A read blew up before the sync, so GitHub is never touched.
    expect(syncMock).not.toHaveBeenCalled();
  });

  it('resolves to "failed" when the pure build/sync path throws unexpectedly', async () => {
    // Both scope reads succeed (no doc), then the sync itself throws — still
    // swallowed into 'failed' rather than propagating.
    getMock.mockResolvedValue({ exists: false });
    syncMock.mockRejectedValue(new Error('unexpected'));
    const result = await publishPublicLeaderboard('token', NOW);
    expect(result).toEqual({ status: 'failed', hasMonth: false });
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
      .mockResolvedValueOnce({ exists: false });
    syncMock.mockResolvedValue('committed');

    const result = await publishPublicLeaderboard('token', NOW);
    expect(result).toEqual({ status: 'committed', hasMonth: false });
    expect(syncMock).toHaveBeenCalledTimes(1);
    // The published content is the built public JSON string (top-3, no uid).
    const [content] = syncMock.mock.calls[0] as [string];
    expect(content).toContain('Anna');
    expect(content).not.toContain('"uid"');
  });
});
