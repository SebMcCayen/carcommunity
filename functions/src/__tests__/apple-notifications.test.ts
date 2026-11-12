import { describe, expect, it, vi } from 'vitest';
import {
  SignedDataVerifier,
  VerificationException,
  VerificationStatus,
} from '@apple/app-store-server-library';
import { appleAppAccountTokenForUid } from '../subscription/apple-store-core';
import {
  appleNotificationState,
  appleTransactionState,
  isNewerAppleState,
} from '../subscription/apple-state-core';
import { AppleSignedDataError } from '../subscription/apple-store';
import {
  runAppleNotification,
  verifyAppleNotification,
  type AppleNotificationDeps,
} from '../subscription/apple-notifications';

vi.mock('../firebase', () => ({ db: {}, adminAuth: {} }));

const NOW = new Date('2026-11-12T12:00:00Z');
const UID = 'apple-test';
const TOKEN = appleAppAccountTokenForUid(UID);
function transaction(overrides: Record<string, unknown> = {}) {
  return {
    type: 'Auto-Renewable Subscription',
    inAppOwnershipType: 'PURCHASED',
    productId: 'plus_monthly',
    originalTransactionId: '123',
    appAccountToken: TOKEN,
    purchaseDate: NOW.getTime() - 1000,
    expiresDate: NOW.getTime() + 1000,
    signedDate: NOW.getTime(),
    ...overrides,
  };
}
function notification(
  status = 1,
  tx = transaction(),
  renewal: Record<string, unknown> | null = null,
) {
  return {
    kind: 'subscription' as const,
    transaction: tx,
    renewal,
    status,
    signedDate: NOW.getTime(),
  };
}
function deps(): AppleNotificationDeps {
  return {
    config: vi.fn(async () => ({ environment: 'sandbox' as const })),
    verify: vi.fn(async () => notification()),
    resolveOwner: vi.fn(async () => UID),
    accountAvailable: vi.fn(async () => true),
    apply: vi.fn(async () => undefined),
    now: () => NOW,
  };
}

describe('verified Apple renewal state', () => {
  it('keeps paid access after renewal is cancelled', () => {
    const state = appleNotificationState(
      transaction(),
      {
        productId: 'plus_monthly',
        originalTransactionId: '123',
        autoRenewStatus: 0,
      },
      1,
      NOW.getTime(),
      TOKEN,
      NOW,
    );
    expect(state.outcome).toMatchObject({ status: 'cancelled', entitlement: 'member_monthly' });
  });
  it('uses the signed grace deadline after the paid transaction expires', () => {
    const until = NOW.getTime() + 5000;
    const state = appleNotificationState(
      transaction({ expiresDate: NOW.getTime() - 1 }),
      {
        productId: 'plus_monthly',
        originalTransactionId: '123',
        gracePeriodExpiresDate: until,
      },
      4,
      NOW.getTime(),
      TOKEN,
      NOW,
    );
    expect(state.outcome).toMatchObject({
      status: 'grace_period',
      entitlement: 'member_monthly',
      expiresAt: new Date(until),
    });
  });
  it.each([2, 3, 5])(
    'removes access for expired, billing retry, or revoked status %s',
    (status) => {
      expect(
        appleNotificationState(transaction(), null, status, NOW.getTime(), TOKEN, NOW).outcome
          .entitlement,
      ).toBe('none');
    },
  );
  it('rejects grace without signed renewal info and mismatched renewal chains', () => {
    expect(() =>
      appleNotificationState(transaction(), null, 4, NOW.getTime(), TOKEN, NOW),
    ).toThrow();
    expect(() =>
      appleNotificationState(
        transaction(),
        { originalTransactionId: 'other', productId: 'plus_monthly' },
        1,
        NOW.getTime(),
        TOKEN,
        NOW,
      ),
    ).toThrow();
  });
  it('never restores a revoked transaction through grace', () => {
    expect(
      appleNotificationState(
        transaction({ revocationDate: NOW.getTime() }),
        null,
        4,
        NOW.getTime(),
        TOKEN,
        NOW,
      ).outcome.entitlement,
    ).toBe('none');
  });
  it('orders by purchased period before notification date', () => {
    const current = appleTransactionState(transaction(), TOKEN, NOW).version;
    expect(
      isNewerAppleState(
        {
          ...current,
          source: 'notification',
          purchaseDateMillis: current.purchaseDateMillis - 1000,
          signedDateMillis: current.signedDateMillis + 5000,
        },
        current,
      ),
    ).toBe(false);
    expect(isNewerAppleState(current, { ...current, source: 'notification' })).toBe(false);
    expect(isNewerAppleState(current, current)).toBe(false);
  });
});

describe('Apple webhook orchestration', () => {
  it('is inert with the provider off and requests retry', async () => {
    const d = deps();
    vi.mocked(d.config).mockResolvedValue(null);
    expect(await runAppleNotification('signed', d)).toBe(503);
    expect(d.verify).not.toHaveBeenCalled();
    expect(d.apply).not.toHaveBeenCalled();
  });
  it('applies a verified notification for the bound account', async () => {
    const d = deps();
    expect(await runAppleNotification('signed', d)).toBe(200);
    expect(d.resolveOwner).toHaveBeenCalledWith(expect.stringMatching(/^[a-f0-9]{64}$/));
    expect(d.apply).toHaveBeenCalledWith(
      UID,
      expect.objectContaining({
        outcome: expect.objectContaining({ entitlement: 'member_monthly' }),
      }),
      NOW,
    );
  });
  it.each(['test', 'ignored'] as const)(
    'acknowledges %s without touching accounts',
    async (kind) => {
      const d = deps();
      vi.mocked(d.verify).mockResolvedValue({ kind });
      expect(await runAppleNotification('signed', d)).toBe(200);
      expect(d.resolveOwner).not.toHaveBeenCalled();
      expect(d.apply).not.toHaveBeenCalled();
    },
  );
  it('requests redelivery when a first purchase has not registered ownership yet', async () => {
    const d = deps();
    vi.mocked(d.resolveOwner).mockResolvedValue(null);
    expect(await runAppleNotification('signed', d)).toBe(503);
    expect(d.apply).not.toHaveBeenCalled();
  });
  it('does not recreate a deleted or disabled account', async () => {
    const d = deps();
    vi.mocked(d.accountAvailable).mockResolvedValue(false);
    expect(await runAppleNotification('signed', d)).toBe(200);
    expect(d.apply).not.toHaveBeenCalled();
  });
  it('rejects a mismatched Firebase account binding', async () => {
    const d = deps();
    vi.mocked(d.verify).mockResolvedValue(
      notification(1, transaction({ appAccountToken: appleAppAccountTokenForUid('other') })),
    );
    expect(await runAppleNotification('signed', d)).toBe(400);
    expect(d.apply).not.toHaveBeenCalled();
  });
  it('retries transient certificate-verification failures', async () => {
    const d = deps();
    vi.mocked(d.verify).mockRejectedValue(new AppleSignedDataError('unavailable'));
    expect(await runAppleNotification('signed', d)).toBe(503);
    expect(d.apply).not.toHaveBeenCalled();
  });
});

describe('Apple JWS verification boundary', () => {
  it('rejects an unsigned payload using the real Apple verifier', async () => {
    await expect(
      verifyAppleNotification('not-a-jws', { environment: 'sandbox' }),
    ).rejects.toMatchObject({ reason: 'invalid_signature' });
  });
  it('verifies nested JWS rather than trusting the outer payload alone', async () => {
    const envelope = vi
      .spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeNotification')
      .mockResolvedValue({
        version: '2.0',
        notificationType: 'DID_RENEW',
        data: { signedTransactionInfo: 'bad-inner-jws', status: 1 },
      });
    const inner = vi
      .spyOn(SignedDataVerifier.prototype, 'verifyAndDecodeTransaction')
      .mockRejectedValue(new VerificationException(VerificationStatus.INVALID_ENVIRONMENT));
    try {
      await expect(
        verifyAppleNotification('verified-outer', { environment: 'sandbox' }),
      ).rejects.toMatchObject({ reason: 'invalid_signature' });
      expect(inner).toHaveBeenCalledWith('bad-inner-jws');
    } finally {
      envelope.mockRestore();
      inner.mockRestore();
    }
  });
});
