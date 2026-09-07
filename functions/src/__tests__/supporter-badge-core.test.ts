import { describe, expect, it } from 'vitest';
import { supporterBadgeEligible } from '../subscription/supporter-badge-core';

const now = new Date('2026-09-05T12:00:00Z');
const active = {
  entitlement: 'member_monthly',
  tier: 'supporter',
  status: 'active',
  platform: 'google',
  expiresAt: new Date('2026-10-05T12:00:00Z'),
};

describe('Supporter public crown eligibility', () => {
  it('grants purchase, renewal, paid cancellation and grace', () => {
    for (const status of ['active', 'cancelled', 'grace_period']) {
      expect(supporterBadgeEligible({ ...active, status }, now)).toBe(true);
    }
  });
  it('removes at effective downgrade or revoke despite retained historical tier', () => {
    for (const patch of [
      { tier: 'plus' },
      { tier: 'community' },
      { tier: undefined },
      { entitlement: 'none' },
      { status: 'expired' },
      { status: 'revoked' },
      { status: 'inactive' },
    ])
      expect(supporterBadgeEligible({ ...active, ...patch }, now)).toBe(false);
  });
  it('uses paid cancellation expiry and existing 72 hour active/grace tolerance', () => {
    expect(supporterBadgeEligible({ ...active, status: 'cancelled', expiresAt: now }, now)).toBe(
      false,
    );
    for (const status of ['active', 'grace_period']) {
      const expiresAt = new Date(now.getTime() - 72 * 60 * 60 * 1000);
      expect(supporterBadgeEligible({ ...active, status, expiresAt }, now)).toBe(false);
      expect(
        supporterBadgeEligible(
          { ...active, status, expiresAt: new Date(expiresAt.getTime() + 1) },
          now,
        ),
      ).toBe(true);
    }
  });
  it('fails closed for malformed/missing records and allows explicit perpetual manual grants', () => {
    for (const record of [
      null,
      {},
      { ...active, expiresAt: 'bad' },
      { ...active, expiresAt: new Date(NaN) },
      { ...active, expiresAt: null },
    ]) {
      expect(supporterBadgeEligible(record, now)).toBe(false);
    }
    expect(supporterBadgeEligible({ ...active, platform: 'manual', expiresAt: null }, now)).toBe(
      true,
    );
  });
});
