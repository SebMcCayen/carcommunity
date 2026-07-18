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
import { MEMBER_EVENT_RATE_LIMIT_MAX } from '../events/events-core';

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

/**
 * A provisioned user carrying the backend-managed `activeMember` entitlement.
 * Created fresh per call so one test's member-creation rate-limit budget can
 * never leak into another's.
 */
async function createMemberUser(prefix: string, suspended = false): Promise<TestUser> {
  const user = await createProvisionedUser(prefix);
  await adminDb
    .collection('users')
    .doc(user.uid)
    .set({ activeMember: true, suspended }, { merge: true });
  return user;
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

  it('rejects a signed-in caller who is neither an active member nor an admin', async () => {
    await signInAs(regularUser);
    // events-create admits members as well as admins, but regularUser has no
    // activeMember entitlement — so it is still permission-denied for them.
    expect(await callableErrorCode(call('events-create', validCreate))).toBe(
      'functions/permission-denied',
    );
    expect(
      await callableErrorCode(call('events-publish', { eventId: 'irrelevant' })),
    ).toBe('functions/permission-denied');
  });

  it('keeps the rest of the events lifecycle admin-only for members', async () => {
    const member = await createMemberUser('events-member-lifecycle');
    const eventId = await createDraftEvent();
    await signInAs(member);

    // A member may create, but must not drive anyone's event lifecycle.
    expect(await callableErrorCode(call('events-publish', { eventId }))).toBe(
      'functions/permission-denied',
    );
    expect(
      await callableErrorCode(call('events-cancel', { eventId, reason: 'Nope.' })),
    ).toBe('functions/permission-denied');
    expect(await callableErrorCode(call('events-update', { eventId, title: 'Hijacked' }))).toBe(
      'functions/permission-denied',
    );
  });
});

describe('events-create – member-created events', () => {
  it('lets an active member create a published, attributed event', async () => {
    const member = await createMemberUser('events-member-create');
    await signInAs(member);

    // isOfficial: true is passed deliberately — the club-sanctioned badge must
    // be forced off for a member-created event.
    const result = await call('events-create', { ...validCreate, isOfficial: true });
    const { eventId, status } = result.data as { eventId: string; status: string };
    expect(status).toBe('published');

    const event = (await adminDb.collection('events').doc(eventId).get()).data()!;
    expect(event.status).toBe('published');
    expect(event.createdByUserId).toBe(member.uid);
    expect(event.createdByRole).toBe('member');
    expect(event.isOfficial).toBe(false);
    // Exact location still lands only on the member-gated document.
    expect(event.address).toBeUndefined();
    const detail = (
      await adminDb.collection('events').doc(eventId).collection('details').doc('private').get()
    ).data()!;
    expect(detail.address).toBe(validCreate.address);

    // The adminAuditEvents log stays a record of ADMIN actions only — a member
    // creation must never appear there with the member's uid as adminId.
    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('targetId', '==', eventId)
      .get();
    expect(audit.empty).toBe(true);
  });

  it('rejects a suspended member', async () => {
    const suspended = await createMemberUser('events-member-suspended', true);
    await signInAs(suspended);
    expect(await callableErrorCode(call('events-create', validCreate))).toBe(
      'functions/permission-denied',
    );
  });

  it('rejects a member event that would publish with a start time in the past', async () => {
    const member = await createMemberUser('events-member-past');
    await signInAs(member);
    // Member events publish on creation, so they must clear the same
    // preconditions events-publish enforces — no back door to a published
    // event in the past.
    const pastStart = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString();
    expect(
      await callableErrorCode(
        call('events-create', { ...validCreate, startsAt: pastStart }),
      ),
    ).toBe('functions/failed-precondition');
  });

  it('caps member creations per rolling window', async () => {
    const member = await createMemberUser('events-member-ratelimit');
    await signInAs(member);

    for (let i = 0; i < MEMBER_EVENT_RATE_LIMIT_MAX; i += 1) {
      await call('events-create', { ...validCreate, title: `Member cruise ${i}` });
    }
    expect(
      await callableErrorCode(call('events-create', { ...validCreate, title: 'One too many' })),
    ).toBe('functions/resource-exhausted');

    // The cap is per member: a different member is unaffected.
    const other = await createMemberUser('events-member-ratelimit-other');
    await signInAs(other);
    await expect(call('events-create', validCreate)).resolves.toBeDefined();
  });

  it('counts only member-created events toward the cap, not admin-created ones by the same uid', async () => {
    const member = await createMemberUser('events-member-ratelimit-role');

    // Seed the window with events this SAME uid created as an admin (the
    // shape events-create writes on the admin path). The cap is on
    // MEMBER-created events, so these must not count against them — a count
    // filtered on createdByUserId alone would wrongly exhaust the budget here.
    for (let i = 0; i < MEMBER_EVENT_RATE_LIMIT_MAX + 1; i += 1) {
      await adminDb.collection('events').add({
        title: `Admin-era cruise ${i}`,
        status: 'draft',
        createdByUserId: member.uid,
        createdByRole: 'admin',
        createdAt: new Date(),
      });
    }

    await signInAs(member);
    // Full member budget still available despite the seeded admin events.
    for (let i = 0; i < MEMBER_EVENT_RATE_LIMIT_MAX; i += 1) {
      await expect(
        call('events-create', { ...validCreate, title: `Member cruise ${i}` }),
      ).resolves.toBeDefined();
    }
    // ...and member-created events DO still count: the cap bites right after.
    expect(
      await callableErrorCode(call('events-create', { ...validCreate, title: 'One too many' })),
    ).toBe('functions/resource-exhausted');
  });

  it('does not rate-limit admins', async () => {
    for (let i = 0; i <= MEMBER_EVENT_RATE_LIMIT_MAX; i += 1) {
      await expect(createDraftEvent({ title: `Admin cruise ${i}` })).resolves.toBeDefined();
    }
  });

  it('lets an admin moderate (update and cancel) a member-created event', async () => {
    const member = await createMemberUser('events-member-moderated');
    await signInAs(member);
    const { eventId } = (await call('events-create', validCreate)).data as { eventId: string };

    // Post-moderation: the member event is live, and the existing audited admin
    // path takes it down again.
    await signInAs(adminUser);
    await call('events-update', { eventId, title: 'Renamed by admin' });
    await call('events-cancel', { eventId, reason: 'Duplicate meetup.' });

    const event = (await adminDb.collection('events').doc(eventId).get()).data()!;
    expect(event.status).toBe('cancelled');
    expect(event.title).toBe('Renamed by admin');
    expect(event.cancelledAt).not.toBeNull();
    // Attribution survives moderation.
    expect(event.createdByUserId).toBe(member.uid);
    expect(event.createdByRole).toBe('member');

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'event.cancel')
      .where('targetId', '==', eventId)
      .get();
    expect(audit.size).toBe(1);
    expect(audit.docs[0].data().adminId).toBe(adminUser.uid);
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

describe('events-complete – creator or admin', () => {
  it('lets the member who created an event end it, with no admin audit record', async () => {
    const member = await createMemberUser('events-creator-complete');
    await signInAs(member);
    // A member-created event is published immediately (post-moderation).
    const eventId = (
      (await call('events-create', validCreate)).data as { eventId: string }
    ).eventId;

    const result = await call('events-complete', { eventId });
    expect((result.data as { status: string }).status).toBe('completed');
    expect((await adminDb.collection('events').doc(eventId).get()).data()!.status).toBe(
      'completed',
    );

    // adminAuditEvents stays a log of ADMIN actions: a member ending their own
    // event must not write their uid into an adminId field.
    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'event.complete')
      .where('targetId', '==', eventId)
      .get();
    expect(audit.empty).toBe(true);
  });

  it('refuses a member who did not create the event', async () => {
    const creator = await createMemberUser('events-complete-creator');
    await signInAs(creator);
    const eventId = (
      (await call('events-create', validCreate)).data as { eventId: string }
    ).eventId;

    const stranger = await createMemberUser('events-complete-stranger');
    await signInAs(stranger);
    expect(await callableErrorCode(call('events-complete', { eventId }))).toBe(
      'functions/permission-denied',
    );
    // Still live for its actual organiser.
    expect((await adminDb.collection('events').doc(eventId).get()).data()!.status).toBe(
      'published',
    );
  });

  it('refuses a signed-in caller who is neither member nor admin', async () => {
    const creator = await createMemberUser('events-complete-nonmember-target');
    await signInAs(creator);
    const eventId = (
      (await call('events-create', validCreate)).data as { eventId: string }
    ).eventId;

    await signInAs(regularUser);
    expect(await callableErrorCode(call('events-complete', { eventId }))).toBe(
      'functions/permission-denied',
    );
  });

  it('lets an admin end a member’s event and audits that one', async () => {
    const member = await createMemberUser('events-admin-completes');
    await signInAs(member);
    const eventId = (
      (await call('events-create', validCreate)).data as { eventId: string }
    ).eventId;

    await signInAs(adminUser);
    await call('events-complete', { eventId });
    const completed = (await adminDb.collection('events').doc(eventId).get()).data()!;
    expect(completed.status).toBe('completed');
    // autoClosedAt distinguishes an auto-close from a hand-completed event, so
    // the callable must NOT stamp it (events/scheduled.ts documents this as the
    // sweep's only trace, standing in for the audit record it does not write).
    expect(completed.autoClosedAt).toBeUndefined();

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'event.complete')
      .where('targetId', '==', eventId)
      .get();
    expect(audit.size).toBe(1);
    expect(audit.docs[0].data().adminId).toBe(adminUser.uid);
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
