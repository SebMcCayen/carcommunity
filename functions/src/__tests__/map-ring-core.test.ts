import { describe, expect, it, vi } from 'vitest';
import { MAX_MAP_RING_REFRESHES, membershipMapRingProjection, runMapRingRefresh } from '../subscription/map-ring-core';

const now = new Date('2026-10-30T12:00:00Z');
const expiry = new Date('2026-11-30T12:00:00Z');
const paid = {
  userId: 'member', platform: 'google', entitlement: 'member_monthly',
  tier: 'plus', status: 'active', expiresAt: expiry,
};
const hidden = { membershipMapRingEligible: false, refreshAt: null };

describe('Paid live-map ring projection', () => {
  it.each(['plus', 'supporter', undefined])('includes %s and legacy paid records', (tier) => {
    expect(membershipMapRingProjection({ ...paid, tier }, now, 'member')).toEqual({
      membershipMapRingEligible: true, refreshAt: expiry,
    });
  });

  it.each(['active', 'cancelled', 'grace_period'])('keeps %s only until paid expiry', (status) => {
    const record = { ...paid, status };
    expect(membershipMapRingProjection(record, new Date(expiry.getTime() - 1))).toEqual({
      membershipMapRingEligible: true, refreshAt: expiry,
    });
    expect(membershipMapRingProjection(record, expiry)).toEqual(hidden);
    expect(membershipMapRingProjection(record, new Date(expiry.getTime() + 1))).toEqual(hidden);
  });

  it('does not turn cancelled auto-renewal or a stored hide choice into entitlement loss', () => {
    expect(membershipMapRingProjection({ ...paid, status: 'cancelled', showMembershipMapRing: false }, now))
      .toEqual({ membershipMapRingEligible: true, refreshAt: expiry });
  });

  it('removes revoked/expired/unpaid/malformed records, including wrong-user records', () => {
    for (const record of [null, [], {},
      { ...paid, tier: 'community' }, { ...paid, tier: 'unknown' },
      { ...paid, entitlement: 'none' }, { ...paid, status: 'revoked' },
      { ...paid, status: 'expired' }, { ...paid, status: 'inactive' },
      { ...paid, expiresAt: null }, { ...paid, expiresAt: '2099-01-01' },
      { ...paid, expiresAt: new Date(NaN) }, { ...paid, userId: 'other' },
    ]) expect(membershipMapRingProjection(record, now, 'member')).toEqual(hidden);
    expect(membershipMapRingProjection(paid, new Date(NaN))).toEqual(hidden);
  });

  it('allows only explicit manual perpetual grants without an expiry', () => {
    expect(membershipMapRingProjection({ ...paid, platform: 'manual', expiresAt: null }, now))
      .toEqual({ membershipMapRingEligible: true, refreshAt: null });
    expect(membershipMapRingProjection({ ...paid, platform: 'manual', expiresAt: 'bad' }, now))
      .toEqual(hidden);
  });
});

describe('Private cosmetic expiry orchestration', () => {
  it('queries only due deadlines with a bound and does no work on an empty page', async () => {
    const queryDue = vi.fn(async () => [] as string[]);
    const refresh = vi.fn();
    expect(await runMapRingRefresh({ queryDue, refresh }, now)).toBe(0);
    expect(queryDue).toHaveBeenCalledWith(now, MAX_MAP_RING_REFRESHES);
    expect(refresh).not.toHaveBeenCalled();
  });
  it('is bounded even if a dependency returns an oversized result', async () => {
    const refresh = vi.fn(async () => {});
    await runMapRingRefresh({ queryDue: async () => Array.from({ length: 250 }, (_, i) => `member-${i}`), refresh }, now);
    expect(refresh).toHaveBeenCalledTimes(MAX_MAP_RING_REFRESHES);
  });
  it('continues after a repair fails and reports failure without account/receipt data', async () => {
    const refreshed: string[] = [];
    const result = runMapRingRefresh({ queryDue: async () => ['first', 'private-user', 'last'],
      refresh: async (uid) => { refreshed.push(uid); if (uid === 'private-user') throw new Error('private receipt'); },
    }, now);
    await expect(result).rejects.toThrow('Map-ring refresh: 1 of 3 cosmetic repairs failed');
    expect(refreshed).toEqual(['first', 'private-user', 'last']);
  });
  it('propagates query failures for retry without treating them as a successful empty page', async () => {
    const refresh = vi.fn();
    await expect(runMapRingRefresh({ queryDue: async () => { throw new Error('unavailable'); }, refresh }, now)).rejects.toThrow('unavailable');
    expect(refresh).not.toHaveBeenCalled();
  });
});
