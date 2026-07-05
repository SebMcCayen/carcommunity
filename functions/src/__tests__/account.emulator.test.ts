/**
 * Account deletion emulator integration tests (Phase 9p).
 *
 * Exercises both stages: the immediate soft delete via
 * account-deleteAccount (Auth user disabled, users/{uid}.deleted, request
 * record, idempotency, callables closed) and the hard purge via the
 * exported runAccountPurge runner (Firestore trees, owned documents,
 * storage prefixes, Auth user, processed request record retained).
 *
 * Requires the Functions emulator — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= '127.0.0.1:9199';
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
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { getStorage as getAdminStorage } from 'firebase-admin/storage';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runAccountPurge } from '../account/scheduled';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'account-emulator-tests');
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);
const adminBucket = getAdminStorage(adminApp).bucket(`${PROJECT_ID}.appspot.com`);

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

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'account-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('account-deleteAccount (soft delete)', () => {
  it('disables the auth user, marks deleted, records the request — idempotently', async () => {
    const user = await createProvisionedUser('del-user');
    await signInAs(user);

    const result = (await call('account-deleteAccount', { reason: 'Vill radera mitt konto.' }))
      .data as { requestId: string; status: string };
    expect(result).toEqual({ requestId: user.uid, status: 'pending' });

    const request = (
      await adminDb.collection('accountDeletionRequests').doc(user.uid).get()
    ).data()!;
    expect(request.status).toBe('pending');
    expect(request.reason).toBe('Vill radera mitt konto.');

    expect((await adminDb.collection('users').doc(user.uid).get()).data()!.deleted).toBe(true);
    expect((await adminAuth.getUser(user.uid)).disabled).toBe(true);

    // Idempotent replay with the still-valid ID token.
    const replay = (await call('account-deleteAccount', {})).data as { status: string };
    expect(replay.status).toBe('pending');

    // The deleted state closes normal callables for the remaining session.
    expect(await callableErrorCode(call('notifications-markAllRead', {}))).toBe(
      'functions/permission-denied',
    );
  });

  it('works while suspended (deletion is a support path)', async () => {
    const user = await createProvisionedUser('del-suspended');
    await adminDb.collection('users').doc(user.uid).set({ suspended: true }, { merge: true });
    await signInAs(user);

    const result = (await call('account-deleteAccount', {})).data as { status: string };
    expect(result.status).toBe('pending');
  });
});

describe('account purge (hard delete after retention)', () => {
  it('purges due requests: trees, owned docs, storage, auth user; keeps the record', async () => {
    const user = await createProvisionedUser('purge-user');
    const uid = user.uid;
    const dayMs = 24 * 60 * 60 * 1000;
    const now = new Date();

    // Seed data across the purge plan.
    await adminDb.collection('userPrivate').doc(uid).collection('pushTokens').doc('t'.repeat(64))
      .set({ platform: 'android', createdAt: Timestamp.now(), lastSeenAt: Timestamp.now() });
    await adminDb.collection('notifications').doc(uid).collection('items').doc('n1')
      .set({ category: 'system_notice', title: 'x', previewText: 'x', read: false, createdAt: Timestamp.now() });
    await adminDb.collection('pointsLedger').doc(uid).set({ balance: 10, updatedAt: Timestamp.now() });
    await adminDb.collection('vehicles').add({ userId: uid, make: 'Volvo' });
    await adminDb.collection('rides').add({ userId: uid, distanceMeters: 1000 });
    await adminBucket.file(`profileImages/${uid}/avatar.png`).save(Buffer.from('img'));
    await adminBucket.file(`rideRoutes/${uid}/ride-1/route.bin`).save(Buffer.from('gps'));

    // A due (31-day-old pending) request and soft-delete state.
    await adminDb.collection('accountDeletionRequests').doc(uid).set({
      userId: uid,
      reason: null,
      status: 'pending',
      createdAt: Timestamp.fromDate(new Date(now.getTime() - 31 * dayMs)),
    });

    // A NOT-due request for another user must be untouched.
    const fresh = await createProvisionedUser('purge-fresh');
    await adminDb.collection('accountDeletionRequests').doc(fresh.uid).set({
      userId: fresh.uid,
      reason: null,
      status: 'pending',
      createdAt: Timestamp.fromDate(new Date(now.getTime() - 1 * dayMs)),
    });

    const result = await runAccountPurge(now);
    expect(result.purgedUids).toContain(uid);
    expect(result.purgedUids).not.toContain(fresh.uid);

    // Firestore trees and owned docs gone.
    expect((await adminDb.collection('users').doc(uid).get()).exists).toBe(false);
    expect((await adminDb.collection('userPrivate').doc(uid).collection('pushTokens').get()).size).toBe(0);
    expect((await adminDb.collection('notifications').doc(uid).collection('items').get()).size).toBe(0);
    expect((await adminDb.collection('pointsLedger').doc(uid).get()).exists).toBe(false);
    expect((await adminDb.collection('vehicles').where('userId', '==', uid).get()).size).toBe(0);
    expect((await adminDb.collection('rides').where('userId', '==', uid).get()).size).toBe(0);

    // Storage prefixes gone.
    const [profileFiles] = await adminBucket.getFiles({ prefix: `profileImages/${uid}/` });
    expect(profileFiles).toHaveLength(0);

    // Auth user gone; proof-of-deletion record retained as processed.
    await expect(adminAuth.getUser(uid)).rejects.toMatchObject({ code: 'auth/user-not-found' });
    const record = (await adminDb.collection('accountDeletionRequests').doc(uid).get()).data()!;
    expect(record.status).toBe('processed');
    expect(record.processedAt).toBeInstanceOf(Timestamp);

    // Fresh user untouched.
    expect((await adminDb.collection('users').doc(fresh.uid).get()).exists).toBe(true);
  });
});
