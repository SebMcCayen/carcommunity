/**
 * Feedback "Report a problem" emulator integration tests.
 *
 * Exercises the deployed-in-emulator callable end-to-end:
 * - `feedback-reportIssue` (auth gating, input validation, per-user rate
 *   limit, private feedbackReports record with the uid, no-PII GitHub body).
 *
 * The GitHub REST call is NOT made in the emulator: the GITHUB_ISSUE_TOKEN
 * secret is unset, so the callable records the report and returns
 * status: 'failed' (the report is captured either way). No network is hit.
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'feedback-emulator-tests');
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

const validReport = {
  description: 'The live map does not load after opening the app.',
  summary: 'Map fails to load',
  appVersion: '1.2.3',
  osVersion: 'Android 14',
  deviceModel: 'Pixel 8',
};

let reporter: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'feedback-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  reporter = await createProvisionedUser('feedback-reporter');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('feedback-reportIssue', () => {
  it('rejects unauthenticated callers', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('feedback-reportIssue', validReport))).toBe(
      'functions/unauthenticated',
    );
  });

  it('rejects an empty description', async () => {
    await signInAs(reporter);
    expect(await callableErrorCode(call('feedback-reportIssue', { description: '   ' }))).toBe(
      'functions/invalid-argument',
    );
  });

  it('captures the report privately with the uid and no GitHub token', async () => {
    await signInAs(reporter);
    const result = await call('feedback-reportIssue', validReport);
    const data = result.data as {
      reportId: string;
      githubIssueNumber: number | null;
      githubIssueUrl: string | null;
      status: 'created' | 'failed';
    };

    expect(data.reportId).toBeTruthy();
    // No GITHUB_ISSUE_TOKEN in the emulator → the issue attempt fails, but the
    // report is still captured and success is returned to the caller.
    expect(data.status).toBe('failed');
    expect(data.githubIssueNumber).toBeNull();

    const doc = (await adminDb.collection('feedbackReports').doc(data.reportId).get()).data()!;
    expect(doc.uid).toBe(reporter.uid);
    expect(doc.description).toBe(validReport.description);
    expect(doc.platform).toBe('android');
    expect(doc.githubIssueStatus).toBe('failed');
  });

  it('rate-limits after five reports within the window', async () => {
    const burst = await createProvisionedUser('feedback-burst');
    await signInAs(burst);
    for (let i = 0; i < 5; i += 1) {
      await call('feedback-reportIssue', { description: `report number ${i}` });
    }
    expect(
      await callableErrorCode(call('feedback-reportIssue', { description: 'one too many' })),
    ).toBe('functions/resource-exhausted');
  });
});
