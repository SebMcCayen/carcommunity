import { describe, expect, it } from 'vitest';
import {
  AppleVerificationError,
  appleAppAccountTokenForUid,
  parseAppleTransaction,
} from '../subscription/apple-store-core';

const NOW = new Date('2026-10-22T12:00:00.000Z');
const UID = 'member-apple-1';
const TOKEN = appleAppAccountTokenForUid(UID);

function transaction(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    type: 'Auto-Renewable Subscription',
    inAppOwnershipType: 'PURCHASED',
    appAccountToken: TOKEN,
    originalTransactionId: '2000000123456789',
    productId: 'plus_monthly',
    purchaseDate: Date.parse('2026-10-01T12:00:00.000Z'),
    expiresDate: Date.parse('2026-11-01T12:00:00.000Z'),
    ...overrides,
  };
}

describe('appleAppAccountTokenForUid', () => {
  it('is stable, UUID-shaped, and account-specific', () => {
    expect(TOKEN).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(appleAppAccountTokenForUid(UID)).toBe(TOKEN);
    expect(appleAppAccountTokenForUid('member-apple-2')).not.toBe(TOKEN);
  });
});

describe('parseAppleTransaction', () => {
  it('maps a verified active Plus transaction', () => {
    expect(parseAppleTransaction(transaction(), TOKEN, NOW)).toEqual({
      productId: 'plus_monthly',
      tier: 'plus',
      status: 'active',
      entitlement: 'member_monthly',
      startsAt: new Date('2026-10-01T12:00:00.000Z'),
      expiresAt: new Date('2026-11-01T12:00:00.000Z'),
      originalTransactionId: '2000000123456789',
    });
  });

  it('maps Supporter and preserves the subscription chain id', () => {
    const result = parseAppleTransaction(
      transaction({ productId: 'supporter_monthly' }),
      TOKEN,
      NOW,
    );
    expect(result.tier).toBe('supporter');
    expect(result.originalTransactionId).toBe('2000000123456789');
  });

  it.each([
    [{ expiresDate: NOW.getTime() }, 'expired'],
    [{ isUpgraded: true }, 'expired'],
    [{ revocationDate: NOW.getTime() - 1 }, 'revoked'],
  ])('fails closed for inactive lifecycle state %#', (overrides, status) => {
    expect(parseAppleTransaction(transaction(overrides), TOKEN, NOW)).toMatchObject({
      entitlement: 'none',
      status,
    });
  });

  it.each([
    [{ appAccountToken: appleAppAccountTokenForUid('other') }, 'account_mismatch'],
    [{ productId: 'unknown' }, 'unsupported_product'],
    [{ type: 'Consumable' }, 'unsupported_transaction'],
    [{ inAppOwnershipType: 'FAMILY_SHARED' }, 'unsupported_transaction'],
  ])('rejects an untrusted transaction boundary %#', (overrides, code) => {
    expect(() => parseAppleTransaction(transaction(overrides), TOKEN, NOW)).toThrowError(
      expect.objectContaining<Partial<AppleVerificationError>>({ code }),
    );
  });
});
