/**
 * Partner-insights admin read emulator integration tests.
 *
 * partnerInsights is backend-only (rules read/write:false); this exercises the
 * only admin read path — partnerInsights-adminSummary — end-to-end: admin
 * gating, deterministic aggregate lookup, and read-time threshold re-application.
 *
 * Requires the Auth + Functions + Firestore emulators — run via:
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
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { aggregateId, resolvePeriodBounds } from '../partnerInsights/insights-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'pi-admin-emulator-tests');
const adminDb = getAdminFirestore(adminApp);
const adminAuth = getAdminAuth(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

async function pollUntil<T>(read: () => Promise<T | undefined>, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, 250));
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
let regularUser: TestUser;

const companyId = `pi-co-${Date.now()}`;
const REFERENCE_DATE = '2026-07-01T12:00:00.000Z';
const bounds = resolvePeriodBounds(new Date(REFERENCE_DATE), 'day');

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'pi-admin-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('piadmin-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  regularUser = await createProvisionedUser('piadmin-user');

  // Simulate the floor being RAISED after aggregation: configure 15, above the
  // absolute floor of 10. The seeded anonymous_pass_by (unique 12) was a
  // legitimate "available" row when written at the floor of 10, but now falls
  // below 15 — exactly the case the read-time re-application guards against.
  await adminDb.collection('config').doc('partnerInsights').set({ minThreshold: 15 }, { merge: true });

  // Seed a day aggregate for the company: an available map_view (unique 20, still
  // >= 15) and an available anonymous_pass_by now BELOW the raised threshold
  // (unique 12 < 15) — which the callable must re-zero to insufficient_data.
  const seed = async (type: string, doc: Record<string, unknown>) => {
    await adminDb
      .collection('partnerInsights')
      .doc(aggregateId(companyId, type as never, 'day', bounds.start))
      .set({
        companyId,
        interactionType: type,
        periodType: 'day',
        periodStart: Timestamp.fromDate(bounds.start),
        periodEnd: Timestamp.fromDate(bounds.end),
        updatedAt: Timestamp.fromDate(new Date()),
        ...doc,
      });
  };
  await seed('map_view', { totalCount: 42, uniqueContributorCount: 20, resultStatus: 'available' });
  await seed('anonymous_pass_by', { totalCount: 15, uniqueContributorCount: 12, resultStatus: 'available' });
}, 120_000);

afterAll(async () => {
  // Restore the shared config floor so a raised threshold doesn't leak into
  // other emulator-suite files (config/partnerInsights is a single global doc).
  await adminDb.collection('config').doc('partnerInsights').set({ minThreshold: 10 }, { merge: true });
  await deleteApp(app);
});

describe('partnerInsights-adminSummary', () => {
  it('rejects non-admin callers', async () => {
    await signInAs(regularUser);
    expect(await callableErrorCode(call('partnerInsights-adminSummary', { companyId }))).toBe(
      'functions/permission-denied',
    );
  });

  it('returns threshold-safe per-type metrics for a company/day', async () => {
    await signInAs(adminUser);
    const result = await call('partnerInsights-adminSummary', {
      companyId,
      periodType: 'day',
      date: REFERENCE_DATE,
    });
    const data = result.data as {
      companyId: string;
      periodType: string;
      metrics: Array<{ interactionType: string; totalCount: number; uniqueContributorCount: number | null; status: string }>;
    };
    expect(data.companyId).toBe(companyId);
    expect(data.periodType).toBe('day');
    // One metric per interaction type.
    expect(data.metrics.length).toBeGreaterThanOrEqual(9);

    const byType = new Map(data.metrics.map((m) => [m.interactionType, m]));
    // Available aggregate is surfaced as-is.
    expect(byType.get('map_view')).toMatchObject({ totalCount: 42, status: 'available' });
    // Below-threshold pass-by is re-zeroed defensively at read time.
    expect(byType.get('anonymous_pass_by')).toMatchObject({
      totalCount: 0,
      uniqueContributorCount: null,
      status: 'insufficient_data',
    });
    // Types with no aggregate are no_data (never leak raw event data).
    expect(byType.get('profile_view')).toMatchObject({ totalCount: 0, status: 'no_data' });
  });
});
