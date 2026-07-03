/**
 * Events domain emulator integration tests (Phase 9b).
 *
 * Exercises the deployed-in-emulator callables end-to-end:
 * - `events-create` / `events-update` (callables events.create/update)
 * - `events-publish` / `events-cancel` / `events-complete`
 * plus the `events-onRsvpWrite` trigger maintaining rsvpCounts.
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'events-emulator-tests');
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
let regularUser: TestUser;

const futureStart = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

const validCreate = {
  title: 'Kronjakt kickoff cruise',
  summary: 'Season opener',
  description: 'Member-only long description',
  startsAt: futureStart,
  approximateArea: 'Stockholm area',
  locationName: 'Exact parking lot',
  address: 'Garagevägen 1',
  latitude: 59.3,
  longitude: 18.0,
};

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'events-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('events-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  regularUser = await createProvisionedUser('events-regular');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

async function createDraftEvent(overrides: Record<string, unknown> = {}): Promise<string> {
  await signInAs(adminUser);
  const result = await call('events-create', { ...validCreate, ...overrides });
  return (result.data as { eventId: string }).eventId;
}

describe('events callables – authorization', () => {
  it('rejects unauthenticated calls', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('events-create', validCreate))).toBe(
      'functions/unauthenticated',
    );
  });

  it('rejects non-admin callers', async () => {
    await signInAs(regularUser);
    expect(await callableErrorCode(call('events-create', validCreate))).toBe(
      'functions/permission-denied',
    );
    expect(
      await callableErrorCode(call('events-publish', { eventId: 'irrelevant' })),
    ).toBe('functions/permission-denied');
  });
});

describe('events-create / events-update', () => {
  it('creates a draft with the teaser/private document split and an audit record', async () => {
    const eventId = await createDraftEvent();

    const eventSnap = await adminDb.collection('events').doc(eventId).get();
    const event = eventSnap.data()!;
    expect(event.status).toBe('draft');
    expect(event.title).toBe(validCreate.title);
    expect(event.approximateArea).toBe(validCreate.approximateArea);
    expect(event.rsvpCounts).toEqual({ going: 0, maybe: 0, not_going: 0 });
    expect(event.createdByUserId).toBe(adminUser.uid);
    // Exact location must not leak onto the teaser document.
    expect(event.locationName).toBeUndefined();
    expect(event.latitude).toBeUndefined();
    expect(event.description).toBeUndefined();

    const privateSnap = await adminDb
      .collection('events')
      .doc(eventId)
      .collection('details')
      .doc('private')
      .get();
    const detail = privateSnap.data()!;
    expect(detail.locationName).toBe(validCreate.locationName);
    expect(detail.latitude).toBe(validCreate.latitude);
    expect(detail.description).toBe(validCreate.description);

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'event.create')
      .where('targetId', '==', eventId)
      .get();
    expect(audit.size).toBe(1);
    expect(audit.docs[0].data().adminId).toBe(adminUser.uid);
  });

  it('rejects invalid create input with contract codes', async () => {
    await signInAs(adminUser);
    expect(await callableErrorCode(call('events-create', { title: 'No required fields' }))).toBe(
      'functions/invalid-argument',
    );
    expect(
      await callableErrorCode(
        call('events-create', { ...validCreate, endsAt: '2000-01-01T00:00:00.000Z' }),
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(call('events-create', { ...validCreate, longitude: null })),
    ).toBe('functions/invalid-argument');
  });

  it('updates fields on the correct documents and records changedFields', async () => {
    const eventId = await createDraftEvent();
    await call('events-update', { eventId, title: 'Renamed cruise', address: 'New address 2' });

    const event = (await adminDb.collection('events').doc(eventId).get()).data()!;
    expect(event.title).toBe('Renamed cruise');
    const detail = (
      await adminDb.collection('events').doc(eventId).collection('details').doc('private').get()
    ).data()!;
    expect(detail.address).toBe('New address 2');

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'event.update')
      .where('targetId', '==', eventId)
      .get();
    expect(audit.size).toBe(1);
    expect(audit.docs[0].data().details.changedFields.sort()).toEqual(['address', 'title']);
  });

  it('rejects updates to unknown events and empty updates', async () => {
    await signInAs(adminUser);
    expect(
      await callableErrorCode(call('events-update', { eventId: 'missing-event', title: 'X' })),
    ).toBe('functions/not-found');
    const eventId = await createDraftEvent();
    expect(await callableErrorCode(call('events-update', { eventId }))).toBe(
      'functions/invalid-argument',
    );
  });
});

describe('events lifecycle transitions', () => {
  it('publishes a future-starting draft and blocks a second publish', async () => {
    const eventId = await createDraftEvent();
    const result = await call('events-publish', { eventId });
    expect((result.data as { status: string }).status).toBe('published');

    const event = (await adminDb.collection('events').doc(eventId).get()).data()!;
    expect(event.status).toBe('published');

    expect(await callableErrorCode(call('events-publish', { eventId }))).toBe(
      'functions/failed-precondition',
    );
  });

  it('refuses to publish an event that starts in the past', async () => {
    const eventId = await createDraftEvent();
    // Backdate startsAt out-of-band (create validates it, publish re-checks).
    await adminDb
      .collection('events')
      .doc(eventId)
      .update({ startsAt: new Date(Date.now() - 60_000) });
    expect(await callableErrorCode(call('events-publish', { eventId }))).toBe(
      'functions/failed-precondition',
    );
  });

  it('cancels with a required reason, sets cancelledAt, and blocks re-cancel', async () => {
    const eventId = await createDraftEvent();
    await call('events-publish', { eventId });

    expect(await callableErrorCode(call('events-cancel', { eventId }))).toBe(
      'functions/invalid-argument',
    );

    await call('events-cancel', { eventId, reason: 'Storm warning' });
    const event = (await adminDb.collection('events').doc(eventId).get()).data()!;
    expect(event.status).toBe('cancelled');
    expect(event.cancelledAt).not.toBeNull();

    expect(
      await callableErrorCode(call('events-cancel', { eventId, reason: 'Again' })),
    ).toBe('functions/failed-precondition');

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'event.cancel')
      .where('targetId', '==', eventId)
      .get();
    expect(audit.size).toBe(1);
    expect(audit.docs[0].data().reason).toBe('Storm warning');
  });

  it('completes published events only and freezes completed/cancelled events', async () => {
    const eventId = await createDraftEvent();
    expect(await callableErrorCode(call('events-complete', { eventId }))).toBe(
      'functions/failed-precondition',
    );

    await call('events-publish', { eventId });
    await call('events-complete', { eventId });
    const event = (await adminDb.collection('events').doc(eventId).get()).data()!;
    expect(event.status).toBe('completed');

    expect(
      await callableErrorCode(call('events-update', { eventId, title: 'Too late' })),
    ).toBe('functions/failed-precondition');
    expect(
      await callableErrorCode(call('events-cancel', { eventId, reason: 'Too late' })),
    ).toBe('functions/failed-precondition');
  });
});

describe('events-onRsvpWrite trigger', () => {
  it('maintains rsvpCounts across create, change, and delete', async () => {
    const eventId = await createDraftEvent();
    await call('events-publish', { eventId });
    const rsvpRef = adminDb
      .collection('events')
      .doc(eventId)
      .collection('rsvps')
      .doc(regularUser.uid);
    const counts = async () =>
      (await adminDb.collection('events').doc(eventId).get()).data()!.rsvpCounts;

    await rsvpRef.set({ status: 'going', updatedAt: new Date() });
    await pollUntil(async () => ((await counts()).going === 1 ? true : undefined));
    expect(await counts()).toEqual({ going: 1, maybe: 0, not_going: 0 });

    await rsvpRef.set({ status: 'maybe', updatedAt: new Date() });
    await pollUntil(async () => ((await counts()).maybe === 1 ? true : undefined));
    expect(await counts()).toEqual({ going: 0, maybe: 1, not_going: 0 });

    await rsvpRef.delete();
    await pollUntil(async () => ((await counts()).maybe === 0 ? true : undefined));
    expect(await counts()).toEqual({ going: 0, maybe: 0, not_going: 0 });
  });
});
