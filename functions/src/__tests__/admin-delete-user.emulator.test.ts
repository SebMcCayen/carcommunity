/**
 * admin.deleteUser emulator integration tests.
 *
 * Exercises the deployed-in-emulator `admin-deleteUser` callable end to end:
 * - authorization (unauthenticated / non-admin rejected),
 * - safety guards (no self-deletion; admins cannot delete owners),
 * - the comprehensive erasure — it reuses the SAME purgeUserData routine as
 *   self-service deletion, so a representative spread of the purge plan
 *   (doc tree + subcollection, owned docs, a friend MIRROR row, a block
 *   MIRROR row + its RTDB node, the liveLocation session node that no
 *   stop/hide/sweep path deletes, Storage, and the Firebase Auth user) is
 *   asserted gone, and
 * - the immutable adminAuditEvents (action `user.delete`) record.
 *
 * The "last remaining admin" REJECTION is covered deterministically by the
 * guardNotLastAdmin unit test (admin-claims-core.test.ts): the emulator's
 * Firestore is shared across test files, so the global admin count cannot be
 * forced to zero here, and the acting admin always remains a non-deleted admin
 * regardless — the branch is defense-in-depth. This file instead pins that the
 * guard does not OVER-block: an admin can be deleted while other admins remain.
 *
 * displayNames are suffixed `-adu` (this file) to stay unique in the shared
 * emulator Firestore.
 *
 * Requires the Functions + Auth + Firestore + Database + Storage emulators —
 * run via: pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= '127.0.0.1:9199';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
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
import { getDatabase as getAdminDatabase } from 'firebase-admin/database';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

// A DEDICATED admin app found BY NAME with an explicit emulator databaseURL.
// The `getAdminApps()[0]` shorthand is load-order-fragile in the shared emulator
// process: a URL-less app (initialized with projectId only) can win the [0] slot
// before any RTDB-using file adds a databaseURL, and getDatabase() then throws at
// module load ("Can't determine Firebase Database URL"), collecting 0 tests. The
// `?ns=${PROJECT_ID}` namespace matches the functions runtime
// (functions/src/firebase.ts getDatabase()), so this app observes the purge's
// RTDB deletions.
const RTDB_APP_NAME = 'admin-delete-user-tests';
const adminApp =
  getAdminApps().find((existing) => existing.name === RTDB_APP_NAME) ??
  initializeAdminApp(
    { projectId: PROJECT_ID, databaseURL: `http://${EMULATOR_HOST}:9000?ns=${PROJECT_ID}` },
    RTDB_APP_NAME,
  );
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);
const adminBucket = getAdminStorage(adminApp).bucket(`${PROJECT_ID}.appspot.com`);
const adminRtdb = getAdminDatabase(adminApp);

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

async function promoteOutOfBand(user: TestUser, role: 'admin' | 'owner'): Promise<void> {
  await adminAuth.setCustomUserClaims(user.uid, { admin: true });
  await adminDb.collection('users').doc(user.uid).set({ role }, { merge: true });
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

beforeAll(() => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'admin-delete-user-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('admin-deleteUser – authorization & guards', () => {
  it('rejects unauthenticated calls', async () => {
    if (auth.currentUser) await auth.signOut();
    expect(await callableErrorCode(call('admin-deleteUser', { targetUid: 'x', reason: 'r' }))).toBe(
      'functions/unauthenticated',
    );
  });

  it('rejects non-admin callers with permission-denied', async () => {
    const user = await createProvisionedUser('adu-nonadmin');
    await signInAs(user);
    expect(
      await callableErrorCode(call('admin-deleteUser', { targetUid: 'someone-else', reason: 'r' })),
    ).toBe('functions/permission-denied');
  });

  it('requires a reason (invalid-argument)', async () => {
    const actor = await createProvisionedUser('adu-reasonless');
    await promoteOutOfBand(actor, 'admin');
    await signInAs(actor);
    expect(await callableErrorCode(call('admin-deleteUser', { targetUid: 'x' }))).toBe(
      'functions/invalid-argument',
    );
    expect(await callableErrorCode(call('admin-deleteUser', { targetUid: 'x', reason: '' }))).toBe(
      'functions/invalid-argument',
    );
  });

  it('rejects self-deletion (failed-precondition — use account deletion)', async () => {
    const actor = await createProvisionedUser('adu-self');
    await promoteOutOfBand(actor, 'admin');
    await signInAs(actor);
    expect(
      await callableErrorCode(call('admin-deleteUser', { targetUid: actor.uid, reason: 'me' })),
    ).toBe('functions/failed-precondition');
    // The account is untouched.
    expect((await adminDb.collection('users').doc(actor.uid).get()).exists).toBe(true);
    await expect(adminAuth.getUser(actor.uid)).resolves.toBeTruthy();
  });

  it('admins cannot delete owner accounts (permission-denied)', async () => {
    const actor = await createProvisionedUser('adu-admin');
    await promoteOutOfBand(actor, 'admin');
    const owner = await createProvisionedUser('adu-owner');
    await promoteOutOfBand(owner, 'owner');
    await signInAs(actor);
    expect(
      await callableErrorCode(call('admin-deleteUser', { targetUid: owner.uid, reason: 'coup' })),
    ).toBe('functions/permission-denied');
    // The owner is untouched.
    expect((await adminDb.collection('users').doc(owner.uid).get()).exists).toBe(true);
    await expect(adminAuth.getUser(owner.uid)).resolves.toBeTruthy();
  });

  it('rejects an unknown target with not-found', async () => {
    const actor = await createProvisionedUser('adu-notfound');
    await promoteOutOfBand(actor, 'admin');
    await signInAs(actor);
    expect(
      await callableErrorCode(
        call('admin-deleteUser', { targetUid: 'no-such-uid-at-all', reason: 'r' }),
      ),
    ).toBe('functions/not-found');
  });

  it('fails CLOSED when the authoritative users/{uid} doc is missing (unverifiable role)', async () => {
    // An Auth record with NO users/{uid} profile (partially-provisioned account):
    // the owner/last-admin guards cannot be verified, so the delete must be
    // refused rather than defaulting the role to a deletable 'user'.
    const actor = await createProvisionedUser('adu-verifier');
    await promoteOutOfBand(actor, 'admin');
    const target = await createProvisionedUser('adu-noprofile');
    await adminDb.collection('users').doc(target.uid).delete();
    await signInAs(actor);

    expect(
      await callableErrorCode(call('admin-deleteUser', { targetUid: target.uid, reason: 'r' })),
    ).toBe('functions/failed-precondition');
    // Fail-closed: the Auth user is NOT deleted (no purge ran).
    await expect(adminAuth.getUser(target.uid)).resolves.toBeTruthy();
  });
});

describe('admin-deleteUser – erasure', () => {
  it('erases the target across the purge plan, deletes the Auth user, and audits it', async () => {
    const actor = await createProvisionedUser('adu-actor');
    await promoteOutOfBand(actor, 'admin');
    const target = await createProvisionedUser('adu-target');
    const uid = target.uid;

    // Seed a representative spread of the purge plan.
    // (1) doc tree + subcollection.
    await adminDb
      .collection('notifications')
      .doc(uid)
      .collection('items')
      .doc('n1')
      .set({ category: 'system_notice', title: 'x', read: false, createdAt: Timestamp.now() });
    await adminDb
      .collection('pointsLedger')
      .doc(uid)
      .set({ balance: 5, updatedAt: Timestamp.now() });
    // (2) owned docs.
    await adminDb.collection('vehicles').add({ userId: uid, make: 'Saab' });
    await adminDb.collection('rides').add({ userId: uid, distanceMeters: 500 });
    // (3) friend MIRROR row on another member (a known erasure-gap shape).
    const friendUid = 'adu-graph-friend';
    const mirrorFriendRef = adminDb
      .collection('users')
      .doc(friendUid)
      .collection('friends')
      .doc(uid);
    await mirrorFriendRef.set({
      friendUid: uid,
      displayName: 'Raderad-adu',
      avatarPath: null,
      createdAt: Timestamp.now(),
    });
    // (4) block MIRROR row + its RTDB node.
    const blockerUid = 'adu-blocker';
    const mirrorBlockRef = adminDb
      .collection('userBlocks')
      .doc(blockerUid)
      .collection('blocked')
      .doc(uid);
    await mirrorBlockRef.set({
      blockedUserId: uid,
      displayName: 'Raderad-adu',
      createdAt: Timestamp.now(),
    });
    await adminRtdb.ref(`liveLocationBlocks/${blockerUid}/${uid}`).set(true);
    // (5) liveLocation session node — never removed by stop/hide/sweep; a known gap.
    await adminRtdb.ref(`liveLocation/${uid}/session`).set({
      id: 'adu-session',
      status: 'stopped',
      displayName: 'Raderad-adu',
      pointsLastLatitude: 57.7,
      pointsLastLongitude: 11.97,
    });
    // (6) Storage prefix.
    await adminBucket.file(`profileImages/${uid}/avatar.png`).save(Buffer.from('img'));

    // A bystander friend row that must SURVIVE (the collection-group mirror sweep
    // must take only rows naming the deleted user).
    const bystanderFriendRef = adminDb
      .collection('users')
      .doc(friendUid)
      .collection('friends')
      .doc('adu-bystander');
    await bystanderFriendRef.set({
      friendUid: 'adu-bystander',
      displayName: 'Kvar-adu',
      avatarPath: null,
      createdAt: Timestamp.now(),
    });

    await signInAs(actor);
    const result = await call('admin-deleteUser', {
      targetUid: uid,
      reason: 'GDPR erasure request',
    });
    expect(result.data).toEqual({ targetUid: uid, deleted: true });

    // Doc tree + subcollection + owned docs gone.
    expect((await adminDb.collection('users').doc(uid).get()).exists).toBe(false);
    expect(
      (await adminDb.collection('notifications').doc(uid).collection('items').get()).size,
    ).toBe(0);
    expect((await adminDb.collection('pointsLedger').doc(uid).get()).exists).toBe(false);
    expect((await adminDb.collection('vehicles').where('userId', '==', uid).get()).size).toBe(0);
    expect((await adminDb.collection('rides').where('userId', '==', uid).get()).size).toBe(0);

    // Known-gap items gone: friend mirror, block mirror + RTDB node, live session.
    expect((await mirrorFriendRef.get()).exists).toBe(false);
    expect((await mirrorBlockRef.get()).exists).toBe(false);
    expect((await adminRtdb.ref(`liveLocationBlocks/${blockerUid}/${uid}`).get()).exists()).toBe(
      false,
    );
    expect((await adminRtdb.ref(`liveLocation/${uid}`).get()).exists()).toBe(false);

    // Storage gone; Auth user gone.
    const [profileFiles] = await adminBucket.getFiles({ prefix: `profileImages/${uid}/` });
    expect(profileFiles).toHaveLength(0);
    await expect(adminAuth.getUser(uid)).rejects.toMatchObject({ code: 'auth/user-not-found' });

    // Bystander friendship untouched.
    expect((await bystanderFriendRef.get()).exists).toBe(true);

    // Immutable audit record: actor, target, reason, action `user.delete`, role snapshot.
    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('targetId', '==', uid)
      .where('action', '==', 'user.delete')
      .get();
    expect(audit.size).toBe(1);
    const event = audit.docs[0]!.data();
    expect(event.adminId).toBe(actor.uid);
    expect(event.reason).toBe('GDPR erasure request');
    expect(event.targetType).toBe('user');
    expect((event.details as { role: string }).role).toBe('user');
    expect(event.createdAt).toBeTruthy();
  });

  it('does not over-block: an admin can be deleted while other admins remain', async () => {
    const actor = await createProvisionedUser('adu-actor2');
    await promoteOutOfBand(actor, 'admin');
    const targetAdmin = await createProvisionedUser('adu-target-admin');
    await promoteOutOfBand(targetAdmin, 'admin');
    await signInAs(actor);

    const result = await call('admin-deleteUser', {
      targetUid: targetAdmin.uid,
      reason: 'Redundant admin cleanup',
    });
    expect(result.data).toEqual({ targetUid: targetAdmin.uid, deleted: true });
    await expect(adminAuth.getUser(targetAdmin.uid)).rejects.toMatchObject({
      code: 'auth/user-not-found',
    });
  });

  it('audits immutably on retry: a repeated deletion does not re-stamp createdAt', async () => {
    const actor = await createProvisionedUser('adu-idem-actor');
    await promoteOutOfBand(actor, 'admin');
    const target = await createProvisionedUser('adu-idem-target');
    const uid = target.uid;
    await signInAs(actor);

    // First deletion writes the audit record via idempotent create().
    await call('admin-deleteUser', { targetUid: uid, reason: 'first deletion' });
    const auditRef = adminDb.collection('adminAuditEvents').doc(`user-delete_${uid}`);
    const first = (await auditRef.get()).data()!;
    expect(first.action).toBe('user.delete');
    const firstCreatedAt = first.createdAt as Timestamp;

    // Re-materialize ONLY the profile doc so the existence/role guard passes,
    // then invoke the delete a SECOND time for the same uid. The audit create()
    // hits ALREADY_EXISTS and is ignored — the original record must be untouched.
    await adminDb
      .collection('users')
      .doc(uid)
      .set({ role: 'user', displayName: 'Adu Idem Retry-adu' });
    const second = await call('admin-deleteUser', { targetUid: uid, reason: 'second deletion' });
    expect(second.data).toEqual({ targetUid: uid, deleted: true });

    // Exactly one audit record; createdAt and reason are the FIRST write's.
    const auditQuery = await adminDb
      .collection('adminAuditEvents')
      .where('targetId', '==', uid)
      .where('action', '==', 'user.delete')
      .get();
    expect(auditQuery.size).toBe(1);
    const after = (await auditRef.get()).data()!;
    expect((after.createdAt as Timestamp).toMillis()).toBe(firstCreatedAt.toMillis());
    expect(after.reason).toBe('first deletion');
  });

  it('tolerates an already-deleted Auth user and still purges the Firestore data', async () => {
    const actor = await createProvisionedUser('adu-authgone-actor');
    await promoteOutOfBand(actor, 'admin');
    const target = await createProvisionedUser('adu-authgone-target');
    const uid = target.uid;

    // Seed Firestore data that must still be erased.
    await adminDb
      .collection('pointsLedger')
      .doc(uid)
      .set({ balance: 3, updatedAt: Timestamp.now() });
    await adminDb.collection('vehicles').add({ userId: uid, make: 'Opel' });

    // Remove ONLY the Auth user, leaving the users/{uid} doc + data behind — the
    // partial-prior-purge / Auth-removed-first shape. getUser() now returns
    // not-found, so the lockdown is skipped and the purge must still run.
    await adminAuth.deleteUser(uid);
    await signInAs(actor);

    const result = await call('admin-deleteUser', { targetUid: uid, reason: 'auth already gone' });
    expect(result.data).toEqual({ targetUid: uid, deleted: true });

    // The Firestore data is erased (the purge completed despite the missing Auth user).
    expect((await adminDb.collection('users').doc(uid).get()).exists).toBe(false);
    expect((await adminDb.collection('pointsLedger').doc(uid).get()).exists).toBe(false);
    expect((await adminDb.collection('vehicles').where('userId', '==', uid).get()).size).toBe(0);
    // And the audit record was still written.
    expect(
      (await adminDb.collection('adminAuditEvents').doc(`user-delete_${uid}`).get()).exists,
    ).toBe(true);
  });

  it('uses the retained delete marker to finish purge retries after users/{uid} is gone', async () => {
    const actor = await createProvisionedUser('adu-marker-actor');
    await promoteOutOfBand(actor, 'admin');
    const target = await createProvisionedUser('adu-marker-target');
    const uid = target.uid;

    await adminDb
      .collection('pointsLedger')
      .doc(uid)
      .set({ balance: 9, updatedAt: Timestamp.now() });
    await adminDb.collection('vehicles').add({ userId: uid, make: 'Volvo' });

    // Simulate a prior partial purge run:
    // - users/{uid} already removed (first purge phase),
    // - Auth user already removed,
    // - idempotency marker retained with the verified role snapshot.
    await adminDb.collection('users').doc(uid).delete();
    await adminAuth.deleteUser(uid);
    await adminDb.collection('adminDeleteUserMarkers').doc(uid).set({
      targetUid: uid,
      targetRole: 'user',
      status: 'pending',
      createdAt: Timestamp.now(),
      updatedAt: Timestamp.now(),
    });

    await signInAs(actor);
    const result = await call('admin-deleteUser', { targetUid: uid, reason: 'retry purge' });
    expect(result.data).toEqual({ targetUid: uid, deleted: true });

    expect((await adminDb.collection('pointsLedger').doc(uid).get()).exists).toBe(false);
    expect((await adminDb.collection('vehicles').where('userId', '==', uid).get()).size).toBe(0);

    const marker = (await adminDb.collection('adminDeleteUserMarkers').doc(uid).get()).data();
    expect(marker?.status).toBe('processed');
    expect(marker?.targetRole).toBe('user');
    expect(marker?.processedAt).toBeTruthy();
    expect((await adminDb.collection('adminAuditEvents').doc(`user-delete_${uid}`).get()).exists).toBe(
      true,
    );
  });
});
