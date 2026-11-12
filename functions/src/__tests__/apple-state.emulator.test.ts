import { randomUUID } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { initializeTestEnvironment, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc } from 'firebase/firestore';
import { adminAuth, db } from '../firebase';
import { applyAppleSubscriptionState } from '../subscription/apple-state';
import { appleAppAccountTokenForUid } from '../subscription/apple-store-core';
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
    ...['subscriptionPurchaseTokens', 'subscriptionAppleEvents'].map((c) =>
      db.collection(c).doc(hashPurchaseToken(chain)).delete(),
    ),
  ]);
});

describe('Apple lifecycle writes across Firestore and Auth', () => {
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
      appleTransactionState(transaction(), appleAppAccountTokenForUid(uid), NOW),
      NOW,
    );
    const env = await initializeTestEnvironment({
      projectId: 'demo-test',
      firestore: { host: '127.0.0.1', port: 8080 },
    });
    try {
      for (const claims of [{}, { admin: true }]) {
        const ref = doc(
          env.authenticatedContext(uid, claims).firestore(),
          'subscriptionAppleEvents',
          hashPurchaseToken(chain),
        );
        await assertFails(getDoc(ref));
        await assertFails(setDoc(ref, { signedDateMillis: NOW.getTime() + 1000 }));
      }
    } finally {
      await env.cleanup();
    }
  });
});
