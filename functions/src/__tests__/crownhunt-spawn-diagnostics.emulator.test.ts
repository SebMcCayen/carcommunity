/**
 * Kronjakt MARKED-AREA auto-spawn DIAGNOSTICS emulator integration tests.
 *
 * Exercises crownHunt.spawnDiagnostics — the admin-only, read-only troubleshooting
 * view over runCrownAreaSpawnPass: the next-run countdown facts, the candidate
 * cells ("where it will spawn"), and the area-level blockers ("why nothing is
 * spawning"). It is purely observational, so these tests assert the SNAPSHOT it
 * returns rather than any side effect.
 *
 * CI ONLY. Requires the Firebase Emulator Suite. Run via:
 *   pnpm --dir functions emulators:test
 *
 * Emulator suites share ONE Firestore, so every user here carries a file-unique
 * prefix ('csd-') and the coordinates sit near Malmö, far from the other Kronjakt
 * suites' Stockholm/Gothenburg fixtures, so their crowns cannot bleed in.
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

import { crownActivityUserHash, crownCellKey } from '../crownHunt/crown-spawn-core';
import { crownPoiDocId } from '../crownHunt/osm-poi-core';
import type { CrownSpawnAreaShape } from '../crownHunt/crown-area-core';
import type { SpawnDiagnosticsResponse } from '../crownHunt/spawnDiagnostics';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'crown-spawn-diag-tests');
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

// A quiet cell near Malmö, deliberately far from the other Kronjakt suites.
const CELL_LAT = 55.605;
const CELL_LON = 13.005;
const CELL_KEY = crownCellKey(CELL_LAT, CELL_LON);

// A small circle centred in that cell.
const AREA_CIRCLE: CrownSpawnAreaShape = {
  type: 'circle',
  center: { lat: CELL_LAT, lon: CELL_LON },
  radiusMeters: 300,
};

const AREA_POIS: { lat: number; lon: number; category: 'parking' | 'fuel' | 'charging' }[] = [
  { lat: CELL_LAT, lon: CELL_LON, category: 'parking' },
  { lat: CELL_LAT + 0.0018, lon: CELL_LON, category: 'fuel' },
];

async function setSpawnFlag(enabled: boolean): Promise<void> {
  await adminDb
    .collection('config')
    .doc('featureFlags')
    .set({ crownHunt: true, crownHuntSpawn: enabled }, { merge: true });
}

async function seedAreaPois(areaId: string): Promise<void> {
  const poisRef = adminDb.collection('crownSpawnAreaPois').doc(areaId).collection('pois');
  const batch = adminDb.batch();
  for (const poi of AREA_POIS) {
    batch.set(poisRef.doc(crownPoiDocId(areaId, poi.lat, poi.lon)), {
      lat: poi.lat,
      lon: poi.lon,
      category: poi.category,
      cellKey: crownCellKey(poi.lat, poi.lon),
      refreshedAt: Timestamp.fromDate(new Date()),
    });
  }
  await batch.commit();
  await adminDb
    .collection('crownSpawnAreas')
    .doc(areaId)
    .set(
      { poiCount: AREA_POIS.length, poisRefreshedAt: Timestamp.fromDate(new Date()) },
      { merge: true },
    );
}

async function seedActivity(cellKey: string, count: number, now: Date): Promise<void> {
  const cellRef = adminDb.collection('crownCellActivity').doc(cellKey);
  const batch = adminDb.batch();
  batch.set(cellRef, { cellKey, lastActivityAt: Timestamp.fromDate(now) });
  for (let i = 0; i < count; i += 1) {
    batch.set(
      cellRef.collection('recentUsers').doc(crownActivityUserHash(cellKey, `csd-seed-${i}`)),
      {
        lastSeenAt: Timestamp.fromDate(now),
      },
    );
  }
  await batch.commit();
}

interface AreaMutation {
  areaId: string;
  active: boolean;
  safeAreaConfirmed: boolean;
}

async function createActiveArea(): Promise<string> {
  await signInAs(adminUser);
  const created = (
    await call('crownHunt-createSpawnArea', {
      shape: AREA_CIRCLE,
      name: 'Malmö diag',
      active: true,
      safeAreaConfirmed: true,
    })
  ).data as AreaMutation;
  return created.areaId;
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'crown-spawn-diag-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('csd-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  member = await createProvisionedUser('csd-member');
  await adminDb.collection('users').doc(member.uid).set({ activeMember: true }, { merge: true });

  await setSpawnFlag(true);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('crownHunt-spawnDiagnostics', () => {
  it('requires an admin', async () => {
    await signInAs(member);
    expect(
      await callableErrorCode(call('crownHunt-spawnDiagnostics', { areaId: 'whatever' })),
    ).toBe('functions/permission-denied');
  });

  it('rejects a missing/blank areaId', async () => {
    await signInAs(adminUser);
    expect(await callableErrorCode(call('crownHunt-spawnDiagnostics', {}))).toBe(
      'functions/invalid-argument',
    );
  });

  it('404s an unknown area', async () => {
    await signInAs(adminUser);
    expect(
      await callableErrorCode(call('crownHunt-spawnDiagnostics', { areaId: 'no-such-area-xyz' })),
    ).toBe('functions/not-found');
  });

  it('reports the next-run facts, candidate cells and no blockers for a healthy area', async () => {
    await setSpawnFlag(true);
    const areaId = await createActiveArea();
    const now = new Date();
    await seedActivity(CELL_KEY, 12, now);
    await seedAreaPois(areaId);

    const res = (await call('crownHunt-spawnDiagnostics', { areaId }))
      .data as SpawnDiagnosticsResponse;

    expect(res.areaId).toBe(areaId);
    expect(res.flagEnabled).toBe(true);
    expect(res.active).toBe(true);
    expect(res.safeAreaConfirmed).toBe(true);
    expect(res.runIntervalSeconds).toBe(600);
    // The next run is strictly in the future of the server clock.
    expect(new Date(res.nextRunAt).getTime()).toBeGreaterThan(new Date(res.serverTime).getTime());
    expect(res.maxAreasPerRun).toBeGreaterThan(0);
    expect(res.totalCells).toBeGreaterThan(0);
    expect(res.cellsScanned).toBeGreaterThan(0);

    // The seeded cell is a real candidate: below target, with POIs to anchor to.
    const cell = res.cells.find((c) => c.cellKey === CELL_KEY);
    expect(cell).toBeDefined();
    expect(cell!.target).toBeGreaterThan(0);
    expect(cell!.poiCount).toBeGreaterThan(0);
    expect(cell!.eligible).toBe(true);
    expect(cell!.reason).toBe('would_spawn');
    expect(res.candidateCellCount).toBeGreaterThan(0);
    expect(res.blockers).not.toContain('spawn_flag_off');
    expect(res.blockers).not.toContain('area_inactive');
    expect(res.blockers).not.toContain('no_area_pois');

    await call('crownHunt-deleteSpawnArea', { areaId });
  });

  it('flags spawn_flag_off when the feature flag is off', async () => {
    const areaId = await createActiveArea();
    await seedAreaPois(areaId);
    await setSpawnFlag(false);

    const res = (await call('crownHunt-spawnDiagnostics', { areaId }))
      .data as SpawnDiagnosticsResponse;
    expect(res.flagEnabled).toBe(false);
    expect(res.blockers).toContain('spawn_flag_off');

    await setSpawnFlag(true);
    await call('crownHunt-deleteSpawnArea', { areaId });
  });

  it('flags area_inactive and no_area_pois for a drawn-but-off area with no POIs', async () => {
    await setSpawnFlag(true);
    await signInAs(adminUser);
    const created = (
      await call('crownHunt-createSpawnArea', { shape: AREA_CIRCLE, name: 'Off area' })
    ).data as AreaMutation;

    const res = (await call('crownHunt-spawnDiagnostics', { areaId: created.areaId }))
      .data as SpawnDiagnosticsResponse;
    expect(res.active).toBe(false);
    expect(res.blockers).toContain('area_inactive');
    expect(res.blockers).toContain('no_area_pois');

    await call('crownHunt-deleteSpawnArea', { areaId: created.areaId });
  });
});
