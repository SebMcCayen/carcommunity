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
import { FieldValue, getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { pointCollectorDocId } from '../crownHunt/crownhunt-core';

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
        latitude: POINT_LAT,
        longitude: POINT_LON,
        geofenceRadiusMeters: 30,
        rewardPoints: 10,
        repeatRule: 'daily',
      })
    ).data as { pointId: string; status: string };
    expect(created.status).toBe('draft');

    // A Crown is a textless collectable: create accepts no title/description,
    // and the stored doc carries title '' / description null for reader
    // back-compat while still storing the reward.
    const draftDoc = (
      await adminDb.collection('crownHuntPoints').doc(created.pointId).get()
    ).data()!;
    expect(draftDoc.title).toBe('');
    expect(draftDoc.description).toBeNull();
    expect(draftDoc.rewardPoints).toBe(10);

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
  it('AWARDS a non-member while member gating is disabled', async () => {
    // Was: not_eligible (a result code, not an error). Re-locking restores it.
    const pointId = await createActivePoint();
    await signInAs(freeUser);
    const response = (await call('crownHunt-submitClaim', claimInput({ pointId }))).data as {
      result: string;
      pointsAwarded: number | null;
    };
    expect(response.result).toBe('awarded');
    expect(response.pointsAwarded).toBe(25);
  });

  it('STILL returns not_eligible for a suspended user, as a result code', async () => {
    const pointId = await createActivePoint();
    const suspended = await createProvisionedUser('ch-suspended');
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
    const response = (await call('crownHunt-submitClaim', claimInput({ pointId }))).data as {
      result: string;
    };
    expect(response.result).toBe('not_eligible');
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
    // size asserted === 1 above, so docs[0] is present.
    const claim = claims.docs[0]!.data();
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
    // size asserted === 1 above, so docs[0] is present.
    expect(claims.docs[0]!.data().result).toBe('awarded');
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

  it('awards only once when many claims race with distinct idempotency keys', async () => {
    // The core race fix (H2): N concurrent submitClaim calls for the same
    // once-point, each with a DIFFERENT idempotency key (so the ledger's
    // key-based idempotency does NOT collapse them), must produce exactly one
    // award. Before the deterministic in-transaction award guard, every
    // parallel request passed the non-transactional repeat-rule pre-check and
    // double-credited the Kronpoäng balance.
    const pointId = await createActivePoint({ rewardPoints: 25 });
    const racer = await createProvisionedUser('ch-racer');
    await adminDb.collection('users').doc(racer.uid).set({ activeMember: true }, { merge: true });
    await signInAs(racer);

    const CONCURRENCY = 6;
    // Do NOT swallow rejections: a losing racer must return the 'already_claimed'
    // result code, never throw. Letting Promise.all reject makes the test fail
    // loudly if the server ever surfaces an internal error for a loser.
    const results = await Promise.all(
      Array.from({ length: CONCURRENCY }, () =>
        call('crownHunt-submitClaim', claimInput({ pointId })).then(
          (r) => (r.data as { result: string }).result,
        ),
      ),
    );

    // Exactly one winner; every loser gets the authoritative repeat-rule code.
    expect(results.filter((r) => r === 'awarded').length).toBe(1);
    expect(results.filter((r) => r === 'already_claimed').length).toBe(CONCURRENCY - 1);

    // Exactly one awarded claim row and one ledger credit — no inflation.
    const awardedClaims = await adminDb
      .collection('crownHuntClaims')
      .where('userId', '==', racer.uid)
      .where('pointId', '==', pointId)
      .where('result', '==', 'awarded')
      .get();
    expect(awardedClaims.size).toBe(1);

    const balance = (await adminDb.collection('pointsLedger').doc(racer.uid).get()).data()!
      .balance as number;
    expect(balance).toBe(25);

    // The deterministic guard document exists exactly once for this window.
    const guards = await adminDb
      .collection('crownHuntAwardGuards')
      .where('userId', '==', racer.uid)
      .where('pointId', '==', pointId)
      .get();
    expect(guards.size).toBe(1);
  });

  it('seeds the daily counter from prior awards when no counter doc exists', async () => {
    // Mid-day-deploy / deleted-counter safety: a fresh award must not reset
    // the daily cap to zero. Seed 3 awarded claims for today with NO
    // crownHuntDailyClaims counter, then a new award must persist an absolute
    // count of 4 (3 prior + 1), not 1.
    const capUser = await createProvisionedUser('ch-cap');
    await adminDb.collection('users').doc(capUser.uid).set({ activeMember: true }, { merge: true });
    const today = new Date();
    for (let i = 0; i < 3; i += 1) {
      await adminDb.collection('crownHuntClaims').doc(`seed-${capUser.uid}-${i}`).set({
        userId: capUser.uid,
        pointId: `seed-point-${i}`,
        result: 'awarded',
        claimedAt: today,
        createdAt: today,
      });
    }

    const pointId = await createActivePoint();
    await signInAs(capUser);
    const response = (await call('crownHunt-submitClaim', claimInput({ pointId }))).data as {
      result: string;
    };
    expect(response.result).toBe('awarded');

    const counters = await adminDb
      .collection('crownHuntDailyClaims')
      .where('userId', '==', capUser.uid)
      .get();
    expect(counters.size).toBe(1);
    // size asserted === 1 above, so docs[0] is present.
    expect(counters.docs[0]!.data().count).toBe(4);
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
    // size asserted === 1 above, so docs[0] is present.
    expect(riskDocs.docs[0]!.data().riskScore).toBeGreaterThanOrEqual(60);
    expect(riskDocs.docs[0]!.data().riskReasons).toContain('platform_integrity_failed');
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

// ---------------------------------------------------------------------------
// maxCollectors — per-crown distinct-collector cap (manual crowns)
// ---------------------------------------------------------------------------

describe('crownHunt maxCollectors (distinct-collector cap)', () => {
  /** A fresh active member who is NOT reused across tests, so distinct-collector
   *  counts are deterministic even though the emulator shares one Firestore. */
  async function createFreshMember(prefix: string): Promise<TestUser> {
    const user = await createProvisionedUser(prefix);
    await adminDb.collection('users').doc(user.uid).set({ activeMember: true }, { merge: true });
    return user;
  }

  async function collectAs(user: TestUser, pointId: string): Promise<string> {
    await signInAs(user);
    const response = (await call('crownHunt-submitClaim', claimInput({ pointId }))).data as {
      result: string;
    };
    return response.result;
  }

  it('validates N: maxCollectors must be an integer >= 1', async () => {
    await signInAs(adminUser);
    for (const bad of [0, -1, 1.5]) {
      expect(
        await callableErrorCode(
          call('crownHunt-createPoint', {
            latitude: POINT_LAT,
            longitude: POINT_LON,
            geofenceRadiusMeters: 50,
            rewardPoints: 25,
            repeatRule: 'once',
            maxCollectors: bad,
          }),
        ),
      ).toBe('functions/invalid-argument');
    }
  });

  it('stores maxCollectors=null (unlimited) by default and lets many distinct users collect', async () => {
    // Default create sends no maxCollectors → stored null (unlimited). Two
    // distinct users both collect; the same user collecting twice is rejected;
    // the point stays active and is not headcount-tracked.
    const pointId = await createActivePoint();
    const stored = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(stored.maxCollectors).toBeNull();
    expect(stored.collectorCount).toBe(0);

    const a = await createFreshMember('ch-unl-a');
    const b = await createFreshMember('ch-unl-b');
    expect(await collectAs(a, pointId)).toBe('awarded');
    expect(await collectAs(b, pointId)).toBe('awarded');

    // Same user again (once rule) → already_claimed, no cap involvement.
    await signInAs(a);
    expect(
      ((await call('crownHunt-submitClaim', claimInput({ pointId }))).data as { result: string })
        .result,
    ).toBe('already_claimed');

    // Unlimited crowns are never deactivated by collection and never write
    // collector markers.
    const after = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(after.status).toBe('active');
    expect(after.collectorCount).toBe(0);
    const markers = await adminDb
      .collection('crownHuntPointCollectors')
      .where('pointId', '==', pointId)
      .get();
    expect(markers.size).toBe(0);
  });

  it('Limited-to-2: first two distinct users collect, third is rejected, crown deactivates', async () => {
    const pointId = await createActivePoint({ maxCollectors: 2 });
    const created = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(created.maxCollectors).toBe(2);

    const first = await createFreshMember('ch-lim-1');
    const second = await createFreshMember('ch-lim-2');
    const third = await createFreshMember('ch-lim-3');

    expect(await collectAs(first, pointId)).toBe('awarded');
    const afterFirst = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(afterFirst.collectorCount).toBe(1);
    expect(afterFirst.status).toBe('active');

    // Second distinct collector fills the cap → the crown is done.
    expect(await collectAs(second, pointId)).toBe('awarded');
    const afterSecond = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(afterSecond.collectorCount).toBe(2);
    expect(afterSecond.status).toBe('ended');

    // Third user is rejected — the point is inactive (collected out) and stops
    // rendering (members read only active points).
    expect(await collectAs(third, pointId)).toBe('point_inactive');
    const afterThird = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(afterThird.collectorCount).toBe(2);

    // Exactly two distinct-collector markers and two awarded claims.
    const markers = await adminDb
      .collection('crownHuntPointCollectors')
      .where('pointId', '==', pointId)
      .get();
    expect(markers.size).toBe(2);
    const awarded = await adminDb
      .collection('crownHuntClaims')
      .where('pointId', '==', pointId)
      .where('result', '==', 'awarded')
      .get();
    expect(awarded.size).toBe(2);
  });

  it('Limited-to-1 is a single exclusive collector', async () => {
    const pointId = await createActivePoint({ maxCollectors: 1 });
    const only = await createFreshMember('ch-solo-1');
    const other = await createFreshMember('ch-solo-2');
    expect(await collectAs(only, pointId)).toBe('awarded');
    const doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.status).toBe('ended');
    expect(doc.collectorCount).toBe(1);
    expect(await collectAs(other, pointId)).toBe('point_inactive');
  });

  it('consumes a slot only on AWARD, never on a failed attempt', async () => {
    // A slot is tied to a distinct AWARDED collector, computed from the award
    // transaction's own reads — not to attempts. A failed (non-awarding)
    // attempt must not move collectorCount, and the same user then succeeding
    // must take exactly one slot.
    const pointId = await createActivePoint({ maxCollectors: 2 });
    const a = await createFreshMember('ch-slot-a');
    const b = await createFreshMember('ch-slot-b');
    expect(await collectAs(a, pointId)).toBe('awarded');

    // b misses the geofence — no award, so no slot is consumed.
    await signInAs(b);
    const outside = (
      await call('crownHunt-submitClaim', claimInput({ pointId, latitude: POINT_LAT + 0.01 }))
    ).data as { result: string };
    expect(outside.result).toBe('outside_geofence');
    const mid = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(mid.collectorCount).toBe(1);
    expect(mid.status).toBe('active');

    // b now collects properly → exactly one new slot, filling the cap.
    expect(await collectAs(b, pointId)).toBe('awarded');
    const after = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(after.collectorCount).toBe(2);
    expect(after.status).toBe('ended');
    const markers = await adminDb
      .collection('crownHuntPointCollectors')
      .where('pointId', '==', pointId)
      .get();
    expect(markers.size).toBe(2);
  });

  it('retires a limited crown to ended when an admin lowers the cap to <= collectorCount', async () => {
    // "Full → done": lowering maxCollectors at or below the live collectorCount
    // must retire the crown (status 'ended') AT UPDATE TIME — the same
    // collected-out state submitClaim sets when the Nth collector is awarded —
    // rather than leaving a reactivatable point that renders active but is
    // uncollectable (every new user would hit "full").
    const pointId = await createActivePoint({ maxCollectors: 3 });
    const a = await createFreshMember('ch-lower-a');
    const b = await createFreshMember('ch-lower-b');
    expect(await collectAs(a, pointId)).toBe('awarded');
    expect(await collectAs(b, pointId)).toBe('awarded');
    let doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.collectorCount).toBe(2);
    expect(doc.status).toBe('active'); // cap 3 not yet reached

    // Admin pauses, then lowers the cap to 1 (below collectorCount 2).
    await signInAs(adminUser);
    await call('crownHunt-pausePoint', { pointId, reason: 'Justerar antalet insamlare.' });
    const updated = (await call('crownHunt-updatePoint', { pointId, maxCollectors: 1 })).data as {
      status: string;
    };
    // The update itself retires the now-full crown.
    expect(updated.status).toBe('ended');
    doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.status).toBe('ended');
    expect(doc.collectorCount).toBe(2);

    // It cannot be reactivated (ended points are terminal) and no longer
    // collects — a fresh user gets point_inactive at the availability check.
    expect(
      await callableErrorCode(
        call('crownHunt-activatePoint', {
          pointId,
          safeLocationConfirmed: true,
          approvalNote: 'Försök att återaktivera.',
        }),
      ),
    ).toBe('functions/failed-precondition');
    const c = await createFreshMember('ch-lower-c');
    expect(await collectAs(c, pointId)).toBe('point_inactive');
    doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.collectorCount).toBe(2);
  });

  it('daily limited crown: an existing collector re-collects in a new window without taking a slot; new collectors still capped', async () => {
    // maxCollectors caps the HEADCOUNT; it must NOT turn a daily/weekly crown
    // into a one-shot. An existing collector re-collecting in a fresh window is
    // allowed (award guard governs the window) and consumes NO new slot, while
    // NEW distinct collectors are still capped.
    const pointId = await createActivePoint({ maxCollectors: 2, repeatRule: 'daily' });
    const a = await createFreshMember('ch-daily-a');

    // Simulate A having collected in a PRIOR daily window: an awarded claim
    // dated yesterday (so today's repeat-window query passes), the distinct-
    // collector marker, and collectorCount = 1. No award guard for TODAY, so
    // today's claim reaches the collector block with the marker already present.
    const yesterday = new Date(Date.now() - 24 * 60 * 60 * 1000);
    await adminDb.collection('crownHuntClaims').doc(`seed-daily-${a.uid}`).set({
      userId: a.uid,
      pointId,
      result: 'awarded',
      claimedAt: yesterday,
      createdAt: yesterday,
    });
    await adminDb
      .collection('crownHuntPointCollectors')
      .doc(pointCollectorDocId(pointId, a.uid))
      .set({ pointId, userId: a.uid, collectedAt: yesterday, createdAt: yesterday });
    await adminDb.collection('crownHuntPoints').doc(pointId).update({ collectorCount: 1 });

    // A re-collects TODAY (new award-guard window): allowed, no new slot.
    expect(await collectAs(a, pointId)).toBe('awarded');
    let doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.collectorCount).toBe(1); // unchanged — existing collector
    expect(doc.status).toBe('active');
    const markers = await adminDb
      .collection('crownHuntPointCollectors')
      .where('pointId', '==', pointId)
      .get();
    expect(markers.size).toBe(1); // still just A's marker

    // A NEW distinct collector consumes the 2nd slot and fills the cap → ended.
    const b = await createFreshMember('ch-daily-b');
    expect(await collectAs(b, pointId)).toBe('awarded');
    doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.collectorCount).toBe(2);
    expect(doc.status).toBe('ended');

    // A third new user is rejected — the crown is done.
    const c = await createFreshMember('ch-daily-c');
    expect(await collectAs(c, pointId)).toBe('point_inactive');
  });

  it('rejects enabling a collector limit on a previously-unlimited crown that has already been collected', async () => {
    // Unlimited crowns never counted distinct collectors (no markers,
    // collectorCount stays 0), so a cap added AFTER awards exist would be
    // unenforceable — reject the unlimited→limited transition.
    const pointId = await createActivePoint(); // unlimited (maxCollectors null)
    const a = await createFreshMember('ch-enable-a');
    expect(await collectAs(a, pointId)).toBe('awarded');
    let doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.maxCollectors).toBeNull();
    expect(doc.collectorCount).toBe(0); // unlimited never tracked collectors

    await signInAs(adminUser);
    await call('crownHunt-pausePoint', { pointId, reason: 'Vill lägga till ett tak.' });
    // Adding a cap now is rejected as invalid.
    expect(
      await callableErrorCode(call('crownHunt-updatePoint', { pointId, maxCollectors: 5 })),
    ).toBe('functions/invalid-argument');
    doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.maxCollectors).toBeNull(); // unchanged

    // Other edits (that keep it unlimited) still work.
    await call('crownHunt-updatePoint', { pointId, rewardPoints: 100 });
    doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.rewardPoints).toBe(100);
    expect(doc.maxCollectors).toBeNull();
  });

  it('allows changing N (limited -> limited) on an already-collected limited crown', async () => {
    // Only the unenforceable unlimited→limited transition is blocked; raising or
    // lowering N on a crown that was ALWAYS limited (and thus tracked
    // collectors) stays allowed.
    const pointId = await createActivePoint({ maxCollectors: 5 });
    const a = await createFreshMember('ch-changeN-a');
    expect(await collectAs(a, pointId)).toBe('awarded');
    let doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.collectorCount).toBe(1);

    await signInAs(adminUser);
    await call('crownHunt-pausePoint', { pointId, reason: 'Justerar N.' });
    await call('crownHunt-updatePoint', { pointId, maxCollectors: 10 });
    doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.maxCollectors).toBe(10);
    expect(doc.status).toBe('paused'); // still above the collector count, not retired
  });

  it('back-compat: a point with no maxCollectors field behaves as unlimited', async () => {
    // Simulate a pre-existing point created before this feature: activate a
    // point, then strip the maxCollectors/collectorCount fields to mimic the
    // old document shape, and confirm distinct users still collect with no cap.
    const pointId = await createActivePoint();
    await adminDb.collection('crownHuntPoints').doc(pointId).update({
      maxCollectors: FieldValue.delete(),
      collectorCount: FieldValue.delete(),
    });
    const legacy = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(legacy.maxCollectors).toBeUndefined();

    const a = await createFreshMember('ch-legacy-a');
    const b = await createFreshMember('ch-legacy-b');
    expect(await collectAs(a, pointId)).toBe('awarded');
    expect(await collectAs(b, pointId)).toBe('awarded');
    const after = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(after.status).toBe('active');
  });

  it('caps DISTINCT collectors across many users (event scenario)', async () => {
    // maxCollectors=2 with 6 distinct users: exactly two awards, the rest
    // rejected as inactive, and the crown ends. (A single Auth client holds one
    // currentUser, so the users are driven back-to-back; the in-transaction
    // collectorCount is the authoritative guard regardless of interleaving.)
    const pointId = await createActivePoint({ maxCollectors: 2 });
    const users = await Promise.all(
      Array.from({ length: 6 }, (_unused, i) => createFreshMember(`ch-burst-${i}`)),
    );
    const results: string[] = [];
    for (const u of users) {
      results.push(await collectAs(u, pointId));
    }
    expect(results.filter((r) => r === 'awarded').length).toBe(2);
    expect(results.filter((r) => r === 'point_inactive').length).toBe(4);
    const doc = (await adminDb.collection('crownHuntPoints').doc(pointId).get()).data()!;
    expect(doc.collectorCount).toBe(2);
    expect(doc.status).toBe('ended');
  });
});
