import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import type { CallableRequest } from 'firebase-functions/v2/https';

const mocks = vi.hoisted(() => ({
  actor: vi.fn(),
  subscription: vi.fn(),
  where: vi.fn(),
  select: vi.fn(),
  rides: vi.fn(),
}));
vi.mock('../shared/memberActor', () => ({ requireActiveActor: mocks.actor }));
vi.mock('../firebase', () => ({
  db: {
    collection: (name: string) => {
      if (name === 'subscriptions') return { doc: () => ({ get: mocks.subscription }) };
      if (name === 'rides') return { where: mocks.where };
      throw new Error(`Unexpected collection ${name}`);
    },
  },
}));
import { driveStats } from '../drives/driveStats';

const request = { data: {} } as CallableRequest;
beforeEach(() => {
  vi.clearAllMocks();
  mocks.actor.mockResolvedValue({ uid: 'owner', state: {} });
  mocks.where.mockReturnValue({ select: mocks.select });
  mocks.select.mockReturnValue({ get: mocks.rides });
  mocks.subscription.mockResolvedValue({ exists: false, data: () => undefined });
  mocks.rides.mockResolvedValue({
    docs: Array.from({ length: 8 }, (_, i) => ({
      data: () => ({
        distanceMeters: 1000,
        durationSeconds: 60,
        averageSpeedMetersPerSecond: 5,
        maxSpeedMetersPerSecond: 10,
        // All older than Plus history; one legacy undated drive also counts.
        createdAt: i === 0 ? undefined : Timestamp.fromMillis(Date.now() - 200 * 86400000),
      }),
    })),
  });
});

describe('free owner-only driving statistics', () => {
  it.each(['community', 'plus', 'supporter'])(
    'returns all retained drives for %s',
    async (tier) => {
      mocks.subscription.mockResolvedValue({
        exists: true,
        data: () => ({
          userId: 'owner',
          tier,
          entitlement: tier === 'community' ? 'none' : 'member_monthly',
          status: 'active',
        }),
      });
      const result = await driveStats.run(request);
      expect(result.tier).toBe(tier);
      expect(result.totalDrives).toBe(8);
      expect(result.totalDistanceMeters).toBe(8000);
      expect(result.totalDurationSeconds).toBe(480);
      expect(result.thisMonthDrives).toBe(0);
      expect(result).not.toHaveProperty('drives');
      expect(mocks.where).toHaveBeenCalledExactlyOnceWith('userId', '==', 'owner');
      expect(mocks.select.mock.calls[0]).not.toContain('route');
    },
  );

  it('does not shrink statistics after entitlement is removed', async () => {
    mocks.subscription.mockResolvedValue({
      exists: true,
      data: () => ({
        userId: 'owner',
        tier: 'supporter',
        entitlement: 'member_monthly',
        status: 'active',
      }),
    });
    const before = await driveStats.run(request);
    mocks.subscription.mockResolvedValue({
      exists: true,
      data: () => ({
        userId: 'owner',
        tier: 'supporter',
        entitlement: 'none',
        status: 'expired',
      }),
    });
    const after = await driveStats.run(request);
    expect(before.tier).toBe('supporter');
    expect(after.tier).toBe('community');
    expect(after.totalDrives).toBe(before.totalDrives);
    expect(after.totalDistanceMeters).toBe(before.totalDistanceMeters);
  });

  it('preserves actor rejection before reading drives', async () => {
    mocks.actor.mockRejectedValueOnce(new Error('Account access is restricted.'));
    await expect(driveStats.run(request)).rejects.toThrow('restricted');
    expect(mocks.where).not.toHaveBeenCalled();
  });

  it('does not accept a caller-selected owner', async () => {
    await expect(driveStats.run({ data: { userId: 'victim' } } as CallableRequest)).rejects.toThrow(
      'Expected',
    );
    expect(mocks.where).not.toHaveBeenCalled();
  });
});
