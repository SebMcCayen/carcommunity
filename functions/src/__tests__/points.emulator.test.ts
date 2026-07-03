/**
 * Kronpoäng ledger emulator integration tests (Phase 9g).
 *
 * Exercises the admin callables and the internal transaction primitives'
 * invariants end-to-end:
 * - points-adminAdjust (credit/debit, overdraft rejection, owner protection)
 * - points-adminReverse (single reversal, no reversal-of-reversal)
 * - concurrent debits never overdraft (transaction serialization)
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'points-emulator-tests');
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

const wallet = (uid: string) => adminDb.collection('pointsLedger').doc(uid);

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'points-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('points-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  member = await createProvisionedUser('points-member');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('points-adminAdjust', () => {
  it('rejects non-admin callers and invalid input', async () => {
    await signInAs(member);
    expect(
      await callableErrorCode(
        call('points-adminAdjust', {
          targetUid: member.uid,
          type: 'adjustment_credit',
          amount: 1000,
          reason: 'Self enrichment',
        }),
      ),
    ).toBe('functions/permission-denied');

    await signInAs(adminUser);
    expect(
      await callableErrorCode(
        call('points-adminAdjust', {
          targetUid: member.uid,
          type: 'adjustment_credit',
          amount: -5,
          reason: 'Negative',
        }),
      ),
    ).toBe('functions/invalid-argument');
  });

  it('credits and debits atomically with running balanceAfter', async () => {
    await signInAs(adminUser);
    const credit = (
      await call('points-adminAdjust', {
        targetUid: member.uid,
        type: 'adjustment_credit',
        amount: 100,
        reason: 'Välkomstbonus',
      })
    ).data as { balanceAfter: number; entryId: string };
    expect(credit.balanceAfter).toBe(100);

    const debit = (
      await call('points-adminAdjust', {
        targetUid: member.uid,
        type: 'adjustment_debit',
        amount: 30,
        reason: 'Korrigering',
      })
    ).data as { balanceAfter: number };
    expect(debit.balanceAfter).toBe(70);

    const walletDoc = (await wallet(member.uid).get()).data()!;
    expect(walletDoc.balance).toBe(70);

    const entries = await wallet(member.uid).collection('entries').get();
    expect(entries.size).toBe(2);
    const amounts = entries.docs.map((d) => d.data().amount).sort((a, b) => a - b);
    expect(amounts).toEqual([-30, 100]);
  });

  it('rejects overdrafting debits (failed-precondition)', async () => {
    await signInAs(adminUser);
    expect(
      await callableErrorCode(
        call('points-adminAdjust', {
          targetUid: member.uid,
          type: 'adjustment_debit',
          amount: 1_000_000,
          reason: 'För stort',
        }),
      ),
    ).toBe('functions/failed-precondition');
    expect(((await wallet(member.uid).get()).data() as { balance: number }).balance).toBe(70);
  });

  it('only an owner may adjust another owner (owner protection)', async () => {
    const ownerTarget = await createProvisionedUser('points-owner');
    await adminDb.collection('users').doc(ownerTarget.uid).set({ role: 'owner' }, { merge: true });
    await signInAs(adminUser);
    expect(
      await callableErrorCode(
        call('points-adminAdjust', {
          targetUid: ownerTarget.uid,
          type: 'adjustment_credit',
          amount: 10,
          reason: 'Test',
        }),
      ),
    ).toBe('functions/permission-denied');
  });
});

describe('points-adminReverse', () => {
  it('reverses an entry exactly once and never reverses a reversal', async () => {
    await signInAs(adminUser);
    const credit = (
      await call('points-adminAdjust', {
        targetUid: member.uid,
        type: 'adjustment_credit',
        amount: 40,
        reason: 'Att återföra',
      })
    ).data as { entryId: string; balanceAfter: number };

    const reversal = (
      await call('points-adminReverse', {
        targetUid: member.uid,
        entryId: credit.entryId,
        reason: 'Felaktig kreditering',
      })
    ).data as { entryId: string; amount: number; balanceAfter: number; alreadyApplied: boolean };
    expect(reversal.amount).toBe(-40);
    expect(reversal.alreadyApplied).toBe(false);
    expect(reversal.balanceAfter).toBe(credit.balanceAfter - 40);

    // Replay is a no-op returning the same reversal.
    const replay = (
      await call('points-adminReverse', {
        targetUid: member.uid,
        entryId: credit.entryId,
        reason: 'Igen',
      })
    ).data as { alreadyApplied: boolean; balanceAfter: number };
    expect(replay.alreadyApplied).toBe(true);
    expect(((await wallet(member.uid).get()).data() as { balance: number }).balance).toBe(
      reversal.balanceAfter,
    );

    // A reversal cannot be reversed.
    expect(
      await callableErrorCode(
        call('points-adminReverse', {
          targetUid: member.uid,
          entryId: reversal.entryId,
          reason: 'Återför återföringen',
        }),
      ),
    ).toBe('functions/failed-precondition');

    // Unknown entry → not-found.
    expect(
      await callableErrorCode(
        call('points-adminReverse', {
          targetUid: member.uid,
          entryId: 'missing-entry',
          reason: 'x',
        }),
      ),
    ).toBe('functions/not-found');
  });
});

describe('ledger invariants under concurrency', () => {
  it('concurrent debits serialize and never overdraft', async () => {
    const racer = await createProvisionedUser('points-racer');
    await signInAs(adminUser);
    await call('points-adminAdjust', {
      targetUid: racer.uid,
      type: 'adjustment_credit',
      amount: 100,
      reason: 'Startsaldo',
    });

    // Five concurrent 30-point debits against a 100-point balance: exactly
    // three can succeed (90 spent), the other two must fail-precondition.
    const results = await Promise.allSettled(
      Array.from({ length: 5 }, () =>
        call('points-adminAdjust', {
          targetUid: racer.uid,
          type: 'adjustment_debit',
          amount: 30,
          reason: 'Samtidig debitering',
        }),
      ),
    );
    const succeeded = results.filter((r) => r.status === 'fulfilled').length;
    expect(succeeded).toBe(3);

    const walletDoc = (await wallet(racer.uid).get()).data()!;
    expect(walletDoc.balance).toBe(10);

    // The ledger and the denormalized balance agree.
    const entries = await wallet(racer.uid).collection('entries').get();
    const sum = entries.docs.reduce((acc, d) => acc + (d.data().amount as number), 0);
    expect(sum).toBe(10);
  });
});
