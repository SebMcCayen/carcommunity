import { describe, expect, it } from 'vitest';
import { membershipMapRingProjection } from '../subscription/map-ring-core';

const now = new Date('2026-10-30T12:00:00Z');
const expiry = new Date('2026-11-30T12:00:00Z');
const paid = {
  userId: 'member', platform: 'google', entitlement: 'member_monthly',
  tier: 'plus', status: 'active', expiresAt: expiry,
};
const hidden = { membershipMapRingEligible: false, membershipMapRingUntil: null };

describe('Paid live-map ring projection', () => {
  it.each(['plus', 'supporter', undefined])('includes %s and legacy paid records', (tier) => {
    expect(membershipMapRingProjection({ ...paid, tier }, now, 'member')).toEqual({
      membershipMapRingEligible: true, membershipMapRingUntil: expiry,
    });
  });

  it.each(['active', 'cancelled', 'grace_period'])('keeps %s only until paid expiry', (status) => {
    const record = { ...paid, status };
    expect(membershipMapRingProjection(record, new Date(expiry.getTime() - 1))).toEqual({
      membershipMapRingEligible: true, membershipMapRingUntil: expiry,
    });
    expect(membershipMapRingProjection(record, expiry)).toEqual(hidden);
    expect(membershipMapRingProjection(record, new Date(expiry.getTime() + 1))).toEqual(hidden);
  });

  it('does not turn cancelled auto-renewal or a stored hide choice into entitlement loss', () => {
    expect(membershipMapRingProjection({ ...paid, status: 'cancelled', showMembershipMapRing: false }, now))
      .toEqual({ membershipMapRingEligible: true, membershipMapRingUntil: expiry });
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
      .toEqual({ membershipMapRingEligible: true, membershipMapRingUntil: null });
    expect(membershipMapRingProjection({ ...paid, platform: 'manual', expiresAt: 'bad' }, now))
      .toEqual(hidden);
  });
});
