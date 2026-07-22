/**
 * events.listAttendees emulator integration tests.
 *
 * Exercises the deployed-in-emulator `events-listAttendees` callable end-to-end:
 * - a signed-in member gets the roster of a PUBLISHED event with each answer
 * - unauthenticated + suspended callers are rejected
 * - a DRAFT event's roster is not exposed (not-found)
 * - a member the caller blocked (either direction) is filtered out
 * - a deleted / missing users/{uid} is skipped rather than shown nameless
 *
 * NOTE: member gating is currently DISABLED (shared/memberGating.ts), so a
 * signed-in, non-suspended account passes the member gate regardless of the
 * `activeMember` entitlement — the "non-member" rejection this suite asserts is
 * therefore the suspension path, which always closes the door.
 *
 * Requires the Functions emulator in addition to Auth/Firestore — CI-only, run via:
 *   pnpm emulators:test   (no local JVM here, so this file is not run locally).
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'attendees-emulator-tests');
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

interface Attendee {
  userId: string;
  displayName: string | null;
  avatarPath: string | null;
  status: string;
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

async function createProvisionedUser(prefix: string, displayName: string): Promise<TestUser> {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'password-123';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  const uid = credential.user.uid;
  await pollUntil(async () => {
    const snap = await adminDb.collection('users').doc(uid).get();
    return snap.exists ? true : undefined;
  });
  await adminDb.collection('users').doc(uid).set({ displayName }, { merge: true });
  return { uid, email, password };
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

async function setRsvp(eventId: string, uid: string, status: string): Promise<void> {
  await adminDb
    .collection('events')
    .doc(eventId)
    .collection('rsvps')
    .doc(uid)
    .set({ status, updatedAt: new Date() });
}

let adminUser: TestUser;
let viewer: TestUser;
let going: TestUser;
let maybe: TestUser;
let notGoing: TestUser;
let blocked: TestUser;
let suspended: TestUser;
let ghostUid: string;
let publishedEventId: string;
let draftEventId: string;

const futureStart = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString();

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'attendees-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('att-admin', 'Admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });

  viewer = await createProvisionedUser('att-viewer', 'Viewer');
  going = await createProvisionedUser('att-going', 'Gina Going');
  maybe = await createProvisionedUser('att-maybe', 'Max Maybe');
  notGoing = await createProvisionedUser('att-not', 'Nils NotGoing');
  blocked = await createProvisionedUser('att-blocked', 'Bad Blocked');
  suspended = await createProvisionedUser('att-suspended', 'Suspended Sam');
  await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });

  // A RSVP whose users/{uid} doc does not exist — a deleted/missing account.
  ghostUid = `ghost-${Date.now()}`;

  // Published event with a spread of RSVP answers.
  await signInAs(adminUser);
  const created = await call('events-create', {
    title: 'Attendee test event',
    startsAt: futureStart,
    approximateArea: 'Test area',
  });
  publishedEventId = (created.data as { eventId: string }).eventId;
  await call('events-publish', { eventId: publishedEventId });

  await setRsvp(publishedEventId, going.uid, 'going');
  await setRsvp(publishedEventId, maybe.uid, 'maybe');
  await setRsvp(publishedEventId, notGoing.uid, 'not_going');
  await setRsvp(publishedEventId, blocked.uid, 'going');
  await setRsvp(publishedEventId, ghostUid, 'going');

  // Viewer has blocked `blocked` (block store written directly; the block
  // callable path is covered by the blocking suite).
  await adminDb
    .collection('userBlocks')
    .doc(viewer.uid)
    .collection('blocked')
    .doc(blocked.uid)
    .set({ blockedUserId: blocked.uid, displayName: 'Bad Blocked', createdAt: new Date() });

  // A separate draft event (never published) to prove drafts are not exposed.
  const draft = await call('events-create', {
    title: 'Draft attendee event',
    startsAt: futureStart,
    approximateArea: 'Test area',
  });
  draftEventId = (draft.data as { eventId: string }).eventId;
  await setRsvp(draftEventId, going.uid, 'going');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('events-listAttendees', () => {
  it('rejects unauthenticated callers', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('events-listAttendees', { eventId: publishedEventId }))).toBe(
      'functions/unauthenticated',
    );
  });

  it('rejects a suspended caller', async () => {
    await signInAs(suspended);
    expect(await callableErrorCode(call('events-listAttendees', { eventId: publishedEventId }))).toBe(
      'functions/permission-denied',
    );
  });

  it('returns the roster of a published event, grouped by status', async () => {
    await signInAs(viewer);
    const result = await call('events-listAttendees', { eventId: publishedEventId });
    const attendees = (result.data as { attendees: Attendee[] }).attendees;
    const byId = new Map(attendees.map((a) => [a.userId, a]));

    // going / maybe / not_going all present with the right answer + identity.
    expect(byId.get(going.uid)?.status).toBe('going');
    expect(byId.get(going.uid)?.displayName).toBe('Gina Going');
    expect(byId.get(maybe.uid)?.status).toBe('maybe');
    expect(byId.get(notGoing.uid)?.status).toBe('not_going');

    // Status grouping order is stable: going before maybe before not_going.
    const statuses = attendees.map((a) => a.status);
    const firstMaybe = statuses.indexOf('maybe');
    const firstNotGoing = statuses.indexOf('not_going');
    expect(statuses.lastIndexOf('going')).toBeLessThan(firstMaybe);
    expect(firstMaybe).toBeLessThan(firstNotGoing);
  });

  it('filters out a blocked member and skips a deleted user', async () => {
    await signInAs(viewer);
    const result = await call('events-listAttendees', { eventId: publishedEventId });
    const ids = (result.data as { attendees: Attendee[] }).attendees.map((a) => a.userId);
    expect(ids).not.toContain(blocked.uid);
    expect(ids).not.toContain(ghostUid);
  });

  it('does not expose a draft event roster', async () => {
    await signInAs(viewer);
    expect(await callableErrorCode(call('events-listAttendees', { eventId: draftEventId }))).toBe(
      'functions/not-found',
    );
  });

  it('returns not-found for an unknown event', async () => {
    await signInAs(viewer);
    expect(await callableErrorCode(call('events-listAttendees', { eventId: 'no-such-event' }))).toBe(
      'functions/not-found',
    );
  });
});
