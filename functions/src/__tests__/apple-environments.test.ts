import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  Environment,
  SignedDataVerifier,
  VerificationException,
  VerificationStatus,
} from '@apple/app-store-server-library';
import { parseAppleSubscriptionProviderConfig } from '../subscription/provider-config';
import { verifyAppleTransactionState } from '../subscription/apple-store';
import {
  verifyAppleNotification,
  runAppleNotification,
  type AppleNotificationDeps,
} from '../subscription/apple-notifications';
import {
  appleAppAccountTokenForUid,
  applePurchaseTokenHash,
  parseAppleTransaction,
} from '../subscription/apple-store-core';
import { hashPurchaseToken } from '../subscription/subscription-core';
import { PurchaseTokenOwnershipError } from '../subscription/purchase-token-ownership-core';

vi.mock('../firebase', () => ({ db: {}, adminAuth: {} }));
const NOW = new Date('2026-11-23T12:00:00Z');
const TOKEN = appleAppAccountTokenForUid('environment-test');
const CONFIG = { environment: 'production-and-sandbox' as const, appAppleId: 6828916736 };
const environmentOf = (verifier: SignedDataVerifier) =>
  (verifier as unknown as { environment: Environment }).environment;
function payload(environment: Environment = Environment.PRODUCTION) {
  return {
    environment,
    bundleId: 'com.kungsbackacarcommunity.app',
    type: 'Auto-Renewable Subscription',
    inAppOwnershipType: 'PURCHASED',
    appAccountToken: TOKEN,
    productId: 'plus_monthly',
    originalTransactionId: '123',
    purchaseDate: NOW.getTime() - 1000,
    signedDate: NOW.getTime(),
    expiresDate: NOW.getTime() + 10000,
  };
}
afterEach(() => vi.restoreAllMocks());

describe('Apple environment configuration and verification', () => {
  it.each(['sandbox', 'production', 'production-and-sandbox'] as const)(
    'accepts explicit %s configuration',
    (environment) => {
      expect(
        parseAppleSubscriptionProviderConfig({
          enabled: true,
          environment,
          appAppleId: CONFIG.appAppleId,
        }),
      ).toEqual({ environment, appAppleId: CONFIG.appAppleId });
    },
  );
  it.each([
    undefined,
    null,
    {},
    { enabled: false, environment: 'production' },
    { enabled: true, environment: 'automatic' },
    { enabled: true, environment: 'production' },
    { enabled: true, environment: 'production-and-sandbox' },
    { enabled: true, environment: 'sandbox', appAppleId: -1 },
    { enabled: true, environment: 'production-and-sandbox', appAppleId: '6828916736' },
  ])('fails closed for incomplete configuration %#', (value) => {
    expect(parseAppleSubscriptionProviderConfig(value)).toBeNull();
  });
  it('preserves the existing sandbox-only configuration and chain hashes', () => {
    expect(parseAppleSubscriptionProviderConfig({ enabled: true, environment: 'sandbox' })).toEqual(
      { environment: 'sandbox' },
    );
    expect(applePurchaseTokenHash('123', 'sandbox')).toBe(hashPurchaseToken('123'));
    expect(applePurchaseTokenHash('123', 'production')).not.toBe(hashPurchaseToken('123'));
  });
  it('verifies production first, including its app identifier', async () => {
    const environments: Environment[] = [];
    vi.spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeTransaction').mockImplementation(
      async function (this: SignedDataVerifier) {
        environments.push(environmentOf(this));
        expect((this as unknown as { appAppleId: number }).appAppleId).toBe(CONFIG.appAppleId);
        return payload();
      },
    );
    expect(
      (await verifyAppleTransactionState('signed', TOKEN, CONFIG, NOW)).outcome.environment,
    ).toBe('production');
    expect(environments).toEqual([Environment.PRODUCTION]);
  });
  it('retries only an authenticated environment mismatch with the sandbox verifier', async () => {
    const environments: Environment[] = [];
    vi.spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeTransaction').mockImplementation(
      async function (this: SignedDataVerifier) {
        environments.push(environmentOf(this));
        if (environmentOf(this) === Environment.PRODUCTION)
          throw new VerificationException(VerificationStatus.INVALID_ENVIRONMENT);
        return payload(Environment.SANDBOX);
      },
    );
    expect(
      (await verifyAppleTransactionState('signed', TOKEN, CONFIG, NOW)).outcome.environment,
    ).toBe('sandbox');
    expect(environments).toEqual([Environment.PRODUCTION, Environment.SANDBOX]);
  });
  it.each([
    VerificationStatus.VERIFICATION_FAILURE,
    VerificationStatus.INVALID_APP_IDENTIFIER,
    VerificationStatus.RETRYABLE_VERIFICATION_FAILURE,
  ])('does not downgrade verification after failure %s', async (status) => {
    const verify = vi
      .spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeTransaction')
      .mockRejectedValue(new VerificationException(status));
    await expect(verifyAppleTransactionState('signed', TOKEN, CONFIG, NOW)).rejects.toMatchObject({
      reason:
        status === VerificationStatus.RETRYABLE_VERIFICATION_FAILURE
          ? 'unavailable'
          : 'invalid_signature',
    });
    expect(verify).toHaveBeenCalledTimes(1);
  });
  it('keeps production-only mode strict', async () => {
    const verify = vi
      .spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeTransaction')
      .mockRejectedValue(new VerificationException(VerificationStatus.INVALID_ENVIRONMENT));
    await expect(
      verifyAppleTransactionState('signed', TOKEN, { ...CONFIG, environment: 'production' }, NOW),
    ).rejects.toMatchObject({ reason: 'invalid_signature' });
    expect(verify).toHaveBeenCalledTimes(1);
  });
  it.each([undefined, 'Xcode', 'LocalTesting', 'sandbox', 'unknown'])(
    'rejects unsupported verified payload environments %#',
    (environment) => {
      expect(() => parseAppleTransaction({ ...payload(), environment }, TOKEN, NOW)).toThrow();
    },
  );
  it('does not retry when the sandbox signature also fails', async () => {
    const verify = vi
      .spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeTransaction')
      .mockRejectedValueOnce(new VerificationException(VerificationStatus.INVALID_ENVIRONMENT))
      .mockRejectedValueOnce(new VerificationException(VerificationStatus.VERIFICATION_FAILURE));
    await expect(verifyAppleTransactionState('signed', TOKEN, CONFIG, NOW)).rejects.toMatchObject({
      reason: 'invalid_signature',
    });
    expect(verify).toHaveBeenCalledTimes(2);
  });
});

describe('Apple notification environment boundaries', () => {
  it.each([Environment.PRODUCTION, Environment.SANDBOX])(
    'uses one verified environment for the entire %s notification',
    async (environment) => {
      vi.spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeNotification').mockImplementation(
        async function (this: SignedDataVerifier) {
          if (environmentOf(this) !== environment)
            throw new VerificationException(VerificationStatus.INVALID_ENVIRONMENT);
          return {
            version: '2.0',
            notificationType: 'DID_RENEW',
            signedDate: NOW.getTime(),
            data: { signedTransactionInfo: 'transaction', signedRenewalInfo: 'renewal', status: 1 },
          };
        },
      );
      vi.spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeTransaction').mockImplementation(
        async function (this: SignedDataVerifier) {
          expect(environmentOf(this)).toBe(environment);
          return payload(environment);
        },
      );
      vi.spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeRenewalInfo').mockImplementation(
        async function (this: SignedDataVerifier) {
          expect(environmentOf(this)).toBe(environment);
          return { environment, originalTransactionId: '123', productId: 'plus_monthly' };
        },
      );
      const result = await verifyAppleNotification('envelope', CONFIG);
      expect(result.kind).toBe('subscription');
      if (result.kind === 'subscription') expect(result.transaction.environment).toBe(environment);
    },
  );
  it.each(['transaction', 'renewal'])(
    'rejects a production envelope with a sandbox %s',
    async (mismatch) => {
      vi.spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeNotification').mockImplementation(
        async function (this: SignedDataVerifier) {
          if (environmentOf(this) !== Environment.PRODUCTION)
            throw new VerificationException(VerificationStatus.INVALID_ENVIRONMENT);
          return {
            version: '2.0',
            notificationType: 'DID_RENEW',
            data: { signedTransactionInfo: 'tx', signedRenewalInfo: 'renewal', status: 1 },
          };
        },
      );
      vi.spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeTransaction').mockImplementation(
        async () => {
          if (mismatch === 'transaction')
            throw new VerificationException(VerificationStatus.INVALID_ENVIRONMENT);
          return payload();
        },
      );
      vi.spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeRenewalInfo').mockRejectedValue(
        new VerificationException(VerificationStatus.INVALID_ENVIRONMENT),
      );
      await expect(verifyAppleNotification('envelope', CONFIG)).rejects.toMatchObject({
        reason: 'invalid_signature',
      });
    },
  );
  it.each([Environment.PRODUCTION, Environment.SANDBOX])(
    'uses the %s owner lookup and acknowledges superseded sandbox events',
    async (environment) => {
      const deps: AppleNotificationDeps = {
        config: async () => CONFIG,
        verify: async () => ({
          kind: 'subscription',
          transaction: payload(environment),
          renewal: null,
          status: 1,
          signedDate: NOW.getTime(),
        }),
        resolveOwner: vi.fn(async () => 'environment-test'),
        accountAvailable: async () => true,
        apply: vi.fn(async () => {
          if (environment === Environment.SANDBOX)
            throw new PurchaseTokenOwnershipError('environment_conflict');
        }),
        now: () => NOW,
      };
      expect(await runAppleNotification('signed', deps)).toBe(200);
      expect(deps.resolveOwner).toHaveBeenCalledWith(
        applePurchaseTokenHash(
          '123',
          environment === Environment.PRODUCTION ? 'production' : 'sandbox',
        ),
      );
    },
  );
});
