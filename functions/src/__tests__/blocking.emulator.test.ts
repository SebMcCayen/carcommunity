/**
 * Blocking emulator integration tests.
 *
 * Exercises the deployed-in-emulator callables end-to-end plus the
 * userBlocks Firestore rules:
 * - `blocking-block` (self-block, not-found, idempotency, denormalized name)
 * - `blocking-unblock` (idempotent removal)
 * - userBlocks/{uid}/blocked: owner-only read, no client writes.
 *
 * Requires the Auth + Functions + Firestore emulators — run via:
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
import {
  collection,
  connectFirestoreEmulator,
  doc,
  getDoc,
  getDocs,
  getFirestore,
  setDoc,
  type Firestore,
} from 'firebase/firestore';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'blocking-emulator-tests');
const adminDb = getAdminFirestore(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;
let firestore: Firestore;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

async function pollUntil<T>(read: () => Promise<T | undefined>, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, 250));
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

let alice: TestUser;
let bob: TestUser;
let carol: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'blocking-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
  firestore = getFirestore(app);
  connectFirestoreEmulator(firestore, EMULATOR_HOST, 8080);

  alice = await createProvisionedUser('block-alice');
  bob = await createProvisionedUser('block-bob');
  carol = await createProvisionedUser('block-carol');
  await adminDb.collection('users').doc(bob.uid).set({ displayName: 'Bob' }, { merge: true });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('blocking-block', () => {
  it('rejects unauthenticated callers', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('blocking-block', { targetUserId: bob.uid }))).toBe(
      'functions/unauthenticated',
    );
  });

  it('rejects self-blocking and missing targets', async () => {
    await signInAs(alice);
    expect(await callableErrorCode(call('blocking-block', { targetUserId: alice.uid }))).toBe(
      'functions/invalid-argument',
    );
    expect(await callableErrorCode(call('blocking-block', { targetUserId: 'does-not-exist' }))).toBe(
      'functions/not-found',
    );
    expect(await callableErrorCode(call('blocking-block', {}))).toBe('functions/invalid-argument');
  });

  it('blocks a user, denormalizing the display name, and is idempotent', async () => {
    await signInAs(alice);
    const first = await call('blocking-block', { targetUserId: bob.uid });
    const firstData = first.data as {
      block: { userId: string; displayName: string | null; blockedAt: string };
      shouldRefreshMarkers: boolean;
    };
    expect(firstData.block.userId).toBe(bob.uid);
    expect(firstData.block.displayName).toBe('Bob');
    expect(firstData.shouldRefreshMarkers).toBe(true);

    const stored = (
      await adminDb.collection('userBlocks').doc(alice.uid).collection('blocked').doc(bob.uid).get()
    ).data()!;
    expect(stored.blockedUserId).toBe(bob.uid);
    expect(stored.displayName).toBe('Bob');
    const createdAt = stored.createdAt;

    // Re-blocking keeps the original createdAt (idempotent).
    await call('blocking-block', { targetUserId: bob.uid });
    const reStored = (
      await adminDb.collection('userBlocks').doc(alice.uid).collection('blocked').doc(bob.uid).get()
    ).data()!;
    expect(reStored.createdAt.toMillis()).toBe(createdAt.toMillis());
  });
});

describe('blocking-unblock', () => {
  it('removes an existing block and is a no-op otherwise', async () => {
    await signInAs(alice);
    await call('blocking-block', { targetUserId: carol.uid });

    const first = await call('blocking-unblock', { targetUserId: carol.uid });
    expect((first.data as { unblocked: boolean }).unblocked).toBe(true);
    const gone = await adminDb
      .collection('userBlocks')
      .doc(alice.uid)
      .collection('blocked')
      .doc(carol.uid)
      .get();
    expect(gone.exists).toBe(false);

    const second = await call('blocking-unblock', { targetUserId: carol.uid });
    expect((second.data as { unblocked: boolean }).unblocked).toBe(false);

    const self = await call('blocking-unblock', { targetUserId: alice.uid });
    expect((self.data as { unblocked: boolean }).unblocked).toBe(false);
  });
});

describe('userBlocks rules', () => {
  it('lets the owner read their own blocked list but no one else', async () => {
    await signInAs(alice);
    await call('blocking-block', { targetUserId: bob.uid });

    // Owner can list their own blocks.
    const ownList = await getDocs(collection(firestore, 'userBlocks', alice.uid, 'blocked'));
    expect(ownList.docs.some((d) => d.id === bob.uid)).toBe(true);

    // A different user cannot read Alice's blocked list (private).
    await signInAs(bob);
    await expect(
      getDoc(doc(firestore, 'userBlocks', alice.uid, 'blocked', bob.uid)),
    ).rejects.toMatchObject({ code: 'permission-denied' });
  });

  it('forbids direct client writes to userBlocks', async () => {
    await signInAs(alice);
    await expect(
      setDoc(doc(firestore, 'userBlocks', alice.uid, 'blocked', carol.uid), { blockedUserId: carol.uid }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
  });
});
