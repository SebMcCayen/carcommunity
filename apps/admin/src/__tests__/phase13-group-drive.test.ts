/**
 * Unit tests for the Phase 13 migrated group-drive admin module: aggregate
 * counts computed from the participant roster over a mocked Firestore client.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const getDocsMock = vi.fn();

vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('firebase/firestore', () => ({
  collection: (...segments: unknown[]) => ({ segments }),
  getDocs: (...args: unknown[]) => getDocsMock(...args),
}));

import { loadAdminGroupDriveSummary } from '../features/group-drive';

const roster = (statuses: string[]) => ({
  empty: statuses.length === 0,
  docs: statuses.map((status) => ({ data: () => ({ status }) })),
});

beforeEach(() => {
  getDocsMock.mockReset();
});

describe('group-drive module', () => {
  it('returns null for an empty roster (no active group drive)', async () => {
    getDocsMock.mockResolvedValue(roster([]));
    expect(await loadAdminGroupDriveSummary('e1')).toBeNull();
  });

  it('counts by status and excludes left participants', async () => {
    getDocsMock.mockResolvedValue(
      roster(['joined', 'joined', 'on_the_way', 'arrived', 'left']),
    );
    const summary = await loadAdminGroupDriveSummary('e1');
    expect(summary).toEqual({
      totalActive: 4,
      joinedCount: 2,
      onTheWayCount: 1,
      arrivedCount: 1,
    });
  });

  it('treats a roster of only-left participants as zero active (but not null)', async () => {
    getDocsMock.mockResolvedValue(roster(['left', 'left']));
    expect(await loadAdminGroupDriveSummary('e1')).toEqual({
      totalActive: 0,
      joinedCount: 0,
      onTheWayCount: 0,
      arrivedCount: 0,
    });
  });
});
