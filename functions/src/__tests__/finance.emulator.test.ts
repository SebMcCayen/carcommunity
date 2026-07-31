/**
 * finance-estimate emulator integration test.
 *
 * Verifies the admin-only cost-estimate callable end-to-end:
 *  - a non-admin is rejected (permission-denied),
 *  - an admin gets an estimate whose variable half used the LIVE member count
 *    read from the latest metrics/{date} snapshot,
 *  - the returned figures are internally consistent (grand total = the three
 *    separable sections), and Mapbox / fixed subscriptions are separate.
 *
 * The heavy arithmetic is unit-tested in finance/model.test.ts; this test is
 * about the wiring (auth gate + snapshot read + callable transport).
 *
 * Requires the Functions + Firestore + Auth emulators — run via:
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
// Unique per-file suffix so seeded ids/displayNames never collide with other
// emulator files sharing the same Firestore instance.
const S = 'fin';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'finance-emulator-tests');
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
let memberUser: TestUser;

const SNAPSHOT_MEMBER_COUNT = 137;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'finance-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser(`${S}-admin`);
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set(
    { role: 'admin', displayName: `Finance Admin ${S}` },
    { merge: true },
  );
  memberUser = await createProvisionedUser(`${S}-member`);
  await adminDb.collection('users').doc(memberUser.uid).set(
    { displayName: `Finance Member ${S}` },
    { merge: true },
  );

  // Seed a metrics snapshot so the variable half reads a live member count.
  await adminDb.collection('metrics').doc('2026-07-30').set({
    date: '2026-07-30',
    capturedAtMs: Date.parse('2026-07-30T02:30:00Z'),
    totalUsers: SNAPSHOT_MEMBER_COUNT,
    convoysCreated: 0,
    totalDistanceMeters: 0,
    eventsHeld: 0,
    drivesSaved: 0,
    crownsCollected: 0,
    friendConnections: 0,
    activeConvoys: 0,
    vehicleProfiles: 0,
    brandDistribution: {},
  });
}, 120_000);

afterAll(async () => {
  await adminDb.collection('metrics').doc('2026-07-30').delete();
  await deleteApp(app);
});

interface EstimateResult {
  member: { count: number; source: string; asOf: string | null };
  googleCloud: { totalSekPerMonth: number; trafikverketWritesSekPerMonth: number };
  mapbox: { sekPerMonth: number };
  fixedSubscriptions: { totalSekPerMonth: number; hasUnset: boolean };
  grandTotalSekPerMonth: number;
  fx: { usdToSek: number };
}

describe('finance-estimate', () => {
  it('rejects a non-admin caller', async () => {
    await signInAs(memberUser);
    expect(await callableErrorCode(call('finance-estimate', {}))).toBe('functions/permission-denied');
  });

  it('returns an estimate that used the live member count from the latest snapshot', async () => {
    await signInAs(adminUser);
    const result = (await call('finance-estimate', {})).data as EstimateResult;

    expect(result.member.count).toBe(SNAPSHOT_MEMBER_COUNT);
    expect(result.member.source).toBe('metrics-snapshot');
    expect(result.member.asOf).toBe('2026-07-30');

    // Trafikverket committed writes are the dominant Google Cloud line.
    expect(result.googleCloud.trafikverketWritesSekPerMonth).toBeGreaterThan(0);
    expect(result.fx.usdToSek).toBeGreaterThan(0);

    // Grand total is the three separable sections summed.
    expect(result.grandTotalSekPerMonth).toBeCloseTo(
      result.googleCloud.totalSekPerMonth +
        result.mapbox.sekPerMonth +
        result.fixedSubscriptions.totalSekPerMonth,
      4,
    );

    // Claude subscription is unset by default → flagged, contributes nothing.
    expect(result.fixedSubscriptions.hasUnset).toBe(true);
    expect(result.fixedSubscriptions.totalSekPerMonth).toBe(0);
  });
});
