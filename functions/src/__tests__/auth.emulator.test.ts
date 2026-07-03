/**
 * Auth domain emulator integration tests.
 *
 * Exercises the deployed-in-emulator functions end-to-end:
 * - `auth-onUserCreate` (1st-gen Auth trigger): first sign-in provisions
 *   `users/{uid}` and `userPrivate/{uid}`.
 * - `auth-completeOnboarding` (callable `auth.completeOnboarding`): writes
 *   onboarding/consent timestamps server-side and is idempotent.
 *
 * Requires the Functions emulator in addition to Auth/Firestore — run via:
 *   pnpm emulators:test
 */

import { deleteApp, initializeApp, type FirebaseApp } from 'firebase/app';
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  updateProfile,
  type Auth,
} from 'firebase/auth';
import {
  connectFirestoreEmulator,
  doc,
  getDoc,
  getFirestore,
  type Firestore,
} from 'firebase/firestore';
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import { FirebaseError } from 'firebase/app';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';
const CALLABLE_NAME = 'auth-completeOnboarding';

let app: FirebaseApp;
let auth: Auth;
let firestore: Firestore;
let functions: Functions;

beforeAll(() => {
  app = initializeApp({
    projectId: PROJECT_ID,
    apiKey: 'demo-api-key',
    appId: 'demo-app-id',
  });
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  firestore = getFirestore(app);
  connectFirestoreEmulator(firestore, EMULATOR_HOST, 8080);
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
});

afterAll(async () => {
  await deleteApp(app);
});

async function pollUntil<T>(
  read: () => Promise<T | undefined>,
  // Generous default: the first trigger execution pays the Functions
  // emulator's runtime cold-start cost, which can be slow on CI runners.
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

const validInput = {
  ageConfirmed: true,
  termsAccepted: true,
  privacyPolicyAccepted: true,
} as const;

describe('auth-onUserCreate trigger', () => {
  it('provisions users/{uid} and userPrivate/{uid} on first sign-in', async () => {
    const email = `trigger-${Date.now()}@example.com`;
    const credential = await createUserWithEmailAndPassword(auth, email, 'password-123');
    const uid = credential.user.uid;

    const profile = await pollUntil(async () => {
      const snap = await getDoc(doc(firestore, 'users', uid));
      return snap.exists() ? snap.data() : undefined;
    });

    expect(profile.role).toBe('user');
    expect(profile.activeMember).toBe(false);
    expect(profile.suspended).toBe(false);
    expect(profile.deleted).toBe(false);
    expect(profile.onboardingCompletedAt).toBeNull();
    expect(profile.displayName).toBeTruthy();
    expect(profile.createdAt).toBeTruthy();
    // Email must never appear on the public profile.
    expect(JSON.stringify(profile)).not.toContain(email);

    const priv = await pollUntil(async () => {
      const snap = await getDoc(doc(firestore, 'userPrivate', uid));
      return snap.exists() ? snap.data() : undefined;
    });
    expect(priv.email).toBe(email);
    expect(priv.ageConfirmedAt).toBeNull();
    expect(priv.termsAcceptedAt).toBeNull();
    expect(priv.privacyPolicyAcceptedAt).toBeNull();
    expect(priv.anonymousPartnerStatsOptIn).toBe(false);
  });
});

describe('auth-completeOnboarding callable', () => {
  it('rejects unauthenticated calls with the unauthenticated contract code', async () => {
    if (auth.currentUser) await auth.signOut();
    const callable = httpsCallable(functions, CALLABLE_NAME);
    expect(await callableErrorCode(callable(validInput))).toBe('functions/unauthenticated');
  });

  it('rejects invalid input with the invalid-argument contract code', async () => {
    const email = `invalid-input-${Date.now()}@example.com`;
    await createUserWithEmailAndPassword(auth, email, 'password-123');
    const callable = httpsCallable(functions, CALLABLE_NAME);

    expect(await callableErrorCode(callable({}))).toBe('functions/invalid-argument');
    expect(await callableErrorCode(callable({ ...validInput, ageConfirmed: false }))).toBe(
      'functions/invalid-argument',
    );
    expect(await callableErrorCode(callable({ ...validInput, role: 'admin' }))).toBe(
      'functions/invalid-argument',
    );
  });

  it('writes onboarding timestamps server-side and returns onboardingStatus', async () => {
    const email = `onboarding-${Date.now()}@example.com`;
    const credential = await createUserWithEmailAndPassword(auth, email, 'password-123');
    const uid = credential.user.uid;
    await updateProfile(credential.user, { displayName: 'Provider Name' });

    const callable = httpsCallable(functions, CALLABLE_NAME);
    const result = await callable({ ...validInput, displayName: 'Onboarded Anna' });
    const status = result.data as Record<string, unknown>;

    for (const field of [
      'onboardingCompletedAt',
      'ageConfirmedAt',
      'termsAcceptedAt',
      'privacyPolicyAcceptedAt',
    ]) {
      expect(typeof status[field], `${field} should be an ISO string`).toBe('string');
      expect(Number.isNaN(Date.parse(status[field] as string))).toBe(false);
    }

    const profile = await pollUntil(async () => {
      const snap = await getDoc(doc(firestore, 'users', uid));
      const data = snap.exists() ? snap.data() : undefined;
      return data?.onboardingCompletedAt ? data : undefined;
    });
    expect(profile.displayName).toBe('Onboarded Anna');

    const privSnap = await getDoc(doc(firestore, 'userPrivate', uid));
    expect(privSnap.exists()).toBe(true);
    expect(privSnap.data()?.ageConfirmedAt).toBeTruthy();
  });

  it('is idempotent: repeat calls preserve the original consent timestamps', async () => {
    const email = `idempotent-${Date.now()}@example.com`;
    await createUserWithEmailAndPassword(auth, email, 'password-123');
    const callable = httpsCallable(functions, CALLABLE_NAME);

    const first = (await callable(validInput)).data as Record<string, unknown>;
    // Ensure a measurable gap so identical values prove idempotency.
    await new Promise((resolve) => setTimeout(resolve, 1_100));
    const second = (await callable(validInput)).data as Record<string, unknown>;

    expect(second.onboardingCompletedAt).toBe(first.onboardingCompletedAt);
    expect(second.ageConfirmedAt).toBe(first.ageConfirmedAt);
    expect(second.termsAcceptedAt).toBe(first.termsAcceptedAt);
    expect(second.privacyPolicyAcceptedAt).toBe(first.privacyPolicyAcceptedAt);
  });
});
