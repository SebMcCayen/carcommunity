/**
 * Diagnostics emulator integration tests (Phase 9n).
 *
 * Exercises diagnostics-submitReport end-to-end: anonymous and
 * authenticated submission, server-side metadata sanitization, and the
 * 90-day retention sweep via the exported runner.
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
  signOut,
  type Auth,
} from 'firebase/auth';
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runDiagnosticsCleanup } from '../diagnostics/scheduled';
import { DIAGNOSTICS_RATE_LIMIT_MAX } from '../diagnostics/diagnostics-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'diagnostics-emulator-tests');
const adminDb = getAdminFirestore(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;

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

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

const validReport = {
  severity: 'error',
  platform: 'android',
  featureArea: 'auth',
  safeMessage: 'Inloggningen misslyckades: nätverksfel',
  appVersion: '1.0.0',
};

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'diagnostics-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('diagnostics-submitReport', () => {
  it('accepts ANONYMOUS reports (sign-in failures must be reportable)', async () => {
    await signOut(auth);
    const result = (await call('diagnostics-submitReport', validReport)).data as {
      reportId: string;
      fingerprint: string;
    };
    expect(result.fingerprint).toMatch(/^[a-f0-9]{64}$/);

    const stored = (
      await adminDb.collection('diagnosticsReports').doc(result.reportId).get()
    ).data()!;
    expect(stored.userId).toBeNull();
    expect(stored.safeMessage).toBe(validReport.safeMessage);
    expect(stored.createdAt).toBeInstanceOf(Timestamp);
  });

  it('attaches the UID for signed-in reporters and sanitizes metadata server-side', async () => {
    const email = `diag-${Date.now()}@example.com`;
    const credential = await createUserWithEmailAndPassword(auth, email, 'password-123');
    const uid = credential.user.uid;
    await pollUntil(async () => {
      const snap = await adminDb.collection('users').doc(uid).get();
      return snap.exists ? true : undefined;
    });

    const result = (
      await call('diagnostics-submitReport', {
        ...validReport,
        metadata: {
          idToken: 'super-secret-token',
          latitude: 59.33,
          screen: 'LoginScreen',
          retryCount: 2,
        },
      })
    ).data as { reportId: string };

    const stored = (
      await adminDb.collection('diagnosticsReports').doc(result.reportId).get()
    ).data()!;
    expect(stored.userId).toBe(uid);
    expect(stored.metadata).toEqual({ screen: 'LoginScreen', retryCount: 2 });
    expect(JSON.stringify(stored)).not.toContain('super-secret-token');
    expect(JSON.stringify(stored)).not.toContain('59.33');
  });

  it('enforces the per-caller rate limit and stores rateLimitKey/appCheckPresent', async () => {
    // Authenticated caller so the rate-limit key is `uid:<uid>` and does not
    // depend on emulator-supplied IP headers.
    const email = `diag-ratelimit-${Date.now()}@example.com`;
    const credential = await createUserWithEmailAndPassword(auth, email, 'password-123');
    const uid = credential.user.uid;
    await pollUntil(async () => {
      const snap = await adminDb.collection('users').doc(uid).get();
      return snap.exists ? true : undefined;
    });

    // Exactly MAX submissions must all succeed (awaited so they land in one
    // rolling window and count against the same uid-scoped key).
    for (let i = 0; i < DIAGNOSTICS_RATE_LIMIT_MAX; i += 1) {
      await call('diagnostics-submitReport', validReport);
    }

    // The (MAX + 1)th call must be rejected by the transactional cap.
    expect(await callableErrorCode(call('diagnostics-submitReport', validReport))).toBe(
      'functions/resource-exhausted',
    );

    // Stored docs for this uid carry the server-derived pseudonymised key and
    // the App Check presence flag. Scope the query to THIS uid so the 20+ docs
    // do not interfere with (or get asserted against) other tests' data.
    const snap = await adminDb
      .collection('diagnosticsReports')
      .where('userId', '==', uid)
      .get();
    expect(snap.size).toBe(DIAGNOSTICS_RATE_LIMIT_MAX);
    const sample = snap.docs[0].data();
    expect(sample.rateLimitKey).toBe(`uid:${uid}`);
    expect(typeof sample.appCheckPresent).toBe('boolean');
  });

  it('rejects malformed reports', async () => {
    expect(
      await callableErrorCode(
        call('diagnostics-submitReport', { ...validReport, severity: 'fatal' }),
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(call('diagnostics-submitReport', { severity: 'error' })),
    ).toBe('functions/invalid-argument');
  });
});

describe('diagnostics retention cleanup', () => {
  it('deletes reports older than 90 days and keeps newer ones', async () => {
    const now = new Date();
    const dayMs = 24 * 60 * 60 * 1000;
    const seed = (id: string, daysAgo: number) =>
      adminDb
        .collection('diagnosticsReports')
        .doc(id)
        .set({
          userId: null,
          severity: 'info',
          platform: 'android',
          featureArea: 'unknown',
          safeMessage: 'seed',
          appVersion: null,
          buildNumber: null,
          osVersion: null,
          errorCode: null,
          metadata: null,
          fingerprint: 'f'.repeat(64),
          createdAt: Timestamp.fromDate(new Date(now.getTime() - daysAgo * dayMs)),
        });
    await seed('diag-old', 91);
    await seed('diag-recent', 89);

    const result = await runDiagnosticsCleanup(now);
    expect(result.deletedCount).toBeGreaterThanOrEqual(1);
    expect((await adminDb.collection('diagnosticsReports').doc('diag-old').get()).exists).toBe(
      false,
    );
    expect((await adminDb.collection('diagnosticsReports').doc('diag-recent').get()).exists).toBe(
      true,
    );
  });
});
