/**
 * Kronjakt emulator integration tests (Phase 9h).
 *
 * Exercises the admin point lifecycle and the full submitClaim validation
 * chain end-to-end, including the atomic Kronpoäng award.
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

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'crownhunt-emulator-tests');
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
let freeUser: TestUser;

// The point placed at Sergels torg; claims report positions relative to it.
const POINT_LAT = 59.3326;
const POINT_LON = 18.0649;

let keyCounter = 0;
function claimInput(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  keyCounter += 1;
  return {
    latitude: POINT_LAT,
    longitude: POINT_LON,
    accuracyMeters: 10,
    speedMetersPerSecond: 0,
    recordedAt: new Date().toISOString(),
    idempotencyKey: `press-${Date.now()}-${keyCounter}`,
    ...overrides,
  };
}

async function createActivePoint(overrides: Record<string, unknown> = {}): Promise<string> {
  await signInAs(adminUser);
  const created = (
    await call('crownHunt-createPoint', {
      title: 'Kronan vid torget',
      latitude: POINT_LAT,
      longitude: POINT_LON,
      geofenceRadiusMeters: 50,
      rewardPoints: 25,
      repeatRule: 'once',
      ...overrides,
    })
  ).data as { pointId: string };
  await call('crownHunt-activatePoint', {
    pointId: created.pointId,
    safeLocationConfirmed: true,
    approvalNote: 'Trygg parkeringsficka intill torget.',
  });
  return created.pointId;
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'crownhunt-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('ch-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  member = await createProvisionedUser('ch-member');
  await adminDb.collection('users').doc(member.uid).set({ activeMember: true }, { merge: true });
  freeUser = await createProvisionedUser('ch-free');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('crownHunt admin point lifecycle', () => {
  it('creates drafts, gates activation behind the safety note, and pauses', async () => {
    await signInAs(adminUser);
    const created = (
      await call('crownHunt-createPoint', {
        title: 'Lifecycle point',
        latitude: POINT_LAT,
        longitude: POINT_LON,
        geofenceRadiusMeters: 30,
        rewardPoints: 10,
        repeatRule: 'daily',
      })
    ).data as { pointId: string; status: string };
    expect(created.status).toBe('draft');

    // Activation without the safety confirmation is invalid input.
    expect(
      await callableErrorCode(
        call('crownHunt-activatePoint', {
          pointId: created.pointId,
          safeLocationConfirmed: false,
          approvalNote: 'Ser bra ut.',
        }),
      ),
    ).toBe('functions/invalid-argument');

    await call('crownHunt-activatePoint', {
      pointId: created.pointId,
      safeLocationConfirmed: true,
      approvalNote: 'Säker plats bekräftad.',
    });
    const active = (await adminDb.collection('crownHuntPoints').doc(created.pointId).get()).data()!;
    expect(active.status).toBe('active');
    expect(active.approvedByUserId).toBe(adminUser.uid);

    // Active points cannot be edited; paused points can.
    expect(
      await callableErrorCode(
        call('crownHunt-updatePoint', { pointId: created.pointId, rewardPoints: 20 }),
      ),
    ).toBe('functions/failed-precondition');
    await call('crownHunt-pausePoint', { pointId: created.pointId, reason: 'Vägarbete' });
    await call('crownHunt-updatePoint', { pointId: created.pointId, rewardPoints: 20 });
    const paused = (await adminDb.collection('crownHuntPoints').doc(created.pointId).get()).data()!;
    expect(paused.status).toBe('paused');
    expect(paused.rewardPoints).toBe(20);

    // Non-admins cannot manage points.
    await signInAs(member);
    expect(
      await callableErrorCode(
        call('crownHunt-createPoint', {
          title: 'Nope',
          latitude: 0,
          longitude: 0,
          geofenceRadiusMeters: 30,
          rewardPoints: 5,
          repeatRule: 'once',
        }),
      ),
    ).toBe('functions/permission-denied');
  });
});

describe('crownHunt-submitClaim', () => {
  it('returns not_eligible for non-members as a result code, not an error', async () => {
    const pointId = await createActivePoint();
    await signInAs(freeUser);
    const response = (await call('crownHunt-submitClaim', claimInput({ pointId }))).data as {
      result: string;
      message: string;
    };
    expect(response.result).toBe('not_eligible');
    expect(response.message).toContain('medlemskap');
  });

  it('awards points atomically and records the claim with ledger linkage', async () => {
    const pointId = await createActivePoint();
    await signInAs(member);
    const response = (await call('crownHunt-submitClaim', claimInput({ pointId }))).data as {
      result: string;
      pointsAwarded: number | null;
      newBalance: number | null;
    };
    expect(response.result).toBe('awarded');
    expect(response.pointsAwarded).toBe(25);

    const claims = await adminDb
      .collection('crownHuntClaims')
      .where('userId', '==', member.uid)
      .where('pointId', '==', pointId)
      .get();
    expect(claims.size).toBe(1);
    const claim = claims.docs[0].data();
    expect(claim.result).toBe('awarded');
    expect(typeof claim.pointsLedgerEntryId).toBe('string');

    const entry = await adminDb
      .collection('pointsLedger')
      .doc(member.uid)
      .collection('entries')
      .doc(claim.pointsLedgerEntryId as string)
      .get();
    expect(entry.exists).toBe(true);
    expect(entry.data()!.source).toBe('crown_hunt');
    expect(entry.data()!.amount).toBe(25);
  });

  it('replays the same idempotency key without double-awarding', async () => {
    const pointId = await createActivePoint();
    await signInAs(member);
    const input = claimInput({ pointId });
    const first = (await call('crownHunt-submitClaim', input)).data as { newBalance: number };
    const replay = (await call('crownHunt-submitClaim', input)).data as {
      result: string;
      newBalance: number | null;
    };
    expect(replay.result).toBe('awarded');
    expect(replay.newBalance).toBe(first.newBalance);

    const balance = (await adminDb.collection('pointsLedger').doc(member.uid).get()).data()!
      .balance as number;
    expect(balance).toBe(first.newBalance);
  });

  it('rejects negative speeds as invalid input (safety gate not bypassable)', async () => {
    const pointId = await createActivePoint();
    await signInAs(member);
    expect(
      await callableErrorCode(
        call('crownHunt-submitClaim', claimInput({ pointId, speedMetersPerSecond: -1 })),
      ),
    ).toBe('functions/invalid-argument');
  });

  it('a same-key failure replay never overwrites an awarded claim record', async () => {
    const pointId = await createActivePoint();
    await signInAs(member);
    const input = claimInput({ pointId });
    expect(
      ((await call('crownHunt-submitClaim', input)).data as { result: string }).result,
    ).toBe('awarded');

    // Same idempotency key, now from outside the geofence — must replay the
    // stored awarded result WITH its award data, not record outside_geofence
    // over it.
    const replay = (
      await call('crownHunt-submitClaim', { ...input, latitude: POINT_LAT + 0.01 })
    ).data as { result: string; pointsAwarded: number | null; newBalance: number | null };
    expect(replay.result).toBe('awarded');
    expect(replay.pointsAwarded).toBe(25);
    expect(replay.newBalance).not.toBeNull();

    // Key reuse across a DIFFERENT point is a duplicate (legacy parity),
    // never a leak of the other claim's result.
    const otherPointId = await createActivePoint();
    await signInAs(member);
    const crossPoint = (
      await call('crownHunt-submitClaim', { ...input, pointId: otherPointId })
    ).data as { result: string };
    expect(crossPoint.result).toBe('already_claimed');

    const claims = await adminDb
      .collection('crownHuntClaims')
      .where('userId', '==', member.uid)
      .where('pointId', '==', pointId)
      .get();
    expect(claims.size).toBe(1);
    expect(claims.docs[0].data().result).toBe('awarded');
  });

  it('enforces the once repeat rule on a fresh key', async () => {
    const pointId = await createActivePoint();
    await signInAs(member);
    expect(
      ((await call('crownHunt-submitClaim', claimInput({ pointId }))).data as { result: string })
        .result,
    ).toBe('awarded');
    const second = (await call('crownHunt-submitClaim', claimInput({ pointId }))).data as {
      result: string;
    };
    expect(second.result).toBe('already_claimed');
  });

  it('rejects claims outside the geofence, at speed, and with stale positions', async () => {
    const pointId = await createActivePoint();
    await signInAs(member);

    // ~1.1 km north of the point.
    const outside = (
      await call('crownHunt-submitClaim', claimInput({ pointId, latitude: POINT_LAT + 0.01 }))
    ).data as { result: string };
    expect(outside.result).toBe('outside_geofence');

    const speeding = (
      await call('crownHunt-submitClaim', claimInput({ pointId, speedMetersPerSecond: 8 }))
    ).data as { result: string };
    expect(speeding.result).toBe('moving_too_fast');

    const stale = (
      await call(
        'crownHunt-submitClaim',
        claimInput({ pointId, recordedAt: new Date(Date.now() - 120_000).toISOString() }),
      )
    ).data as { result: string };
    expect(stale.result).toBe('position_too_old');

    // Failed attempts award nothing.
    const claims = await adminDb
      .collection('crownHuntClaims')
      .where('userId', '==', member.uid)
      .where('pointId', '==', pointId)
      .get();
    expect(claims.docs.every((d) => d.data().result !== 'awarded')).toBe(true);
  });

  it('flags high-risk claims for review without awarding, risk data backend-only', async () => {
    const pointId = await createActivePoint();
    await signInAs(member);
    // Platform integrity failure (40) + poor accuracy (10) + accuracy>50 → 50…
    // add stale? No — combine integrity failure with poor accuracy AND
    // excessive-attempt signals is racy; use integrity failure + impossible?
    // Simplest deterministic high-risk: integrity failed + poor accuracy +
    // stale position would return position_too_old first. Use accuracy 60
    // (poor_gps_accuracy, 10) + platformIntegrityPassed false (40) = 50 — not
    // enough. Geofence buffer keeps accuracy 60 inside (50 + 30 = 80 m). Add
    // a large accuracy AND integrity failure AND 4 rapid attempts (25) → 75.
    for (let i = 0; i < 3; i += 1) {
      await call(
        'crownHunt-submitClaim',
        claimInput({ pointId, latitude: POINT_LAT + 0.01 }), // failed attempts
      );
    }
    const risky = (
      await call(
        'crownHunt-submitClaim',
        claimInput({ pointId, accuracyMeters: 60, platformIntegrityPassed: false }),
      )
    ).data as { result: string; pointsAwarded: number | null };
    expect(risky.result).toBe('risk_review');
    expect(risky.pointsAwarded).toBeNull();

    const riskDocs = await adminDb
      .collection('crownHuntClaimRisk')
      .where('userId', '==', member.uid)
      .where('pointId', '==', pointId)
      .get();
    expect(riskDocs.size).toBe(1);
    expect(riskDocs.docs[0].data().riskScore).toBeGreaterThanOrEqual(60);
    expect(riskDocs.docs[0].data().riskReasons).toContain('platform_integrity_failed');
  });

  it('honors the feature flag from config/featureFlags', async () => {
    const pointId = await createActivePoint();
    await adminDb.collection('config').doc('featureFlags').set({ crownHunt: false });
    try {
      await signInAs(member);
      const response = (await call('crownHunt-submitClaim', claimInput({ pointId }))).data as {
        result: string;
      };
      expect(response.result).toBe('feature_disabled');
    } finally {
      await adminDb.collection('config').doc('featureFlags').set({ crownHunt: true });
    }
  });
});
