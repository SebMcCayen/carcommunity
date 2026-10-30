import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { deleteField, doc, getDoc, serverTimestamp, setDoc, updateDoc } from 'firebase/firestore';
import type { EntitlementRecordInput } from '../subscription/subscription-core';

let env: RulesTestEnvironment;
let firebase: typeof import('../firebase');
let apply: typeof import('../subscription/entitlement').applyEntitlement;
let reconcile: typeof import('../subscription/supporter-badge').reconcileSupporterBadge;
const uid = 'supporter-crown-owner';
const input: EntitlementRecordInput = {
  userId: uid,
  platform: 'google',
  tier: 'supporter',
  entitlement: 'member_monthly',
  status: 'active',
  purchaseTokenHash: 'hash',
  expiresAt: new Date('2099-01-01T00:00:00Z'),
};

beforeAll(async () => {
  // Never fall through to production ADC, even when invoked outside emulators:exec.
  if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
    throw new Error('Requires local Firestore and Auth emulators');
  }
  const projectId = process.env.GCLOUD_PROJECT ?? 'demo-supporter-crown';
  if (!projectId.startsWith('demo-')) throw new Error('Requires a demo project');
  process.env.GCLOUD_PROJECT = projectId;
  process.env.FIREBASE_CONFIG = JSON.stringify({
    projectId,
    databaseURL: `https://${projectId}.firebaseio.com`,
    storageBucket: `${projectId}.appspot.com`,
  });
  const [host, port] = process.env.FIRESTORE_EMULATOR_HOST.split(':');
  env = await initializeTestEnvironment({
    projectId,
    firestore: {
      host,
      port: Number(port),
      rules: readFileSync(resolve(__dirname, '../../../firebase/firestore.rules'), 'utf8'),
    },
  });
  firebase = await import('../firebase');
  apply = (await import('../subscription/entitlement')).applyEntitlement;
  reconcile = (await import('../subscription/supporter-badge')).reconcileSupporterBadge;
  await firebase.adminAuth.createUser({ uid });
  await firebase.db
    .collection('users')
    .doc(uid)
    .set({ displayName: 'Supporter', activeMember: false, showSupporterBadge: false, showMembershipMapRing: false });
});
afterAll(async () => {
  await env?.cleanup();
});

describe('Supporter badge persistence and security', () => {
  it('atomically projects purchase/update/downgrade/revoke/expiry/reactivation, preserving preference', async () => {
    const transitions: [Partial<EntitlementRecordInput>, boolean, boolean][] = [
      [{}, true, true],
      [{ status: 'cancelled' }, true, true],
      [{ status: 'grace_period' }, true, true],
      [{ tier: 'plus' }, false, true],
      [{}, true, true],
      [{ status: 'revoked', entitlement: 'none' }, false, false],
      [{ status: 'expired', entitlement: 'none' }, false, false],
      [{}, true, true],
    ];
    for (const [patch, eligible, active] of transitions) {
      await apply({ ...input, ...patch });
      const profile = (await firebase.db.collection('users').doc(uid).get()).data();
      expect(profile).toMatchObject({
        supporterBadgeEligible: eligible,
        activeMember: active,
        showSupporterBadge: false,
        showMembershipMapRing: false,
        membershipMapRingEligible: active,
      });
      for (const privateField of ['membershipMapRingUntil', 'refreshAt', 'mapRingRefreshAt', 'expiresAt']) {
        expect(profile).not.toHaveProperty(privateField);
      }
      const subscription = await firebase.db.collection('subscriptions').doc(uid).get();
      expect(subscription.get('mapRingRefreshAt')?.toMillis?.() ?? null).toBe(
        active ? input.expiresAt?.getTime() : null,
      );
      expect((await firebase.adminAuth.getUser(uid)).customClaims?.activeMember === true).toBe(
        active,
      );
    }
  });

  it('backfills missing eligibility and repairs stale downgrades without touching preference or claims', async () => {
    const profile = firebase.db.collection('users').doc(uid);
    const { FieldValue } = await import('firebase-admin/firestore');
    await profile.update({ supporterBadgeEligible: FieldValue.delete(), membershipMapRingEligible: FieldValue.delete() });
    await reconcile(uid);
    expect((await profile.get()).get('supporterBadgeEligible')).toBe(true);
    expect((await profile.get()).get('membershipMapRingEligible')).toBe(true);
    const unchangedTime = (await profile.get()).updateTime;
    await reconcile(uid);
    expect((await profile.get()).updateTime?.isEqual(unchangedTime!)).toBe(true);
    const claims = (await firebase.adminAuth.getUser(uid)).customClaims;
    await firebase.db.collection('subscriptions').doc(uid).update({ tier: 'plus' });
    await reconcile(uid);
    expect((await profile.get()).data()).toMatchObject({
      activeMember: true,
      supporterBadgeEligible: false,
      showSupporterBadge: false,
      showMembershipMapRing: false,
      membershipMapRingEligible: true,
    });
    expect((await firebase.adminAuth.getUser(uid)).customClaims).toEqual(claims);
    await reconcile(uid);
    expect((await profile.get()).get('showSupporterBadge')).toBe(false);
    await firebase.db
      .collection('subscriptions')
      .doc(uid)
      .update({ tier: 'supporter', status: 'cancelled', expiresAt: new Date(0) });
    await reconcile(uid);
    expect((await profile.get()).get('supporterBadgeEligible')).toBe(false);
    expect((await profile.get()).get('membershipMapRingEligible')).toBe(false);
    expect((await profile.get()).data()).not.toHaveProperty('membershipMapRingUntil');
    expect((await firebase.db.collection('subscriptions').doc(uid).get()).get('mapRingRefreshAt')).toBeUndefined();
  });

  it('allows only an owner boolean ring preference, never public eligibility/deadline forgery', async () => {
    const owner = env.authenticatedContext(uid).firestore();
    const other = env.authenticatedContext('ring-viewer').firestore();
    const profile = doc(owner, 'users', uid);
    for (const value of [true, false]) {
      await assertSucceeds(updateDoc(profile, { showMembershipMapRing: value, updatedAt: serverTimestamp() }));
    }
    for (const value of ['true', 1, null, deleteField()]) {
      await assertFails(updateDoc(profile, { showMembershipMapRing: value }));
    }
    for (const actor of [owner, other, env.authenticatedContext('ring-admin', { admin: true }).firestore()]) {
      await assertFails(updateDoc(doc(actor, 'users', uid), { membershipMapRingEligible: true }));
      await assertFails(updateDoc(doc(actor, 'users', uid), { membershipMapRingUntil: new Date('2099-01-01T00:00:00Z') }));
      await assertFails(updateDoc(doc(actor, 'users', uid), { membershipMapRingEligible: deleteField() }));
    }
    await assertFails(updateDoc(doc(other, 'users', uid), { showMembershipMapRing: true }));
    await assertFails(updateDoc(doc(env.authenticatedContext(uid, { suspended: true }).firestore(), 'users', uid), { showMembershipMapRing: true }));
    await assertSucceeds(getDoc(doc(other, 'users', uid)));
    await assertFails(getDoc(doc(other, 'subscriptions', uid)));
    await assertFails(updateDoc(doc(owner, 'subscriptions', uid), { mapRingRefreshAt: new Date() }));
    expect((await getDoc(doc(other, 'users', uid))).data()).not.toHaveProperty('membershipMapRingUntil');
  });

  it('expires the public ring from a private due field without changing access, receipts or preferences', async () => {
    await apply(input);
    const subscription = firebase.db.collection('subscriptions').doc(uid);
    const profile = firebase.db.collection('users').doc(uid);
    await subscription.update({ expiresAt: new Date(0), mapRingRefreshAt: new Date(0) });
    const claims = (await firebase.adminAuth.getUser(uid)).customClaims;
    const receipt = (await subscription.get()).data();
    const { refreshMapRingsOnce } = await import('../subscription/map-ring-scheduled');
    expect(await refreshMapRingsOnce()).toBe(1);
    expect((await profile.get()).data()).toMatchObject({
      membershipMapRingEligible: false, showMembershipMapRing: false, activeMember: true,
    });
    const unchangedReceipt = { ...receipt };
    delete unchangedReceipt.mapRingRefreshAt;
    expect((await subscription.get()).data()).toEqual(unchangedReceipt);
    expect((await firebase.adminAuth.getUser(uid)).customClaims).toEqual(claims);
    expect(await refreshMapRingsOnce()).toBe(0);
  });

  it('re-reads a renewal after stale due selection and leaves its ring and private future deadline intact', async () => {
    await apply(input);
    const subscription = firebase.db.collection('subscriptions').doc(uid);
    await subscription.update({ expiresAt: new Date(0), mapRingRefreshAt: new Date(0) });
    const { runMapRingRefresh } = await import('../subscription/map-ring-core');
    await runMapRingRefresh({
      queryDue: async () => {
        const due = await firebase.db.collection('subscriptions').where('mapRingRefreshAt', '<=', new Date()).get();
        await apply(input); // renewal between selection and the repair transaction
        return due.docs.map((doc) => doc.id);
      },
      refresh: reconcile,
    }, new Date());
    expect((await firebase.db.collection('users').doc(uid).get()).get('membershipMapRingEligible')).toBe(true);
    expect((await subscription.get()).get('mapRingRefreshAt').toMillis()).toBe(input.expiresAt!.getTime());
  });

  it('does not recreate deleted profiles when a private cosmetic deadline is due', async () => {
    const orphanUid = 'map-ring-deleted-profile';
    const subscription = firebase.db.collection('subscriptions').doc(orphanUid);
    await subscription.set({ ...input, userId: orphanUid, expiresAt: new Date(0), mapRingRefreshAt: new Date(0) });
    await reconcile(orphanUid);
    expect((await firebase.db.collection('users').doc(orphanUid).get()).exists).toBe(false);
    expect((await subscription.get()).get('mapRingRefreshAt')).toBeUndefined();
    expect((await subscription.get()).get('purchaseTokenHash')).toBe(input.purchaseTokenHash);
  });

  it('allows boolean owner preference but forbids eligibility forgery, deletion and cross-user changes', async () => {
    await firebase.db.collection('users').doc(uid).update({ supporterBadgeEligible: false });
    const owner = env.authenticatedContext(uid).firestore();
    const other = env.authenticatedContext('crown-viewer').firestore();
    const profile = doc(owner, 'users', uid);
    await assertSucceeds(
      updateDoc(profile, { showSupporterBadge: true, updatedAt: serverTimestamp() }),
    );
    await assertSucceeds(
      updateDoc(profile, { showSupporterBadge: false, updatedAt: serverTimestamp() }),
    );
    for (const value of ['true', 1, null, deleteField()]) {
      await assertFails(updateDoc(profile, { showSupporterBadge: value }));
    }
    await assertFails(updateDoc(profile, { supporterBadgeEligible: true }));
    await assertFails(updateDoc(profile, { supporterBadgeEligible: deleteField() }));
    const admin = env.authenticatedContext('crown-admin', { admin: true }).firestore();
    await assertFails(updateDoc(doc(admin, 'users', uid), { supporterBadgeEligible: true }));
    await assertFails(updateDoc(doc(admin, 'users', uid), { showSupporterBadge: true }));
    await assertFails(updateDoc(doc(other, 'users', uid), { showSupporterBadge: true }));
    await assertFails(
      setDoc(doc(other, 'users', 'crown-viewer'), { supporterBadgeEligible: true }),
    );
    await assertFails(
      updateDoc(doc(env.authenticatedContext(uid, { suspended: true }).firestore(), 'users', uid), {
        showSupporterBadge: true,
      }),
    );
    // Profile projection can be read without loading the private subscription.
    const publicProfile = await assertSucceeds(getDoc(doc(other, 'users', uid)));
    expect(publicProfile.data()?.showSupporterBadge).toBe(false);
    await assertFails(getDoc(doc(other, 'subscriptions', uid)));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'users', uid)));
    await assertSucceeds(getDoc(doc(owner, 'subscriptions', uid)));
    await assertFails(updateDoc(doc(owner, 'subscriptions', uid), { tier: 'supporter' }));
  });
});
