import { randomUUID } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { initializeTestEnvironment, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc } from 'firebase/firestore';
import { adminAuth, db } from '../firebase';
import { applyAppleSubscriptionState } from '../subscription/apple-state';
import {
  appleAppAccountTokenForUid,
  applePurchaseTokenHash,
  appleProductionAccountMarkerId,
} from '../subscription/apple-store-core';
import { applyEntitlement } from '../subscription/entitlement';
import {
  reservePurchaseVerification,
  releasePurchaseVerification,
} from '../subscription/purchase-token-ownership';
import { appleNotificationState, appleTransactionState } from '../subscription/apple-state-core';
import { hashPurchaseToken } from '../subscription/subscription-core';

vi.hoisted(() => {
  process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
  process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
  process.env.GCLOUD_PROJECT ??= 'demo-test';
  process.env.FIREBASE_CONFIG ??= JSON.stringify({
    projectId: 'demo-test',
    databaseURL: 'https://demo-test.firebaseio.com',
  });
});

const NOW = new Date('2026-11-12T12:00:00Z');
let uid: string;
let chain: string;
function transaction(overrides: Record<string, unknown> = {}) {
  return {
    environment: 'Sandbox',
    type: 'Auto-Renewable Subscription',
    inAppOwnershipType: 'PURCHASED',
    productId: 'plus_monthly',
    originalTransactionId: chain,
    appAccountToken: appleAppAccountTokenForUid(uid),
    purchaseDate: NOW.getTime() - 1000,
    expiresDate: NOW.getTime() + 10000,
    signedDate: NOW.getTime(),
    ...overrides,
  };
}
async function current() {
  return (await db.collection('subscriptions').doc(uid).get()).data();
}

beforeEach(async () => {
  uid = `apple-state-${randomUUID()}`;
  chain = randomUUID();
  await adminAuth.createUser({ uid });
  await db.collection('users').doc(uid).set({ activeMember: false });
});
afterEach(async () => {
  await Promise.all([
    adminAuth.deleteUser(uid),
    ...['users', 'subscriptions', 'subscriptionPurchaseVerifications'].map((c) =>
      db.collection(c).doc(uid).delete(),
    ),
    ...['subscriptionPurchaseTokens', 'subscriptionAppleEvents'].flatMap((c) =>
      ['sandbox', 'production'].map((env) =>
        db
          .collection(c)
          .doc(applePurchaseTokenHash(chain, env as 'sandbox' | 'production'))
          .delete(),
      ),
    ),
    db.collection('subscriptionAppleEvents').doc(appleProductionAccountMarkerId(uid)).delete(),
  ]);
});

describe('Apple lifecycle writes across Firestore and Auth', () => {
  it('keeps a sweep out of an in-flight purchase verification lease', async () => {
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(
        transaction({ environment: 'Production' }),
        appleAppAccountTokenForUid(uid),
        NOW,
      ),
      NOW,
    );
    const record = await current();
    const tokenHash = applePurchaseTokenHash(chain, 'production');
    const lease = await reservePurchaseVerification(
      tokenHash,
      { uid, productId: 'plus_monthly', appleEnvironment: 'production' },
      new Date(),
    );
    try {
      await expect(
        applyEntitlement({
          userId: uid,
          platform: 'apple',
          purchaseTokenHash: tokenHash,
          tier: record!.tier,
          expiresAt: record!.expiresAt.toDate(),
          status: 'expired',
          entitlement: 'none',
        }),
      ).rejects.toMatchObject({ reason: 'verification_in_progress' });
      expect((await adminAuth.getUser(uid)).customClaims?.activeMember).toBe(true);
    } finally {
      await releasePurchaseVerification(uid, tokenHash, lease);
    }
  });
  it('does not replace a different active production chain', async () => {
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(
        transaction({ environment: 'Production' }),
        appleAppAccountTokenForUid(uid),
        NOW,
      ),
      NOW,
    );
    const before = await current();
    await expect(
      applyAppleSubscriptionState(
        uid,
        appleTransactionState(
          transaction({ environment: 'Production', originalTransactionId: `${chain}-different` }),
          appleAppAccountTokenForUid(uid),
          NOW,
        ),
        NOW,
      ),
    ).rejects.toMatchObject({ reason: 'different_active_token' });
    expect(await current()).toEqual(before);
  });
  it('does not let another Firebase account claim an existing production chain', async () => {
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(
        transaction({ environment: 'Production' }),
        appleAppAccountTokenForUid(uid),
        NOW,
      ),
      NOW,
    );
    await expect(
      reservePurchaseVerification(
        applePurchaseTokenHash(chain, 'production'),
        {
          uid: 'unrelated-local-test-account',
          productId: 'plus_monthly',
          appleEnvironment: 'production',
        },
        NOW,
      ),
    ).rejects.toMatchObject({ reason: 'different_user' });
    expect(
      (
        await db
          .collection('subscriptionPurchaseTokens')
          .doc(applePurchaseTokenHash(chain, 'production'))
          .get()
      ).get('uid'),
    ).toBe(uid);
  });
  it('lets a real purchase replace active sandbox access and ignores late test notifications', async () => {
    const sandbox = appleTransactionState(
      transaction({ productId: 'supporter_monthly' }),
      appleAppAccountTokenForUid(uid),
      NOW,
    );
    await applyAppleSubscriptionState(uid, sandbox, NOW);
    const real = appleTransactionState(
      transaction({ environment: 'Production', purchaseDate: NOW.getTime() - 2000 }),
      appleAppAccountTokenForUid(uid),
      NOW,
    );
    await applyAppleSubscriptionState(uid, real, NOW);
    const expected = await current();
    expect(expected).toMatchObject({
      tier: 'plus',
      purchaseTokenHash: applePurchaseTokenHash(chain, 'production'),
      entitlement: 'member_monthly',
    });
    expect(
      (
        await db
          .collection('subscriptionAppleEvents')
          .doc(appleProductionAccountMarkerId(uid))
          .get()
      ).data(),
    ).toMatchObject({ uid, kind: 'production-account' });
    expect(await applyAppleSubscriptionState(uid, sandbox, NOW, true)).toBeNull();
    await expect(applyAppleSubscriptionState(uid, sandbox, NOW)).rejects.toMatchObject({
      reason: 'environment_conflict',
    });
    expect(await current()).toEqual(expected);
    expect((await adminAuth.getUser(uid)).customClaims?.activeMember).toBe(true);
    expect((await db.collection('users').doc(uid).get()).get('supporterBadgeEligible')).toBe(false);
  });
  it('never revives sandbox access after the real purchase expires', async () => {
    const real = appleTransactionState(
      transaction({ environment: 'Production', expiresDate: NOW.getTime() - 1 }),
      appleAppAccountTokenForUid(uid),
      NOW,
    );
    await applyAppleSubscriptionState(uid, real, NOW);
    const sandbox = appleTransactionState(transaction(), appleAppAccountTokenForUid(uid), NOW);
    await expect(applyAppleSubscriptionState(uid, sandbox, NOW)).rejects.toMatchObject({
      reason: 'environment_conflict',
    });
    expect(await current()).toMatchObject({ entitlement: 'none', status: 'expired' });
    expect((await adminAuth.getUser(uid)).customClaims?.activeMember).not.toBe(true);
  });
  it('retries a first production grant that failed before committing entitlement', async () => {
    const sandbox = appleTransactionState(transaction(), appleAppAccountTokenForUid(uid), NOW);
    await applyAppleSubscriptionState(uid, sandbox, NOW);
    const real = appleTransactionState(
      transaction({ environment: 'Production' }),
      appleAppAccountTokenForUid(uid),
      NOW,
    );
    const fail = vi
      .spyOn(adminAuth, 'getUser')
      .mockRejectedValueOnce(new Error('local transient Auth failure'));
    try {
      await expect(applyAppleSubscriptionState(uid, real, NOW)).rejects.toThrow('local transient');
    } finally {
      fail.mockRestore();
    }
    expect(await current()).toMatchObject({ purchaseTokenHash: hashPurchaseToken(chain) });
    await applyAppleSubscriptionState(uid, real, NOW);
    expect(await current()).toMatchObject({
      purchaseTokenHash: applePurchaseTokenHash(chain, 'production'),
    });
    expect((await adminAuth.getUser(uid)).customClaims?.activeMember).toBe(true);
  });
  it('serializes concurrent environments and converges to production on retry', async () => {
    const sandbox = appleTransactionState(transaction(), appleAppAccountTokenForUid(uid), NOW);
    const real = appleTransactionState(
      transaction({ environment: 'Production' }),
      appleAppAccountTokenForUid(uid),
      NOW,
    );
    await Promise.allSettled([
      applyAppleSubscriptionState(uid, sandbox, NOW),
      applyAppleSubscriptionState(uid, real, NOW),
    ]);
    await applyAppleSubscriptionState(uid, real, NOW);
    await expect(applyAppleSubscriptionState(uid, sandbox, NOW)).rejects.toMatchObject({
      reason: 'environment_conflict',
    });
    expect(await current()).toMatchObject({
      purchaseTokenHash: applePurchaseTokenHash(chain, 'production'),
      entitlement: 'member_monthly',
    });
  });
  it('blocks a stale sandbox expiry sweep before it can revoke a newer real claim', async () => {
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(transaction(), appleAppAccountTokenForUid(uid), NOW),
      NOW,
    );
    const old = await current();
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(
        transaction({ environment: 'Production' }),
        appleAppAccountTokenForUid(uid),
        NOW,
      ),
      NOW,
    );
    await expect(
      applyEntitlement({
        userId: uid,
        platform: 'apple',
        purchaseTokenHash: old!.purchaseTokenHash,
        tier: old!.tier,
        expiresAt: old!.expiresAt.toDate(),
        status: 'expired',
        entitlement: 'none',
      }),
    ).rejects.toMatchObject({ reason: 'different_active_token' });
    expect((await adminAuth.getUser(uid)).customClaims?.activeMember).toBe(true);
    const currentReal = await current();
    await applyEntitlement({
      userId: uid,
      platform: 'apple',
      purchaseTokenHash: currentReal!.purchaseTokenHash,
      tier: currentReal!.tier,
      expiresAt: currentReal!.expiresAt.toDate(),
      status: 'expired',
      entitlement: 'none',
    });
    expect((await adminAuth.getUser(uid)).customClaims?.activeMember).not.toBe(true);
    expect(await current()).toMatchObject({ entitlement: 'none' });
  });
  it('grants records and claims, and repairs an interrupted claim write on duplicate delivery', async () => {
    const state = appleTransactionState(transaction(), appleAppAccountTokenForUid(uid), NOW);
    await applyAppleSubscriptionState(uid, state, NOW);
    expect(await current()).toMatchObject({ entitlement: 'member_monthly', tier: 'plus' });
    expect((await adminAuth.getUser(uid)).customClaims?.activeMember).toBe(true);
    expect(
      (await db.collection('subscriptionAppleEvents').doc(hashPurchaseToken(chain)).get()).data(),
    ).toMatchObject(state.version);
    await adminAuth.setCustomUserClaims(uid, { activeMember: false });
    await applyAppleSubscriptionState(uid, state, NOW);
    expect((await adminAuth.getUser(uid)).customClaims?.activeMember).toBe(true);
  });
  it('refunds access and prevents a later device JWS from restoring the same period', async () => {
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(transaction(), appleAppAccountTokenForUid(uid), NOW),
      NOW,
    );
    const refund = appleNotificationState(
      transaction({ revocationDate: NOW.getTime() }),
      null,
      5,
      NOW.getTime() + 1,
      appleAppAccountTokenForUid(uid),
      NOW,
    );
    await applyAppleSubscriptionState(uid, refund, NOW, true);
    expect((await adminAuth.getUser(uid)).customClaims?.activeMember).toBeUndefined();
    const result = await applyAppleSubscriptionState(
      uid,
      appleTransactionState(
        transaction({ signedDate: NOW.getTime() + 5000 }),
        appleAppAccountTokenForUid(uid),
        NOW,
      ),
      NOW,
    );
    expect(result?.entitlement).toBe('none');
    expect(await current()).toMatchObject({ status: 'revoked', entitlement: 'none' });
  });
  it('keeps a newer Supporter upgrade when an old Plus refund arrives late', async () => {
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(transaction(), appleAppAccountTokenForUid(uid), NOW),
      NOW,
    );
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(
        transaction({
          productId: 'supporter_monthly',
          purchaseDate: NOW.getTime(),
          signedDate: NOW.getTime() + 1,
        }),
        appleAppAccountTokenForUid(uid),
        NOW,
      ),
      NOW,
    );
    await applyAppleSubscriptionState(
      uid,
      appleNotificationState(
        transaction({ revocationDate: NOW.getTime() }),
        null,
        5,
        NOW.getTime() + 5000,
        appleAppAccountTokenForUid(uid),
        NOW,
      ),
      NOW,
      true,
    );
    expect(await current()).toMatchObject({ entitlement: 'member_monthly', tier: 'supporter' });
    expect((await adminAuth.getUser(uid)).customClaims?.activeMember).toBe(true);
  });
  it('keeps cancelled subscriptions paid until expiry and revokes at the deadline', async () => {
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(transaction(), appleAppAccountTokenForUid(uid), NOW),
      NOW,
    );
    const cancelled = appleNotificationState(
      transaction(),
      { originalTransactionId: chain, productId: 'plus_monthly', autoRenewStatus: 0 },
      1,
      NOW.getTime() + 1,
      appleAppAccountTokenForUid(uid),
      NOW,
    );
    await applyAppleSubscriptionState(uid, cancelled, NOW, true);
    expect(await current()).toMatchObject({ status: 'cancelled', entitlement: 'member_monthly' });
    await applyAppleSubscriptionState(uid, cancelled, new Date(NOW.getTime() + 10001), true);
    expect(await current()).toMatchObject({ status: 'expired', entitlement: 'none' });
  });
  it('ignores a late notification after a manual grant replaces the store subscription', async () => {
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(transaction(), appleAppAccountTokenForUid(uid), NOW),
      NOW,
    );
    await db.collection('subscriptions').doc(uid).set({
      platform: 'manual',
      entitlement: 'member_monthly',
      status: 'active',
      tier: 'supporter',
      purchaseTokenHash: null,
    });
    const result = await applyAppleSubscriptionState(
      uid,
      appleNotificationState(
        transaction(),
        null,
        5,
        NOW.getTime(),
        appleAppAccountTokenForUid(uid),
        NOW,
      ),
      NOW,
      true,
    );
    expect(result).toBeNull();
    expect(await current()).toMatchObject({ platform: 'manual', tier: 'supporter' });
  });

  it('preserves signed grace access when the device submits an expired transaction', async () => {
    const tx = transaction({ expiresDate: NOW.getTime() - 1 });
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(tx, appleAppAccountTokenForUid(uid), NOW),
      NOW,
    );
    const grace = appleNotificationState(
      tx,
      {
        originalTransactionId: chain,
        productId: 'plus_monthly',
        gracePeriodExpiresDate: NOW.getTime() + 10000,
      },
      4,
      NOW.getTime(),
      appleAppAccountTokenForUid(uid),
      NOW,
    );
    await applyAppleSubscriptionState(uid, grace, NOW, true);
    const result = await applyAppleSubscriptionState(
      uid,
      appleTransactionState(tx, appleAppAccountTokenForUid(uid), NOW),
      NOW,
    );
    expect(result).toMatchObject({ status: 'grace_period', entitlement: 'member_monthly' });
    expect((await adminAuth.getUser(uid)).customClaims?.activeMember).toBe(true);
  });

  it('denies marker reads and writes even to the owner and an admin client', async () => {
    await applyAppleSubscriptionState(
      uid,
      appleTransactionState(
        transaction({ environment: 'Production' }),
        appleAppAccountTokenForUid(uid),
        NOW,
      ),
      NOW,
    );
    const env = await initializeTestEnvironment({
      projectId: 'demo-test',
      firestore: { host: '127.0.0.1', port: 8080 },
    });
    try {
      for (const claims of [{}, { admin: true }]) {
        for (const marker of [
          applePurchaseTokenHash(chain, 'production'),
          appleProductionAccountMarkerId(uid),
        ]) {
          const ref = doc(
            env.authenticatedContext(uid, claims).firestore(),
            'subscriptionAppleEvents',
            marker,
          );
          await assertFails(getDoc(ref));
          await assertFails(setDoc(ref, { signedDateMillis: NOW.getTime() + 1000 }));
        }
      }
    } finally {
      await env.cleanup();
    }
  });
});
