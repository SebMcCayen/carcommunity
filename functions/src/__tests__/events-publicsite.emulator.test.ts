/**
 * events-setPublicSite emulator integration tests — the creator-or-admin
 * authorization model for the public-site flag, end to end against the
 * deployed-in-emulator callables.
 *
 * The homepage REGENERATION itself never runs here (events-onPublicSiteWrite
 * exits early under FUNCTIONS_EMULATOR, and homepageRepo would refuse the
 * network anyway — the suite stays hermetic); its logic is covered by the
 * pure-unit suites publicSite-core.test.ts and homepageRepo.test.ts. What
 * MUST be integration-tested is who can flip the flag on whose event, which
 * is exactly what these cases pin.
 *
 * Requires the Functions emulator in addition to Auth/Firestore — run via:
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'publicsite-emulator-tests');
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

async function createMemberUser(prefix: string): Promise<TestUser> {
  const user = await createProvisionedUser(prefix);
  await adminDb.collection('users').doc(user.uid).set({ activeMember: true }, { merge: true });
  return user;
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

let adminUser: TestUser;

const futureStart = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

const validCreate = {
  title: 'Publik sida-testträff',
  summary: 'Kort sammanfattning',
  startsAt: futureStart,
  locationName: 'Testparkeringen',
};

/**
 * Creates a published event via the member create path, owned by a FRESH
 * member (returned alongside the id, signed in). A new creator per event —
 * never a shared one — so the 3-per-rolling-24h member creation cap can
 * never leak from one test into another (same rule the events emulator
 * suite follows).
 */
async function createCreatorEvent(
  overrides: Record<string, unknown> = {},
): Promise<{ eventId: string; creator: TestUser }> {
  const creator = await createMemberUser('publicsite-creator');
  await signInAs(creator);
  const result = await call('events-create', { ...validCreate, ...overrides });
  return { eventId: (result.data as { eventId: string }).eventId, creator };
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'publicsite-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('publicsite-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('events-create – creator opt-in flag', () => {
  it('persists publicSiteEnabled + publicSiteEnabledAt when the creator opts in', async () => {
    const { eventId } = await createCreatorEvent({ publicSiteEnabled: true });
    const snap = await adminDb.collection('events').doc(eventId).get();
    expect(snap.get('publicSiteEnabled')).toBe(true);
    expect(snap.get('publicSiteEnabledAt')).not.toBeNull();
    expect(snap.get('status')).toBe('published');
  });

  it('defaults to NOT publicly enabled', async () => {
    const { eventId } = await createCreatorEvent();
    const snap = await adminDb.collection('events').doc(eventId).get();
    expect(snap.get('publicSiteEnabled')).toBe(false);
    expect(snap.get('publicSiteEnabledAt')).toBeNull();
  });
});

describe('events-setPublicSite – authorization', () => {
  it('rejects unauthenticated calls', async () => {
    await auth.signOut();
    expect(
      await callableErrorCode(call('events-setPublicSite', { eventId: 'whatever', enabled: true })),
    ).toBe('functions/unauthenticated');
  });

  it('lets the CREATOR enable and disable their own event', async () => {
    const { eventId, creator } = await createCreatorEvent();

    await signInAs(creator);
    const enabled = (await call('events-setPublicSite', { eventId, enabled: true })).data as {
      eventId: string;
      publicSiteEnabled: boolean;
    };
    expect(enabled).toEqual({ eventId, publicSiteEnabled: true });
    let snap = await adminDb.collection('events').doc(eventId).get();
    expect(snap.get('publicSiteEnabled')).toBe(true);
    expect(snap.get('publicSiteEnabledAt')).not.toBeNull();

    await call('events-setPublicSite', { eventId, enabled: false });
    snap = await adminDb.collection('events').doc(eventId).get();
    expect(snap.get('publicSiteEnabled')).toBe(false);
    expect(snap.get('publicSiteEnabledAt')).toBeNull();
  });

  it('denies ANY OTHER member — publishing someone else’s event is not theirs to decide', async () => {
    const { eventId } = await createCreatorEvent();
    const otherMember = await createMemberUser('publicsite-other');
    await signInAs(otherMember);
    expect(
      await callableErrorCode(call('events-setPublicSite', { eventId, enabled: true })),
    ).toBe('functions/permission-denied');
    const snap = await adminDb.collection('events').doc(eventId).get();
    expect(snap.get('publicSiteEnabled')).toBe(false);
  });

  it('lets an ADMIN force-unpublish someone else’s event (moderation safety valve) with an audit record', async () => {
    const { eventId } = await createCreatorEvent({ publicSiteEnabled: true });

    await signInAs(adminUser);
    await call('events-setPublicSite', { eventId, enabled: false });

    const snap = await adminDb.collection('events').doc(eventId).get();
    expect(snap.get('publicSiteEnabled')).toBe(false);

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('targetId', '==', eventId)
      .where('action', '==', 'event.public_site_disable')
      .get();
    expect(audit.size).toBe(1);
    expect(audit.docs[0]!.get('adminId')).toBe(adminUser.uid);
  });

  it('writes NO admin audit record for a creator toggling their own event', async () => {
    const { eventId, creator } = await createCreatorEvent();
    await signInAs(creator);
    await call('events-setPublicSite', { eventId, enabled: true });
    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('targetId', '==', eventId)
      .get();
    expect(audit.size).toBe(0);
  });
});

describe('events-setPublicSite – lifecycle guards', () => {
  it('refuses ENABLING a cancelled event but always allows disabling', async () => {
    const { eventId, creator } = await createCreatorEvent({ publicSiteEnabled: true });
    await signInAs(adminUser);
    await call('events-cancel', { eventId, reason: 'Testnedtagning.' });

    await signInAs(creator);
    expect(
      await callableErrorCode(call('events-setPublicSite', { eventId, enabled: true })),
    ).toBe('functions/failed-precondition');

    // Disabling must keep working — never stuck publicly listed.
    await call('events-setPublicSite', { eventId, enabled: false });
    const snap = await adminDb.collection('events').doc(eventId).get();
    expect(snap.get('publicSiteEnabled')).toBe(false);
  });

  it('answers not-found for a missing event', async () => {
    await signInAs(await createMemberUser('publicsite-caller'));
    expect(
      await callableErrorCode(call('events-setPublicSite', { eventId: 'does-not-exist', enabled: true })),
    ).toBe('functions/not-found');
  });

  it('rejects a malformed payload', async () => {
    await signInAs(await createMemberUser('publicsite-caller'));
    expect(await callableErrorCode(call('events-setPublicSite', { eventId: 'x' }))).toBe(
      'functions/invalid-argument',
    );
  });
});
