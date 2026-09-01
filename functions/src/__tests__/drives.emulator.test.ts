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
