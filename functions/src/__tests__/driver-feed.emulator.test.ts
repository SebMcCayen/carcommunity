/** Native callable integration; emulator-only. Never targets production. */
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, createUserWithEmailAndPassword } from 'firebase/auth';
import { getFunctions, connectFunctionsEmulator, httpsCallable } from 'firebase/functions';
import { getApps, initializeApp as initializeAdmin } from 'firebase-admin/app';
import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { DetailResponse, ListResponse } from '../driverFeed/contract';

const app = initializeApp({ projectId: 'demo-test', apiKey: 'demo-api-key', appId: 'demo-driver-feed' }, 'driver-feed-tests');
const auth = getAuth(app);
connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
const functions = getFunctions(app, 'europe-west1');
connectFunctionsEmulator(functions, '127.0.0.1', 5001);
const db = getFirestore(getApps()[0] ?? initializeAdmin({ projectId: 'demo-test' }));
const call = async <T>(name: string, data: unknown): Promise<T> => (await httpsCallable<unknown, T>(functions, name)(data)).data;
const prefix = `nativefeed_${Date.now()}`;
const author = `${prefix}_author`;
const threadId = `${prefix}_thread`;
const thread = db.doc(`driverThreads/${threadId}`);
// Firestore persists microsecond precision; retain a sub-millisecond value.
const createdAt = new Timestamp(253000000000, 1000);
const canonical = { displayName: 'Canonical author', onboardingCompletedAt: Timestamp.now() };
const driver = { handle: 'native_driver', onboardingComplete: true };
const content = { authorUid: author, authorName: 'Outdated', authorHandle: 'outdated',
  text: 'Native bridge integration', status: 'active', createdAt, mediaUrls: [], likeCount: 1, replyCount: 2 };
let uid: string;
let ratePath: string;

beforeAll(async () => {
  if (process.env.GCLOUD_PROJECT !== 'demo-test' ||
      !process.env.FIRESTORE_EMULATOR_HOST?.startsWith('127.0.0.1:')) throw new Error('Local demo emulators required.');
  uid = (await createUserWithEmailAndPassword(auth, `${prefix}@example.com`, 'emulator-only-password')).user.uid;
  ratePath = `driverRateLimits/${uid}_nativeFeedRead`;
  // Wait for existing auth provisioning before replacing its fixture profile.
  const deadline = Date.now() + 45_000;
  while (Date.now() < deadline) {
    if ((await db.doc(`users/${uid}`).get()).exists) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error('Auth provisioning did not finish.');
});

beforeEach(async () => {
  const batch = db.batch();
  batch.set(db.doc(`users/${uid}`), canonical);
  batch.set(db.doc(`driverUsers/${uid}`), driver);
  batch.set(db.doc(`users/${author}`), canonical);
  batch.set(db.doc(`driverUsers/${author}`), driver);
  batch.set(thread, content);
  batch.set(thread.collection('replies').doc('reply_00001'), content);
  batch.set(thread.collection('replies').doc('reply_00002'), content);
  batch.delete(db.doc(ratePath));
  batch.delete(db.doc(`blockVisibility/${uid}`));
  for (const path of [
    `driverUsers/${uid}/blockedUsers/${author}`, `driverUsers/${author}/blockedUsers/${uid}`,
    `userBlocks/${uid}/blocked/${author}`, `userBlocks/${author}/blocked/${uid}`,
  ]) batch.delete(db.doc(path));
  await batch.commit();
});
afterAll(async () => { await deleteApp(app); });

describe('driverFeed-list/detail callable emulator', () => {
  it('projects canonical fields and a stable nanosecond cursor through real onCall serialization', async () => {
    const page = await call<ListResponse>('driverFeed-list', { limit: 1 });
    expect(page.posts[0]).toMatchObject({ id: threadId, authorName: 'Canonical author', authorHandle: 'native_driver' });
    expect(page.nextCursor).toEqual({ seconds: createdAt.seconds, nanoseconds: 1000, id: threadId });
    expect((await db.doc(ratePath).get()).data()?.count).toBe(1);
  });
  it('paginates actual Firestore replies with equal timestamps by ascending ID', async () => {
    const first = await call<DetailResponse>('driverFeed-detail', { threadId, limit: 1 });
    expect(first.comments.map((c) => c.id)).toEqual(['reply_00001']);
    const next = await call<DetailResponse>('driverFeed-detail', { threadId, limit: 1, cursor: first.nextCursor });
    expect(next.comments.map((c) => c.id)).toEqual(['reply_00002']);
  });
  it('fails closed when the parent is deleted between reply pages', async () => {
    const first = await call<DetailResponse>('driverFeed-detail', { threadId, limit: 1 });
    await thread.delete();
    await expect(call('driverFeed-detail', { threadId, limit: 1, cursor: first.nextCursor }))
      .rejects.toMatchObject({ code: 'functions/not-found', details: { reason: 'thread-unavailable' } });
  });
  it.each(['driver', 'canonical'])('honors reverse %s blocks without waiting for a mirror', async (kind) => {
    await db.doc(kind === 'driver' ? `driverUsers/${author}/blockedUsers/${uid}` :
      `userBlocks/${author}/blocked/${uid}`).set({});
    const list = await call<ListResponse>('driverFeed-list', { limit: 1 });
    expect(list.posts).toEqual([]);
    expect(list.nextCursor?.id).toBe(threadId);
    await expect(call('driverFeed-detail', { threadId })).rejects.toMatchObject({ code: 'functions/not-found' });
  });
  it('rechecks Driver onboarding and suspension with stable localized reasons', async () => {
    await db.doc(`driverUsers/${uid}`).update({ onboardingComplete: false });
    await expect(call('driverFeed-list', {})).rejects.toMatchObject({
      code: 'functions/failed-precondition', details: { reason: 'driver-onboarding-required' },
    });
    await db.doc(`driverUsers/${uid}`).update({ onboardingComplete: true, status: 'suspended' });
    await expect(call('driverFeed-list', {})).rejects.toMatchObject({
      code: 'functions/permission-denied', details: { reason: 'account-restricted' },
    });
  });
  it('rejects oversized requests without charging and enforces the shared page budget', async () => {
    await expect(call('driverFeed-list', { limit: 21 })).rejects.toMatchObject({ code: 'functions/invalid-argument' });
    expect((await db.doc(ratePath).get()).exists).toBe(false);
    await db.doc(ratePath).set({ bucket: Math.floor(Date.now() / 3_600_000), count: 119 });
    await call('driverFeed-list', { limit: 1 });
    await expect(call('driverFeed-detail', { threadId })).rejects.toMatchObject({ code: 'functions/resource-exhausted' });
  });
});
