/**
 * Kronjakt MARKED-AREA + SHARED/EXCLUSIVE-collection emulator integration tests.
 *
 * Covers the second half of the auto-spawn safety model — admin-drawn areas
 * (polygon/circle/rectangle) instead of single approved cells — and the crown
 * collection model (shared crowns collectable once per member and persistent;
 * exclusive crowns first-come and removed):
 *
 *  - the admin CRUD callables (auth + safeAreaConfirmed gate + audit trail);
 *  - the area spawn pass places crowns ONLY inside the drawn shape;
 *  - deactivating / deleting an area DRAINS its live crowns;
 *  - shared crown: two distinct members each collect once, the crown persists,
 *    and a member's second attempt is refused;
 *  - exclusive crown: the first taker removes it, the second is told it is gone.
 *
 * CI ONLY. Requires the Firebase Emulator Suite. Run via:
 *   pnpm --dir functions emulators:test
 *
 * Emulator suites share ONE Firestore, so every user here carries a file-unique
 * prefix ('caq-') and the coordinates sit far from the other Kronjakt suite's.
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
import { runCrownAreaSpawnPass } from '../crownHunt/spawnScheduled';
import {
  COLLECT_RADIUS_METERS,
  crownActivityUserHash,
  crownCellKey,
  createSeededRng,
  spawnCollectorDocId,
} from '../crownHunt/crown-spawn-core';
import { isPointInShape, type CrownSpawnAreaShape } from '../crownHunt/crown-area-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'crownarea-emulator-tests');
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
let hunter: TestUser;
let rival: TestUser;
let third: TestUser;

// A quiet cell near Stockholm, deliberately far from the other spawn suite's
// Gothenburg-area fixtures so their crowns cannot bleed into a neighbourhood read.
const CELL_LAT = 59.325;
const CELL_LON = 18.075;
const CELL_KEY = crownCellKey(CELL_LAT, CELL_LON);

// A circle centred in that cell, small enough that part of the ~1.1×0.57 km cell
// falls OUTSIDE it — so "every crown is inside the shape" is a real constraint,
// not one the cell satisfies for free.
const AREA_CIRCLE: CrownSpawnAreaShape = {
  type: 'circle',
  center: { lat: CELL_LAT, lon: CELL_LON },
  radiusMeters: 300,
};

async function setSpawnFlag(enabled: boolean): Promise<void> {
  await adminDb
    .collection('config')
    .doc('featureFlags')
    .set({ crownHunt: true, crownHuntSpawn: enabled }, { merge: true });
}

async function seedActivity(cellKey: string, count: number, now: Date): Promise<void> {
  const cellRef = adminDb.collection('crownCellActivity').doc(cellKey);
  const batch = adminDb.batch();
  batch.set(cellRef, { cellKey, lastActivityAt: Timestamp.fromDate(now) });
  for (let i = 0; i < count; i += 1) {
    batch.set(
      cellRef.collection('recentUsers').doc(crownActivityUserHash(cellKey, `caq-seed-${i}`)),
      {
        lastSeenAt: Timestamp.fromDate(now),
      },
    );
  }
  await batch.commit();
}

async function clearSpawns(): Promise<void> {
  const snap = await adminDb.collection('crownSpawns').get();
  if (snap.empty) return;
  const batch = adminDb.batch();
  for (const doc of snap.docs) batch.delete(doc.ref);
  await batch.commit();
}

async function clearActivity(cellKey: string): Promise<void> {
  await adminDb.recursiveDelete(adminDb.collection('crownCellActivity').doc(cellKey));
}

/** Places one live crown directly at the cell centre, so claim tests do not depend on sampling. */
async function placeCrown(
  overrides: Record<string, unknown> = {},
  now: Date = new Date(),
): Promise<string> {
  const ref = adminDb.collection('crownSpawns').doc();
  await ref.set({
    cellKey: CELL_KEY,
    latitude: CELL_LAT,
    longitude: CELL_LON,
    rarity: 'common',
    rewardPoints: 10,
    collectRadiusMeters: COLLECT_RADIUS_METERS,
    collectMode: 'shared',
    status: 'live',
    source: 'auto',
    safeLocationConfirmed: false,
    approvedCellBy: 'admin-seed',
    areaId: null,
    claimedByUid: null,
    claimedAt: null,
    createdAt: Timestamp.fromDate(now),
    expiresAt: Timestamp.fromMillis(now.getTime() + 6 * 60 * 60 * 1000),
    ...overrides,
  });
  return ref.id;
}

let keyCounter = 0;
/** A stationary claim: two fixes 6 s apart, both at the crown, both stopped. */
function claimInput(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  keyCounter += 1;
  const now = Date.now();
  return {
    latitude: CELL_LAT,
    longitude: CELL_LON,
    accuracyMeters: 8,
    speedMetersPerSecond: 0,
    recordedAt: new Date(now).toISOString(),
    previousFix: {
      latitude: CELL_LAT,
      longitude: CELL_LON,
      accuracyMeters: 8,
      speedMetersPerSecond: 0,
      recordedAt: new Date(now - 6000).toISOString(),
    },
    idempotencyKey: `caq-${now}-${keyCounter}`,
    ...overrides,
  };
}

interface ClaimResponse {
  result: string;
  pointsAwarded: number | null;
  newBalance: number | null;
  rarity: string | null;
  message: string;
}

interface AreaMutation {
  areaId: string;
  active: boolean;
  safeAreaConfirmed: boolean;
  removedCrowns: number;
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'crownarea-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('caq-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  hunter = await createProvisionedUser('caq-hunter');
  await adminDb.collection('users').doc(hunter.uid).set({ activeMember: true }, { merge: true });
  rival = await createProvisionedUser('caq-rival');
  await adminDb.collection('users').doc(rival.uid).set({ activeMember: true }, { merge: true });
  third = await createProvisionedUser('caq-third');
  await adminDb.collection('users').doc(third.uid).set({ activeMember: true }, { merge: true });

  await setSpawnFlag(true);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('marked-area CRUD', () => {
  it('requires an admin to create an area', async () => {
    await signInAs(hunter);
    expect(await callableErrorCode(call('crownHunt-createSpawnArea', { shape: AREA_CIRCLE }))).toBe(
      'functions/permission-denied',
    );
  });

  it('refuses to ACTIVATE an area without safeAreaConfirmed', async () => {
    await signInAs(adminUser);
    expect(
      await callableErrorCode(
        call('crownHunt-createSpawnArea', { shape: AREA_CIRCLE, active: true }),
      ),
    ).toBe('functions/invalid-argument');
  });

  it('creates an active area with the safety literal and audits it', async () => {
    await signInAs(adminUser);
    const created = (
      await call('crownHunt-createSpawnArea', {
        shape: AREA_CIRCLE,
        name: 'Torget',
        active: true,
        safeAreaConfirmed: true,
      })
    ).data as AreaMutation;
    expect(created.active).toBe(true);
    expect(created.safeAreaConfirmed).toBe(true);

    const stored = (await adminDb.collection('crownSpawnAreas').doc(created.areaId).get()).data()!;
    expect(stored.active).toBe(true);
    expect(stored.approvedByUserId).toBe(adminUser.uid);
    expect((stored.lastSpawnPassAt as Timestamp).toMillis()).toBe(0);

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'crownHunt.createSpawnArea')
      .where('targetId', '==', created.areaId)
      .get();
    expect(audit.empty).toBe(false);

    // Non-admin cannot list; admin can and sees it.
    await signInAs(hunter);
    expect(await callableErrorCode(call('crownHunt-listSpawnAreas', {}))).toBe(
      'functions/permission-denied',
    );
    await signInAs(adminUser);
    const listed = (await call('crownHunt-listSpawnAreas', { activeOnly: true })).data as {
      areas: { areaId: string }[];
    };
    expect(listed.areas.some((a) => a.areaId === created.areaId)).toBe(true);

    // Cleanup so later spawner tests start from a known set.
    await call('crownHunt-deleteSpawnArea', { areaId: created.areaId });
  });
});

describe('marked-area spawner', () => {
  it('places crowns ONLY inside the drawn shape', async () => {
    await clearSpawns();
    await clearActivity(CELL_KEY);
    await signInAs(adminUser);
    const created = (
      await call('crownHunt-createSpawnArea', {
        shape: AREA_CIRCLE,
        active: true,
        safeAreaConfirmed: true,
      })
    ).data as AreaMutation;

    const now = new Date();
    await seedActivity(CELL_KEY, 12, now);

    const result = await runCrownAreaSpawnPass(
      now,
      { maxAreas: 10, maxCells: 60, maxSpawns: 50 },
      createSeededRng(101),
    );
    expect(result.spawned).toBeGreaterThan(0);

    const spawns = await adminDb
      .collection('crownSpawns')
      .where('areaId', '==', created.areaId)
      .get();
    expect(spawns.empty).toBe(false);
    for (const doc of spawns.docs) {
      const d = doc.data();
      // Every crown sits inside the CIRCLE, not merely inside the grid cell.
      expect(isPointInShape(d.latitude as number, d.longitude as number, AREA_CIRCLE)).toBe(true);
      expect(d.areaId).toBe(created.areaId);
      expect(d.approvedCellBy).toBe(adminUser.uid);
      expect(d.source).toBe('auto');
      expect(d.safeLocationConfirmed).toBe(false);
    }

    // Deactivating the area DRAINS its live crowns immediately.
    const deactivated = (
      await call('crownHunt-updateSpawnArea', { areaId: created.areaId, active: false })
    ).data as AreaMutation;
    expect(deactivated.active).toBe(false);
    expect(deactivated.removedCrowns).toBe(spawns.size);
    const after = await adminDb
      .collection('crownSpawns')
      .where('areaId', '==', created.areaId)
      .get();
    expect(after.empty).toBe(true);

    await call('crownHunt-deleteSpawnArea', { areaId: created.areaId });
    await clearActivity(CELL_KEY);
  });

  it('deleting an area drains its crowns and removes the document', async () => {
    await clearSpawns();
    await clearActivity(CELL_KEY);
    await signInAs(adminUser);
    const created = (
      await call('crownHunt-createSpawnArea', {
        shape: {
          type: 'rectangle',
          bounds: { north: 59.33, south: 59.32, east: 18.08, west: 18.07 },
        },
        active: true,
        safeAreaConfirmed: true,
      })
    ).data as AreaMutation;

    const now = new Date();
    await seedActivity(CELL_KEY, 12, now);
    await runCrownAreaSpawnPass(
      now,
      { maxAreas: 10, maxCells: 60, maxSpawns: 50 },
      createSeededRng(7),
    );
    const before = await adminDb
      .collection('crownSpawns')
      .where('areaId', '==', created.areaId)
      .get();
    expect(before.empty).toBe(false);

    const deleted = (
      await call('crownHunt-deleteSpawnArea', { areaId: created.areaId, reason: 'test' })
    ).data as AreaMutation;
    expect(deleted.removedCrowns).toBe(before.size);
    expect((await adminDb.collection('crownSpawnAreas').doc(created.areaId).get()).exists).toBe(
      false,
    );
    const after = await adminDb
      .collection('crownSpawns')
      .where('areaId', '==', created.areaId)
      .get();
    expect(after.empty).toBe(true);
    await clearActivity(CELL_KEY);
  });

  it('spawns nothing from an area while the flag is off', async () => {
    await clearSpawns();
    await clearActivity(CELL_KEY);
    await signInAs(adminUser);
    const created = (
      await call('crownHunt-createSpawnArea', {
        shape: AREA_CIRCLE,
        active: true,
        safeAreaConfirmed: true,
      })
    ).data as AreaMutation;
    await seedActivity(CELL_KEY, 12, new Date());

    await setSpawnFlag(false);
    const result = await runCrownAreaSpawnPass(new Date());
    expect(result.skipped).toBe(true);
    expect(result.spawned).toBe(0);
    await setSpawnFlag(true);

    await call('crownHunt-deleteSpawnArea', { areaId: created.areaId });
    await clearActivity(CELL_KEY);
  });

  it('SKIPS an oversize area (bounding box past the cap) instead of spawning in a partial box', async () => {
    await clearSpawns();
    await clearActivity(CELL_KEY);
    // Written DIRECTLY: the CRUD callable would reject this shape, so this models
    // a console edit / hand migration that bypassed the size gate. A ~10°×10°
    // rectangle spans ~1e6 grid cells, far past MAX_AREA_CELLS, so enumeration
    // truncates — and the spawner must place NOTHING rather than spawn in the
    // prefix subset the cap kept.
    const areaRef = adminDb.collection('crownSpawnAreas').doc();
    await areaRef.set({
      areaId: areaRef.id,
      name: 'Oversize',
      shape: { type: 'rectangle', bounds: { north: 60, south: 50, east: 20, west: 10 } },
      active: true,
      safeAreaConfirmed: true,
      createdByUserId: adminUser.uid,
      approvedByUserId: adminUser.uid,
      lastSpawnPassAt: Timestamp.fromMillis(0),
      nextCellOffset: 0,
    });
    // Seed activity inside the oversize box so the ONLY thing stopping a spawn is
    // the truncation guard, not the activity floor.
    await seedActivity(crownCellKey(55, 15), 12, new Date());

    const result = await runCrownAreaSpawnPass(
      new Date(),
      { maxAreas: 10, maxCells: 60, maxSpawns: 50 },
      createSeededRng(3),
    );
    expect(result.areasSkippedOversize).toBeGreaterThanOrEqual(1);
    const spawns = await adminDb.collection('crownSpawns').where('areaId', '==', areaRef.id).get();
    expect(spawns.empty).toBe(true);

    await adminDb.recursiveDelete(
      adminDb.collection('crownCellActivity').doc(crownCellKey(55, 15)),
    );
    await areaRef.delete();
  });
});

describe('crown collection model — SHARED', () => {
  it('lets two distinct members each collect once, and the crown PERSISTS', async () => {
    await clearSpawns();
    // Common rarity → shared. No collectMode override needed, but set explicitly.
    const spawnId = await placeCrown({ rarity: 'common', rewardPoints: 10, collectMode: 'shared' });

    await signInAs(hunter);
    const mine = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;
    expect(mine.result).toBe('awarded');
    expect(mine.pointsAwarded).toBe(10);

    // The crown is STILL on the map after the first pickup.
    const afterFirst = (await adminDb.collection('crownSpawns').doc(spawnId).get()).data()!;
    expect(afterFirst.status).toBe('live');

    // The collector record carries an expireAt (= the crown's expiry) so a TTL
    // policy can reap it alongside the crown rather than let it grow unbounded.
    const collector = (
      await adminDb
        .collection('crownSpawnCollectors')
        .doc(spawnCollectorDocId(spawnId, hunter.uid))
        .get()
    ).data()!;
    expect(collector.expireAt).toBeDefined();
    expect((collector.expireAt as Timestamp).toMillis()).toBe(
      (afterFirst.expiresAt as Timestamp).toMillis(),
    );

    await signInAs(rival);
    const theirs = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;
    expect(theirs.result).toBe('awarded');
    expect(theirs.pointsAwarded).toBe(10);

    const afterSecond = (await adminDb.collection('crownSpawns').doc(spawnId).get()).data()!;
    expect(afterSecond.status).toBe('live');
  });

  it("refuses the SAME member's second attempt on a shared crown", async () => {
    await clearSpawns();
    const spawnId = await placeCrown({ rarity: 'common', rewardPoints: 10, collectMode: 'shared' });

    await signInAs(third);
    // Two DIFFERENT idempotency keys, so this is a genuine second attempt, not a
    // replay — the per-(crown, user) collector record must still stop it.
    const first = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;
    expect(first.result).toBe('awarded');
    const second = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;
    expect(second.result).toBe('already_collected');
    expect(second.pointsAwarded).toBeNull();

    // Still live for everyone else.
    const crown = (await adminDb.collection('crownSpawns').doc(spawnId).get()).data()!;
    expect(crown.status).toBe('live');
  });
});

describe('crown collection model — EXCLUSIVE', () => {
  it('first member removes it; the second is told it is gone', async () => {
    await clearSpawns();
    // Legendary → exclusive.
    const spawnId = await placeCrown({
      rarity: 'legendary',
      rewardPoints: 500,
      collectMode: 'exclusive',
    });

    await signInAs(hunter);
    const mine = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;
    expect(mine.result).toBe('awarded');
    expect(mine.pointsAwarded).toBe(500);

    // Removed from the map: claimed and expired at the instant.
    const crown = (await adminDb.collection('crownSpawns').doc(spawnId).get()).data()!;
    expect(crown.status).toBe('claimed');
    expect(crown.claimedByUid).toBe(hunter.uid);

    await signInAs(rival);
    const theirs = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;
    expect(theirs.result).toBe('already_taken');
    expect(theirs.pointsAwarded).toBeNull();
  });
});
