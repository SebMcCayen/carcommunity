/**
 * App version config emulator integration tests.
 *
 * Exercises admin-setAppVersion end-to-end: the audited write to the flat
 * config/appVersion document, the complete-state semantics (an omitted
 * minimum resets to "block nobody"), the unsatisfiable-minimum rejection,
 * and admin-only access.
 *
 * Requires the Functions emulator — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
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
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'appversion-emulator-tests');
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);

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

const versionDoc = () => adminDb.collection('config').doc('appVersion');

let adminUser: TestUser;
let user: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'appversion-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('av-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  user = await createProvisionedUser('av-user');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('admin-setAppVersion', () => {
  it('writes the config document and commits an audit record atomically', async () => {
    await signInAs(adminUser);

    const result = (
      await call('admin-setAppVersion', {
        latestVersionCode: 23,
        latestVersionName: '0.8.12',
        minimumSupportedVersionCode: 20,
        reason: 'Release 0.8.12 rullad till 100%.',
      })
    ).data as Record<string, unknown>;
    expect(result).toEqual({
      latestVersionCode: 23,
      latestVersionName: '0.8.12',
      minimumSupportedVersionCode: 20,
    });

    const stored = (await versionDoc().get()).data()!;
    expect(stored.latestVersionCode).toBe(23);
    expect(stored.latestVersionName).toBe('0.8.12');
    expect(stored.minimumSupportedVersionCode).toBe(20);
    expect(stored.updatedAt).toBeTruthy();

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'admin.setAppVersion')
      .where('targetId', '==', 'appVersion')
      .get();
    expect(audit.size).toBeGreaterThanOrEqual(1);
    expect(audit.docs.some((doc) => doc.data().adminId === adminUser.uid)).toBe(true);
  });

  it('treats every call as a complete config: an omitted minimum resets to 0', async () => {
    await signInAs(adminUser);
    await call('admin-setAppVersion', { latestVersionCode: 24, minimumSupportedVersionCode: 24 });
    expect((await versionDoc().get()).data()!.minimumSupportedVersionCode).toBe(24);

    await call('admin-setAppVersion', { latestVersionCode: 25 });
    const stored = (await versionDoc().get()).data()!;
    expect(stored.minimumSupportedVersionCode).toBe(0);
    expect(stored.latestVersionName).toBeNull();
  });

  it('rejects an unsatisfiable minimum, bad payloads and non-admin callers', async () => {
    await signInAs(adminUser);
    expect(
      await callableErrorCode(
        call('admin-setAppVersion', { latestVersionCode: 25, minimumSupportedVersionCode: 26 }),
      ),
    ).toBe('functions/invalid-argument');
    expect(await callableErrorCode(call('admin-setAppVersion', { latestVersionCode: 0 }))).toBe(
      'functions/invalid-argument',
    );
    expect(await callableErrorCode(call('admin-setAppVersion', { latestVersionCode: '25' }))).toBe(
      'functions/invalid-argument',
    );

    await signInAs(user);
    expect(await callableErrorCode(call('admin-setAppVersion', { latestVersionCode: 26 }))).toBe(
      'functions/permission-denied',
    );
  });
});
