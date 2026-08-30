import { describe, expect, it } from 'vitest';
import {
  GOOGLE_PLAY_PACKAGE_NAME,
  GOOGLE_PLAY_PRODUCT_IDS,
  GooglePlayVerificationError,
  obfuscatedAccountIdForUid,
  parseGooglePlaySubscription,
} from '../subscription/google-play-core';
import { classifyGooglePlayGetError } from '../subscription/google-play';
import {
  PurchaseTokenOwnershipError,
  assertNoDifferentActiveToken,
  validateTokenOwnership,
} from '../subscription/purchase-token-ownership-core';

const NOW = new Date('2026-08-29T12:00:00Z');
const UID = 'firebase-user-123';
const ACCOUNT_ID = obfuscatedAccountIdForUid(UID);

function response(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    kind: 'androidpublisher#subscriptionPurchaseV2',
    startTime: '2026-08-01T12:00:00Z',
    subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
    acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING',
    externalAccountIdentifiers: { obfuscatedExternalAccountId: ACCOUNT_ID },
    lineItems: [
      {
        productId: 'plus_monthly',
        expiryTime: '2026-09-29T12:00:00Z',
      },
    ],
    ...overrides,
  };
}

function expectCode(run: () => unknown, code: GooglePlayVerificationError['code']): void {
  try {
    run();
    throw new Error('Expected parser to throw.');
  } catch (error) {
    expect(error).toBeInstanceOf(GooglePlayVerificationError);
    expect((error as GooglePlayVerificationError).code).toBe(code);
  }
}

describe('Google Play subscription verification core', () => {
  it('classifies rejected purchase tokens separately from retryable provider failures', () => {
    for (const status of [400, 404, 410]) {
      expect(classifyGooglePlayGetError({ response: { status } })).toBe('invalid_purchase');
    }
    for (const error of [
      { response: { status: 401 } },
      { response: { status: 403 } },
      { response: { status: 429 } },
      { response: { status: 500 } },
      new Error('network'),
      null,
    ]) {
      expect(classifyGooglePlayGetError(error)).toBe('unavailable');
    }
    expect(classifyGooglePlayGetError({ status: 404 })).toBe('invalid_purchase');
  });

  it('pins the Android package and the only purchasable products', () => {
    expect(GOOGLE_PLAY_PACKAGE_NAME).toBe('com.kungsbackacarcommunity.app');
    expect(GOOGLE_PLAY_PRODUCT_IDS).toEqual(['plus_monthly', 'supporter_monthly']);
  });

  it('produces a one-way 64-character account binding', () => {
    expect(ACCOUNT_ID).toMatch(/^[a-f0-9]{64}$/);
    expect(ACCOUNT_ID).toBe('4ab08c5d68eeb18c08df44084fd659b3945ca897720de9a8bce5301bd7d2360d');
    expect(ACCOUNT_ID).not.toContain(UID);
    expect(obfuscatedAccountIdForUid(UID)).toBe(ACCOUNT_ID);
    expect(obfuscatedAccountIdForUid(`${UID}-other`)).not.toBe(ACCOUNT_ID);
  });

  it.each([
    ['SUBSCRIPTION_STATE_ACTIVE', 'active'],
    ['SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'grace_period'],
    ['SUBSCRIPTION_STATE_CANCELED', 'cancelled'],
  ] as const)('retains Plus access for %s', (subscriptionState, status) => {
    expect(parseGooglePlaySubscription(response({ subscriptionState }), ACCOUNT_ID, NOW)).toEqual({
      productId: 'plus_monthly',
      tier: 'plus',
      status,
      entitlement: 'member_monthly',
      startsAt: new Date('2026-08-01T12:00:00Z'),
      expiresAt: new Date('2026-09-29T12:00:00Z'),
      acknowledgementRequired: true,
      linkedPurchaseTokenHash: null,
    });
  });

  it('maps Supporter and an acknowledged purchase without inventing another product path', () => {
    const parsed = parseGooglePlaySubscription(
      response({
        acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
        lineItems: [{ productId: 'supporter_monthly', expiryTime: '2026-09-29T12:00:00Z' }],
      }),
      ACCOUNT_ID,
      NOW,
    );
    expect(parsed).toMatchObject({
      productId: 'supporter_monthly',
      tier: 'supporter',
      entitlement: 'member_monthly',
      acknowledgementRequired: false,
    });
  });

  it('hashes Play linkedPurchaseToken for an authenticated plan replacement', () => {
    const parsed = parseGooglePlaySubscription(
      response({ linkedPurchaseToken: 'old-purchase-token' }),
      ACCOUNT_ID,
      NOW,
    );
    expect(parsed.linkedPurchaseTokenHash).toBe(
      'b2ce3e8f065eb7f07d885380b7ab4b7b8b1fd9726fdb552a13947696d2695b54',
    );
  });

  it.each([
    'SUBSCRIPTION_STATE_PENDING',
    'SUBSCRIPTION_STATE_PAUSED',
    'SUBSCRIPTION_STATE_ON_HOLD',
    'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED',
  ])('removes entitlement for %s', (subscriptionState) => {
    const parsed = parseGooglePlaySubscription(response({ subscriptionState }), ACCOUNT_ID, NOW);
    expect(parsed).toMatchObject({
      status: 'inactive',
      entitlement: 'none',
      tier: 'plus',
      acknowledgementRequired: false,
    });
  });

  it('removes entitlement for a consistently expired purchase', () => {
    const parsed = parseGooglePlaySubscription(
      response({
        subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED',
        lineItems: [{ productId: 'plus_monthly', expiryTime: '2026-08-28T12:00:00Z' }],
      }),
      ACCOUNT_ID,
      NOW,
    );
    expect(parsed).toMatchObject({ status: 'expired', entitlement: 'none', tier: 'plus' });
  });

  it('rejects a purchase bound to another Firebase user or with no binding', () => {
    expectCode(
      () =>
        parseGooglePlaySubscription(
          response({
            externalAccountIdentifiers: {
              obfuscatedExternalAccountId: obfuscatedAccountIdForUid('another-user'),
            },
          }),
          ACCOUNT_ID,
          NOW,
        ),
      'account_mismatch',
    );
    expectCode(
      () =>
        parseGooglePlaySubscription(
          response({ externalAccountIdentifiers: undefined }),
          ACCOUNT_ID,
          NOW,
        ),
      'account_mismatch',
    );
  });

  it('rejects legacy, unknown, and ambiguous product ids', () => {
    for (const productId of ['member_monthly', 'unknown_monthly']) {
      expectCode(
        () =>
          parseGooglePlaySubscription(
            response({ lineItems: [{ productId, expiryTime: '2026-09-29T12:00:00Z' }] }),
            ACCOUNT_ID,
            NOW,
          ),
        'unsupported_product',
      );
    }
    expectCode(
      () =>
        parseGooglePlaySubscription(
          response({
            lineItems: [
              { productId: 'plus_monthly', expiryTime: '2026-09-20T12:00:00Z' },
              { productId: 'supporter_monthly', expiryTime: '2026-09-29T12:00:00Z' },
            ],
          }),
          ACCOUNT_ID,
          NOW,
        ),
      'malformed_response',
    );
  });

  it('fails closed on unknown state and inconsistent active or expired timestamps', () => {
    expectCode(
      () =>
        parseGooglePlaySubscription(
          response({ subscriptionState: 'SUBSCRIPTION_STATE_FUTURE' }),
          ACCOUNT_ID,
          NOW,
        ),
      'unknown_state',
    );
    expectCode(
      () =>
        parseGooglePlaySubscription(
          response({
            lineItems: [{ productId: 'plus_monthly', expiryTime: '2026-08-28T12:00:00Z' }],
          }),
          ACCOUNT_ID,
          NOW,
        ),
      'malformed_response',
    );
    expectCode(
      () =>
        parseGooglePlaySubscription(
          response({ subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED' }),
          ACCOUNT_ID,
          NOW,
        ),
      'malformed_response',
    );
  });

  it.each([
    null,
    [],
    {},
    response({ kind: 'wrong-kind' }),
    response({ lineItems: [] }),
    response({ acknowledgementState: 'ACKNOWLEDGEMENT_STATE_UNKNOWN' }),
  ])('fails closed on malformed response %#', (value) => {
    expectCode(() => parseGooglePlaySubscription(value, ACCOUNT_ID, NOW), 'malformed_response');
  });
});

describe('Google Play purchase token ownership', () => {
  const expected = { uid: UID, productId: 'plus_monthly' as const };

  it('creates once and permits same-UID same-product retries', () => {
    expect(validateTokenOwnership(null, expected)).toBe('create');
    expect(validateTokenOwnership(expected, expected)).toBe('idempotent');
  });

  it('rejects cross-UID replay', () => {
    try {
      validateTokenOwnership({ ...expected, uid: 'attacker' }, expected);
      throw new Error('Expected ownership conflict.');
    } catch (error) {
      expect(error).toBeInstanceOf(PurchaseTokenOwnershipError);
      expect((error as PurchaseTokenOwnershipError).reason).toBe('different_user');
    }
  });

  it('rejects product mutation for an existing token', () => {
    expect(() =>
      validateTokenOwnership({ ...expected, productId: 'supporter_monthly' }, expected),
    ).toThrow(PurchaseTokenOwnershipError);
  });

  it('permits a Play-verified deferred product transition for the same UID and token', () => {
    expect(
      validateTokenOwnership({ ...expected, productId: 'supporter_monthly' }, expected, true),
    ).toBe('update_product');
  });

  it.each([
    {},
    { uid: 123, productId: 'plus_monthly' },
    { uid: '', productId: 'plus_monthly' },
    { uid: UID },
    { uid: UID, productId: 123 },
    { uid: UID, productId: 'unknown_monthly' },
  ])('classifies malformed ownership state separately from a replay %#', (current) => {
    try {
      validateTokenOwnership(current, expected);
      throw new Error('Expected malformed ownership state.');
    } catch (error) {
      expect(error).toBeInstanceOf(PurchaseTokenOwnershipError);
      expect((error as PurchaseTokenOwnershipError).reason).toBe('malformed_record');
    }
  });

  it('allows the same token to update or revoke its own effective subscription', () => {
    expect(() =>
      assertNoDifferentActiveToken(
        {
          grantsAccess: true,
          purchaseTokenHash: 'a'.repeat(64),
          expiresAt: new Date('2026-09-01T00:00:00Z'),
        },
        'a'.repeat(64),
        null,
        NOW,
      ),
    ).not.toThrow();
  });

  it('rejects a second token while another paid subscription is effective', () => {
    try {
      assertNoDifferentActiveToken(
        {
          grantsAccess: true,
          purchaseTokenHash: 'a'.repeat(64),
          expiresAt: new Date('2026-09-01T00:00:00Z'),
        },
        'b'.repeat(64),
        null,
        NOW,
      );
      throw new Error('Expected a different-active-token conflict.');
    } catch (error) {
      expect(error).toBeInstanceOf(PurchaseTokenOwnershipError);
      expect((error as PurchaseTokenOwnershipError).reason).toBe('different_active_token');
    }
  });

  it('permits a new token only when Play links it to the current active token', () => {
    expect(() =>
      assertNoDifferentActiveToken(
        {
          grantsAccess: true,
          purchaseTokenHash: 'a'.repeat(64),
          expiresAt: new Date('2026-09-01T00:00:00Z'),
        },
        'b'.repeat(64),
        'a'.repeat(64),
        NOW,
      ),
    ).not.toThrow();
  });

  it('does not treat missing manual and linked token hashes as a replacement match', () => {
    expect(() =>
      assertNoDifferentActiveToken(
        {
          grantsAccess: true,
          purchaseTokenHash: null,
          expiresAt: new Date('2026-09-01T00:00:00Z'),
        },
        'b'.repeat(64),
        null,
        NOW,
      ),
    ).toThrow(PurchaseTokenOwnershipError);
  });

  it('permits a replacement after the current token no longer grants paid access', () => {
    for (const current of [
      {
        grantsAccess: false,
        purchaseTokenHash: 'a'.repeat(64),
        expiresAt: new Date('2026-09-01T00:00:00Z'),
      },
      {
        grantsAccess: true,
        purchaseTokenHash: 'a'.repeat(64),
        expiresAt: new Date('2026-08-29T11:59:59Z'),
      },
    ]) {
      expect(() => assertNoDifferentActiveToken(current, 'b'.repeat(64), null, NOW)).not.toThrow();
    }
  });
});
