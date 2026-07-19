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
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
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
      routePath: string;
      previewImagePath: string;
      alreadySaved: boolean;
    };

    expect(data.alreadySaved).toBe(false);
    expect(data.durationSeconds).toBe(3600);
    expect(data.distanceMeters).toBeGreaterThan(1_500);
    expect(data.averageSpeedMetersPerSecond).toBeCloseTo(
      (data.distanceMeters as number) / 3600,
      6,
    );
    expect(data.routePath).toBe(`rideRoutes/${member.uid}/${data.rideId}/route.bin`);
    expect(data.previewImagePath).toBe(`rideRoutes/${member.uid}/${data.rideId}/preview.png`);

    const docData = (await adminDb.collection('rides').doc(data.rideId).get()).data()!;
    expect(docData.userId).toBe(member.uid);
    expect(docData.title).toBe('Kvällstur');
    expect(docData.distanceMeters).toBe(data.distanceMeters);
    expect(docData).not.toHaveProperty('topSpeed');
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
