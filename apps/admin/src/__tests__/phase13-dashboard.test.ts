/**
 * Unit tests for the admin dashboard stats module: live counts via mocked
 * Firestore aggregation, with per-tile isolation (a failing count → null).
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const getCountFromServerMock = vi.fn();

vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('firebase/firestore', () => ({
  collection: (...segments: unknown[]) => ({ kind: 'collection', segments }),
  query: (target: unknown, ...clauses: unknown[]) => ({ kind: 'query', target, clauses }),
  where: (field: string, op: string, value: unknown) => ({ field, op, value }),
  getCountFromServer: (...args: unknown[]) => getCountFromServerMock(...args),
}));

import { loadDashboardStats } from '../features/dashboard';

const countResult = (count: number) => ({ data: () => ({ count }) });

beforeEach(() => {
  getCountFromServerMock.mockReset();
});

describe('dashboard stats module', () => {
  it('returns live counts for countable tiles and null for the rest', async () => {
    // 4 countable queries resolve in order: users, activeMembers, openReports, vehicles
    getCountFromServerMock
      .mockResolvedValueOnce(countResult(1247))
      .mockResolvedValueOnce(countResult(432))
      .mockResolvedValueOnce(countResult(7))
      .mockResolvedValueOnce(countResult(15));

    const stats = await loadDashboardStats();

    expect(stats.totalUsers).toBe(1247);
    expect(stats.activeMembers).toBe(432);
    expect(stats.openReports).toBe(7);
    expect(stats.vehicleProfiles).toBe(15);
    // Not countable yet — explicitly null so the page renders "—".
    expect(stats.liveSessions).toBeNull();
    expect(stats.pendingPartners).toBeNull();
    expect(stats.pendingBillboards).toBeNull();
    expect(stats.usersWithVehicles).toBeNull();
  });

  it('isolates a failing count to its own tile (null), not the whole page', async () => {
    getCountFromServerMock
      .mockResolvedValueOnce(countResult(10)) // users ok
      .mockRejectedValueOnce(new Error('permission-denied')) // activeMembers fails
      .mockResolvedValueOnce(countResult(0)) // openReports ok
      .mockResolvedValueOnce(countResult(3)); // vehicles ok

    const stats = await loadDashboardStats();

    expect(stats.totalUsers).toBe(10);
    expect(stats.activeMembers).toBeNull(); // failed count → null, page unaffected
    expect(stats.openReports).toBe(0);
    expect(stats.vehicleProfiles).toBe(3);
  });
});
