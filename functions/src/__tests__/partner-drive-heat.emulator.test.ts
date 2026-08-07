/**
 * Partner DRIVE HEATMAP emulator integration tests.
 *
 * Exercises the privacy-critical aggregation + read paths end-to-end:
 * - runDriveHeatAggregation: decodes route.bin files from Storage, trims
 *   endpoints, bins to H3, applies the ≥10 unique-contributor floor, and
 *   writes the anonymised partnerDriveHeat/current aggregate.
 * - partnerInsights-driveHeat callable: admin-gated read of the aggregate.
 *
 * The scheduled runner is imported directly and driven against the emulator —
 * onSchedule functions cannot be invoked over the callable protocol.
 *
 * Isolation: this test drives users through REMOTE Lapland coordinates that no
 * other suite touches, so its cells are unaffected by rides other test files
 * seed into the shared emulator Firestore.
 *
 * Requires the Functions + Firestore + Storage emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= '127.0.0.1:9199';
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
import { getStorage as getAdminStorage } from 'firebase-admin/storage';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { encodeRoute, type RoutePoint } from '../drives/route-codec';
import { rideRoutePath } from '../drives/drives-core';
import { routeCells, trimRouteEndpoints } from '../partnerInsights/drive-heat-core';
import { runDriveHeatAggregation } from '../partnerInsights/driveHeatAggregation';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'drive-heat-emulator-tests');
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);
const adminBucket = getAdminStorage(adminApp).bucket(`${PROJECT_ID}.appspot.com`);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

async function createUser(prefix: string): Promise<TestUser> {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'password-123';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  return { uid: credential.user.uid, email, password };
}

/** A straight line of GPS fixes at a fixed latitude, `stepM` metres apart. */
function routeAt(startLat: number, startLon: number, count: number, stepM: number): RoutePoint[] {
  const metresPerDegLon = 111_320 * Math.cos((startLat * Math.PI) / 180);
  const dLon = stepM / metresPerDegLon;
  const t0 = Date.now() - 10 * 24 * 60 * 60 * 1000; // within the 90-day window
  return Array.from({ length: count }, (_, i) => ({
    latitude: startLat,
    longitude: startLon + i * dLon,
    timestampMs: t0 + i * 1000,
  }));
}

/** Seed a completed ride doc + its gzipped route.bin for `uid`. */
async function seedRide(
  uid: string,
  rideId: string,
  route: RoutePoint[],
  consented: boolean,
): Promise<void> {
  await adminDb
    .collection('userPrivate')
    .doc(uid)
    .set({ anonymousPartnerStatsOptIn: consented }, { merge: true });
  await adminDb
    .collection('rides')
    .doc(rideId)
    .set({
      userId: uid,
      startedAt: Timestamp.fromDate(new Date(route[0]!.timestampMs)),
      endedAt: Timestamp.fromDate(new Date(route[route.length - 1]!.timestampMs)),
      routePath: rideRoutePath(uid, rideId),
      createdAt: Timestamp.now(),
    });
  await adminBucket
    .file(rideRoutePath(uid, rideId))
    .save(Buffer.from(encodeRoute(route, true)), { contentType: 'application/gzip' });
}

// A ~1 km line in remote Lapland: after trimming ~200 m off each end, a healthy
// middle span survives and bins into several res-10 cells shared by everyone who
// drives the identical route.
const BUSY_ROUTE = routeAt(68.0, 20.0, 100, 10);
// A separate remote line far from BUSY_ROUTE — only a few users drive it, so it
// must stay BELOW the ≥10 floor and be omitted entirely.
const QUIET_ROUTE = routeAt(68.5, 21.0, 100, 10);

const BUSY_CELLS = new Set(routeCells(trimRouteEndpoints(BUSY_ROUTE)));
const QUIET_CELLS = new Set(routeCells(trimRouteEndpoints(QUIET_ROUTE)));

let adminUser: TestUser;
let regularUser: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'drive-heat-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createUser('driveheat-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  regularUser = await createUser('driveheat-user');

  // 10 CONSENTED users drive the identical BUSY route → clears the ≥10 floor.
  for (let i = 0; i < 10; i += 1) {
    const u = await createUser(`driveheat-busy-${i}`);
    await seedRide(u.uid, `busy-${u.uid}`, BUSY_ROUTE, true);
  }
  // 1 NON-consented user drives the same BUSY route → must be EXCLUDED (so the
  // busy cells stay at contributorCount 10 and weight 10, not 11).
  const optedOut = await createUser('driveheat-optout');
  await seedRide(optedOut.uid, `busy-${optedOut.uid}`, BUSY_ROUTE, false);

  // 3 CONSENTED users drive the QUIET route → below the floor, must be omitted.
  for (let i = 0; i < 3; i += 1) {
    const u = await createUser(`driveheat-quiet-${i}`);
    await seedRide(u.uid, `quiet-${u.uid}`, QUIET_ROUTE, true);
  }
}, 60_000);

afterAll(async () => {
  if (app) await deleteApp(app);
});

describe('runDriveHeatAggregation', () => {
  it('emits busy cells at the ≥10 floor, excludes non-consented, omits below-floor', async () => {
    const result = await runDriveHeatAggregation(new Date());
    expect(result.cellsWritten).toBeGreaterThan(0);

    const snap = await adminDb.collection('partnerDriveHeat').doc('current').get();
    expect(snap.exists).toBe(true);
    const cells = (snap.data()?.cells ?? []) as Array<{
      h3Index: string;
      contributorCount: number;
      weight: number;
    }>;

    const busy = cells.filter((c) => BUSY_CELLS.has(c.h3Index));
    expect(busy.length).toBeGreaterThan(0);
    for (const c of busy) {
      // 10 consented drivers; the opted-out driver on the same route is excluded.
      expect(c.contributorCount).toBe(10);
      expect(c.weight).toBe(10);
    }

    // Below-floor QUIET cells (only 3 contributors) are omitted entirely.
    const quiet = cells.filter((c) => QUIET_CELLS.has(c.h3Index));
    expect(quiet).toEqual([]);

    // No aggregate cell can ever carry a below-floor contributor count.
    for (const c of cells) {
      expect(c.contributorCount).toBeGreaterThanOrEqual(10);
    }
  }, 60_000);
});

describe('partnerInsights-driveHeat callable', () => {
  it('returns the anonymised cells to an admin', async () => {
    await runDriveHeatAggregation(new Date());
    await signInWithEmailAndPassword(auth, adminUser.email, adminUser.password);
    const res = (await httpsCallable(functions, 'partnerInsights-driveHeat')({})) as {
      data: {
        cells: Array<{ h3Index: string; contributorCount: number; weight: number }>;
        resolution: number;
        windowDays: number;
        generatedAt: string | null;
      };
    };
    expect(res.data.resolution).toBe(10);
    expect(res.data.windowDays).toBe(90);
    expect(res.data.generatedAt).not.toBeNull();
    const busy = res.data.cells.filter((c) => BUSY_CELLS.has(c.h3Index));
    expect(busy.length).toBeGreaterThan(0);
    for (const c of res.data.cells) {
      expect(c.contributorCount).toBeGreaterThanOrEqual(10);
      // Never any user identity in the payload.
      expect(Object.keys(c).sort()).toEqual(['contributorCount', 'h3Index', 'weight']);
    }
  }, 60_000);

  it('rejects a non-admin caller with permission-denied', async () => {
    await signInWithEmailAndPassword(auth, regularUser.email, regularUser.password);
    let code = 'no-error';
    try {
      await httpsCallable(functions, 'partnerInsights-driveHeat')({});
    } catch (error) {
      if (error instanceof FirebaseError) code = error.code;
      else throw error;
    }
    expect(code).toBe('functions/permission-denied');
  }, 60_000);
});
