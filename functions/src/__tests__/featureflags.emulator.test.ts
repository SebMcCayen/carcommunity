/**
 * Feature flags emulator integration tests (Phase 9m).
 *
 * Exercises admin-setFeatureFlag end-to-end: the audited merge-write to
 * the flat config/featureFlags document, the closed key namespace,
 * admin-only access, and — the part that matters — that flipping a flag
 * through the callable actually gates a dependent domain callable
 * (notifications-registerPushToken).
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'featureflags-emulator-tests');
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

const flagsDoc = () => adminDb.collection('config').doc('featureFlags');

let adminUser: TestUser;
let user: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'featureflags-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('ff-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  user = await createProvisionedUser('ff-user');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('admin-setFeatureFlag', () => {
  it('merge-writes the flag field and commits an audit record atomically', async () => {
    await signInAs(adminUser);

    // Seed an unrelated flag so we can prove merge semantics.
    await flagsDoc().set({ chat: true }, { merge: true });

    const result = (
      await call('admin-setFeatureFlag', {
        key: 'socialSharing',
        enabled: false,
        reason: 'Delning pausad under granskning.',
      })
    ).data as { key: string; enabled: boolean };
    expect(result).toEqual({ key: 'socialSharing', enabled: false });

    const flags = (await flagsDoc().get()).data()!;
    expect(flags.socialSharing).toBe(false);
    expect(flags.chat).toBe(true); // untouched — merge, not overwrite

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'admin.setFeatureFlag')
      .where('targetId', '==', 'socialSharing')
      .get();
    expect(audit.size).toBe(1);
    // size asserted === 1 above, so docs[0] is present.
    expect(audit.docs[0]!.data().reason).toBe('Delning pausad under granskning.');
    expect(audit.docs[0]!.data().adminId).toBe(adminUser.uid);

    // Restore.
    await call('admin-setFeatureFlag', { key: 'socialSharing', enabled: true });
  });

  it('rejects unknown keys (closed namespace) and non-admin callers', async () => {
    await signInAs(adminUser);
    expect(
      await callableErrorCode(call('admin-setFeatureFlag', { key: 'notAFlag', enabled: true })),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(call('admin-setFeatureFlag', { key: '__proto__', enabled: true })),
    ).toBe('functions/invalid-argument');

    await signInAs(user);
    expect(
      await callableErrorCode(call('admin-setFeatureFlag', { key: 'chat', enabled: false })),
    ).toBe('functions/permission-denied');
  });

  it('actually gates dependent callables end-to-end', async () => {
    await signInAs(adminUser);
    await call('admin-setFeatureFlag', {
      key: 'pushNotifications',
      enabled: false,
      reason: 'Emulator test toggle.',
    });

    try {
      await signInAs(user);
      expect(
        await callableErrorCode(
          call('notifications-registerPushToken', { token: 'ff-token-1', platform: 'android' }),
        ),
      ).toBe('functions/failed-precondition');
    } finally {
      await signInAs(adminUser);
      await call('admin-setFeatureFlag', { key: 'pushNotifications', enabled: true });
    }

    await signInAs(user);
    const registered = (
      await call('notifications-registerPushToken', { token: 'ff-token-1', platform: 'android' })
    ).data as { tokenId: string };
    expect(registered.tokenId).toMatch(/^[a-f0-9]{64}$/);
  });
});
