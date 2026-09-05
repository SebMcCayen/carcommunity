/**
 * Saved drives emulator integration tests (Phase 9d).
 *
 * Exercises the deployed-in-emulator callables end-to-end:
 * - `drives-save` (member gating, server-side stats, idempotent retries)
 * - `drives-delete` (ownership, Cloud Storage prefix cleanup)
 *
 * Requires the Functions + Storage emulators — run via:
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
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { getStorage as getAdminStorage } from 'firebase-admin/storage';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'drives-emulator-tests');
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

async function seedDriveHistory(user: TestUser, agesInDays: number[]): Promise<string[]> {
  const now = Date.now();
  const ids: string[] = [];
  for (const [index, ageInDays] of agesInDays.entries()) {
    const rideId = `${user.uid}-history-${index}`;
    ids.push(rideId);
    const createdAt = Timestamp.fromMillis(now - ageInDays * 24 * 60 * 60 * 1000);
    await adminDb
      .collection('rides')
      .doc(rideId)
      .set({
        userId: user.uid,
        title: `Drive ${index}`,
        distanceMeters: 1_000 + index,
        durationSeconds: 600 + index,
        averageSpeedMetersPerSecond: 2,
        maxSpeedMetersPerSecond: 10,
        startedAt: createdAt,
        endedAt: createdAt,
        createdAt,
        routePath: `rideRoutes/${user.uid}/${rideId}/route.bin`,
        previewImagePath: `rideRoutes/${user.uid}/${rideId}/preview.png`,
        sourceSessionId: `history-${index}`,
        routeThumbnail: 'encoded-thumbnail',
        convoyMembers: [],
      });
  }
  return ids;
}

interface StatsDriveSpec {
  ageDays: number;
  distanceMeters: number | null;
  durationSeconds: number;
  averageSpeedMetersPerSecond: number | null;
  maxSpeedMetersPerSecond: number | null;
}

/**
 * Seeds rides with explicit per-drive stats and ages (in days before now) so a
 * test can assert exactly which drives fall inside a given tier window and/or
 * month range. Returns the created rideIds in the order supplied.
 */
async function seedStatsDrives(user: TestUser, specs: StatsDriveSpec[]): Promise<string[]> {
  const now = Date.now();
  const ids: string[] = [];
  for (const [index, spec] of specs.entries()) {
    const rideId = `${user.uid}-stat-${index}`;
    ids.push(rideId);
    const createdAt = Timestamp.fromMillis(now - spec.ageDays * 24 * 60 * 60 * 1000);
    await adminDb
      .collection('rides')
      .doc(rideId)
      .set({
        userId: user.uid,
        title: `Stat drive ${index}`,
        distanceMeters: spec.distanceMeters,
        durationSeconds: spec.durationSeconds,
        averageSpeedMetersPerSecond: spec.averageSpeedMetersPerSecond,
        maxSpeedMetersPerSecond: spec.maxSpeedMetersPerSecond,
        startedAt: createdAt,
        endedAt: createdAt,
        createdAt,
        routePath: `rideRoutes/${user.uid}/${rideId}/route.bin`,
        previewImagePath: `rideRoutes/${user.uid}/${rideId}/preview.png`,
        sourceSessionId: `stat-${index}`,
        routeThumbnail: 'encoded-thumbnail',
        convoyMembers: [],
      });
  }
  return ids;
}

const DAY = 24 * 60 * 60 * 1000;

async function setPaidTier(user: TestUser, tier: 'plus' | 'supporter'): Promise<void> {
  await adminDb.collection('subscriptions').doc(user.uid).set({
    userId: user.uid,
    platform: 'manual',
    status: 'active',
    entitlement: 'member_monthly',
    tier,
    startsAt: Timestamp.now(),
    expiresAt: null,
    purchaseTokenHash: null,
  });
}

let member: TestUser;
let freeUser: TestUser;
let unentitledOwner: TestUser;

const validSave = {
  title: 'Kvällstur',
  startedAt: '2026-07-01T18:00:00.000Z',
  endedAt: '2026-07-01T19:00:00.000Z',
  routePoints: [
    { latitude: 59.3293, longitude: 18.0686, timestampMs: 1_751_392_800_000 },
    { latitude: 59.3393, longitude: 18.0686, timestampMs: 1_751_393_400_000 },
    { latitude: 59.3493, longitude: 18.0786, timestampMs: 1_751_394_000_000 },
  ],
};

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'drives-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  member = await createProvisionedUser('drives-member');
  await adminDb.collection('users').doc(member.uid).set({ activeMember: true }, { merge: true });
  freeUser = await createProvisionedUser('drives-free');
  unentitledOwner = await createProvisionedUser('drives-owner');
  await adminDb
    .collection('users')
    .doc(unentitledOwner.uid)
    .set({ role: 'owner', activeMember: false }, { merge: true });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('drives-save', () => {
  it('rejects unauthenticated callers, but SAVES for a signed-in non-member', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('drives-save', validSave))).toBe(
      'functions/unauthenticated',
    );

    // Member gating is disabled (functions/src/shared/memberGating.ts): a
    // non-member may save a drive they recorded. This is the bug Seb hit —
    // recording was never member-gated but saving was, so a non-member could
    // record a drive and then be refused when saving it, with no way to keep
    // the recording. RE-LOCKING MUST RE-ALIGN BOTH GATES or it returns.
    await signInAs(freeUser);
    const saved = (await call('drives-save', validSave)).data as { rideId: string };
    expect(typeof saved.rideId).toBe('string');
  });

  it('STILL rejects a suspended caller', async () => {
    // Teeth: requireActiveActor keeps the suspended/deleted guard even though
    // membership is no longer required.
    const suspended = await createProvisionedUser('drives-suspended');
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
    expect(await callableErrorCode(call('drives-save', validSave))).toBe(
      'functions/permission-denied',
    );
  });

  /**
   * Regression (v0.8.0 "Could not save the drive") — now INVERTED, because
   * this PR is the fix for it.
   *
   * The diagnosis, preserved: the owner/admin ROLE does NOT bypass the member
   * entitlement here. saveDrive gates on requireMemberActor (activeMember
   * only), not requireMemberOrAdminActor (role bypass), so an owner whose
   * users/{uid}.activeMember was false got refused exactly like a free user.
   * That was the whole delta between live-startSession (requireActiveActor —
   * free, succeeded) and drives-save (requireMemberActor — refused) for the
   * same account in the same session.
   *
   * The role still does not bypass anything — that asymmetry is untouched.
   * What changed is the gate itself: member gating is DISABLED
   * (functions/src/shared/memberGating.ts), so requireMemberActor now resolves
   * to active-actor semantics and the unentitled owner saves like anyone else.
   * Re-locking (MEMBER_GATING_ENABLED = true) restores the refusal — and the
   * original bug with it, unless the recording ENTRY is gated to match.
   */
  it('SAVES for an owner without an active membership (gating disabled)', async () => {
    await signInAs(unentitledOwner);
    const saved = (await call('drives-save', validSave)).data as { rideId: string };
    expect(typeof saved.rideId).toBe('string');
  });

  it('STILL rejects a SUSPENDED owner (role never bypassed suspension either)', async () => {
    // Teeth: the unlock must not hand a suspended owner access via their role.
    const suspendedOwner = await createProvisionedUser('drives-susp-owner');
    await adminDb
      .collection('users')
      .doc(suspendedOwner.uid)
      .set({ role: 'owner', activeMember: false, suspended: true }, { merge: true });
    await signInAs(suspendedOwner);
    expect(await callableErrorCode(call('drives-save', validSave))).toBe(
      'functions/permission-denied',
    );
  });

  it('saves a drive with server-computed stats and canonical storage paths', async () => {
    await signInAs(member);
    const result = await call('drives-save', validSave);
    const data = result.data as {
      rideId: string;
      durationSeconds: number;
      distanceMeters: number | null;
      averageSpeedMetersPerSecond: number | null;
      maxSpeedMetersPerSecond: number | null;
      routePath: string;
      previewImagePath: string;
      alreadySaved: boolean;
    };

    expect(data.alreadySaved).toBe(false);
    expect(data.durationSeconds).toBe(3600);
    expect(data.distanceMeters).toBeGreaterThan(1_500);
    expect(data.averageSpeedMetersPerSecond).toBeCloseTo((data.distanceMeters as number) / 3600, 6);
    expect(data.routePath).toBe(`rideRoutes/${member.uid}/${data.rideId}/route.bin`);
    expect(data.previewImagePath).toBe(`rideRoutes/${member.uid}/${data.rideId}/preview.png`);

    const docData = (await adminDb.collection('rides').doc(data.rideId).get()).data()!;
    expect(docData.userId).toBe(member.uid);
    expect(docData.title).toBe('Kvällstur');
    expect(docData.distanceMeters).toBe(data.distanceMeters);
    // REVERSED 2026-07 by an explicit product decision. This assertion used to
    // read
    //   expect(docData).not.toHaveProperty('topSpeed');
    // and pinned the rule that the callable never persisted a top speed. It
    // does now, as `maxSpeedMetersPerSecond`, so the assertion is rewritten to
    // pin the NEW contract end to end rather than deleted: the callable RETURNS
    // the figure, the stored document carries the SAME figure, and the value
    // stays inside the >200 km/h GPS-glitch bound the derivation applies. The
    // legacy `topSpeed` name is still never written, so a regression that
    // resurrects the old shape is still caught.
    expect(data.maxSpeedMetersPerSecond).not.toBeNull();
    expect(data.maxSpeedMetersPerSecond as number).toBeGreaterThan(0);
    expect(data.maxSpeedMetersPerSecond as number).toBeLessThanOrEqual(55.6);
    expect(docData.maxSpeedMetersPerSecond).toBe(data.maxSpeedMetersPerSecond);
    // The route thumbnail is stored (so the History list can draw the drive's
    // shape with no extra read) but deliberately NOT echoed in the response.
    expect(typeof docData.routeThumbnail).toBe('string');
    expect((docData.routeThumbnail as string).length).toBeGreaterThan(0);
    expect(data).not.toHaveProperty('routeThumbnail');
    expect(docData).not.toHaveProperty('topSpeed');
  });

  /**
   * Regression for GitHub #800 ([Auto-error] drives.saveDrive — "Saving a
   * live-session drive failed (1207 points)", code INTERNAL).
   *
   * The report's "1207 points" is INCIDENTAL, not causal: the server compute
   * path (distance/speed stats plus the ~64-point RDP thumbnail) is bounded and
   * iterative, and the ride document stores only that thumbnail — the full
   * track is a separate client upload to Cloud Storage, never inline. A drive
   * of this size therefore saves in a few milliseconds and the document stays a
   * few hundred bytes. The original INTERNAL was an unhandled TRANSIENT
   * Firestore failure (now mapped to a retryable error + logged, PR #804), so
   * this pins the invariant the point count never actually threatened: a large
   * drive saves cleanly and its document does NOT balloon with inline points.
   */
  it('saves a large (1207-point) live-session drive without ballooning the document (#800)', async () => {
    await signInAs(member);

    const POINT_COUNT = 1207; // the exact magnitude from the #800 report
    const startMs = 1_751_392_800_000;
    const routePoints = Array.from({ length: POINT_COUNT }, (_, i) => ({
      // ~5.5 m/s of plausible motion with a sine wiggle so the route has real
      // shape (non-null distance + maxSpeed, a non-trivial thumbnail), all well
      // under the >200 km/h GPS-glitch filter.
      latitude: 57.487 + i * 0.00005,
      longitude: 12.076 + Math.sin(i / 40) * 0.002,
      timestampMs: startMs + i * 1_000,
    }));
    const input = {
      title: 'Lång tur',
      startedAt: new Date(startMs).toISOString(),
      endedAt: new Date(startMs + (POINT_COUNT - 1) * 1_000).toISOString(),
      routePoints,
      sourceSessionId: 'large-drive-800',
    };

    const data = (await call('drives-save', input)).data as {
      rideId: string;
      distanceMeters: number | null;
      maxSpeedMetersPerSecond: number | null;
      alreadySaved: boolean;
    };

    // The save succeeds cleanly — no INTERNAL, no failed-precondition. Assert
    // the stats are present before reading them as numbers, so a null (a
    // summary-only save) fails loudly here rather than passing a NaN comparison.
    expect(data.alreadySaved).toBe(false);
    expect(typeof data.rideId).toBe('string');
    expect(data.distanceMeters).not.toBeNull();
    expect(data.distanceMeters).toBeGreaterThan(0);
    expect(data.maxSpeedMetersPerSecond).not.toBeNull();
    expect(data.maxSpeedMetersPerSecond).toBeGreaterThan(0);
    expect(data.maxSpeedMetersPerSecond).toBeLessThanOrEqual(55.6);

    const docData = (await adminDb.collection('rides').doc(data.rideId).get()).data()!;
    // Only a BOUNDED thumbnail is stored — the 1207 raw points never land
    // inline (they belong in rideRoutes/{uid}/{rideId}/route.bin in Storage).
    expect(typeof docData.routeThumbnail).toBe('string');
    expect(docData).not.toHaveProperty('routePoints');
    // The whole document stays tiny — far under Firestore's 1 MiB limit — proof
    // the point count cannot drive a size-based failure at save time. Measured
    // in BYTES (Buffer.byteLength), the unit the 1 MiB limit is expressed in,
    // not UTF-16 code units.
    expect(Buffer.byteLength(JSON.stringify(docData), 'utf8')).toBeLessThan(20_000);
  });

  it('is idempotent per sourceSessionId', async () => {
    await signInAs(member);
    const input = { ...validSave, sourceSessionId: 'session-abc-123' };
    const first = (await call('drives-save', input)).data as { rideId: string };
    const second = (await call('drives-save', input)).data as {
      rideId: string;
      alreadySaved: boolean;
    };
    expect(second.alreadySaved).toBe(true);
    expect(second.rideId).toBe(first.rideId);

    const dupes = await adminDb
      .collection('rides')
      .where('userId', '==', member.uid)
      .where('sourceSessionId', '==', 'session-abc-123')
      .get();
    expect(dupes.size).toBe(1);
  });

  it('rejects invalid times and unordered points', async () => {
    await signInAs(member);
    expect(
      await callableErrorCode(
        call('drives-save', { startedAt: validSave.endedAt, endedAt: validSave.startedAt }),
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(
        call('drives-save', {
          ...validSave,
          routePoints: [
            { latitude: 59.34, longitude: 18.07, timestampMs: 2_000 },
            { latitude: 59.33, longitude: 18.07, timestampMs: 1_000 },
          ],
        }),
      ),
    ).toBe('functions/invalid-argument');
  });
});

describe('drives-delete', () => {
  it('deletes an owned drive together with its Cloud Storage files', async () => {
    await signInAs(member);
    const saved = (await call('drives-save', { ...validSave, sourceSessionId: 'delete-me-1' }))
      .data as { rideId: string; routePath: string };

    // Simulate the client's post-save uploads.
    await adminBucket.file(saved.routePath).save(Buffer.from([0x1f, 0x8b, 0x08]), {
      contentType: 'application/gzip',
    });
    await adminBucket
      .file(`rideRoutes/${member.uid}/${saved.rideId}/preview.png`)
      .save(Buffer.from([0x89, 0x50, 0x4e, 0x47]), { contentType: 'image/png' });

    await call('drives-delete', { rideId: saved.rideId });

    expect((await adminDb.collection('rides').doc(saved.rideId).get()).exists).toBe(false);
    const [routeExists] = await adminBucket.file(saved.routePath).exists();
    const [previewExists] = await adminBucket
      .file(`rideRoutes/${member.uid}/${saved.rideId}/preview.png`)
      .exists();
    expect(routeExists).toBe(false);
    expect(previewExists).toBe(false);
  });

  it('a non-member owner can still delete (legacy parity)', async () => {
    await signInAs(member);
    const saved = (await call('drives-save', { ...validSave, sourceSessionId: 'delete-me-2' }))
      .data as { rideId: string };

    // Membership lapses; the drive stays deletable by its owner.
    await adminDb.collection('users').doc(member.uid).set({ activeMember: false }, { merge: true });
    try {
      await call('drives-delete', { rideId: saved.rideId });
      expect((await adminDb.collection('rides').doc(saved.rideId).get()).exists).toBe(false);
    } finally {
      await adminDb
        .collection('users')
        .doc(member.uid)
        .set({ activeMember: true }, { merge: true });
    }
  });

  it("rejects deleting someone else's drive and unknown drives", async () => {
    await signInAs(member);
    const saved = (await call('drives-save', { ...validSave, sourceSessionId: 'delete-me-3' }))
      .data as { rideId: string };

    await signInAs(freeUser);
    expect(await callableErrorCode(call('drives-delete', { rideId: saved.rideId }))).toBe(
      'functions/permission-denied',
    );
    expect(await callableErrorCode(call('drives-delete', { rideId: 'missing-ride' }))).toBe(
      'functions/not-found',
    );
  });
});

describe('drives-listHistory subscription visibility', () => {
  it('returns only the newest five drives for Community and signals retained history', async () => {
    const user = await createProvisionedUser('history-community');
    const rideIds = await seedDriveHistory(user, [0, 1, 2, 3, 4, 5, 6]);
    await adminDb
      .collection('rides')
      .doc(rideIds[0]!)
      .update({
        convoyMembers: [
          { uid: 'member-without-profile-fields' },
          { uid: 'member-with-profile-fields', displayName: 'Anna', avatarPath: 'avatars/anna' },
        ],
      });
    await signInAs(user);

    const data = (await call('drives-listHistory', { pageSize: 25 })).data as {
      tier: string;
      policy: { kind: string; limit: number };
      drives: Array<Record<string, unknown>>;
      hasMore: boolean;
      nextCursorRideId: string | null;
      hasTierRestrictedHistory: boolean;
      hiddenDriveCount: number;
    };

    expect(data.tier).toBe('community');
    expect(data.policy).toEqual({ kind: 'latest_count', limit: 5 });
    expect(data.drives.map((drive) => drive.rideId)).toEqual(rideIds.slice(0, 5));
    expect(data.hasMore).toBe(false);
    expect(data.nextCursorRideId).toBeNull();
    expect(data.hasTierRestrictedHistory).toBe(true);
    expect(data.hiddenDriveCount).toBe(2);
    expect(data.drives[0]).not.toHaveProperty('routePath');
    expect(data.drives[0]).not.toHaveProperty('previewImagePath');
    expect(data.drives[0]).not.toHaveProperty('sourceSessionId');
    expect(data.drives[0]?.convoyMembers).toEqual([
      { uid: 'member-without-profile-fields' },
      { uid: 'member-with-profile-fields', displayName: 'Anna', avatarPath: 'avatars/anna' },
    ]);

    // Hidden by the read policy does not mean locked or deleted: the owner can
    // still remove an older retained drive by id after a downgrade.
    const hiddenRideId = rideIds[6]!;
    await call('drives-delete', { rideId: hiddenRideId });
    expect((await adminDb.collection('rides').doc(hiddenRideId).get()).exists).toBe(false);
  });

  it('returns only the rolling 90-day window for Plus', async () => {
    const user = await createProvisionedUser('history-plus');
    await setPaidTier(user, 'plus');
    const rideIds = await seedDriveHistory(user, [1, 89, 91]);
    await signInAs(user);

    const data = (await call('drives-listHistory', {})).data as {
      tier: string;
      policy: { kind: string; days: number };
      drives: Array<{ rideId: string }>;
      hiddenDriveCount: number;
      windowStartsAtMillis: number;
    };
    expect(data.tier).toBe('plus');
    expect(data.policy).toEqual({ kind: 'rolling_days', days: 90 });
    expect(data.drives.map((drive) => drive.rideId)).toEqual(rideIds.slice(0, 2));
    expect(data.hiddenDriveCount).toBe(1);
    expect(typeof data.windowStartsAtMillis).toBe('number');
  });

  it('paginates the complete history for Supporter', async () => {
    const user = await createProvisionedUser('history-supporter');
    await setPaidTier(user, 'supporter');
    const rideIds = await seedDriveHistory(user, [0, 1, 2, 3, 365]);
    await signInAs(user);

    const first = (await call('drives-listHistory', { pageSize: 2 })).data as {
      tier: string;
      drives: Array<{ rideId: string }>;
      hasMore: boolean;
      nextCursorRideId: string | null;
      hiddenDriveCount: number;
    };
    expect(first.tier).toBe('supporter');
    expect(first.drives.map((drive) => drive.rideId)).toEqual(rideIds.slice(0, 2));
    expect(first.hasMore).toBe(true);
    expect(first.nextCursorRideId).toBe(rideIds[1]);
    expect(first.hiddenDriveCount).toBe(0);

    const second = (
      await call('drives-listHistory', {
        pageSize: 2,
        cursorRideId: first.nextCursorRideId,
      })
    ).data as {
      drives: Array<{ rideId: string }>;
      hasMore: boolean;
      nextCursorRideId: string | null;
    };
    expect(second.drives.map((drive) => drive.rideId)).toEqual(rideIds.slice(2, 4));
    expect(second.hasMore).toBe(true);
    expect(second.nextCursorRideId).toBe(rideIds[3]);
  });

  it("does not allow a cursor to probe another owner's drive", async () => {
    const owner = await createProvisionedUser('history-owner');
    const viewer = await createProvisionedUser('history-viewer');
    await setPaidTier(viewer, 'supporter');
    const [foreignRideId] = await seedDriveHistory(owner, [0]);
    await signInAs(viewer);

    expect(
      await callableErrorCode(
        call('drives-listHistory', { cursorRideId: foreignRideId, pageSize: 10 }),
      ),
    ).toBe('functions/not-found');
  });
});

describe('drives-routeUrl signed route access', () => {
  async function callErrorMessage(promise: Promise<unknown>): Promise<string> {
    try {
      await promise;
      return 'no-error';
    } catch (error) {
      if (error instanceof FirebaseError) return error.message;
      throw error;
    }
  }

  it('rejects an unauthenticated caller', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('drives-routeUrl', { rideId: 'anything' }))).toBe(
      'functions/unauthenticated',
    );
  });

  it("returns not-found for a missing ride and for another owner's ride (no existence leak)", async () => {
    const owner = await createProvisionedUser('routeurl-owner');
    const [ownedRideId] = await seedDriveHistory(owner, [0]);
    const viewer = await createProvisionedUser('routeurl-viewer');
    await setPaidTier(viewer, 'supporter');
    await signInAs(viewer);

    // Another member's real ride and a rideId that does not exist are the SAME
    // response, so a caller cannot probe which ids belong to whom.
    expect(await callableErrorCode(call('drives-routeUrl', { rideId: ownedRideId }))).toBe(
      'functions/not-found',
    );
    expect(
      await callableErrorCode(call('drives-routeUrl', { rideId: 'routeurl-missing-ride' })),
    ).toBe('functions/not-found');
  });

  it('denies a Community drive hidden beyond the newest five (downgrade-replay guard)', async () => {
    const user = await createProvisionedUser('routeurl-community');
    // Ages 0..5 (six drives): indexes 0-4 are the visible newest five, index 5
    // is retained-but-hidden for Community.
    const rideIds = await seedDriveHistory(user, [0, 1, 2, 3, 4, 5]);
    await signInAs(user);

    // The 6th-newest is owned and still exists, but is outside the tier window.
    expect(await callableErrorCode(call('drives-routeUrl', { rideId: rideIds[5]! }))).toBe(
      'functions/permission-denied',
    );
  });

  it('denies a Plus drive older than the rolling 90-day window', async () => {
    const user = await createProvisionedUser('routeurl-plus');
    await setPaidTier(user, 'plus');
    const rideIds = await seedDriveHistory(user, [1, 91]);
    await signInAs(user);

    // The 91-day-old drive is owned but outside the Plus window.
    expect(await callableErrorCode(call('drives-routeUrl', { rideId: rideIds[1]! }))).toBe(
      'functions/permission-denied',
    );
  });

  it('returns failed-precondition when a visible drive has no stored route file', async () => {
    // Community's newest drive is visible, but seedDriveHistory uploads no
    // route.bin, so the file-existence check fails before any signing.
    const user = await createProvisionedUser('routeurl-noroute');
    const [rideId] = await seedDriveHistory(user, [0]);
    await signInAs(user);

    const message = await callErrorMessage(call('drives-routeUrl', { rideId: rideId! }));
    expect(message).toContain('no stored route');
  });

  it('signs a URL for a visible drive with an uploaded route, or fails closed when signing is unavailable', async () => {
    // Happy path for the AUTHORIZATION + storage-existence chain. Real V4
    // signing calls IAM signBlob as the runtime SA; the Storage emulator runs
    // without those credentials, so signing typically fails here and the
    // callable returns the documented fail-safe (failed-precondition,
    // 'temporarily unavailable') rather than a URL. Both outcomes are asserted
    // so the authorization path is exercised in-emulator; whether real signing
    // ran is reported by the suite, and the signing happy/failure branches are
    // unit-tested deterministically in routeUrl-core.test.ts (signRouteUrl).
    const user = await createProvisionedUser('routeurl-signed');
    await setPaidTier(user, 'supporter');
    const [rideId] = await seedDriveHistory(user, [0]);
    // Simulate the client's post-save route upload at the canonical path.
    await adminBucket
      .file(`rideRoutes/${user.uid}/${rideId}/route.bin`)
      .save(Buffer.from([0x1f, 0x8b, 0x08]), { contentType: 'application/gzip' });
    await signInAs(user);

    try {
      const data = (await call('drives-routeUrl', { rideId: rideId! })).data as {
        url: string;
        expiresAtMillis: number;
      };
      expect(typeof data.url).toBe('string');
      expect(data.url.length).toBeGreaterThan(0);
      expect(typeof data.expiresAtMillis).toBe('number');
      expect(data.expiresAtMillis).toBeGreaterThan(Date.now());
    } catch (error) {
      // Emulator cannot sign: the callable must fail CLOSED, never crash/leak.
      expect(error).toBeInstanceOf(FirebaseError);
      expect((error as FirebaseError).code).toBe('functions/failed-precondition');
      expect((error as FirebaseError).message).toContain('temporarily unavailable');
    }
  });
});

describe('drives-stats free lifetime aggregation', () => {
  // Identical drive set for three users on different tiers. Ages in days:
  // [0,1,2,3,4,5,100,200]; distanceMeters=(i+1)*1000; duration=60;
  // avg=(i+1); max=(i+1)*2. Every tier gets all 8 in statistics,
  // independently of the separate history browsing window.
  const SPECS: StatsDriveSpec[] = [0, 1, 2, 3, 4, 5, 100, 200].map((ageDays, i) => ({
    ageDays,
    distanceMeters: (i + 1) * 1_000,
    durationSeconds: 60,
    averageSpeedMetersPerSecond: i + 1,
    maxSpeedMetersPerSecond: (i + 1) * 2,
  }));

  it('Community aggregates all retained drives without exposing individual records', async () => {
    const user = await createProvisionedUser('stats-community');
    await seedStatsDrives(user, SPECS);
    await signInAs(user);
    const data = (await call('drives-stats', {})).data as Record<string, number | string>;
    expect(data.tier).toBe('community');
    expect(data.totalDrives).toBe(8);
    expect(data.totalDistanceMeters).toBe(36_000);
    expect(data.totalDurationSeconds).toBe(480);
    expect(data.longestDriveMeters).toBe(8_000);
    expect(data.fastestAverageSpeedMps).toBe(8);
    expect(data.highestMaxSpeedMps).toBe(16);
    expect(data.averageDriveMeters).toBe(4_500);
    expect(data).not.toHaveProperty('drives');
    expect(data).not.toHaveProperty('rideIds');
    // No month range supplied → thisMonth fields are zeroed.
    expect(data.thisMonthDrives).toBe(0);
    expect(data.thisMonthDistanceMeters).toBe(0);
    expect(typeof data.serverNowMillis).toBe('number');
  });

  it('Plus statistics include drives older than 90 days', async () => {
    const user = await createProvisionedUser('stats-plus');
    await setPaidTier(user, 'plus');
    await seedStatsDrives(user, SPECS);
    await signInAs(user);
    const data = (await call('drives-stats', {})).data as Record<string, number | string>;
    expect(data.tier).toBe('plus');
    expect(data.totalDrives).toBe(8);
    expect(data.totalDistanceMeters).toBe(36_000);
    expect(data.longestDriveMeters).toBe(8_000);
    expect(data.fastestAverageSpeedMps).toBe(8);
    expect(data.highestMaxSpeedMps).toBe(16);
  });

  it('Supporter aggregates the complete history', async () => {
    const user = await createProvisionedUser('stats-supporter');
    await setPaidTier(user, 'supporter');
    await seedStatsDrives(user, SPECS);
    await signInAs(user);
    const data = (await call('drives-stats', {})).data as Record<string, number | string>;
    expect(data.tier).toBe('supporter');
    expect(data.totalDrives).toBe(8);
    expect(data.totalDistanceMeters).toBe(36_000); // 1000+..+8000
    expect(data.totalDurationSeconds).toBe(480); // 8 * 60
    expect(data.longestDriveMeters).toBe(8_000);
    expect(data.fastestAverageSpeedMps).toBe(8);
    expect(data.highestMaxSpeedMps).toBe(16);
  });

  it('includes all Community drives within the month even beyond the five-history limit', async () => {
    const user = await createProvisionedUser('stats-intersect');
    await seedStatsDrives(
      user,
      [0, 1, 2, 3, 4, 5, 6].map((ageDays, i) => ({
        ageDays,
        distanceMeters: (i + 1) * 1_000,
        durationSeconds: 60,
        averageSpeedMetersPerSecond: i + 1,
        maxSpeedMetersPerSecond: (i + 1) * 2,
      })),
    );
    await signInAs(user);
    const now = Date.now();
    const data = (
      await call('drives-stats', {
        monthStartMillis: now - 10 * DAY,
        monthEndMillis: now + 20 * DAY,
      })
    ).data as Record<string, number | string>;
    expect(data.tier).toBe('community');
    expect(data.totalDrives).toBe(7);
    expect(data.thisMonthDrives).toBe(7);
    expect(data.thisMonthDistanceMeters).toBe(28_000);
  });

  it('applies the month range as a real filter for a paid tier', async () => {
    // One drive inside the window (age 0), one outside it (age 40 > 10-day
    // window lower bound). Supporter sees both; only the in-window one counts
    // toward thisMonth.
    const user = await createProvisionedUser('stats-month-filter');
    await setPaidTier(user, 'supporter');
    await seedStatsDrives(user, [
      {
        ageDays: 0,
        distanceMeters: 4_000,
        durationSeconds: 60,
        averageSpeedMetersPerSecond: 5,
        maxSpeedMetersPerSecond: 10,
      },
      {
        ageDays: 40,
        distanceMeters: 9_000,
        durationSeconds: 60,
        averageSpeedMetersPerSecond: 9,
        maxSpeedMetersPerSecond: 18,
      },
    ]);
    await signInAs(user);
    const now = Date.now();
    const data = (
      await call('drives-stats', {
        monthStartMillis: now - 10 * DAY,
        monthEndMillis: now + 20 * DAY,
      })
    ).data as Record<string, number | string>;
    expect(data.totalDrives).toBe(2); // both visible to Supporter
    expect(data.longestDriveMeters).toBe(9_000); // max spans the whole visible set
    expect(data.thisMonthDrives).toBe(1); // only the age-0 drive is in-window
    expect(data.thisMonthDistanceMeters).toBe(4_000);
  });

  it('rejects malformed, future and oversized month ranges', async () => {
    const user = await createProvisionedUser('stats-monthvalidation');
    await signInAs(user);
    const now = Date.now();
    // Half-supplied range.
    expect(await callableErrorCode(call('drives-stats', { monthStartMillis: now - DAY }))).toBe(
      'functions/invalid-argument',
    );
    // Entirely in the future (does not straddle now).
    expect(
      await callableErrorCode(
        call('drives-stats', { monthStartMillis: now + DAY, monthEndMillis: now + 31 * DAY }),
      ),
    ).toBe('functions/invalid-argument');
    // Oversized span (40 days).
    expect(
      await callableErrorCode(
        call('drives-stats', { monthStartMillis: now - 20 * DAY, monthEndMillis: now + 20 * DAY }),
      ),
    ).toBe('functions/invalid-argument');
    // Non-integer bound.
    expect(
      await callableErrorCode(
        call('drives-stats', {
          monthStartMillis: now - 10 * DAY + 0.5,
          monthEndMillis: now + 20 * DAY,
        }),
      ),
    ).toBe('functions/invalid-argument');
  });

  it('rejects an unauthenticated caller', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('drives-stats', {}))).toBe('functions/unauthenticated');
  });
});

describe('drives-lifetimeStats true-lifetime aggregation (un-paywalled)', () => {
  // Ages in days [0,1,2,3,4,5,100,200]; distanceMeters=(i+1)*1000;
  // duration=60; avg=(i+1); max=(i+1)*2. Unlike drives-stats there is NO tier
  // window, so a Community user still gets the true totals over ALL 8 drives.
  const SPECS: StatsDriveSpec[] = [0, 1, 2, 3, 4, 5, 100, 200].map((ageDays, i) => ({
    ageDays,
    distanceMeters: (i + 1) * 1_000,
    durationSeconds: 60,
    averageSpeedMetersPerSecond: i + 1,
    maxSpeedMetersPerSecond: (i + 1) * 2,
  }));

  it('a Community user with >5 drives still gets true-lifetime totals (no tier window)', async () => {
    const user = await createProvisionedUser('lifetime-community');
    await seedStatsDrives(user, SPECS); // no subscription doc → Community tier
    await signInAs(user);
    const data = (await call('drives-lifetimeStats', {})).data as Record<string, number>;
    // drives-stats would cap Community at its newest 5; lifetimeStats does not.
    expect(data.totalDrives).toBe(8);
    expect(data.totalDistanceMeters).toBe(36_000); // 1000+..+8000
    expect(data.totalDurationSeconds).toBe(480); // 8 * 60
    expect(data.longestDriveMeters).toBe(8_000);
    expect(data.fastestAverageSpeedMps).toBe(8);
    expect(data.highestMaxSpeedMps).toBe(16);
    expect(data.averageDriveMeters).toBe(4_500); // 36000 / 8
    expect(typeof data.serverNowMillis).toBe('number');
    // No tier / thisMonth* fields on the lifetime response.
    expect(data.tier).toBeUndefined();
    expect(data.thisMonthDrives).toBeUndefined();
  });

  it('matches the tier-scoped total only for a Supporter (whose window is everything)', async () => {
    const user = await createProvisionedUser('lifetime-supporter');
    await setPaidTier(user, 'supporter');
    await seedStatsDrives(user, SPECS);
    await signInAs(user);
    const lifetime = (await call('drives-lifetimeStats', {})).data as Record<string, number>;
    const tierScoped = (await call('drives-stats', {})).data as Record<string, number>;
    expect(lifetime.totalDrives).toBe(tierScoped.totalDrives);
    expect(lifetime.totalDistanceMeters).toBe(tierScoped.totalDistanceMeters);
  });

  it('drops a malformed-duration drive entirely (excluded from totalDrives and the sums)', async () => {
    const user = await createProvisionedUser('lifetime-malformed');
    await seedStatsDrives(user, [
      {
        ageDays: 0,
        distanceMeters: 3_000,
        durationSeconds: 60,
        averageSpeedMetersPerSecond: 5,
        maxSpeedMetersPerSecond: 10,
      },
    ]);
    // A second ride with a corrupt (negative) durationSeconds, written directly.
    await adminDb
      .collection('rides')
      .doc(`${user.uid}-corrupt`)
      .set({
        userId: user.uid,
        title: 'Corrupt drive',
        distanceMeters: 99_000,
        durationSeconds: -5,
        averageSpeedMetersPerSecond: 3,
        maxSpeedMetersPerSecond: 6,
        startedAt: Timestamp.now(),
        endedAt: Timestamp.now(),
        createdAt: Timestamp.now(),
        routePath: `rideRoutes/${user.uid}/corrupt/route.bin`,
        previewImagePath: `rideRoutes/${user.uid}/corrupt/preview.png`,
        sourceSessionId: 'corrupt',
        routeThumbnail: 'encoded-thumbnail',
        convoyMembers: [],
      });
    await signInAs(user);
    const data = (await call('drives-lifetimeStats', {})).data as Record<string, number>;
    // The corrupt drive is dropped from the single snapshot entirely, so it
    // counts toward neither totalDrives nor the sums — the negative duration and
    // its 99km distance never leak in (every figure is one consistent snapshot).
    expect(data.totalDrives).toBe(1);
    expect(data.totalDistanceMeters).toBe(3_000);
    expect(data.totalDurationSeconds).toBe(60);
    expect(data.longestDriveMeters).toBe(3_000);
  });

  it('returns all zeroes for an owner with no drives', async () => {
    const user = await createProvisionedUser('lifetime-empty');
    await signInAs(user);
    const data = (await call('drives-lifetimeStats', {})).data as Record<string, number>;
    expect(data.totalDrives).toBe(0);
    expect(data.totalDistanceMeters).toBe(0);
    expect(data.totalDurationSeconds).toBe(0);
    expect(data.longestDriveMeters).toBe(0);
    expect(data.averageDriveMeters).toBe(0);
  });

  it("only ever aggregates the CALLER's own drives", async () => {
    const owner = await createProvisionedUser('lifetime-owner');
    const other = await createProvisionedUser('lifetime-other');
    await seedStatsDrives(owner, [
      {
        ageDays: 0,
        distanceMeters: 1_000,
        durationSeconds: 60,
        averageSpeedMetersPerSecond: 5,
        maxSpeedMetersPerSecond: 10,
      },
    ]);
    await seedStatsDrives(other, [
      {
        ageDays: 0,
        distanceMeters: 8_000,
        durationSeconds: 60,
        averageSpeedMetersPerSecond: 9,
        maxSpeedMetersPerSecond: 18,
      },
    ]);
    await signInAs(owner);
    const data = (await call('drives-lifetimeStats', {})).data as Record<string, number>;
    expect(data.totalDrives).toBe(1);
    expect(data.totalDistanceMeters).toBe(1_000); // never sees `other`'s 8km drive
  });

  it('rejects an unauthenticated caller', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('drives-lifetimeStats', {}))).toBe(
      'functions/unauthenticated',
    );
  });
});

describe('drives-listDeletable owner inventory', () => {
  it('returns ALL owned drives (including tier-hidden ones) with MINIMAL fields', async () => {
    const user = await createProvisionedUser('deletable-community');
    // Community would hide everything past the newest five in listHistory.
    const rideIds = await seedDriveHistory(user, [0, 1, 2, 3, 4, 5, 6]);
    await signInAs(user);

    // History is tier-limited to five…
    const history = (await call('drives-listHistory', { pageSize: 25 })).data as {
      drives: Array<{ rideId: string }>;
    };
    expect(history.drives).toHaveLength(5);

    // …but the deletion inventory returns all seven so they stay deletable.
    const data = (await call('drives-listDeletable', {})).data as {
      drives: Array<Record<string, unknown>>;
      hasMore: boolean;
      nextCursorRideId: string | null;
    };
    expect(data.drives.map((d) => d.rideId)).toEqual(rideIds);
    expect(data.hasMore).toBe(false);
    expect(data.nextCursorRideId).toBeNull();

    const row = data.drives[0]!;
    expect(Object.keys(row).sort()).toEqual(
      ['createdAtMillis', 'rideId', 'startedAtMillis', 'title'].sort(),
    );
    // No stats, route, image or session data leaks through this endpoint.
    for (const forbidden of [
      'distanceMeters',
      'durationSeconds',
      'averageSpeedMetersPerSecond',
      'maxSpeedMetersPerSecond',
      'routePath',
      'previewImagePath',
      'routeThumbnail',
      'carImagePath',
      'sourceSessionId',
      'convoyMembers',
      'userId',
    ]) {
      expect(row).not.toHaveProperty(forbidden);
    }
  });

  it('paginates newest-first with a look-ahead cursor', async () => {
    const user = await createProvisionedUser('deletable-paginate');
    const rideIds = await seedDriveHistory(user, [0, 1, 2, 3, 4]);
    await signInAs(user);

    const first = (await call('drives-listDeletable', { pageSize: 2 })).data as {
      drives: Array<{ rideId: string }>;
      hasMore: boolean;
      nextCursorRideId: string | null;
    };
    expect(first.drives.map((d) => d.rideId)).toEqual(rideIds.slice(0, 2));
    expect(first.hasMore).toBe(true);
    expect(first.nextCursorRideId).toBe(rideIds[1]);

    const second = (
      await call('drives-listDeletable', { pageSize: 2, cursorRideId: first.nextCursorRideId })
    ).data as {
      drives: Array<{ rideId: string }>;
      hasMore: boolean;
      nextCursorRideId: string | null;
    };
    expect(second.drives.map((d) => d.rideId)).toEqual(rideIds.slice(2, 4));
    expect(second.hasMore).toBe(true);

    const third = (
      await call('drives-listDeletable', { pageSize: 2, cursorRideId: second.nextCursorRideId })
    ).data as {
      drives: Array<{ rideId: string }>;
      hasMore: boolean;
      nextCursorRideId: string | null;
    };
    expect(third.drives.map((d) => d.rideId)).toEqual(rideIds.slice(4, 5));
    expect(third.hasMore).toBe(false);
    expect(third.nextCursorRideId).toBeNull();
  });

  it('scopes to the caller and does not let a cursor probe another owner', async () => {
    const owner = await createProvisionedUser('deletable-owner');
    const [foreignRideId] = await seedDriveHistory(owner, [0]);
    const other = await createProvisionedUser('deletable-other');
    await signInAs(other);

    // A user with no drives gets an empty inventory (never anyone else's).
    const mine = (await call('drives-listDeletable', {})).data as { drives: unknown[] };
    expect(mine.drives).toEqual([]);

    // A cursor naming another owner's drive is not-found, never a data leak.
    expect(
      await callableErrorCode(call('drives-listDeletable', { cursorRideId: foreignRideId })),
    ).toBe('functions/not-found');
  });

  it('rejects an unauthenticated caller', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('drives-listDeletable', {}))).toBe(
      'functions/unauthenticated',
    );
  });
});
