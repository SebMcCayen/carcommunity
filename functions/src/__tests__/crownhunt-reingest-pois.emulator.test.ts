/**
 * Kronjakt on-demand "Retry POIs" callable — crownHunt.reingestSpawnAreaPois —
 * emulator integration tests.
 *
 * This suite covers the callable's GATED ENTRY paths, the ones that return before
 * any Overpass network call: admin-gating, input validation, not-found, and the
 * no-shape failed-precondition. The actual ingestion the callable then delegates
 * to (runAreaPoiIngestion) is NOT re-exercised here — the callable calls the LIVE
 * httpFetcher (no fetcher injection, by design), so driving its success/failure
 * path in the emulator would make a real Overpass request. Those paths are
 * covered without a network elsewhere:
 *   - runAreaPoiIngestion success + Overpass-failure: crownhunt-osm-poi.emulator.test.ts
 *     (mocked fetcher);
 *   - the result→structured-response mapping (ok:false + message on a failed run):
 *     crownHunt/reingest-area-pois-core.test.ts (pure unit test).
 *
 * CI ONLY. Requires the Firebase Emulator Suite. Run via:
 *   pnpm --dir functions emulators:test
 *
 * Emulator suites share ONE Firestore, so every user/area here carries a
 * file-unique prefix ('crp-') and coordinates far from the other Kronjakt suites.
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

import type { CrownSpawnAreaShape } from '../crownHunt/crown-area-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps().find((a) => a?.name === 'crp-reingest-tests') ??
  initializeAdminApp({ projectId: PROJECT_ID }, 'crp-reingest-tests');
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
let member: TestUser;

// A quiet circle near Kiruna (far north), away from the other suites' fixtures.
const AREA_CIRCLE: CrownSpawnAreaShape = {
  type: 'circle',
  center: { lat: 67.855, lon: 20.225 },
  radiusMeters: 250,
};

interface AreaMutation {
  areaId: string;
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'crp-reingest-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('crp-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  member = await createProvisionedUser('crp-member');
  await adminDb.collection('users').doc(member.uid).set({ activeMember: true }, { merge: true });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('crownHunt-reingestSpawnAreaPois (gated entry paths)', () => {
  it('rejects a non-admin caller', async () => {
    await signInAs(member);
    expect(
      await callableErrorCode(call('crownHunt-reingestSpawnAreaPois', { areaId: 'whatever' })),
    ).toBe('functions/permission-denied');
  });

  it('rejects a missing/blank areaId', async () => {
    await signInAs(adminUser);
    expect(await callableErrorCode(call('crownHunt-reingestSpawnAreaPois', {}))).toBe(
      'functions/invalid-argument',
    );
    expect(
      await callableErrorCode(call('crownHunt-reingestSpawnAreaPois', { areaId: '   ' })),
    ).toBe('functions/invalid-argument');
  });

  it('404s an unknown area', async () => {
    await signInAs(adminUser);
    expect(
      await callableErrorCode(
        call('crownHunt-reingestSpawnAreaPois', { areaId: 'crp-no-such-area' }),
      ),
    ).toBe('functions/not-found');
  });

  it('fails-precondition for an area with no drawn shape', async () => {
    await signInAs(adminUser);
    // A shape-less (corrupted) area document — the callable must reject before it
    // would ever call Overpass.
    const ref = adminDb.collection('crownSpawnAreas').doc(`crp-noshape-${Date.now()}`);
    await ref.set({ areaId: ref.id, name: 'crp shapeless', active: false });
    expect(
      await callableErrorCode(call('crownHunt-reingestSpawnAreaPois', { areaId: ref.id })),
    ).toBe('functions/failed-precondition');
    await ref.delete();
  });

  it('fails-precondition for a structurally corrupt shape (circle missing center/radius)', async () => {
    await signInAs(adminUser);
    // A circle whose required fields are absent would make shapeBoundingBox throw
    // and surface as an opaque internal 500; the callable must instead reject it
    // cleanly BEFORE any ingestion, via the shape schema.
    const ref = adminDb.collection('crownSpawnAreas').doc(`crp-badcircle-${Date.now()}`);
    await ref.set({ areaId: ref.id, name: 'crp bad circle', active: false, shape: { type: 'circle' } });
    expect(
      await callableErrorCode(call('crownHunt-reingestSpawnAreaPois', { areaId: ref.id })),
    ).toBe('functions/failed-precondition');
    await ref.delete();
  });

  // Sanity: a well-formed active area with a shape exists and is reachable by the
  // admin CRUD (the ingestion itself is covered by crownhunt-osm-poi.emulator.test
  // + the pure reingest-area-pois-core test; not re-run here to avoid a live
  // Overpass call).
  it('can create + delete a valid active area (fixture reachability)', async () => {
    await signInAs(adminUser);
    const created = (
      await call('crownHunt-createSpawnArea', {
        shape: AREA_CIRCLE,
        name: 'crp reachable',
        active: true,
        safeAreaConfirmed: true,
      })
    ).data as AreaMutation;
    expect(created.areaId).toBeTruthy();
    await call('crownHunt-deleteSpawnArea', { areaId: created.areaId });
  });
});
