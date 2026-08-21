/**
 * Kronjakt LIVE-SHARE SCORING emulator integration tests.
 *
 * Covers the `crownHuntLiveShareScoring` rule end-to-end across BOTH crown
 * award paths:
 *  - `crownHunt.claimSpawn` (auto-spawned crowns), and
 *  - `crownHunt.submitClaim` (hand-placed admin points),
 * in each of three states:
 *  (a) flag ON + an ACTIVE live session  → full Kronpoäng (multiplier 1);
 *  (b) flag ON + NO active session       → half Kronpoäng (× 0.5, rounded);
 *  (c) flag OFF                          → full Kronpoäng (rule dark).
 *
 * The live-sharing signal is the RTDB session node the live domain writes
 * (liveLocation/{uid}/session). These tests drive it through the REAL
 * `live.startSession` / `live.stopSession` callables rather than seeding RTDB
 * directly, so the session lands in exactly the database namespace the
 * functions runtime — and therefore `resolveLiveShareMultiplier` — reads. A
 * stopped session is a well-formed but inactive node, so it still exercises the
 * "confirmed not sharing → halve" branch.
 *
 * The shared emulator Firestore is why the base flags are set in beforeAll and
 * crownHuntLiveShareScoring is reset to its contract default (false) in afterAll
 * — so a stray ON value can never leak into another suite's crown tests.
 *
 * CI ONLY. Requires the Firebase Emulator Suite (auth + functions + firestore +
 * database). Excluded from the default `vitest run` unit suite by the config.
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { deleteApp, initializeApp, type FirebaseApp } from 'firebase/app';
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

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ??
  initializeAdminApp({ projectId: PROJECT_ID }, 'liveshare-scoring-emulator-tests');
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
  intervalMs = 200,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
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

async function setFlags(flags: Record<string, boolean>): Promise<void> {
  await adminDb.collection('config').doc('featureFlags').set(flags, { merge: true });
}

/** Ages an account well past any new-account eligibility window. */
async function ageAccount(uid: string): Promise<void> {
  await adminDb
    .collection('users')
    .doc(uid)
    .set(
      { createdAt: Timestamp.fromMillis(Date.now() - 30 * 24 * 60 * 60 * 1000) },
      { merge: true },
    );
}

/**
 * Signs in `user` and starts a real live session — the functions runtime writes
 * an ACTIVE node at liveLocation/{uid}/session, exactly what the multiplier
 * reads. Leaves `user` as the signed-in client so a following claim runs as them.
 */
async function startLive(user: TestUser): Promise<void> {
  await signInAs(user);
  await call('live-startSession', { duration: '1h' });
}

/**
 * Signs in `user` and stops any live session — the node becomes status
 * 'stopped' (well-formed but inactive), so the collector is CONFIRMED not
 * sharing. Also leaves `user` signed in.
 */
async function stopLive(user: TestUser): Promise<void> {
  await signInAs(user);
  await call('live-stopSession', {});
}

// A quiet coordinate distinct from the other crown suites' fixtures.
const LAT = 24.751;
const LNG = 24.751;

let idCounter = 0;
function unique(prefix = 'id'): string {
  idCounter += 1;
  return `${prefix}-${Date.now()}-${idCounter}`;
}

/** Places one live SHARED auto-spawn crown worth `rewardPoints` at LAT/LNG. */
async function placeSpawn(rewardPoints: number): Promise<string> {
  const spawnId = unique('lss-spawn');
  await adminDb
    .collection('crownSpawns')
    .doc(spawnId)
    .set({
      status: 'live',
      rarity: 'common',
      collectMode: 'shared',
      rewardPoints,
      latitude: LAT,
      longitude: LNG,
      collectRadiusMeters: 75,
      cellKey: '0_0',
      source: 'auto',
      expiresAt: Timestamp.fromMillis(Date.now() + 60 * 60 * 1000),
      createdAt: Timestamp.now(),
    });
  return spawnId;
}

/** A stationary, in-range claimSpawn payload for the crown at LAT/LNG. */
function spawnClaimInput(spawnId: string): Record<string, unknown> {
  const nowIso = new Date().toISOString();
  const earlierIso = new Date(Date.now() - 10_000).toISOString();
  return {
    spawnId,
    latitude: LAT,
    longitude: LNG,
    accuracyMeters: 5,
    speedMetersPerSecond: 0,
    recordedAt: nowIso,
    previousFix: {
      latitude: LAT,
      longitude: LNG,
      accuracyMeters: 5,
      speedMetersPerSecond: 0,
      recordedAt: earlierIso,
    },
    idempotencyKey: unique('lss-spawn-key'),
  };
}

let adminUser: TestUser;
let hunter: TestUser;

/** Creates an ACTIVE hand-placed point worth `rewardPoints` at LAT/LNG. */
async function createActivePoint(rewardPoints: number): Promise<string> {
  await signInAs(adminUser);
  const created = (
    await call('crownHunt-createPoint', {
      latitude: LAT,
      longitude: LNG,
      geofenceRadiusMeters: 50,
      rewardPoints,
      repeatRule: 'once',
    })
  ).data as { pointId: string };
  await call('crownHunt-activatePoint', {
    pointId: created.pointId,
    safeLocationConfirmed: true,
    approvalNote: 'Trygg plats bekräftad för test.',
  });
  return created.pointId;
}

/** A stationary, in-range submitClaim payload for the point at LAT/LNG. */
function pointClaimInput(pointId: string): Record<string, unknown> {
  return {
    pointId,
    latitude: LAT,
    longitude: LNG,
    accuracyMeters: 10,
    speedMetersPerSecond: 0,
    recordedAt: new Date().toISOString(),
    idempotencyKey: unique('lss-point-key'),
  };
}

type ClaimResponse = { result: string; pointsAwarded: number | null };

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'liveshare-scoring-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('lss-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });

  hunter = await createProvisionedUser('lss-hunter');
  await ageAccount(hunter.uid);

  // Base flags; crownHuntLiveShareScoring is toggled per-test below. liveLocation
  // must be on for the live.startSession path the active-session tests use.
  await setFlags({
    crownHunt: true,
    crownHuntSpawn: true,
    liveLocation: true,
  });
}, 120_000);

afterAll(async () => {
  // Restore the flag's contract default so it cannot leak ON into other suites,
  // and leave the hunter not sharing.
  await stopLive(hunter);
  await setFlags({ crownHuntLiveShareScoring: false });
  await deleteApp(app);
});

describe('crownHunt.claimSpawn — live-share scoring', () => {
  it('awards FULL Kronpoäng while an active live session is shared', async () => {
    await setFlags({ crownHuntLiveShareScoring: true });
    await startLive(hunter); // signs in hunter AND starts an active session
    const spawnId = await placeSpawn(10);

    const res = (await call('crownHunt-claimSpawn', spawnClaimInput(spawnId))).data as ClaimResponse;
    expect(res.result).toBe('awarded');
    expect(res.pointsAwarded).toBe(10); // 10 × 1 (sharing) × 1 (no boost)
  });

  it('HALVES Kronpoäng when the collector is not live-sharing', async () => {
    await setFlags({ crownHuntLiveShareScoring: true });
    await stopLive(hunter); // signs in hunter AND stops the session
    const spawnId = await placeSpawn(10);

    const res = (await call('crownHunt-claimSpawn', spawnClaimInput(spawnId))).data as ClaimResponse;
    expect(res.result).toBe('awarded');
    // Math.round(10 × 1 × 0.5) = 5.
    expect(res.pointsAwarded).toBe(5);
  });

  it('awards FULL Kronpoäng when the flag is OFF (rule dark)', async () => {
    await setFlags({ crownHuntLiveShareScoring: false });
    await stopLive(hunter);
    const spawnId = await placeSpawn(10);

    const res = (await call('crownHunt-claimSpawn', spawnClaimInput(spawnId))).data as ClaimResponse;
    expect(res.result).toBe('awarded');
    expect(res.pointsAwarded).toBe(10);
  });
});

describe('crownHunt.submitClaim — live-share scoring', () => {
  it('awards FULL Kronpoäng while an active live session is shared', async () => {
    await setFlags({ crownHuntLiveShareScoring: true });
    const pointId = await createActivePoint(25);
    await startLive(hunter); // signs in hunter AND starts an active session

    const res = (await call('crownHunt-submitClaim', pointClaimInput(pointId))).data as ClaimResponse;
    expect(res.result).toBe('awarded');
    expect(res.pointsAwarded).toBe(25); // 25 × 1 (sharing) × 1 (no boost)
  });

  it('HALVES Kronpoäng when the collector is not live-sharing', async () => {
    await setFlags({ crownHuntLiveShareScoring: true });
    const pointId = await createActivePoint(25);
    await stopLive(hunter); // signs in hunter AND stops the session

    const res = (await call('crownHunt-submitClaim', pointClaimInput(pointId))).data as ClaimResponse;
    expect(res.result).toBe('awarded');
    // Math.round(25 × 1 × 0.5) = Math.round(12.5) = 13 (round-half-up).
    expect(res.pointsAwarded).toBe(13);
  });

  it('awards FULL Kronpoäng when the flag is OFF (rule dark)', async () => {
    await setFlags({ crownHuntLiveShareScoring: false });
    const pointId = await createActivePoint(25);
    await stopLive(hunter);

    const res = (await call('crownHunt-submitClaim', pointClaimInput(pointId))).data as ClaimResponse;
    expect(res.result).toBe('awarded');
    expect(res.pointsAwarded).toBe(25);
  });
});
