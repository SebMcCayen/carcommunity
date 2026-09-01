/**
 * Unit tests for the RTDN orchestration core
 * (functions/src/subscription/rtdn.ts::runRtdnNotification).
 *
 * Every dependency (provider gate, ownership registry, Play verification,
 * applyEntitlement, processed-marker store) is injected, so these run with no
 * emulator. They pin the safety contract: provider-gated no-op, idempotency,
 * authoritative re-verification, correct grant/revoke transitions, and
 * fail-safe error handling (ack vs. throw-to-retry).
 */

import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  GOOGLE_PLAY_PACKAGE_NAME,
  GooglePlayVerificationError,
  type GooglePlayEntitlementOutcome,
} from '../subscription/google-play-core';
import { GooglePlayApiError } from '../subscription/google-play';
import { hashPurchaseToken, type EntitlementRecordInput } from '../subscription/subscription-core';
import type { DecodedRtdn } from '../subscription/rtdn-core';
import type { RtdnDeps } from '../subscription/rtdn';

let mod: typeof import('../subscription/rtdn');

beforeAll(async () => {
  process.env.GCLOUD_PROJECT ??= 'demo-test';
  process.env.FIREBASE_CONFIG ??= JSON.stringify({
    projectId: 'demo-test',
    databaseURL: 'https://demo-test.firebaseio.com',
    storageBucket: 'demo-test.appspot.com',
  });
  mod = await import('../subscription/rtdn');
});

const UID = 'user-1';
const RAW_TOKEN = 'raw-purchase-token';
const TOKEN_HASH = hashPurchaseToken(RAW_TOKEN);

const activeOutcome: GooglePlayEntitlementOutcome = {
  productId: 'plus_monthly',
  tier: 'plus',
  status: 'active',
  entitlement: 'member_monthly',
  startsAt: new Date('2026-08-01T00:00:00Z'),
  expiresAt: new Date('2026-09-01T00:00:00Z'),
  acknowledgementRequired: false,
  linkedPurchaseTokenHash: null,
};

function subscriptionEnvelope(eventTimeMillis = 1000): DecodedRtdn {
  return {
    kind: 'subscription',
    packageName: GOOGLE_PLAY_PACKAGE_NAME,
    eventTimeMillis,
    notification: { notificationType: 2, purchaseToken: RAW_TOKEN, subscriptionId: 'plus_monthly' },
  };
}

function voidedEnvelope(eventTimeMillis = 1000): DecodedRtdn {
  return {
    kind: 'voided',
    packageName: GOOGLE_PLAY_PACKAGE_NAME,
    eventTimeMillis,
    notification: { purchaseToken: RAW_TOKEN, productType: 1, refundType: 1 },
  };
}

let applied: EntitlementRecordInput[];
let marked: Array<{ hash: string; eventTimeMillis: number }>;
let acknowledged: Array<{ productId: string; purchaseToken: string }>;

function makeDeps(overrides: Partial<RtdnDeps> = {}): RtdnDeps {
  return {
    providerEnabled: vi.fn(async () => true),
    resolveOwner: vi.fn(async () => ({ uid: UID, productId: 'plus_monthly' as const })),
    verify: vi.fn(async () => activeOutcome),
    acknowledge: vi.fn(async (productId: string, purchaseToken: string) => {
      acknowledged.push({ productId, purchaseToken });
    }),
    applyEntitlement: vi.fn(async (input: EntitlementRecordInput) => {
      applied.push(input);
    }),
    // By default the stored subscription was bought with THIS token (the
    // current/effective one), so a revoke path acts. Superseded-token tests
    // override this to a different hash.
    readStoredSubscription: vi.fn(async () => ({
      tier: 'plus',
      startsAt: null,
      expiresAt: null,
      purchaseTokenHash: TOKEN_HASH,
    })),
    lastProcessedEventTime: vi.fn(async () => null),
    markProcessed: vi.fn(async (hash: string, eventTimeMillis: number) => {
      marked.push({ hash, eventTimeMillis });
    }),
    now: () => new Date('2026-08-15T12:00:00Z'),
    ...overrides,
  };
}

const pendingPurchaseOutcome: GooglePlayEntitlementOutcome = {
  ...activeOutcome,
  acknowledgementRequired: true,
};

function purchaseEnvelope(eventTimeMillis = 1000): DecodedRtdn {
  return {
    kind: 'subscription',
    packageName: GOOGLE_PLAY_PACKAGE_NAME,
    eventTimeMillis,
    // notificationType 4 = SUBSCRIPTION_PURCHASED.
    notification: { notificationType: 4, purchaseToken: RAW_TOKEN, subscriptionId: 'plus_monthly' },
  };
}

beforeEach(() => {
  applied = [];
  marked = [];
  acknowledged = [];
});

describe('runRtdnNotification — gating and routing', () => {
  it('is a no-op while the provider is disabled', async () => {
    const deps = makeDeps({ providerEnabled: vi.fn(async () => false) });
    const outcome = await mod.runRtdnNotification(subscriptionEnvelope(), deps);
    expect(outcome).toBe('provider_disabled');
    expect(deps.verify).not.toHaveBeenCalled();
    expect(applied).toEqual([]);
  });

  it('ignores a notification for another app package', async () => {
    const deps = makeDeps();
    const outcome = await mod.runRtdnNotification(
      { ...subscriptionEnvelope(), packageName: 'com.other.app' },
      deps,
    );
    expect(outcome).toBe('foreign_package');
    expect(deps.providerEnabled).not.toHaveBeenCalled();
  });

  it('acks a test notification without touching entitlement', async () => {
    const deps = makeDeps();
    const outcome = await mod.runRtdnNotification(
      { kind: 'test', packageName: GOOGLE_PLAY_PACKAGE_NAME, eventTimeMillis: 5 },
      deps,
    );
    expect(outcome).toBe('test_notification');
    expect(deps.verify).not.toHaveBeenCalled();
  });

  it('acks an unknown purchase token (no owner)', async () => {
    const deps = makeDeps({ resolveOwner: vi.fn(async () => null) });
    const outcome = await mod.runRtdnNotification(subscriptionEnvelope(), deps);
    expect(outcome).toBe('unknown_token');
    expect(deps.verify).not.toHaveBeenCalled();
    expect(applied).toEqual([]);
  });
});

describe('runRtdnNotification — idempotency', () => {
  it('skips a delivery not newer than the last processed eventTime', async () => {
    const deps = makeDeps({ lastProcessedEventTime: vi.fn(async () => 1000) });
    expect(await mod.runRtdnNotification(subscriptionEnvelope(1000), deps)).toBe('duplicate');
    expect(await mod.runRtdnNotification(subscriptionEnvelope(999), deps)).toBe('duplicate');
    expect(deps.verify).not.toHaveBeenCalled();
    expect(applied).toEqual([]);
  });

  it('processes a strictly newer delivery', async () => {
    const deps = makeDeps({ lastProcessedEventTime: vi.fn(async () => 1000) });
    const outcome = await mod.runRtdnNotification(subscriptionEnvelope(1001), deps);
    expect(outcome).toBe('applied');
    expect(marked).toEqual([{ hash: TOKEN_HASH, eventTimeMillis: 1001 }]);
  });
});

describe('runRtdnNotification — authoritative transitions', () => {
  it('re-verifies against Play and keeps access on an active subscription', async () => {
    const deps = makeDeps();
    const outcome = await mod.runRtdnNotification(subscriptionEnvelope(), deps);
    expect(outcome).toBe('applied');
    expect(deps.verify).toHaveBeenCalledWith(RAW_TOKEN, expect.any(String), expect.any(Date));
    expect(applied).toHaveLength(1);
    expect(applied[0]).toMatchObject({
      userId: UID,
      platform: 'google',
      status: 'active',
      entitlement: 'member_monthly',
      tier: 'plus',
      purchaseTokenHash: TOKEN_HASH,
    });
    expect(marked).toHaveLength(1);
  });

  it('downgrades to none when Play reports the subscription no longer active', async () => {
    const expired: GooglePlayEntitlementOutcome = {
      ...activeOutcome,
      status: 'expired',
      entitlement: 'none',
    };
    const deps = makeDeps({ verify: vi.fn(async () => expired) });
    const outcome = await mod.runRtdnNotification(subscriptionEnvelope(), deps);
    expect(outcome).toBe('revoked');
    expect(applied[0]).toMatchObject({ status: 'expired', entitlement: 'none' });
  });

  it('revokes on a voided purchase WITHOUT re-verifying (refund is authoritative)', async () => {
    const deps = makeDeps();
    const outcome = await mod.runRtdnNotification(voidedEnvelope(), deps);
    expect(outcome).toBe('revoked');
    expect(deps.verify).not.toHaveBeenCalled();
    expect(applied[0]).toMatchObject({
      userId: UID,
      platform: 'google',
      status: 'revoked',
      entitlement: 'none',
      tier: 'plus',
    });
    expect(marked).toHaveLength(1);
  });

  it('preserves the stored tier/dates when revoking a voided purchase', async () => {
    const deps = makeDeps({
      resolveOwner: vi.fn(async () => ({ uid: UID, productId: 'plus_monthly' as const })),
      readStoredSubscription: vi.fn(async () => ({
        tier: 'supporter',
        startsAt: new Date('2026-07-01T00:00:00Z'),
        expiresAt: new Date('2026-08-01T00:00:00Z'),
        purchaseTokenHash: TOKEN_HASH,
      })),
    });
    await mod.runRtdnNotification(voidedEnvelope(), deps);
    expect(applied[0]).toMatchObject({
      tier: 'supporter',
      startsAt: new Date('2026-07-01T00:00:00Z'),
      expiresAt: new Date('2026-08-01T00:00:00Z'),
    });
  });
});

describe('runRtdnNotification — superseded-token guard (billing-critical)', () => {
  const OTHER_HASH = 'a-different-current-token-hash';

  it('does NOT revoke a voided OLD token when the stored sub holds a different current token', async () => {
    const deps = makeDeps({
      readStoredSubscription: vi.fn(async () => ({
        tier: 'plus',
        startsAt: null,
        expiresAt: null,
        purchaseTokenHash: OTHER_HASH,
      })),
    });
    const outcome = await mod.runRtdnNotification(voidedEnvelope(), deps);
    expect(outcome).toBe('superseded_token');
    expect(applied).toEqual([]);
    expect(marked).toEqual([]);
  });

  it('DOES revoke a voided token that IS the stored current token', async () => {
    const deps = makeDeps(); // default stored hash === TOKEN_HASH
    const outcome = await mod.runRtdnNotification(voidedEnvelope(), deps);
    expect(outcome).toBe('revoked');
    expect(applied[0]).toMatchObject({ status: 'revoked', entitlement: 'none' });
  });

  it('does NOT revoke on invalid_purchase for a superseded OLD token', async () => {
    const deps = makeDeps({
      verify: vi.fn(async () => {
        throw new GooglePlayApiError('get', 'invalid_purchase');
      }),
      readStoredSubscription: vi.fn(async () => ({
        tier: 'plus',
        startsAt: null,
        expiresAt: null,
        purchaseTokenHash: OTHER_HASH,
      })),
    });
    const outcome = await mod.runRtdnNotification(subscriptionEnvelope(), deps);
    expect(outcome).toBe('superseded_token');
    expect(applied).toEqual([]);
    expect(marked).toEqual([]);
  });

  it('DOES revoke on invalid_purchase when the dead token IS the stored current token', async () => {
    const deps = makeDeps({
      verify: vi.fn(async () => {
        throw new GooglePlayApiError('get', 'invalid_purchase');
      }),
    }); // default stored hash === TOKEN_HASH
    const outcome = await mod.runRtdnNotification(subscriptionEnvelope(), deps);
    expect(outcome).toBe('revoked');
    expect(applied[0]).toMatchObject({ status: 'revoked', entitlement: 'none' });
    expect(marked).toHaveLength(1);
  });
});

describe('runRtdnNotification — acknowledgement (billing-critical)', () => {
  it('acknowledges a first-seen SUBSCRIPTION_PURCHASED after granting, then marks processed', async () => {
    const deps = makeDeps({ verify: vi.fn(async () => pendingPurchaseOutcome) });
    const outcome = await mod.runRtdnNotification(purchaseEnvelope(), deps);
    expect(outcome).toBe('applied');
    // Grant happened, then acknowledge with the RAW token + product id.
    expect(applied).toHaveLength(1);
    expect(acknowledged).toEqual([{ productId: 'plus_monthly', purchaseToken: RAW_TOKEN }]);
    // Only marked processed AFTER a successful acknowledge.
    expect(marked).toHaveLength(1);
  });

  it('does not acknowledge an already-acknowledged purchase', async () => {
    // activeOutcome has acknowledgementRequired: false.
    const deps = makeDeps();
    await mod.runRtdnNotification(subscriptionEnvelope(), deps);
    expect(acknowledged).toEqual([]);
  });

  it('does not acknowledge a downgrade/revoke transition', async () => {
    const expiredPending: GooglePlayEntitlementOutcome = {
      ...pendingPurchaseOutcome,
      status: 'expired',
      entitlement: 'none',
    };
    const deps = makeDeps({ verify: vi.fn(async () => expiredPending) });
    await mod.runRtdnNotification(purchaseEnvelope(), deps);
    expect(acknowledged).toEqual([]);
  });

  it('throws (→ retry) and does NOT advance the watermark when acknowledge fails', async () => {
    const deps = makeDeps({
      verify: vi.fn(async () => pendingPurchaseOutcome),
      acknowledge: vi.fn(async () => {
        throw new GooglePlayApiError('acknowledge');
      }),
    });
    await expect(mod.runRtdnNotification(purchaseEnvelope(), deps)).rejects.toBeInstanceOf(
      mod.TransientRtdnError,
    );
    // Entitlement was granted, but the idempotency watermark stayed put so the
    // Pub/Sub retry re-runs and re-acknowledges.
    expect(applied).toHaveLength(1);
    expect(marked).toEqual([]);
  });
});

describe('runRtdnNotification — error handling', () => {
  it('acks (no mutation) when authoritative verification is rejected', async () => {
    const deps = makeDeps({
      verify: vi.fn(async () => {
        throw new GooglePlayVerificationError('account_mismatch', 'bound to another account');
      }),
    });
    const outcome = await mod.runRtdnNotification(subscriptionEnvelope(), deps);
    expect(outcome).toBe('verification_rejected');
    expect(applied).toEqual([]);
    expect(marked).toEqual([]);
  });

  it('revokes when Play definitively no longer recognises the token', async () => {
    const deps = makeDeps({
      verify: vi.fn(async () => {
        throw new GooglePlayApiError('get', 'invalid_purchase');
      }),
    });
    const outcome = await mod.runRtdnNotification(subscriptionEnvelope(), deps);
    expect(outcome).toBe('revoked');
    expect(applied[0]).toMatchObject({ status: 'revoked', entitlement: 'none' });
    expect(marked).toHaveLength(1);
  });

  it('throws TransientRtdnError (→ Pub/Sub retry) when Play is unavailable', async () => {
    const deps = makeDeps({
      verify: vi.fn(async () => {
        throw new GooglePlayApiError('get', 'unavailable');
      }),
    });
    await expect(mod.runRtdnNotification(subscriptionEnvelope(), deps)).rejects.toBeInstanceOf(
      mod.TransientRtdnError,
    );
    expect(applied).toEqual([]);
    expect(marked).toEqual([]);
  });

  it('throws on an unexpected downstream error (→ retry), never acking silently', async () => {
    const deps = makeDeps({
      verify: vi.fn(async () => {
        throw new Error('socket hang up');
      }),
    });
    await expect(mod.runRtdnNotification(subscriptionEnvelope(), deps)).rejects.toBeInstanceOf(
      mod.TransientRtdnError,
    );
  });
});
