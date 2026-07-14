/**
 * Inactive-account lifecycle emulator integration tests (account lifecycle
 * cross-lane).
 *
 * Covers:
 * - auth-recordLogin: a member's sign-in stamps userLifecycle/{uid}.lastLoginAt.
 * - runInactivityCleanup with the delete gate CLOSED (MVP default): warns an
 *   inactive account (marks + in-app notice), leaves a past-grace account as
 *   would_delete (NOT deleted), and clears the warning for a reactivated user.
 * - runInactivityCleanup with the delete gate OPEN (config flag + email
 *   available): hard-deletes a past-grace account by reusing the deletion
 *   routine, retaining the proof-of-deletion record.
 *
 * Requires the Functions/Firestore/Auth emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= '127.0.0.1:9199';
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
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { runInactivityCleanup } from '../account/inactivityCleanup';
import * as emailModule from '../notifications/email';
import * as deliverModule from '../notifications/deliver';
import {
  ACCOUNT_LIFECYCLE_CONFIG_DOC,
  INACTIVE_DELETION_ENABLED_FIELD,
  INACTIVITY_DELETE_GRACE_DAYS,
  INACTIVITY_DELETION_REASON,
  addDays,
  subtractMonths,
} from '../account/inactivity-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'inactivity-emulator-tests');
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

/**
 * Directly seeds a users/{uid} doc for sweep tests (no Auth user needed).
 * Lifecycle fields (lastLoginAt / inactivityWarnedAt / inactivityDeleteAfter)
 * now live in the backend-only userLifecycle/{uid} doc, so they are split out
 * of the profile doc here and written to userLifecycle instead. createdAt and
 * the profile scalars stay on users/{uid}.
 */
async function seedUser(
  uid: string,
  fields: Record<string, unknown>,
): Promise<void> {
  const lifecycleKeys = ['lastLoginAt', 'inactivityWarnedAt', 'inactivityDeleteAfter'] as const;
  const lifecycle: Record<string, unknown> = {};
  const profile: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(fields)) {
    if ((lifecycleKeys as readonly string[]).includes(key)) {
      lifecycle[key] = value;
    } else {
      profile[key] = value;
    }
  }
  await adminDb
    .collection('users')
    .doc(uid)
    .set({ displayName: 'x', role: 'user', activeMember: false, suspended: false, deleted: false, ...profile });
  if (Object.keys(lifecycle).length > 0) {
    await adminDb.collection('userLifecycle').doc(uid).set(lifecycle);
  }
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

const now = new Date();

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'inactivity-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('auth-recordLogin', () => {
  it('stamps userLifecycle/{uid}.lastLoginAt for a member on sign-in', async () => {
    const user = await createProvisionedUser('login-member');
    await adminDb.collection('users').doc(user.uid).set({ activeMember: true }, { merge: true });
    await signInAs(user);

    const result = (await call('auth-recordLogin', {})).data as { recorded: boolean };
    expect(result).toEqual({ recorded: true });

    // Written to the backend-only userLifecycle doc, NOT the public profile.
    const lastLoginAt = (await adminDb.collection('userLifecycle').doc(user.uid).get()).data()
      ?.lastLoginAt;
    expect(lastLoginAt).toBeInstanceOf(Timestamp);
    // And never leaked onto the public users/{uid} profile.
    const profile = (await adminDb.collection('users').doc(user.uid).get()).data()!;
    expect(profile.lastLoginAt).toBeUndefined();
  });

  it('also records for an active NON-member (gated on active, not membership)', async () => {
    // A signed-in non-member must still refresh lastLoginAt — otherwise an
    // actively signing-in non-member older than 11 months would be wrongly
    // treated as inactive (lastLoginAt ?? createdAt) and auto-deleted.
    const user = await createProvisionedUser('login-nonmember');
    await signInAs(user);

    const result = (await call('auth-recordLogin', {})).data as { recorded: boolean };
    expect(result).toEqual({ recorded: true });

    const lastLoginAt = (await adminDb.collection('userLifecycle').doc(user.uid).get()).data()
      ?.lastLoginAt;
    expect(lastLoginAt).toBeInstanceOf(Timestamp);
  });

  it('rejects a suspended account (active-gated)', async () => {
    const user = await createProvisionedUser('login-suspended');
    // Restricted (suspended) accounts are rejected by requireActiveActor.
    await adminDb.collection('users').doc(user.uid).set({ suspended: true }, { merge: true });
    await signInAs(user);
    await expect(call('auth-recordLogin', {})).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
  });
});

describe('runInactivityCleanup — delete gate CLOSED (MVP default)', () => {
  it('warns, would-deletes (no delete), and clears warnings without hard-deleting', async () => {
    const suffix = `${Date.now()}`;
    const created14mo = Timestamp.fromDate(subtractMonths(now, 14));

    // A: inactive 14 months, never warned → WARN.
    const warnUid = `sweep-warn-${suffix}`;
    await seedUser(warnUid, { createdAt: created14mo });

    // B: warned 40 days ago, deleteAfter 10 days ago, still inactive → WOULD_DELETE.
    const wouldUid = `sweep-would-${suffix}`;
    await seedUser(wouldUid, {
      createdAt: created14mo,
      inactivityWarnedAt: Timestamp.fromDate(addDays(now, -40)),
      inactivityDeleteAfter: Timestamp.fromDate(addDays(now, -10)),
    });

    // C: warned 40 days ago but signed in 5 days ago → CLEAR_WARNING.
    const clearUid = `sweep-clear-${suffix}`;
    await seedUser(clearUid, {
      createdAt: created14mo,
      lastLoginAt: Timestamp.fromDate(addDays(now, -5)),
      inactivityWarnedAt: Timestamp.fromDate(addDays(now, -40)),
      inactivityDeleteAfter: Timestamp.fromDate(addDays(now, -10)),
    });

    const summary = await runInactivityCleanup(now);
    expect(summary.deletionEnabled).toBe(false);
    expect(summary.deleted).toBe(0);
    expect(summary.warned).toBeGreaterThanOrEqual(1);
    expect(summary.wouldDelete).toBeGreaterThanOrEqual(1);

    // Delivery-gated warn: the in-app notice delivers (essential category) so the
    // warning is marked, not deferred.
    expect(summary.warnDeferred).toBe(0);

    // Warning fields land in userLifecycle/{uid}, not the public profile.
    const warned = (await adminDb.collection('userLifecycle').doc(warnUid).get()).data()!;
    expect(warned.inactivityWarnedAt).toBeInstanceOf(Timestamp);
    expect(warned.inactivityDeleteAfter).toBeInstanceOf(Timestamp);
    // Both timestamps share the sweep's `now` basis, so the grace window is
    // exact: inactivityDeleteAfter === inactivityWarnedAt + grace (no drift from
    // a server-resolved warnedAt).
    const warnedAt = (warned.inactivityWarnedAt as Timestamp).toDate();
    const deleteAfter = (warned.inactivityDeleteAfter as Timestamp).toDate();
    expect(deleteAfter.getTime()).toBe(addDays(warnedAt, INACTIVITY_DELETE_GRACE_DAYS).getTime());
    const warnedProfile = (await adminDb.collection('users').doc(warnUid).get()).data()!;
    expect(warnedProfile.inactivityWarnedAt).toBeUndefined();

    // Would-delete account is untouched (still present, still warned).
    expect((await adminDb.collection('users').doc(wouldUid).get()).exists).toBe(true);

    // Cleared account keeps existing but the warning fields are gone from userLifecycle.
    const cleared = (await adminDb.collection('userLifecycle').doc(clearUid).get()).data() ?? {};
    expect(cleared.inactivityWarnedAt).toBeUndefined();
    expect(cleared.inactivityDeleteAfter).toBeUndefined();

    // Short candidate page (<200) → sweep cursor reset to wrap next run.
    const cursor = (await adminDb.collection('system').doc('inactivitySweepCursor').get()).data();
    expect(cursor?.lastCreatedAt).toBeUndefined();
  });

  it('does NOT start the grace clock when the warning cannot be delivered', async () => {
    const suffix = `${Date.now()}-nodel`;
    const created14mo = Timestamp.fromDate(subtractMonths(now, 14));

    // Inactive 14 months, never warned → WARN, but force delivery to fail on
    // every channel (email is off by default; stub the in-app writer to report
    // a non-delivery). The account must be left UNWARNED and retried next run —
    // deletion can never be predicated on an undelivered warning.
    const uid = `sweep-nodel-${suffix}`;
    await seedUser(uid, { createdAt: created14mo });

    const deliverSpy = vi
      .spyOn(deliverModule, 'writeInAppNotification')
      .mockResolvedValue({ delivered: false, notificationId: null, skippedReason: 'suspended' });

    try {
      const summary = await runInactivityCleanup(now);
      expect(summary.warnDeferred).toBeGreaterThanOrEqual(1);
    } finally {
      deliverSpy.mockRestore();
    }

    // No grace clock started: the lifecycle doc has no warning fields (so the
    // next sweep sees the account as not-yet-warned and re-attempts the warning).
    const lifecycle = (await adminDb.collection('userLifecycle').doc(uid).get()).data() ?? {};
    expect(lifecycle.inactivityWarnedAt).toBeUndefined();
    expect(lifecycle.inactivityDeleteAfter).toBeUndefined();
  });
});

describe('runInactivityCleanup — delete gate OPEN', () => {
  it('hard-deletes a past-grace account and retains the proof-of-deletion', async () => {
    const suffix = `${Date.now()}`;
    const created14mo = Timestamp.fromDate(subtractMonths(now, 14));

    // Real Auth user so the reused purgeUserData deletes it too.
    const delUid = (
      await adminAuth.createUser({ email: `sweep-del-${suffix}@example.com` })
    ).uid;
    await seedUser(delUid, {
      createdAt: created14mo,
      inactivityWarnedAt: Timestamp.fromDate(addDays(now, -40)),
      inactivityDeleteAfter: Timestamp.fromDate(addDays(now, -10)),
    });

    // Open BOTH sides of the gate: config flag + email availability. Email
    // availability is stubbed at the module seam (not just the env flag)
    // because in production isEmailDeliveryAvailable() stays hard-closed until a
    // real provider is integrated (EMAIL_PROVIDER_INTEGRATED).
    await adminDb
      .collection('config')
      .doc(ACCOUNT_LIFECYCLE_CONFIG_DOC)
      .set({ [INACTIVE_DELETION_ENABLED_FIELD]: true }, { merge: true });
    const emailSpy = vi
      .spyOn(emailModule, 'isEmailDeliveryAvailable')
      .mockReturnValue(true);

    try {
      const summary = await runInactivityCleanup(now);
      expect(summary.deletionEnabled).toBe(true);
      expect(summary.deleted).toBeGreaterThanOrEqual(1);
    } finally {
      emailSpy.mockRestore();
      await adminDb
        .collection('config')
        .doc(ACCOUNT_LIFECYCLE_CONFIG_DOC)
        .set({ [INACTIVE_DELETION_ENABLED_FIELD]: false }, { merge: true });
    }

    // User doc + userLifecycle + Auth user gone; proof-of-deletion record retained.
    expect((await adminDb.collection('users').doc(delUid).get()).exists).toBe(false);
    expect((await adminDb.collection('userLifecycle').doc(delUid).get()).exists).toBe(false);
    await expect(adminAuth.getUser(delUid)).rejects.toMatchObject({ code: 'auth/user-not-found' });
    const record = (await adminDb.collection('accountDeletionRequests').doc(delUid).get()).data()!;
    expect(record.status).toBe('processed');
    expect(record.reason).toBe(INACTIVITY_DELETION_REASON);
  });
});
