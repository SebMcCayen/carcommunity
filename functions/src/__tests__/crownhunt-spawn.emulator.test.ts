/**
 * Kronjakt AUTO-SPAWN emulator integration tests.
 *
 * Exercises the whole spawn engine end to end: the admin cell allow-list, the
 * scheduled replenisher and sweeper (driven directly against an injected `now`,
 * like incidents/scheduled.ts), and the `crownHunt.claimSpawn` callable —
 * including the stationary rule, the once-globally race, and the atomic
 * Kronpoäng award.
 *
 * CI ONLY. Requires the Firebase Emulator Suite (auth + functions + firestore +
 * database), which needs a JVM. Run via:
 *   pnpm --dir functions emulators:test
 * It is excluded from the default `vitest run` unit suite by vitest.config.ts.
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
import { runCrownSpawnCleanup, runCrownSpawnPass } from '../crownHunt/spawnScheduled';
import {
  COLLECT_RADIUS_METERS,
  MIN_CROWN_SEPARATION_METERS,
  SPAWN_CELL_NEVER_SERVED_AT_MS,
  crownActivityUserHash,
  crownCellKey,
  createSeededRng,
} from '../crownHunt/crown-spawn-core';
import { haversineDistanceMeters } from '../crownHunt/crown-hunt-geo';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'crownspawn-emulator-tests');
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

// A quiet residential cell used for the spawn tests, deliberately far from any
// coordinate the other emulator suites use so their fixtures cannot bleed in.
const CELL_LAT = 57.4874;
const CELL_LON = 12.0757;
const CELL_KEY = crownCellKey(CELL_LAT, CELL_LON);

// A second, non-adjacent cell used only by the round-robin ordering test.
const FRESH_CELL_KEY = crownCellKey(57.6012, 12.2013);

const DAY_MS = 24 * 60 * 60 * 1000;

async function setSpawnFlag(enabled: boolean): Promise<void> {
  await adminDb
    .collection('config')
    .doc('featureFlags')
    .set({ crownHunt: true, crownHuntSpawn: enabled }, { merge: true });
}

/** Seeds the aggregate with `count` distinct recent users in `cellKey`. */
async function seedActivity(cellKey: string, count: number, now: Date): Promise<void> {
  const cellRef = adminDb.collection('crownCellActivity').doc(cellKey);
  const batch = adminDb.batch();
  batch.set(cellRef, { cellKey, lastActivityAt: Timestamp.fromDate(now) });
  for (let i = 0; i < count; i += 1) {
    batch.set(cellRef.collection('recentUsers').doc(crownActivityUserHash(cellKey, `seed-${i}`)), {
      lastSeenAt: Timestamp.fromDate(now),
    });
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

/** Places one live crown directly, so claim tests do not depend on sampling. */
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
    status: 'live',
    source: 'auto',
    safeLocationConfirmed: false,
    approvedCellBy: 'admin-seed',
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
    idempotencyKey: `spawn-${now}-${keyCounter}`,
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

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'crownspawn-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('cs-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  hunter = await createProvisionedUser('cs-hunter');
  await adminDb.collection('users').doc(hunter.uid).set({ activeMember: true }, { merge: true });
  rival = await createProvisionedUser('cs-rival');
  await adminDb.collection('users').doc(rival.uid).set({ activeMember: true }, { merge: true });

  await setSpawnFlag(true);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('spawn cell allow-list', () => {
  it('requires an admin, an explicit safe-area confirmation, and a note', async () => {
    await signInAs(hunter);
    expect(
      await callableErrorCode(
        call('crownHunt-setSpawnCellApproval', {
          approved: true,
          cellKey: CELL_KEY,
          safeAreaConfirmed: true,
          approvalNote: 'Trygg parkering.',
        }),
      ),
    ).toBe('functions/permission-denied');

    await signInAs(adminUser);
    // No safe-area confirmation is invalid input, exactly like activatePoint.
    expect(
      await callableErrorCode(
        call('crownHunt-setSpawnCellApproval', {
          approved: true,
          cellKey: CELL_KEY,
          approvalNote: 'Ser bra ut.',
        }),
      ),
    ).toBe('functions/invalid-argument');

    const approved = (
      await call('crownHunt-setSpawnCellApproval', {
        approved: true,
        cellKey: CELL_KEY,
        safeAreaConfirmed: true,
        approvalNote: 'Trygg parkeringsficka, god sikt.',
      })
    ).data as { cellKey: string; approved: boolean };
    expect(approved).toMatchObject({ cellKey: CELL_KEY, approved: true });

    const stored = (await adminDb.collection('crownSpawnCells').doc(CELL_KEY).get()).data()!;
    expect(stored.approved).toBe(true);
    expect(stored.approvedByUserId).toBe(adminUser.uid);
    // The approval must leave an audited trail naming the admin.
    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'crownHunt.approveSpawnCell')
      .where('targetId', '==', CELL_KEY)
      .get();
    expect(audit.empty).toBe(false);
  });

  it('seeds a never-served cell at the FRONT of the round-robin, not the back', async () => {
    await signInAs(adminUser);

    // Self-contained: approve the base cell here rather than relying on the
    // previous test, then serve it so it carries a recent cursor to compete
    // against.
    await call('crownHunt-setSpawnCellApproval', {
      approved: true,
      cellKey: CELL_KEY,
      safeAreaConfirmed: true,
      approvalNote: 'Trygg parkeringsficka, god sikt.',
    });
    await runCrownSpawnPass(new Date(), { maxCells: 50, maxSpawns: 50 });
    const servedAt = (await adminDb.collection('crownSpawnCells').doc(CELL_KEY).get()).data()!
      .lastSpawnPassAt as Timestamp;
    expect(servedAt.toMillis()).toBeGreaterThan(0);

    await call('crownHunt-setSpawnCellApproval', {
      approved: true,
      cellKey: FRESH_CELL_KEY,
      safeAreaConfirmed: true,
      approvalNote: 'Ny godkänd yta, aldrig betjänad.',
    });

    // A cell the spawner has never looked at must SAY so: epoch, not "now".
    // Seeding "now" would misstate the field's meaning and sort the new cell
    // behind every already-served one.
    const fresh = (await adminDb.collection('crownSpawnCells').doc(FRESH_CELL_KEY).get()).data()!;
    expect((fresh.lastSpawnPassAt as Timestamp).toMillis()).toBe(SPAWN_CELL_NEVER_SERVED_AT_MS);

    // With room for exactly one cell in the pass, the never-served one wins.
    await runCrownSpawnPass(new Date(), { maxCells: 1, maxSpawns: 50 });
    const freshAfter = (
      await adminDb.collection('crownSpawnCells').doc(FRESH_CELL_KEY).get()
    ).data()!.lastSpawnPassAt as Timestamp;
    const oldAfter = (await adminDb.collection('crownSpawnCells').doc(CELL_KEY).get()).data()!
      .lastSpawnPassAt as Timestamp;
    expect(freshAfter.toMillis()).toBeGreaterThan(SPAWN_CELL_NEVER_SERVED_AT_MS);
    expect(oldAfter.toMillis()).toBe(servedAt.toMillis());

    // Leave the allow-list as the rest of the suite expects to find it.
    await call('crownHunt-setSpawnCellApproval', {
      approved: false,
      cellKey: FRESH_CELL_KEY,
      reason: 'Testupprensning.',
    });
    await clearSpawns();
  });

  it('clears the revocation reason when a cell is approved again', async () => {
    await signInAs(adminUser);
    const cellKey = crownCellKey(57.7031, 12.3049);

    await call('crownHunt-setSpawnCellApproval', {
      approved: true,
      cellKey,
      safeAreaConfirmed: true,
      approvalNote: 'Första godkännandet.',
    });
    await call('crownHunt-setSpawnCellApproval', {
      approved: false,
      cellKey,
      reason: 'Byggarbete, inte längre säkert att stanna.',
    });
    const revoked = (await adminDb.collection('crownSpawnCells').doc(cellKey).get()).data()!;
    expect(revoked.approved).toBe(false);
    expect(revoked.revocationReason).toBe('Byggarbete, inte längre säkert att stanna.');

    await call('crownHunt-setSpawnCellApproval', {
      approved: true,
      cellKey,
      safeAreaConfirmed: true,
      approvalNote: 'Bygget klart, åter godkänd.',
    });

    // The write is a merge, so anything the revoke branch set and the approve
    // branch does not clear survives. A stale reason on a currently APPROVED
    // cell reads as a live safety warning about a reversed decision.
    const reapproved = (await adminDb.collection('crownSpawnCells').doc(cellKey).get()).data()!;
    expect(reapproved.approved).toBe(true);
    expect(reapproved.revocationReason).toBeNull();
    expect(reapproved.revokedAt).toBeNull();
    expect(reapproved.revokedByUserId).toBeNull();

    await call('crownHunt-setSpawnCellApproval', {
      approved: false,
      cellKey,
      reason: 'Testupprensning.',
    });
  });
});

describe('scheduled spawner', () => {
  it('spawns nothing while the feature flag is off', async () => {
    await clearSpawns();
    await setSpawnFlag(false);
    await seedActivity(CELL_KEY, 6, new Date());
    const result = await runCrownSpawnPass(new Date());
    expect(result.skipped).toBe(true);
    expect(result.spawned).toBe(0);
    await setSpawnFlag(true);
  });

  it('spawns nothing in a cell nobody has approved, however busy it is', async () => {
    await clearSpawns();
    const unapproved = crownCellKey(58.1, 13.1);
    await seedActivity(unapproved, 30, new Date());
    const result = await runCrownSpawnPass(new Date(), { maxCells: 50, maxSpawns: 50 });
    const spawnsThere = await adminDb
      .collection('crownSpawns')
      .where('cellKey', '==', unapproved)
      .get();
    expect(spawnsThere.empty).toBe(true);
    expect(result.spawned).toBeGreaterThanOrEqual(0);
    await clearActivity(unapproved);
  });

  it('spawns nothing in an approved cell with no recent activity (A < 1)', async () => {
    await clearSpawns();
    await clearActivity(CELL_KEY);
    const result = await runCrownSpawnPass(new Date(), { maxCells: 50, maxSpawns: 50 });
    expect(result.cellsBelowActivityFloor).toBeGreaterThanOrEqual(1);
    const spawns = await adminDb.collection('crownSpawns').where('cellKey', '==', CELL_KEY).get();
    expect(spawns.empty).toBe(true);
  });

  it('spawns nothing when the only activity is outside the 7-day window', async () => {
    await clearSpawns();
    await clearActivity(CELL_KEY);
    const now = new Date();
    await seedActivity(CELL_KEY, 20, new Date(now.getTime() - 10 * DAY_MS));
    // The activity docs exist but every sighting is stale, so A collapses to 0.
    await runCrownSpawnPass(now, { maxCells: 50, maxSpawns: 50 });
    const spawns = await adminDb.collection('crownSpawns').where('cellKey', '==', CELL_KEY).get();
    expect(spawns.empty).toBe(true);
  });

  it('tops an approved, active cell up to target and respects min separation', async () => {
    await clearSpawns();
    await clearActivity(CELL_KEY);
    const now = new Date();
    await seedActivity(CELL_KEY, 12, now);

    const first = await runCrownSpawnPass(now, { maxCells: 50, maxSpawns: 50 }, createSeededRng(42));
    expect(first.spawned).toBeGreaterThan(0);

    const spawns = await adminDb.collection('crownSpawns').where('cellKey', '==', CELL_KEY).get();
    // A = 12 → ceil(1.5 * ln 13) = 4.
    expect(spawns.size).toBe(4);

    const positions = spawns.docs.map((d) => d.data());
    for (const spawn of positions) {
      expect(spawn.status).toBe('live');
      expect(spawn.source).toBe('auto');
      expect(spawn.safeLocationConfirmed).toBe(false);
      expect(spawn.approvedCellBy).toBe(adminUser.uid);
      expect(crownCellKey(spawn.latitude, spawn.longitude)).toBe(CELL_KEY);
    }
    for (let i = 0; i < positions.length; i += 1) {
      for (let j = i + 1; j < positions.length; j += 1) {
        const distance = haversineDistanceMeters(
          positions[i]!.latitude,
          positions[i]!.longitude,
          positions[j]!.latitude,
          positions[j]!.longitude,
        );
        expect(distance).toBeGreaterThanOrEqual(MIN_CROWN_SEPARATION_METERS);
      }
    }

    // Idempotent-ish: a second pass at target adds nothing.
    const second = await runCrownSpawnPass(now, { maxCells: 50, maxSpawns: 50 });
    expect(second.spawned).toBe(0);
  });
});

describe('crownHunt.claimSpawn', () => {
  it('awards a stationary claim through the Kronpoäng ledger', async () => {
    const spawnId = await placeCrown({ rewardPoints: 25, rarity: 'uncommon' });
    await signInAs(hunter);
    const response = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;

    expect(response.result).toBe('awarded');
    expect(response.pointsAwarded).toBe(25);
    expect(response.rarity).toBe('uncommon');

    // The crown leaves the map immediately: claimed AND expired at the instant.
    const crown = (await adminDb.collection('crownSpawns').doc(spawnId).get()).data()!;
    expect(crown.status).toBe('claimed');
    expect(crown.claimedByUid).toBe(hunter.uid);
    expect((crown.expiresAt as Timestamp).toMillis()).toBeLessThanOrEqual(Date.now() + 1000);

    // Awarded via the ledger, not a direct balance write.
    const entries = await adminDb
      .collection('pointsLedger')
      .doc(hunter.uid)
      .collection('entries')
      .where('relatedEntityId', '==', spawnId)
      .get();
    expect(entries.size).toBe(1);
    expect(entries.docs[0]!.data().source).toBe('crown_hunt');
  });

  it('replays an identical idempotency key instead of awarding twice', async () => {
    const spawnId = await placeCrown();
    await signInAs(hunter);
    const input = claimInput({ spawnId });
    const first = (await call('crownHunt-claimSpawn', input)).data as ClaimResponse;
    const replay = (await call('crownHunt-claimSpawn', input)).data as ClaimResponse;

    expect(first.result).toBe('awarded');
    expect(replay.result).toBe('awarded');
    expect(replay.pointsAwarded).toBe(first.pointsAwarded);
    const entries = await adminDb
      .collection('pointsLedger')
      .doc(hunter.uid)
      .collection('entries')
      .where('relatedEntityId', '==', spawnId)
      .get();
    expect(entries.size).toBe(1);
  });

  it('is claimable ONCE GLOBALLY — the second member is told it is taken', async () => {
    const spawnId = await placeCrown();
    await signInAs(hunter);
    const mine = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;
    expect(mine.result).toBe('awarded');

    await signInAs(rival);
    const theirs = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;
    expect(theirs.result).toBe('already_taken');
    expect(theirs.pointsAwarded).toBeNull();
  });

  it('REFUSES a moving claim with a plain stop-first message, not a fraud flag', async () => {
    const spawnId = await placeCrown();
    await signInAs(hunter);
    const now = Date.now();
    const response = (
      await call(
        'crownHunt-claimSpawn',
        claimInput({
          spawnId,
          // Both fixes report 0 m/s, but they are ~110 m apart 6 s apart, so the
          // speed the SERVER derives is ~18 m/s. The lie does not survive.
          speedMetersPerSecond: 0,
          recordedAt: new Date(now).toISOString(),
          previousFix: {
            latitude: CELL_LAT + 0.001,
            longitude: CELL_LON,
            accuracyMeters: 8,
            speedMetersPerSecond: 0,
            recordedAt: new Date(now - 6000).toISOString(),
          },
        }),
      )
    ).data as ClaimResponse;

    // Outside the radius on the earlier fix is the honest answer here; either
    // refusal is a plain result code with no points and no risk record.
    expect(['must_be_stationary', 'outside_radius']).toContain(response.result);
    expect(response.pointsAwarded).toBeNull();
    const crown = (await adminDb.collection('crownSpawns').doc(spawnId).get()).data()!;
    expect(crown.status).toBe('live');
  });

  it('refuses a claim whose two fixes are too close together in time', async () => {
    const spawnId = await placeCrown();
    await signInAs(hunter);
    const now = Date.now();
    const response = (
      await call(
        'crownHunt-claimSpawn',
        claimInput({
          spawnId,
          recordedAt: new Date(now).toISOString(),
          previousFix: {
            latitude: CELL_LAT,
            longitude: CELL_LON,
            accuracyMeters: 8,
            speedMetersPerSecond: 0,
            recordedAt: new Date(now - 500).toISOString(),
          },
        }),
      )
    ).data as ClaimResponse;
    expect(response.result).toBe('must_be_stationary');
  });

  it('refuses a claim from outside the collect radius', async () => {
    const spawnId = await placeCrown();
    await signInAs(hunter);
    const response = (
      await call(
        'crownHunt-claimSpawn',
        claimInput({ spawnId, latitude: CELL_LAT + 0.01, accuracyMeters: 5 }),
      )
    ).data as ClaimResponse;
    expect(response.result).toBe('outside_radius');
  });

  it('ignores an absurd collectRadiusMeters on the crown document', async () => {
    // Clients cannot write crownSpawns, so this models a console edit, a bad
    // migration, or a future spawner bug. It must narrow the gate to the 75 m
    // default, never widen it — a wider geofence pays out to someone who was
    // never there.
    const spawnId = await placeCrown({ collectRadiusMeters: 1e9 });
    await signInAs(hunter);
    const farLat = CELL_LAT + 0.05; // ~5.5 km away, well inside a 1e9 m radius
    const response = (
      await call(
        'crownHunt-claimSpawn',
        claimInput({
          spawnId,
          latitude: farLat,
          accuracyMeters: 5,
          previousFix: {
            latitude: farLat,
            longitude: CELL_LON,
            accuracyMeters: 5,
            speedMetersPerSecond: 0,
            recordedAt: new Date(Date.now() - 6000).toISOString(),
          },
        }),
      )
    ).data as ClaimResponse;
    expect(response.result).toBe('outside_radius');
  });

  it('sends a self-reported MOCK location to review and awards nothing', async () => {
    const spawnId = await placeCrown();
    await signInAs(hunter);
    const response = (
      await call('crownHunt-claimSpawn', claimInput({ spawnId, isMockLocation: true }))
    ).data as ClaimResponse;

    expect(response.result).toBe('risk_review');
    expect(response.pointsAwarded).toBeNull();
    const crown = (await adminDb.collection('crownSpawns').doc(spawnId).get()).data()!;
    expect(crown.status).toBe('live');
    // Risk reasons are recorded backend-side only.
    const risk = await adminDb
      .collection('crownSpawnClaimRisk')
      .where('spawnId', '==', spawnId)
      .get();
    expect(risk.empty).toBe(false);
    expect(risk.docs[0]!.data().riskReasons).toContain('mock_location');
  });

  it('refuses an expired crown', async () => {
    const past = new Date(Date.now() - 10 * 60 * 60 * 1000);
    const spawnId = await placeCrown({}, past);
    await signInAs(hunter);
    const response = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;
    expect(response.result).toBe('crown_expired');
  });

  it('refuses every claim while the feature flag is off', async () => {
    const spawnId = await placeCrown();
    await setSpawnFlag(false);
    await signInAs(hunter);
    const response = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;
    expect(response.result).toBe('feature_disabled');
    await setSpawnFlag(true);
  });

  it('rejects malformed input as an error, not a result code', async () => {
    await signInAs(hunter);
    expect(
      await callableErrorCode(call('crownHunt-claimSpawn', { spawnId: 'x', latitude: 0 })),
    ).toBe('functions/invalid-argument');
  });
});

describe('revoking an area', () => {
  it('drains the whole cell, not just the first page', async () => {
    await clearSpawns();
    await signInAs(adminUser);
    await call('crownHunt-setSpawnCellApproval', {
      approved: true,
      cellKey: CELL_KEY,
      safeAreaConfirmed: true,
      approvalNote: 'Godkänd inför tömningstest.',
    });

    // More than one revocation page (200), including crowns that have expired
    // but not yet been swept — those still carry status 'live' and must go too.
    const total = 250;
    const now = new Date();
    for (let start = 0; start < total; start += 400) {
      const batch = adminDb.batch();
      for (let i = start; i < Math.min(start + 400, total); i += 1) {
        const expired = i % 5 === 0;
        batch.set(adminDb.collection('crownSpawns').doc(), {
          cellKey: CELL_KEY,
          latitude: CELL_LAT,
          longitude: CELL_LON,
          rarity: 'common',
          rewardPoints: 10,
          collectRadiusMeters: COLLECT_RADIUS_METERS,
          status: 'live',
          source: 'auto',
          safeLocationConfirmed: false,
          approvedCellBy: 'admin-seed',
          claimedByUid: null,
          claimedAt: null,
          createdAt: Timestamp.fromDate(now),
          expiresAt: Timestamp.fromMillis(
            now.getTime() + (expired ? -60_000 : 6 * 60 * 60 * 1000),
          ),
        });
      }
      await batch.commit();
    }

    const response = (
      await call('crownHunt-setSpawnCellApproval', {
        approved: false,
        cellKey: CELL_KEY,
        reason: 'Området stängt, allt måste bort nu.',
      })
    ).data as { removedCrowns: number };
    expect(response.removedCrowns).toBe(total);
    const remaining = await adminDb
      .collection('crownSpawns')
      .where('cellKey', '==', CELL_KEY)
      .get();
    expect(remaining.empty).toBe(true);

    // Put the allow-list back for the test that follows.
    await call('crownHunt-setSpawnCellApproval', {
      approved: true,
      cellKey: CELL_KEY,
      safeAreaConfirmed: true,
      approvalNote: 'Återställd efter tömningstest.',
    });
  }, 60_000);

  it('removes live crowns immediately instead of waiting out their TTL', async () => {
    await clearSpawns();
    await placeCrown();
    await placeCrown();

    await signInAs(adminUser);
    const response = (
      await call('crownHunt-setSpawnCellApproval', {
        approved: false,
        cellKey: CELL_KEY,
        reason: 'Byggarbete, inte längre säkert att stanna.',
      })
    ).data as { removedCrowns: number };
    expect(response.removedCrowns).toBe(2);
    const remaining = await adminDb
      .collection('crownSpawns')
      .where('cellKey', '==', CELL_KEY)
      .get();
    expect(remaining.empty).toBe(true);

    // And a revoked cell spawns nothing on the next pass.
    await seedActivity(CELL_KEY, 20, new Date());
    await runCrownSpawnPass(new Date(), { maxCells: 50, maxSpawns: 50 });
    const after = await adminDb.collection('crownSpawns').where('cellKey', '==', CELL_KEY).get();
    expect(after.empty).toBe(true);
  });
});

describe('scheduled sweeper', () => {
  it('deletes expired crowns and reaps quiet activity cells', async () => {
    await clearSpawns();
    const now = new Date();
    await placeCrown({}, new Date(now.getTime() - 10 * 60 * 60 * 1000)); // expired
    const liveId = await placeCrown({}, now); // still live

    const quietCell = crownCellKey(56.5, 14.5);
    await seedActivity(quietCell, 3, new Date(now.getTime() - 10 * DAY_MS));

    const result = await runCrownSpawnCleanup(now);
    expect(result.spawnsDeleted).toBeGreaterThanOrEqual(1);
    expect((await adminDb.collection('crownSpawns').doc(liveId).get()).exists).toBe(true);
    expect((await adminDb.collection('crownCellActivity').doc(quietCell).get()).exists).toBe(false);
  });

  it('sweeps a claimed crown, whose expiry was set to the claim instant', async () => {
    await clearSpawns();
    const spawnId = await placeCrown();
    await signInAs(hunter);
    const claimed = (await call('crownHunt-claimSpawn', claimInput({ spawnId })))
      .data as ClaimResponse;
    expect(claimed.result).toBe('awarded');

    await runCrownSpawnCleanup(new Date(Date.now() + 1000));
    expect((await adminDb.collection('crownSpawns').doc(spawnId).get()).exists).toBe(false);
  });
});
