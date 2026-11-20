/** Real transactions and rules. No production credentials, test-hook API or fake consent guard. */
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= '127.0.0.1:9199';
process.env.GCLOUD_PROJECT ??= 'demo-test';
process.env.FUNCTIONS_EMULATOR = 'true';

import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeAll, afterAll, afterEach, describe, expect, it, vi } from 'vitest';
import { getApps, initializeApp } from 'firebase-admin/app';
import { getFirestore, Timestamp, type Transaction } from 'firebase-admin/firestore';
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { doc, updateDoc, serverTimestamp } from 'firebase/firestore';
import { PARTNER_INTERACTION_TYPES } from '../partnerInsights/insights-core';

if (
  process.env.GCLOUD_PROJECT !== 'demo-test' ||
  !/^127\.0\.0\.1:\d+$/.test(process.env.FIRESTORE_EMULATOR_HOST ?? '')
)
  throw Error('Disposable local demo emulators required.');
if (!getApps().length)
  initializeApp({
    projectId: 'demo-test',
    storageBucket: 'demo-test.appspot.com',
    databaseURL: 'https://demo-test-default-rtdb.firebaseio.com',
  });
const db = getFirestore();
const prefix = `consent-${randomUUID().slice(0, 8)}`;
const fixtures: { uid: string; companyId: string }[] = [];
let write: typeof import('../partnerInsights/recordInteraction').writeInteractionEvent;
let rules: RulesTestEnvironment;
const granted = () => ({
  anonymousPartnerStatsOptIn: true,
  anonymousPartnerStatsConsentVersion: 1,
  anonymousPartnerStatsConsentAt: Timestamp.fromMillis(Date.now() - 60_000),
});
async function fixture(privacy: Record<string, unknown> = granted()) {
  const uid = `${prefix}-${randomUUID()}`,
    companyId = `${prefix}-co-${randomUUID()}`;
  fixtures.push({ uid, companyId });
  await Promise.all([
    db.doc(`users/${uid}`).set({ role: 'member', suspended: false, deleted: false }),
    db.doc(`userPrivate/${uid}`).set(privacy),
    db.doc(`companies/${companyId}`).set({ status: 'active', name: 'Consent fixture' }),
  ]);
  return { uid, companyId };
}
async function events(companyId: string) {
  return db.collection('partnerInsightsEvents').where('companyId', '==', companyId).get();
}
beforeAll(async () => {
  ({ writeInteractionEvent: write } = await import('../partnerInsights/recordInteraction'));
  rules = await initializeTestEnvironment({
    projectId: 'demo-test',
    firestore: {
      host: '127.0.0.1',
      port: 8080,
      rules: readFileSync(resolve(__dirname, '../../../firebase/firestore.rules'), 'utf8'),
    },
  });
});
afterEach(() => vi.restoreAllMocks());
afterAll(async () => {
  await rules?.cleanup();
  for (const f of fixtures) {
    const raw = await events(f.companyId);
    const batch = db.batch();
    for (const e of raw.docs) batch.delete(e.ref);
    for (const c of ['users', 'userPrivate', 'accountDeletionRequests', 'accountPurgeProgress'])
      batch.delete(db.doc(`${c}/${f.uid}`));
    batch.delete(db.doc(`companies/${f.companyId}`));
    await batch.commit();
  }
});

describe('central optional analytics consent', () => {
  it.each(PARTNER_INTERACTION_TYPES)(
    'declines legacy analytics for %s',
    async (interactionType) => {
      const { uid, companyId } = await fixture({ anonymousPartnerStatsOptIn: true });
      expect(await write(uid, { companyId, interactionType })).toBe(false);
      expect((await events(companyId)).empty).toBe(true);
    },
  );
  it.each([
    {},
    { anonymousPartnerStatsOptIn: false },
    { anonymousPartnerStatsOptIn: true, anonymousPartnerStatsConsentVersion: 1 },
    { ...granted(), anonymousPartnerStatsConsentVersion: true },
    { ...granted(), anonymousPartnerStatsConsentVersion: 2 },
    { ...granted(), anonymousPartnerStatsConsentAt: 'yesterday' },
    { ...granted(), anonymousPartnerStatsConsentAt: Timestamp.fromMillis(Date.now() + 86400000) },
  ])('does not manufacture consent from incomplete or malformed data %#', async (privacy) => {
    const { uid, companyId } = await fixture(privacy);
    expect(await write(uid, { companyId, interactionType: 'map_view' })).toBe(false);
    expect((await events(companyId)).empty).toBe(true);
  });
  it('records only consent-version provenance, retaining scoped deduplication', async () => {
    const { uid, companyId } = await fixture();
    for (const interactionType of PARTNER_INTERACTION_TYPES) {
      expect(await write(uid, { companyId, interactionType })).toBe(true);
      expect(await write(uid, { companyId, interactionType })).toBe(false);
    }
    const rows = await events(companyId);
    expect(rows.size).toBe(PARTNER_INTERACTION_TYPES.length);
    for (const row of rows.docs) {
      expect(row.data().consentVersion).toBe(1);
      expect(Object.keys(row.data()).sort()).toEqual([
        'aggregationDate',
        'companyId',
        'consentVersion',
        'expiresAt',
        'interactionType',
        'occurredAt',
        'relatedOfferId',
        'userReferenceHash',
      ]);
      expect(JSON.stringify(row.data())).not.toContain(uid);
    }
  });
  it.each(['revoke', 'pending deletion', 'purge', 'deleted profile', 'missing profile'] as const)(
    'checks authoritative %s immediately before writing',
    async (change) => {
      const { uid, companyId } = await fixture();
      const original = db.runTransaction.bind(db);
      let reached = false;
      // Change authority AFTER the writer's company/offer validation, BEFORE
      // opening the actual transaction. This avoids manufacturing a Firestore
      // retry or waiting on an external write behind a pessimistic read lock.
      vi.spyOn(db, 'runTransaction').mockImplementation((async (
        fn: (tx: Transaction) => Promise<unknown>,
      ) => {
        if (!reached) {
          expect(
            (await db.doc(`userPrivate/${uid}`).get()).data()!.anonymousPartnerStatsOptIn,
          ).toBe(true);
          expect((await db.doc(`users/${uid}`).get()).exists).toBe(true);
          reached = true;
          if (change === 'revoke')
            await db.doc(`userPrivate/${uid}`).update({ anonymousPartnerStatsOptIn: false });
          if (change === 'pending deletion')
            await db.doc(`accountDeletionRequests/${uid}`).set({ status: 'pending' });
          if (change === 'purge')
            await db.doc(`accountPurgeProgress/${uid}`).set({ partnerErasureActive: true });
          if (change === 'deleted profile') await db.doc(`users/${uid}`).update({ deleted: true });
          if (change === 'missing profile') await db.doc(`users/${uid}`).delete();
        }
        return original(fn);
      }) as typeof db.runTransaction);
      const result = await write(uid, { companyId, interactionType: 'profile_view' });
      expect(reached).toBe(true);
      if (change === 'revoke')
        expect((await db.doc(`userPrivate/${uid}`).get()).data()!.anonymousPartnerStatsOptIn).toBe(
          false,
        );
      if (change === 'pending deletion')
        expect((await db.doc(`accountDeletionRequests/${uid}`).get()).data()!.status).toBe(
          'pending',
        );
      if (change === 'purge')
        expect(
          (await db.doc(`accountPurgeProgress/${uid}`).get()).data()!.partnerErasureActive,
        ).toBe(true);
      if (change === 'deleted profile')
        expect((await db.doc(`users/${uid}`).get()).data()!.deleted).toBe(true);
      if (change === 'missing profile')
        expect((await db.doc(`users/${uid}`).get()).exists).toBe(false);
      expect(result).toBe(false);
      expect((await events(companyId)).empty).toBe(true);
    },
  );
});

describe('owner consent rules and legacy compatibility', () => {
  it('permits an explicit atomic grant, legacy revoke, but refuses stale legacy regrant', async () => {
    const { uid } = await fixture({ anonymousPartnerStatsOptIn: false });
    const ref = doc(rules.authenticatedContext(uid).firestore(), 'userPrivate', uid);
    await assertFails(updateDoc(ref, { anonymousPartnerStatsOptIn: true }));
    await assertSucceeds(
      updateDoc(ref, {
        anonymousPartnerStatsOptIn: true,
        anonymousPartnerStatsConsentVersion: 1,
        anonymousPartnerStatsConsentAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      }),
    );
    const saved = (await db.doc(`userPrivate/${uid}`).get()).data()!;
    expect(saved.anonymousPartnerStatsConsentAt).toBeInstanceOf(Timestamp);
    await assertSucceeds(updateDoc(ref, { anonymousPartnerStatsOptIn: false }));
    await assertFails(updateDoc(ref, { anonymousPartnerStatsOptIn: true }));
    expect((await db.doc(`userPrivate/${uid}`).get()).data()!.anonymousPartnerStatsOptIn).toBe(
      false,
    );
    await assertSucceeds(
      updateDoc(ref, {
        anonymousPartnerStatsOptIn: true,
        anonymousPartnerStatsConsentVersion: 1,
        anonymousPartnerStatsConsentAt: serverTimestamp(),
      }),
    );
  });
  it.each([
    { anonymousPartnerStatsConsentVersion: 2 },
    { anonymousPartnerStatsConsentVersion: true },
    { anonymousPartnerStatsConsentAt: new Date('2020-01-01') },
    { anonymousPartnerStatsConsentAt: 'now' },
  ])('rejects fabricated consent evidence %#', async (bad) => {
    const { uid } = await fixture({ anonymousPartnerStatsOptIn: false });
    const ref = doc(rules.authenticatedContext(uid).firestore(), 'userPrivate', uid);
    await assertFails(
      updateDoc(ref, {
        anonymousPartnerStatsOptIn: true,
        anonymousPartnerStatsConsentVersion: 1,
        anonymousPartnerStatsConsentAt: serverTimestamp(),
        ...bad,
      }),
    );
  });
  it('keeps consent private and cannot grant on behalf of another account', async () => {
    const { uid } = await fixture({ anonymousPartnerStatsOptIn: false });
    const ref = doc(rules.authenticatedContext(`${prefix}-other`).firestore(), 'userPrivate', uid);
    await assertFails(
      updateDoc(ref, {
        anonymousPartnerStatsOptIn: true,
        anonymousPartnerStatsConsentVersion: 1,
        anonymousPartnerStatsConsentAt: serverTimestamp(),
      }),
    );
  });
});
