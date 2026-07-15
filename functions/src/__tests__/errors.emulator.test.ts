/**
 * Client-error reporting emulator integration tests.
 *
 * Exercises the deployed-in-emulator callable end-to-end:
 * - `errors-reportClientError` (auth gating, input validation, per-user rate
 *   limit, private clientErrorReports record with the uid, and the
 *   adminAuditEvents entry with action `client.error`).
 *
 * The GitHub REST call (in the errors-onClientErrorReport trigger) is NOT made
 * in the emulator: the GITHUB_ISSUE_TOKEN secret is unset, so the helper logs +
 * returns null and never hits the network. This suite asserts the callable's
 * durable writes; the dedup logic is covered by clientErrors-core.test.ts.
 *
 * Requires the Functions + Firestore emulators — run via:
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
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'errors-emulator-tests');
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

const validError = {
  feature: 'messages.conversationList',
  message: 'Conversation inbox listener failed',
  code: 'FAILED_PRECONDITION',
  appVersion: '1.2.3',
  osVersion: 'Android 14',
  deviceModel: 'Pixel 8',
  platform: 'android',
};

let reporter: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'errors-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  reporter = await createProvisionedUser('error-reporter');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('errors-reportClientError', () => {
  it('rejects unauthenticated callers', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('errors-reportClientError', validError))).toBe(
      'functions/unauthenticated',
    );
  });

  it('rejects an invalid payload', async () => {
    await signInAs(reporter);
    expect(await callableErrorCode(call('errors-reportClientError', { feature: '' }))).toBe(
      'functions/invalid-argument',
    );
  });

  it('captures the error privately with the uid AND writes an audit-log entry', async () => {
    await signInAs(reporter);
    const result = await call('errors-reportClientError', validError);
    const data = result.data as { reportId: string };
    expect(data.reportId).toBeTruthy();

    const doc = (await adminDb.collection('clientErrorReports').doc(data.reportId).get()).data()!;
    expect(doc.uid).toBe(reporter.uid);
    expect(doc.feature).toBe(validError.feature);
    expect(doc.code).toBe(validError.code);
    expect(doc.platform).toBe('android');
    expect(typeof doc.fingerprint).toBe('string');

    // The audit-log entry (adminAuditEvents) makes the error visible in the
    // admin Audit Log; the uid is the audit event's adminId.
    const audit = await pollUntil(async () => {
      const snap = await adminDb
        .collection('adminAuditEvents')
        .where('action', '==', 'client.error')
        .where('adminId', '==', reporter.uid)
        .limit(1)
        .get();
      return snap.empty ? undefined : snap.docs[0]!.data();
    });
    expect(audit.targetId).toBe(doc.fingerprint);
    expect(audit.reason).toBe(validError.feature);
    expect((audit.details as { code?: string }).code).toBe(validError.code);
  });

  it('rate-limits after thirty reports within the window', async () => {
    const burst = await createProvisionedUser('error-burst');
    await signInAs(burst);
    for (let i = 0; i < 30; i += 1) {
      await call('errors-reportClientError', { feature: 'x', message: `error number ${i}` });
    }
    expect(
      await callableErrorCode(call('errors-reportClientError', { feature: 'x', message: 'one too many' })),
    ).toBe('functions/resource-exhausted');
  });
});
