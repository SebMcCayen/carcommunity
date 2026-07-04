/**
 * Moderation / audit emulator integration tests (Phase 9o).
 *
 * Exercises admin-warnUser end-to-end: the atomic moderationActions +
 * adminAuditEvents pair, the essential account_warning in-app notice
 * (delivered through the 9l writer with an idempotent producer ID), the
 * no-access-change guarantee, and the owner-protection guard.
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'moderation-emulator-tests');
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

let adminUser: TestUser;
let target: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'moderation-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('warn-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  target = await createProvisionedUser('warn-target');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('admin-warnUser', () => {
  it('writes the action + audit pair, notifies the user, and never touches access', async () => {
    await signInAs(adminUser);
    const result = (
      await call('admin-warnUser', { targetUid: target.uid, reason: 'Olämpligt språk i chatten.' })
    ).data as { targetUid: string; actionId: string };
    expect(result.targetUid).toBe(target.uid);

    const action = (
      await adminDb.collection('moderationActions').doc(result.actionId).get()
    ).data()!;
    expect(action).toMatchObject({
      targetUserId: target.uid,
      actorUserId: adminUser.uid,
      actionType: 'warning',
      reason: 'Olämpligt språk i chatten.',
    });

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'user.warn')
      .where('targetId', '==', target.uid)
      .get();
    expect(audit.size).toBe(1);

    // Essential account_warning notice, idempotent producer ID.
    const notice = (
      await adminDb
        .collection('notifications')
        .doc(target.uid)
        .collection('items')
        .doc(`warn-${result.actionId}`)
        .get()
    ).data()!;
    expect(notice.category).toBe('account_warning');
    expect(notice.body).toBe('Olämpligt språk i chatten.');

    // A warning NEVER restricts access (deliberate legacy deviation).
    const profile = (await adminDb.collection('users').doc(target.uid).get()).data()!;
    expect(profile.suspended).not.toBe(true);
    const claims = (await adminAuth.getUser(target.uid)).customClaims ?? {};
    expect(claims.suspended).toBeUndefined();
  });

  it('warns suspended users too (essential notices bypass suspension)', async () => {
    const suspended = await createProvisionedUser('warn-suspended');
    // createProvisionedUser signs in as the new user — switch back.
    await signInAs(adminUser);
    await call('admin-suspendUser', { targetUid: suspended.uid, reason: 'Testavstängning.' });

    const result = (
      await call('admin-warnUser', { targetUid: suspended.uid, reason: 'Ytterligare varning.' })
    ).data as { actionId: string };
    const notice = await adminDb
      .collection('notifications')
      .doc(suspended.uid)
      .collection('items')
      .doc(`warn-${result.actionId}`)
      .get();
    expect(notice.exists).toBe(true);
  });

  it('enforces admin-only access and owner protection', async () => {
    await signInAs(target);
    expect(
      await callableErrorCode(call('admin-warnUser', { targetUid: adminUser.uid, reason: 'x' })),
    ).toBe('functions/permission-denied');

    const ownerUser = await createProvisionedUser('warn-owner');
    await adminDb.collection('users').doc(ownerUser.uid).set({ role: 'owner' }, { merge: true });
    // createProvisionedUser signs in as the new user — switch back so the
    // denial below is the OWNER-PROTECTION guard, not a non-admin caller.
    await signInAs(adminUser);
    expect(
      await callableErrorCode(
        call('admin-warnUser', { targetUid: ownerUser.uid, reason: 'Should be blocked.' }),
      ),
    ).toBe('functions/permission-denied');
  });
});
