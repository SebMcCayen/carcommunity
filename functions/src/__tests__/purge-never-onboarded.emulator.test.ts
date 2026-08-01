/**
 * admin.purgeNeverOnboarded emulator integration tests.
 *
 * Exercises the one-off never-onboarded cleanup end-to-end:
 *  - selection: a never-onboarded account IS a candidate; a completed-onboarding
 *    account is NOT; an admin/owner account is NEVER selected even with a null
 *    onboarding flag (and is counted in excludedAdminOwnerCount);
 *  - dryRun returns NON-SENSITIVE identifiers only (uid/createdAt/hasUserPrivate,
 *    never displayName/email) and deletes NOTHING;
 *  - a real run REFUSES without confirmToken "PURGE" (and still deletes nothing);
 *  - a real run purges via the existing cascade (Auth user + Firestore gone) and
 *    writes ONE adminAuditEvents record (uids only);
 *  - a re-run is idempotent (the purged account is no longer a candidate).
 *
 * The emulator suite shares one Firestore across files but runs them
 * SEQUENTIALLY (vitest.emulator.config.ts fileParallelism:false), so the real
 * purge here only ever touches already-finished files' stale accounts; every
 * assertion is scoped to THIS file's own uids rather than global counts.
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
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ??
  initializeAdminApp({ projectId: PROJECT_ID }, 'purge-onboard-emulator-tests');
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
  // Wait for auth-onUserCreate to provision users/{uid} (onboardingCompletedAt
  // starts null — i.e. never onboarded — by default).
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

interface Candidate {
  uid: string;
  createdAt: string | null;
  hasUserPrivate: boolean;
}
interface DryRunResponse {
  dryRun: true;
  candidateCount: number;
  candidates: Candidate[];
  excludedAdminOwnerCount: number;
  capped: boolean;
}
interface RealResponse {
  dryRun: false;
  purgedCount: number;
  purgedUids: string[];
  failures: { uid: string; error: string }[];
  excludedAdminOwnerCount: number;
  capped: boolean;
}

let actor: TestUser;
let neverOnboarded: TestUser;
let completed: TestUser;
let adminNull: TestUser;
let ownerNull: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'purge-onboard-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  // The caller: an active admin.
  actor = await createProvisionedUser('purge-actor');
  await adminAuth.setCustomUserClaims(actor.uid, { admin: true });
  await adminDb.collection('users').doc(actor.uid).set({ role: 'admin' }, { merge: true });

  // A plain never-onboarded account → should be selected.
  neverOnboarded = await createProvisionedUser('purge-never');

  // A completed-onboarding account → should be EXCLUDED (kept).
  completed = await createProvisionedUser('purge-completed');
  await adminDb
    .collection('users')
    .doc(completed.uid)
    .set({ onboardingCompletedAt: Timestamp.now() }, { merge: true });

  // An admin and an owner, BOTH with onboarding still null → must NEVER be
  // selected on the role safety net regardless of the onboarding flag.
  adminNull = await createProvisionedUser('purge-admin-null');
  await adminDb.collection('users').doc(adminNull.uid).set({ role: 'admin' }, { merge: true });
  ownerNull = await createProvisionedUser('purge-owner-null');
  await adminDb.collection('users').doc(ownerNull.uid).set({ role: 'owner' }, { merge: true });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('admin-purgeNeverOnboarded — dry run (selection + no deletion)', () => {
  it('selects never-onboarded, excludes completed and admin/owner, leaks no names, deletes nothing', async () => {
    await signInAs(actor);
    const res = (await call('admin-purgeNeverOnboarded', { dryRun: true })).data as DryRunResponse;

    expect(res.dryRun).toBe(true);
    const candidateUids = res.candidates.map((c) => c.uid);

    // The never-onboarded account is a candidate.
    expect(candidateUids).toContain(neverOnboarded.uid);
    // The completed and admin/owner accounts (incl. the actor) are NOT.
    expect(candidateUids).not.toContain(completed.uid);
    expect(candidateUids).not.toContain(adminNull.uid);
    expect(candidateUids).not.toContain(ownerNull.uid);
    expect(candidateUids).not.toContain(actor.uid);

    // At least the actor + adminNull + ownerNull were excluded on role grounds.
    expect(res.excludedAdminOwnerCount).toBeGreaterThanOrEqual(3);

    // The candidate object carries ONLY non-sensitive identifiers — never the
    // leaked displayName or email (the whole point of this cleanup).
    const mine = res.candidates.find((c) => c.uid === neverOnboarded.uid)!;
    expect(Object.keys(mine).sort()).toEqual(['createdAt', 'hasUserPrivate', 'uid']);
    expect(mine.hasUserPrivate).toBe(true); // provisioning writes userPrivate
    expect(JSON.stringify(res)).not.toContain('@example.com');

    // Dry run deleted NOTHING.
    expect((await adminDb.collection('users').doc(neverOnboarded.uid).get()).exists).toBe(true);
    await expect(adminAuth.getUser(neverOnboarded.uid)).resolves.toBeTruthy();
  });
});

describe('admin-purgeNeverOnboarded — confirm sentinel guard', () => {
  it('refuses a real run without confirmToken and deletes nothing', async () => {
    await signInAs(actor);
    expect(await callableErrorCode(call('admin-purgeNeverOnboarded', { dryRun: false }))).toBe(
      'functions/failed-precondition',
    );
    expect(
      await callableErrorCode(
        call('admin-purgeNeverOnboarded', { dryRun: false, confirmToken: 'nope' }),
      ),
    ).toBe('functions/failed-precondition');

    // Still there.
    expect((await adminDb.collection('users').doc(neverOnboarded.uid).get()).exists).toBe(true);
  });

  it('rejects non-admin callers', async () => {
    await signInAs(neverOnboarded);
    expect(await callableErrorCode(call('admin-purgeNeverOnboarded', { dryRun: true }))).toBe(
      'functions/permission-denied',
    );
  });
});

describe('admin-purgeNeverOnboarded — real purge (cascade + audit + idempotency)', () => {
  it('purges via the cascade, keeps completed/admin/owner, writes ONE audit record', async () => {
    await signInAs(actor);
    const res = (await call('admin-purgeNeverOnboarded', { dryRun: false, confirmToken: 'PURGE' }))
      .data as RealResponse;

    expect(res.dryRun).toBe(false);
    expect(res.purgedUids).toContain(neverOnboarded.uid);

    // The account is gone from BOTH Auth and Firestore (existing cascade).
    expect((await adminDb.collection('users').doc(neverOnboarded.uid).get()).exists).toBe(false);
    await expect(adminAuth.getUser(neverOnboarded.uid)).rejects.toMatchObject({
      code: 'auth/user-not-found',
    });

    // Kept: completed onboarding + admin/owner accounts survive.
    expect((await adminDb.collection('users').doc(completed.uid).get()).exists).toBe(true);
    expect((await adminDb.collection('users').doc(adminNull.uid).get()).exists).toBe(true);
    expect((await adminDb.collection('users').doc(ownerNull.uid).get()).exists).toBe(true);

    // ONE audit record for the operation, by this actor, naming purged uids only.
    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'admin.purgeNeverOnboarded')
      .where('adminId', '==', actor.uid)
      .get();
    expect(audit.size).toBeGreaterThanOrEqual(1);
    const mine = audit.docs.find((d) =>
      ((d.data().details?.purgedUids as string[]) ?? []).includes(neverOnboarded.uid),
    );
    expect(mine).toBeDefined();
    const details = mine!.data().details as { purgedUids: string[]; purgedCount: number };
    expect(details.purgedCount).toBe(details.purgedUids.length);
    // uids only — no names/emails in the audit record.
    expect(JSON.stringify(mine!.data())).not.toContain('@example.com');
  });

  it('is idempotent — the purged account is no longer a candidate and a re-run does not error', async () => {
    await signInAs(actor);
    const dry = (await call('admin-purgeNeverOnboarded', { dryRun: true })).data as DryRunResponse;
    expect(dry.candidates.map((c) => c.uid)).not.toContain(neverOnboarded.uid);

    // A second real run completes without throwing (nothing of ours left to purge).
    const again = (
      await call('admin-purgeNeverOnboarded', { dryRun: false, confirmToken: 'PURGE' })
    ).data as RealResponse;
    expect(again.dryRun).toBe(false);
    expect(again.purgedUids).not.toContain(neverOnboarded.uid);
  });
});
