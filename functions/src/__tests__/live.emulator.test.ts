/**
 * Live location emulator integration tests (Phase 10).
 *
 * Exercises the full lifecycle end-to-end: start → update → marker in
 * RTDB → stop/hideMeNow removal, staleness rejection, member gating and
 * the liveLocation feature flag, and the TTL sweep via runLiveCleanup.
 *
 * Requires the Functions + Database emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { deleteApp, FirebaseError, initializeApp, type FirebaseApp } from 'firebase/app';
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  signInWithEmailAndPassword,
  type Auth,
} from 'firebase/auth';
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { getDatabase as getAdminDatabase } from 'firebase-admin/database';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runLiveCleanup } from '../live/scheduled';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ??
  initializeAdminApp(
    { projectId: PROJECT_ID, databaseURL: `http://${EMULATOR_HOST}:9000?ns=${PROJECT_ID}-default-rtdb` },
    'live-emulator-tests',
  );
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);
const adminRtdb = getAdminDatabase(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

async function pollUntil<T>(
  read: () => Promise<T | undefined>,
  timeoutMs = 30_000,
  intervalMs = 250,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

async function callableErrorCode(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
    return 'no-error';
  } catch (error) {
    if (error instanceof FirebaseError) return error.code;
    throw error;
  }
}

async function createProvisionedUser(prefix: string): Promise<TestUser> {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'password-123';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  const uid = credential.user.uid;
  await pollUntil(async () => {
    const snap = await adminDb.collection('users').doc(uid).get();
    return snap.exists ? true : undefined;
  });
  return { uid, email, password };
}

async function makeMember(user: TestUser): Promise<void> {
  await adminAuth.setCustomUserClaims(user.uid, { activeMember: true });
  await adminDb.collection('users').doc(user.uid).set({ activeMember: true }, { merge: true });
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

const coordinate = (recordedAt: string) => ({
  latitude: 59.334,
  longitude: 18.063,
  accuracyMeters: 12,
  speedMetersPerSecond: 8.3,
  recordedAt,
});

let member: TestUser;
let freeUser: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'live-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  member = await createProvisionedUser('live-member');
  await makeMember(member);
  freeUser = await createProvisionedUser('live-free');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('live session lifecycle', () => {
  it('start → update → marker exists → stop removes it', async () => {
    await signInAs(member);
    await adminDb
      .collection('users')
      .doc(member.uid)
      .set({ displayName: 'Sebbe' }, { merge: true });

    const started = (await call('live-startSession', { duration: '1h' })).data as {
      sessionId: string;
      expiresAt: string;
    };
    expect(started.sessionId).toBeTruthy();

    await call('live-updatePosition', { coordinate: coordinate(new Date().toISOString()) });

    const latest = (await adminRtdb.ref(`liveLocation/${member.uid}/latest`).get()).val();
    expect(latest).toMatchObject({
      latitude: 59.334,
      longitude: 18.063,
      sessionId: started.sessionId,
      displayName: 'Sebbe',
      expiresAt: started.expiresAt,
    });

    await call('live-stopSession', {});
    expect((await adminRtdb.ref(`liveLocation/${member.uid}/latest`).get()).exists()).toBe(false);
    const session = (await adminRtdb.ref(`liveLocation/${member.uid}/session`).get()).val();
    expect(session.status).toBe('stopped');
    expect(session.stopReason).toBe('user_stop');
  });

  it('rejects stale positions and updates without an active session', async () => {
    await signInAs(member);
    // Previous test stopped the session.
    expect(
      await callableErrorCode(
        call('live-updatePosition', { coordinate: coordinate(new Date().toISOString()) }),
      ),
    ).toBe('functions/failed-precondition');

    await call('live-startSession', { duration: '1h' });
    // Restarting removed any previous marker immediately.
    expect((await adminRtdb.ref(`liveLocation/${member.uid}/latest`).get()).exists()).toBe(false);
    const stale = new Date(Date.now() - 90_000).toISOString();
    expect(
      await callableErrorCode(call('live-updatePosition', { coordinate: coordinate(stale) })),
    ).toBe('functions/invalid-argument');
    await call('live-stopSession', {});
  });

  it('is member-gated and honors the liveLocation flag', async () => {
    await signInAs(freeUser);
    expect(await callableErrorCode(call('live-startSession', { duration: '1h' }))).toBe(
      'functions/permission-denied',
    );

    await signInAs(member);
    await adminDb
      .collection('config')
      .doc('featureFlags')
      .set({ liveLocation: false }, { merge: true });
    try {
      expect(await callableErrorCode(call('live-startSession', { duration: '1h' }))).toBe(
        'functions/failed-precondition',
      );
    } finally {
      await adminDb
        .collection('config')
        .doc('featureFlags')
        .set({ liveLocation: true }, { merge: true });
    }
  });

  it('hideMeNow removes the marker and works while suspended', async () => {
    const sharer = await createProvisionedUser('live-hide');
    await makeMember(sharer);
    await signInAs(sharer);
    await call('live-startSession', { duration: '1h' });
    await call('live-updatePosition', { coordinate: coordinate(new Date().toISOString()) });

    // Suspend mid-session; hideMeNow must still work.
    await adminDb.collection('users').doc(sharer.uid).set({ suspended: true }, { merge: true });
    const result = (await call('live-hideMeNow', {})).data as { status: string };
    expect(result.status).toBe('stopped');
    expect((await adminRtdb.ref(`liveLocation/${sharer.uid}/latest`).get()).exists()).toBe(false);
    const session = (await adminRtdb.ref(`liveLocation/${sharer.uid}/session`).get()).val();
    expect(session.stopReason).toBe('hide_me_now');
  });
});

describe('live TTL sweep', () => {
  it('expires overdue sessions and removes silent-stale markers', async () => {
    const now = new Date();
    const iso = (msAgo: number) => new Date(now.getTime() - msAgo).toISOString();

    // Overdue session with a marker.
    await adminRtdb.ref('liveLocation/ttl-expired').set({
      session: {
        id: 's-exp',
        status: 'active',
        duration: '1h',
        startedAt: iso(2 * 3600_000),
        expiresAt: iso(3600_000),
        stoppedAt: null,
      },
      latest: { latitude: 1, longitude: 1, recordedAt: iso(3600_000), sessionId: 's-exp' },
    });
    // Active session whose client went silent 20 minutes ago.
    await adminRtdb.ref('liveLocation/ttl-silent').set({
      session: {
        id: 's-silent',
        status: 'active',
        duration: '4h',
        startedAt: iso(30 * 60_000),
        expiresAt: new Date(now.getTime() + 3 * 3600_000).toISOString(),
        stoppedAt: null,
      },
      latest: { latitude: 2, longitude: 2, recordedAt: iso(20 * 60_000), sessionId: 's-silent' },
    });
    // Healthy session updated seconds ago.
    await adminRtdb.ref('liveLocation/ttl-healthy').set({
      session: {
        id: 's-ok',
        status: 'active',
        duration: '4h',
        startedAt: iso(60_000),
        expiresAt: new Date(now.getTime() + 3 * 3600_000).toISOString(),
        stoppedAt: null,
      },
      latest: { latitude: 3, longitude: 3, recordedAt: iso(10_000), sessionId: 's-ok' },
    });

    const result = await runLiveCleanup(now);
    expect(result.expiredSessions).toBeGreaterThanOrEqual(1);
    expect(result.removedMarkers).toBeGreaterThanOrEqual(2);

    expect((await adminRtdb.ref('liveLocation/ttl-expired/session/status').get()).val()).toBe(
      'expired',
    );
    expect((await adminRtdb.ref('liveLocation/ttl-expired/latest').get()).exists()).toBe(false);
    expect((await adminRtdb.ref('liveLocation/ttl-silent/latest').get()).exists()).toBe(false);
    expect((await adminRtdb.ref('liveLocation/ttl-silent/session/status').get()).val()).toBe(
      'active',
    );
    expect((await adminRtdb.ref('liveLocation/ttl-healthy/latest').get()).exists()).toBe(true);
  });
});
