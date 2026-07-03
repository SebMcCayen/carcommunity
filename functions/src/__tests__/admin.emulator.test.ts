/**
 * Admin domain emulator integration tests.
 *
 * Exercises the deployed-in-emulator callables end-to-end:
 * - `admin-setAdminRole` (callable `admin.setAdminRole`)
 * - `admin-suspendUser` (callable `admin.suspendUser`)
 * - `admin-restoreAccess` (callable `admin.restoreAccess`)
 *
 * Verifies claim propagation, non-admin rejection, no self-elevation,
 * moderation status mirroring, and immutable audit records.
 *
 * The very first admin is bootstrapped with the Firebase Admin SDK pointed
 * at the emulators — exactly the manual step production requires (there is
 * deliberately no in-band path to create the first admin).
 *
 * Requires the Functions emulator in addition to Auth/Firestore — run via:
 *   pnpm emulators:test
 */

// Ensure the Admin SDK targets the emulators even when this file is run
// outside `firebase emulators:exec` (which normally injects these).
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'admin-emulator-tests');
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;

beforeAll(() => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'admin-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
});

afterAll(async () => {
  await deleteApp(app);
});

async function pollUntil<T>(
  read: () => Promise<T | undefined>,
  timeoutMs = 15_000,
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

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

/** Creates a user and waits for the onUserCreate trigger to provision docs. */
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

/**
 * Bootstraps an admin (or owner) out-of-band with the Admin SDK — the same
 * manual, console-restricted procedure production uses for the first admin.
 */
async function promoteOutOfBand(user: TestUser, role: 'admin' | 'owner'): Promise<void> {
  await adminAuth.setCustomUserClaims(user.uid, { admin: true });
  await adminDb.collection('users').doc(user.uid).set({ role }, { merge: true });
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  // Force a token refresh so server-set custom claims are present.
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

describe('admin callables – authorization', () => {
  it('rejects unauthenticated calls with unauthenticated', async () => {
    if (auth.currentUser) await auth.signOut();
    for (const name of ['admin-setAdminRole', 'admin-suspendUser', 'admin-restoreAccess']) {
      expect(
        await callableErrorCode(call(name, { targetUid: 'x', reason: 'r', admin: true })),
      ).toBe('functions/unauthenticated');
    }
  });

  it('rejects non-admin callers with permission-denied (no self-elevation path)', async () => {
    const user = await createProvisionedUser('nonadmin');
    await signInAs(user);
    expect(
      await callableErrorCode(
        call('admin-setAdminRole', { targetUid: user.uid, admin: true, reason: 'self-elevate' }),
      ),
    ).toBe('functions/permission-denied');
    expect(
      await callableErrorCode(
        call('admin-suspendUser', { targetUid: 'someone-else', reason: 'nope' }),
      ),
    ).toBe('functions/permission-denied');
    expect(
      await callableErrorCode(
        call('admin-restoreAccess', { targetUid: 'someone-else', reason: 'nope' }),
      ),
    ).toBe('functions/permission-denied');
  });

  it('rejects a caller whose admin claim is not mirrored by the Firestore role', async () => {
    // Claim says admin, but the authoritative users/{uid}.role says plain
    // user — backend source of truth must win.
    const user = await createProvisionedUser('claim-only');
    await adminAuth.setCustomUserClaims(user.uid, { admin: true });
    await signInAs(user);
    expect(
      await callableErrorCode(
        call('admin-suspendUser', { targetUid: 'someone-else', reason: 'stale claim' }),
      ),
    ).toBe('functions/permission-denied');
  });
});

describe('admin-setAdminRole', () => {
  it('grants the admin claim, mirrors users/{uid}.role, and writes an audit event', async () => {
    const actor = await createProvisionedUser('grantor');
    await promoteOutOfBand(actor, 'admin');
    const target = await createProvisionedUser('grantee');
    await signInAs(actor);

    const result = await call('admin-setAdminRole', {
      targetUid: target.uid,
      admin: true,
      reason: 'Trusted community moderator',
    });
    expect(result.data).toEqual({ targetUid: target.uid, role: 'admin', admin: true });

    // Firestore mirror.
    const profile = await adminDb.collection('users').doc(target.uid).get();
    expect(profile.data()?.role).toBe('admin');

    // Custom claim propagates on the target's next token refresh.
    const targetUser = await adminAuth.getUser(target.uid);
    expect(targetUser.customClaims?.admin).toBe(true);
    await signInAs(target);
    const tokenResult = await auth.currentUser!.getIdTokenResult(true);
    expect(tokenResult.claims.admin).toBe(true);

    // Immutable audit record with actor, target, action, reason, timestamp.
    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('targetId', '==', target.uid)
      .where('action', '==', 'user.setAdminRole')
      .get();
    expect(audit.size).toBe(1);
    const event = audit.docs[0].data();
    expect(event.adminId).toBe(actor.uid);
    expect(event.reason).toBe('Trusted community moderator');
    expect(event.details).toEqual({ admin: true, role: 'admin' });
    expect(event.createdAt).toBeTruthy();
  });

  it('revokes the admin claim and mirrors role back to user', async () => {
    const actor = await createProvisionedUser('revoker');
    await promoteOutOfBand(actor, 'admin');
    const target = await createProvisionedUser('revokee');
    await promoteOutOfBand(target, 'admin');
    await signInAs(actor);

    const result = await call('admin-setAdminRole', {
      targetUid: target.uid,
      admin: false,
      reason: 'Stepping down',
    });
    expect(result.data).toEqual({ targetUid: target.uid, role: 'user', admin: false });

    const profile = await adminDb.collection('users').doc(target.uid).get();
    expect(profile.data()?.role).toBe('user');
    const targetUser = await adminAuth.getUser(target.uid);
    expect(targetUser.customClaims?.admin).toBeUndefined();
    // Refresh tokens were revoked so the stale admin claim cannot be renewed.
    expect(targetUser.tokensValidAfterTime).toBeTruthy();
  });

  it('an admin cannot change their own role (failed-precondition)', async () => {
    const actor = await createProvisionedUser('self-changer');
    await promoteOutOfBand(actor, 'admin');
    await signInAs(actor);
    expect(
      await callableErrorCode(
        call('admin-setAdminRole', { targetUid: actor.uid, admin: false, reason: 'oops' }),
      ),
    ).toBe('functions/failed-precondition');
  });

  it('owner accounts cannot be modified via setAdminRole', async () => {
    const actor = await createProvisionedUser('owner-toucher');
    await promoteOutOfBand(actor, 'admin');
    const target = await createProvisionedUser('the-owner');
    await promoteOutOfBand(target, 'owner');
    await signInAs(actor);
    expect(
      await callableErrorCode(
        call('admin-setAdminRole', { targetUid: target.uid, admin: false, reason: 'coup' }),
      ),
    ).toBe('functions/failed-precondition');
  });

  it('rejects invalid input and unknown targets with contract codes', async () => {
    const actor = await createProvisionedUser('validator');
    await promoteOutOfBand(actor, 'admin');
    await signInAs(actor);
    expect(await callableErrorCode(call('admin-setAdminRole', {}))).toBe(
      'functions/invalid-argument',
    );
    expect(
      await callableErrorCode(
        call('admin-setAdminRole', { targetUid: 'u', admin: true }), // missing reason
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(
        call('admin-setAdminRole', { targetUid: 'no-such-uid', admin: true, reason: 'r' }),
      ),
    ).toBe('functions/not-found');
  });
});

describe('admin-suspendUser / admin-restoreAccess', () => {
  it('suspends: claim + mirrored status + moderation record + audit record', async () => {
    const actor = await createProvisionedUser('moderator');
    await promoteOutOfBand(actor, 'admin');
    const target = await createProvisionedUser('rule-breaker');
    await signInAs(actor);

    const result = await call('admin-suspendUser', {
      targetUid: target.uid,
      reason: 'Repeated harassment',
    });
    expect(result.data).toEqual({ targetUid: target.uid, suspended: true });

    // Backend-managed status mirrored to the user document.
    const profile = await adminDb.collection('users').doc(target.uid).get();
    expect(profile.data()?.suspended).toBe(true);

    // Enforcement claim set and refresh tokens revoked.
    const targetUser = await adminAuth.getUser(target.uid);
    expect(targetUser.customClaims?.suspended).toBe(true);
    expect(targetUser.tokensValidAfterTime).toBeTruthy();

    // Moderation record.
    const actions = await adminDb
      .collection('moderationActions')
      .where('targetUserId', '==', target.uid)
      .get();
    expect(actions.size).toBe(1);
    const action = actions.docs[0].data();
    expect(action.actorUserId).toBe(actor.uid);
    expect(action.actionType).toBe('permanent_suspension');
    expect(action.reason).toBe('Repeated harassment');
    expect(action.createdAt).toBeTruthy();

    // Audit record.
    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('targetId', '==', target.uid)
      .where('action', '==', 'user.suspend')
      .get();
    expect(audit.size).toBe(1);
    expect(audit.docs[0].data().adminId).toBe(actor.uid);
    expect(audit.docs[0].data().reason).toBe('Repeated harassment');
  });

  it('a suspended admin loses admin access (suspension overrides admin)', async () => {
    const owner = await createProvisionedUser('the-boss');
    await promoteOutOfBand(owner, 'owner');
    const badAdmin = await createProvisionedUser('bad-admin');
    await promoteOutOfBand(badAdmin, 'admin');

    await signInAs(owner);
    await call('admin-suspendUser', { targetUid: badAdmin.uid, reason: 'Abuse of power' });

    await signInAs(badAdmin);
    expect(
      await callableErrorCode(
        call('admin-suspendUser', { targetUid: owner.uid, reason: 'revenge' }),
      ),
    ).toBe('functions/permission-denied');
  });

  it('restores access: clears claim and mirrored status, writes records', async () => {
    const actor = await createProvisionedUser('restorer');
    await promoteOutOfBand(actor, 'admin');
    const target = await createProvisionedUser('reformed');
    await signInAs(actor);

    await call('admin-suspendUser', { targetUid: target.uid, reason: 'Cooling off' });
    const result = await call('admin-restoreAccess', {
      targetUid: target.uid,
      reason: 'Appeal approved',
    });
    expect(result.data).toEqual({ targetUid: target.uid, suspended: false });

    const profile = await adminDb.collection('users').doc(target.uid).get();
    expect(profile.data()?.suspended).toBe(false);
    const targetUser = await adminAuth.getUser(target.uid);
    expect(targetUser.customClaims?.suspended).toBeUndefined();

    const actions = await adminDb
      .collection('moderationActions')
      .where('targetUserId', '==', target.uid)
      .where('actionType', '==', 'restore_access')
      .get();
    expect(actions.size).toBe(1);
    expect(actions.docs[0].data().reason).toBe('Appeal approved');
  });

  it('admins cannot suspend themselves or owner accounts', async () => {
    const actor = await createProvisionedUser('overreacher');
    await promoteOutOfBand(actor, 'admin');
    const target = await createProvisionedUser('protected-owner');
    await promoteOutOfBand(target, 'owner');
    await signInAs(actor);

    expect(
      await callableErrorCode(
        call('admin-suspendUser', { targetUid: actor.uid, reason: 'self-suspend' }),
      ),
    ).toBe('functions/failed-precondition');
    expect(
      await callableErrorCode(
        call('admin-suspendUser', { targetUid: target.uid, reason: 'mutiny' }),
      ),
    ).toBe('functions/permission-denied');
  });

  it('owners can moderate owner accounts (legacy parity)', async () => {
    const actor = await createProvisionedUser('senior-owner');
    await promoteOutOfBand(actor, 'owner');
    const target = await createProvisionedUser('junior-owner');
    await promoteOutOfBand(target, 'owner');
    await signInAs(actor);

    const result = await call('admin-suspendUser', {
      targetUid: target.uid,
      reason: 'Owner dispute',
    });
    expect(result.data).toEqual({ targetUid: target.uid, suspended: true });
  });

  it('requires a reason (invalid-argument)', async () => {
    const actor = await createProvisionedUser('reasonless');
    await promoteOutOfBand(actor, 'admin');
    await signInAs(actor);
    expect(await callableErrorCode(call('admin-suspendUser', { targetUid: 'x' }))).toBe(
      'functions/invalid-argument',
    );
    expect(
      await callableErrorCode(call('admin-restoreAccess', { targetUid: 'x', reason: '' })),
    ).toBe('functions/invalid-argument');
  });
});
