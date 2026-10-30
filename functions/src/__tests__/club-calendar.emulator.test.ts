/** Real callable integration. CI demo Auth/Firestore/Functions/RTDB only; no production. */
process.env.GCLOUD_PROJECT ??= 'demo-test';
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';

import { initializeApp, deleteApp } from 'firebase/app';
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  signOut,
} from 'firebase/auth';
import { connectFunctionsEmulator, getFunctions, httpsCallable } from 'firebase/functions';
import { getApps, initializeApp as initializeAdmin } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

const project = process.env.GCLOUD_PROJECT;
if (
  !/^demo-[a-z0-9-]+$/.test(project) ||
  ![
    process.env.FIREBASE_AUTH_EMULATOR_HOST,
    process.env.FIRESTORE_EMULATOR_HOST,
    process.env.FIREBASE_DATABASE_EMULATOR_HOST,
  ].every((host) => /^127\.0\.0\.1:\d+$/.test(host ?? ''))
)
  throw new Error('Calendar callable tests require loopback demo emulators.');

const app = initializeApp(
  { projectId: project, apiKey: 'demo-api-key', appId: 'demo-club-calendar' },
  'club-calendar-tests',
);
const auth = getAuth(app);
connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, {
  disableWarnings: true,
});
const functions = getFunctions(app, 'europe-west1');
connectFunctionsEmulator(functions, '127.0.0.1', 5001);
const adminApp = getApps()[0] ?? initializeAdmin({ projectId: project });
const db = getFirestore(adminApp),
  adminAuth = getAdminAuth(adminApp);
const config = db.doc('config/sharedClubs'),
  policy = db.doc('carCommunityPolicy/access');
const prefix = `club_calendar_${Date.now()}`;
let originalConfig: FirebaseFirestore.DocumentData | undefined;
let originalPolicy: FirebaseFirestore.DocumentData | undefined;
let uid: string, clubId: string;
let serial = 0;
type Subscription = { active: boolean; version: number; url?: string };
const call = async (name: string, data: unknown): Promise<Subscription> =>
  (await httpsCallable<unknown, Subscription>(functions, name)(data)).data;

beforeAll(async () => {
  [originalConfig, originalPolicy] = await Promise.all([
    config.get().then((snapshot) => snapshot.data()),
    policy.get().then((snapshot) => snapshot.data()),
  ]);
});

beforeEach(async () => {
  await signOut(auth);
  uid = (
    await createUserWithEmailAndPassword(
      auth,
      `${prefix}_${++serial}@example.com`,
      'emulator-only-password',
    )
  ).user.uid;
  clubId = `calendar_${uid}`;
  const deadline = Date.now() + 45000;
  while (!(await db.doc(`users/${uid}`).get()).exists) {
    if (Date.now() > deadline) throw new Error('Calendar account provisioning did not finish.');
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  const joinedAt = Timestamp.fromMillis(Date.now() - 60000),
    intervalId = `calendar_${uid}`;
  const batch = db.batch();
  batch.set(config, {
    enabled: true,
    activatedAt: Timestamp.fromMillis(1),
    calendarSubscriptionsReady: true,
  });
  batch.set(policy, { enforceInitialized: true });
  batch.set(db.doc(`users/${uid}`), {
    onboardingCompletedAt: Timestamp.fromMillis(1),
    activeMember: false,
  });
  // The standard product wrapper must initialize this projection before the
  // domain guard runs; directly invoking the domain class cannot prove that.
  batch.set(db.doc(`carCommunityAccess/${uid}`), { initialized: false, suspended: false });
  batch.set(db.doc(`driverClubs/${clubId}`), {
    status: 'active',
    schemaVersion: 2,
    ownerUid: 'calendar-owner',
  });
  batch.set(db.doc(`driverClubs/${clubId}/members/${uid}`), { role: 'member', joinedAt });
  batch.set(db.doc(`driverClubAccounts/${uid}`), {
    activeClubId: clubId,
    role: 'member',
    joinedAt,
    intervalId,
  });
  batch.set(db.doc(`driverClubAccounts/${uid}/history/${intervalId}`), {
    clubId,
    joinedAt,
    leftAt: null,
  });
  await batch.commit();
});

afterAll(async () => {
  await signOut(auth);
  await Promise.all([
    originalConfig ? config.set(originalConfig) : config.delete(),
    originalPolicy ? policy.set(originalPolicy) : policy.delete(),
  ]);
  await deleteApp(app);
});

describe('clubCalendar-manage real callable boundary', () => {
  it('requires Firebase authentication', async () => {
    await signOut(auth);
    await expect(call('clubCalendar-manage', { action: 'status', clubId })).rejects.toMatchObject({
      code: 'functions/unauthenticated',
    });
  });

  it('initializes fresh CC authority for a free ordinary member and issues/revokes a hash-only link', async () => {
    const status = await call('clubCalendar-manage', { action: 'status', clubId });
    expect(status.active).toBe(false);
    expect(status.version).toBe(0);
    expect((await db.doc(`carCommunityAccess/${uid}`).get()).get('initialized')).toBe(true);
    const issued = await call('clubCalendar-manage', {
      action: 'issue',
      clubId,
      expectedVersion: status.version,
    });
    expect(issued.active).toBe(true);
    expect(issued.version).toBe(1);
    // Do not echo a bearer into a failed assertion or CI log.
    expect(typeof issued.url === 'string').toBe(true);
    const url = new URL(issued.url!);
    expect(url.origin).toBe(`https://europe-west1-${project}.cloudfunctions.net`);
    expect(url.pathname).toBe('/clubCalendar-feed/calendar.ics');
    const token = url.searchParams.get('token') ?? '';
    expect(/^[A-Za-z0-9_-]{43}$/.test(token)).toBe(true);
    const state = await db
      .collection('clubCalendarSubscriptions')
      .where('uid', '==', uid)
      .limit(2)
      .get();
    const locators = await db
      .collection('clubCalendarTokens')
      .where('uid', '==', uid)
      .limit(2)
      .get();
    expect(state.size).toBe(1);
    expect(locators.size).toBe(1);
    expect(/^[a-f0-9]{64}$/.test(String(state.docs[0]!.get('tokenHash')))).toBe(true);
    expect(
      JSON.stringify([...state.docs, ...locators.docs].map((doc) => doc.data())).includes(token),
    ).toBe(false);
    const revoked = await call('clubCalendar-manage', {
      action: 'revoke',
      clubId,
      expectedVersion: issued.version,
    });
    expect(revoked).toEqual({ active: false, version: 2 });
    expect(
      (await db.collection('clubCalendarTokens').where('uid', '==', uid).limit(2).get()).empty,
    ).toBe(true);
  });

  it('fails closed when readiness is off but still permits idempotent authorized revoke', async () => {
    const status = await call('clubCalendar-manage', { action: 'status', clubId });
    const issued = await call('clubCalendar-manage', {
      action: 'issue',
      clubId,
      expectedVersion: status.version,
    });
    await config.update({ calendarSubscriptionsReady: false });
    await expect(call('clubCalendar-manage', { action: 'status', clubId })).rejects.toMatchObject({
      code: 'functions/failed-precondition',
    });
    await expect(
      call('clubCalendar-manage', { action: 'issue', clubId, expectedVersion: issued.version }),
    ).rejects.toMatchObject({ code: 'functions/failed-precondition' });
    const revoked = await call('clubCalendar-manage', {
      action: 'revoke',
      clubId,
      expectedVersion: issued.version,
    });
    expect(revoked.active).toBe(false);
    expect(
      await call('clubCalendar-manage', {
        action: 'revoke',
        clubId,
        expectedVersion: revoked.version,
      }),
    ).toEqual(revoked);
  });

  it('fresh CC revocation blocks every manage action, including domain-only cleanup', async () => {
    const status = await call('clubCalendar-manage', { action: 'status', clubId });
    await db.doc(`carCommunityAccess/${uid}`).set({ suspended: true }, { merge: true });
    for (const action of ['status', 'issue', 'revoke']) {
      await expect(
        call('clubCalendar-manage', {
          action,
          clubId,
          ...(action === 'status' ? {} : { expectedVersion: status.version }),
        }),
      ).rejects.toMatchObject({
        code: 'functions/permission-denied',
        details: { reason: 'carcommunity_access_restricted' },
      });
    }
  });

  it('does not grant membership from a cached status; missing membership denies status/issue, not authorized revoke', async () => {
    const status = await call('clubCalendar-manage', { action: 'status', clubId });
    const issued = await call('clubCalendar-manage', {
      action: 'issue',
      clubId,
      expectedVersion: status.version,
    });
    await db.doc(`driverClubs/${clubId}/members/${uid}`).delete();
    await expect(call('clubCalendar-manage', { action: 'status', clubId })).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
    await expect(
      call('clubCalendar-manage', { action: 'issue', clubId, expectedVersion: issued.version }),
    ).rejects.toMatchObject({ code: 'functions/permission-denied' });
    expect(
      (
        await call('clubCalendar-manage', {
          action: 'revoke',
          clubId,
          expectedVersion: issued.version,
        })
      ).active,
    ).toBe(false);
  });

  it('rejects an Auth-disabled account even while its old client token remains cached', async () => {
    await call('clubCalendar-manage', { action: 'status', clubId });
    await adminAuth.updateUser(uid, { disabled: true });
    await expect(call('clubCalendar-manage', { action: 'status', clubId })).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
  });
});
