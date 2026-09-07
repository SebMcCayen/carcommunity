/**
 * events.listAttendees emulator integration tests.
 *
 * Exercises the deployed-in-emulator `events-listAttendees` callable end-to-end:
 * - a signed-in member gets the roster of a PUBLISHED event with each answer
 * - unauthenticated + suspended callers are rejected
 * - a DRAFT event's roster is not exposed (not-found)
 * - the caller is included in their own roster (they see the answer they gave)
 * - a member the caller blocked, AND a member who blocked the caller, are both
 *   filtered out (block honoured in either direction)
 * - a deleted / missing users/{uid} is skipped rather than shown nameless
 * - SUBSCRIPTION GATE (Slice D): a free Community caller gets an empty roster +
 *   requiresPaid; a paid tier (Plus/Supporter) or an admin gets the real roster
 *   with requiresPaid:false
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

/**
 * Grants a PAID subscription (Plus/Supporter) to a user, so the subscription
 * gate on events-listAttendees (Slice D) admits them to the roster. Mirrors the
 * shape effectiveSubscriptionTierFromStoredRecord resolves: an active
 * member_monthly record with an explicit paid tier and a matching userId.
 */
async function grantPaidSubscription(uid: string, tier: 'plus' | 'supporter'): Promise<void> {
  await adminDb.collection('subscriptions').doc(uid).set({
    userId: uid,
    platform: 'manual',
    status: 'active',
    entitlement: 'member_monthly',
    tier,
    purchaseTokenHash: null,
    startsAt: null,
    expiresAt: null,
    updatedAt: new Date(),
  });
}

let adminUser: TestUser;
let viewer: TestUser;
let going: TestUser;
let maybe: TestUser;
let notGoing: TestUser;
let blocked: TestUser;
let blockedByReverse: TestUser;
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
  await grantPaidSubscription(viewer.uid, 'plus');
  going = await createProvisionedUser('att-going', 'Gina Going');
  maybe = await createProvisionedUser('att-maybe', 'Max Maybe');
  notGoing = await createProvisionedUser('att-not', 'Nils NotGoing');
  blocked = await createProvisionedUser('att-blocked', 'Bad Blocked');
  blockedByReverse = await createProvisionedUser('att-revblock', 'Rev Blocker');
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

  await setRsvp(publishedEventId, viewer.uid, 'maybe'); // the caller's own answer
  await setRsvp(publishedEventId, going.uid, 'going');
  await setRsvp(publishedEventId, maybe.uid, 'maybe');
  await setRsvp(publishedEventId, notGoing.uid, 'not_going');
  await setRsvp(publishedEventId, blocked.uid, 'going');
  await setRsvp(publishedEventId, blockedByReverse.uid, 'going');
  await setRsvp(publishedEventId, ghostUid, 'going');

  // Viewer has blocked `blocked` (caller→candidate direction).
  await adminDb
    .collection('userBlocks')
    .doc(viewer.uid)
    .collection('blocked')
    .doc(blocked.uid)
    .set({ blockedUserId: blocked.uid, displayName: 'Bad Blocked', createdAt: new Date() });

  // `blockedByReverse` has blocked the viewer (candidate→caller direction) —
  // the block must still be honoured, filtering them from the viewer's roster.
  await adminDb
    .collection('userBlocks')
    .doc(blockedByReverse.uid)
    .collection('blocked')
    .doc(viewer.uid)
    .set({ blockedUserId: viewer.uid, displayName: 'Viewer', createdAt: new Date() });

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
    expect(
      await callableErrorCode(call('events-listAttendees', { eventId: publishedEventId })),
    ).toBe('functions/unauthenticated');
  });

  it('rejects a suspended caller', async () => {
    await signInAs(suspended);
    expect(
      await callableErrorCode(call('events-listAttendees', { eventId: publishedEventId })),
    ).toBe('functions/permission-denied');
  });

  it('returns the roster of a published event, grouped by status', async () => {
    await signInAs(viewer);
    const result = await call('events-listAttendees', { eventId: publishedEventId });
    const payload = result.data as { attendees: Attendee[]; requiresPaid: boolean };
    // Flag OFF (default): the roster is served to everyone — viewer holds no
    // subscription (Community) yet still gets the full list, requiresPaid:false.
    expect(payload.requiresPaid).toBe(false);
    const attendees = payload.attendees;
    const byId = new Map(attendees.map((a) => [a.userId, a]));

    // going / maybe / not_going all present with the right answer + identity.
    expect(byId.get(going.uid)?.status).toBe('going');
    expect(byId.get(going.uid)?.displayName).toBe('Gina Going');
    expect(byId.get(maybe.uid)?.status).toBe('maybe');
    expect(byId.get(notGoing.uid)?.status).toBe('not_going');

    // The caller sees themselves in their own roster, with the answer they gave.
    expect(byId.get(viewer.uid)?.status).toBe('maybe');
    expect(byId.get(viewer.uid)?.displayName).toBe('Viewer');

    // Status grouping order is stable: going before maybe before not_going.
    const statuses = attendees.map((a) => a.status);
    const firstMaybe = statuses.indexOf('maybe');
    const firstNotGoing = statuses.indexOf('not_going');
    expect(statuses.lastIndexOf('going')).toBeLessThan(firstMaybe);
    expect(firstMaybe).toBeLessThan(firstNotGoing);
  });

  it('filters blocked members in either direction and skips a deleted user', async () => {
    await signInAs(viewer);
    const result = await call('events-listAttendees', { eventId: publishedEventId });
    const ids = (result.data as { attendees: Attendee[] }).attendees.map((a) => a.userId);
    expect(ids).not.toContain(blocked.uid); // viewer blocked them
    expect(ids).not.toContain(blockedByReverse.uid); // they blocked the viewer
    expect(ids).not.toContain(ghostUid); // deleted / missing user
  });

  it('does not expose a draft event roster', async () => {
    await signInAs(viewer);
    expect(await callableErrorCode(call('events-listAttendees', { eventId: draftEventId }))).toBe(
      'functions/not-found',
    );
  });

  it('returns not-found for an unknown event', async () => {
    await signInAs(viewer);
    expect(
      await callableErrorCode(call('events-listAttendees', { eventId: 'no-such-event' })),
    ).toBe('functions/not-found');
  });

  it('withholds a free caller roster even while the legacy flag is OFF', async () => {
    // Legacy flag OFF must not grant roster access without a paid subscription.
    await signInAs(going);
    const result = await call('events-listAttendees', { eventId: publishedEventId });
    const payload = result.data as { attendees: Attendee[]; requiresPaid: boolean };
    expect(payload.requiresPaid).toBe(true);
    expect(payload.attendees).toEqual([]);
  });
});

// The subscription gate is DARK by default; these cases turn the
// `eventDetailsRequirePaid` flag ON (as an operator would at Play billing
// go-live) and assert the paid-vs-free behaviour, then restore the flag OFF so
// the shared-emulator Firestore is left as the other suites expect.
describe('events-listAttendees — subscription gate ON (eventDetailsRequirePaid)', () => {
  beforeAll(async () => {
    await adminDb
      .collection('config')
      .doc('featureFlags')
      .set({ eventDetailsRequirePaid: true }, { merge: true });
  });

  afterAll(async () => {
    await adminDb
      .collection('config')
      .doc('featureFlags')
      .set({ eventDetailsRequirePaid: false }, { merge: true });
  });

  it('denies a free (Community) caller the roster, returning requiresPaid', async () => {
    // `going` holds no subscription → Community. The gate withholds the names
    // server-side (empty roster) and flags requiresPaid so the client can show
    // an upgrade prompt rather than a fabricated "nobody answered".
    await signInAs(going);
    const result = await call('events-listAttendees', { eventId: publishedEventId });
    const payload = result.data as { attendees: Attendee[]; requiresPaid: boolean };
    expect(payload.requiresPaid).toBe(true);
    expect(payload.attendees).toEqual([]);
  });

  it('grants a Plus caller the roster', async () => {
    await grantPaidSubscription(viewer.uid, 'plus');
    await signInAs(viewer);
    const result = await call('events-listAttendees', { eventId: publishedEventId });
    const payload = result.data as { attendees: Attendee[]; requiresPaid: boolean };
    expect(payload.requiresPaid).toBe(false);
    expect(payload.attendees.length).toBeGreaterThan(0);
  });

  it('grants a Supporter caller the roster', async () => {
    // A second paid tier: promote `going` to Supporter and the same event now
    // returns the real list.
    await grantPaidSubscription(going.uid, 'supporter');
    await signInAs(going);
    const result = await call('events-listAttendees', { eventId: publishedEventId });
    const payload = result.data as { attendees: Attendee[]; requiresPaid: boolean };
    expect(payload.requiresPaid).toBe(false);
    expect(payload.attendees.length).toBeGreaterThan(0);
  });

  it('grants an admin the roster even without a subscription', async () => {
    // Admins moderate through the app and never hold a subscription, so the
    // gate admits them regardless of tier.
    await signInAs(adminUser);
    const result = await call('events-listAttendees', { eventId: publishedEventId });
    const payload = result.data as { attendees: Attendee[]; requiresPaid: boolean };
    expect(payload.requiresPaid).toBe(false);
    expect(payload.attendees.length).toBeGreaterThan(0);
  });
});
