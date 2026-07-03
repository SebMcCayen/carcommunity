/**
 * Event chat emulator integration tests (Phase 9c).
 *
 * Exercises the deployed-in-emulator callables end-to-end:
 * - `events-postChatMessage` (member posting + rate limit)
 * - `events-reportChatMessage` (dedupe, own-message rejection)
 * - `events-removeChatMessage` (admin soft-removal + report resolution)
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'chat-emulator-tests');
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
let memberGoing: TestUser;
let memberNoRsvp: TestUser;
let freeUser: TestUser;
let eventId: string;

const futureStart = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString();

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'chat-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('chat-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });

  memberGoing = await createProvisionedUser('chat-member-going');
  memberNoRsvp = await createProvisionedUser('chat-member-norsvp');
  freeUser = await createProvisionedUser('chat-free');
  for (const member of [memberGoing, memberNoRsvp]) {
    await adminDb.collection('users').doc(member.uid).set({ activeMember: true }, { merge: true });
  }

  // Published event with a going RSVP for memberGoing.
  await signInAs(adminUser);
  const created = await call('events-create', {
    title: 'Chat test event',
    startsAt: futureStart,
    approximateArea: 'Test area',
  });
  eventId = (created.data as { eventId: string }).eventId;
  await call('events-publish', { eventId });
  await adminDb
    .collection('events')
    .doc(eventId)
    .collection('rsvps')
    .doc(memberGoing.uid)
    .set({ status: 'going', updatedAt: new Date() });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('events-postChatMessage', () => {
  it('rejects unauthenticated, non-member, and RSVP-less callers', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('events-postChatMessage', { eventId, message: 'x' }))).toBe(
      'functions/unauthenticated',
    );

    await signInAs(freeUser);
    expect(await callableErrorCode(call('events-postChatMessage', { eventId, message: 'x' }))).toBe(
      'functions/permission-denied',
    );

    await signInAs(memberNoRsvp);
    expect(await callableErrorCode(call('events-postChatMessage', { eventId, message: 'x' }))).toBe(
      'functions/permission-denied',
    );
  });

  it('posts a message with denormalized author display name', async () => {
    await signInAs(memberGoing);
    const result = await call('events-postChatMessage', {
      eventId,
      message: '  Hej från testet!  ',
    });
    const { messageId } = result.data as { messageId: string };

    const snap = await adminDb
      .collection('events')
      .doc(eventId)
      .collection('messages')
      .doc(messageId)
      .get();
    const message = snap.data()!;
    expect(message.message).toBe('Hej från testet!');
    expect(message.authorUserId).toBe(memberGoing.uid);
    expect(typeof message.authorDisplayName).toBe('string');
    expect(message.moderationState).toBe('visible');
  });

  it('rejects whitespace-only messages', async () => {
    await signInAs(memberGoing);
    expect(
      await callableErrorCode(call('events-postChatMessage', { eventId, message: '   ' })),
    ).toBe('functions/invalid-argument');
  });

  it('enforces the ~5 messages per 30 seconds rate limit', async () => {
    await signInAs(memberGoing);
    // One message already posted above; four more reach the limit.
    for (let i = 0; i < 4; i += 1) {
      await call('events-postChatMessage', { eventId, message: `msg ${i}` });
    }
    expect(
      await callableErrorCode(call('events-postChatMessage', { eventId, message: 'once more' })),
    ).toBe('functions/resource-exhausted');
  });
});

describe('events-reportChatMessage / events-removeChatMessage', () => {
  let reportedMessageId: string;

  beforeAll(async () => {
    // Author: admin has no RSVP, so post as memberGoing after the rate-limit
    // window from the previous suite would interfere — post via Admin SDK to
    // stay deterministic (rules do not apply to the Admin SDK).
    const ref = adminDb.collection('events').doc(eventId).collection('messages').doc();
    await ref.set({
      authorUserId: 'other-author',
      authorDisplayName: 'Other Author',
      message: 'reportable message',
      moderationState: 'visible',
      removedAt: null,
      removedByUserId: null,
      createdAt: new Date(),
    });
    reportedMessageId = ref.id;
  });

  it('deduplicates reports per (message, reporter, reason) and rejects own messages', async () => {
    await signInAs(memberGoing);
    await call('events-reportChatMessage', {
      eventId,
      messageId: reportedMessageId,
      reason: 'spam',
      details: 'first',
    });
    await call('events-reportChatMessage', {
      eventId,
      messageId: reportedMessageId,
      reason: 'spam',
      details: 'second (should overwrite silently)',
    });

    const reports = await adminDb
      .collection('events')
      .doc(eventId)
      .collection('messageReports')
      .where('messageId', '==', reportedMessageId)
      .get();
    expect(reports.size).toBe(1);
    expect(reports.docs[0].data().details).toBe('second (should overwrite silently)');
    expect(reports.docs[0].data().status).toBe('new');

    // Own message: find the member's own message from the previous suite.
    const own = await adminDb
      .collection('events')
      .doc(eventId)
      .collection('messages')
      .where('authorUserId', '==', memberGoing.uid)
      .limit(1)
      .get();
    expect(
      await callableErrorCode(
        call('events-reportChatMessage', {
          eventId,
          messageId: own.docs[0].id,
          reason: 'other',
        }),
      ),
    ).toBe('functions/invalid-argument');

    expect(
      await callableErrorCode(
        call('events-reportChatMessage', { eventId, messageId: 'missing-msg', reason: 'spam' }),
      ),
    ).toBe('functions/not-found');
  });

  it('admin soft-removal blanks the body, resolves reports, and preserves the original in the audit record', async () => {
    await signInAs(memberGoing);
    expect(
      await callableErrorCode(
        call('events-removeChatMessage', {
          eventId,
          messageId: reportedMessageId,
          reason: 'Spam wave',
        }),
      ),
    ).toBe('functions/permission-denied');

    await signInAs(adminUser);
    await call('events-removeChatMessage', {
      eventId,
      messageId: reportedMessageId,
      reason: 'Spam wave',
    });

    const message = (
      await adminDb
        .collection('events')
        .doc(eventId)
        .collection('messages')
        .doc(reportedMessageId)
        .get()
    ).data()!;
    expect(message.moderationState).toBe('removed');
    expect(message.message).toBe('');
    expect(message.removedByUserId).toBe(adminUser.uid);
    expect(message.removedAt).not.toBeNull();

    const reports = await adminDb
      .collection('events')
      .doc(eventId)
      .collection('messageReports')
      .where('messageId', '==', reportedMessageId)
      .get();
    expect(reports.docs[0].data().status).toBe('resolved');
    expect(reports.docs[0].data().reviewedByUserId).toBe(adminUser.uid);

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'eventChat.removeMessage')
      .where('targetId', '==', reportedMessageId)
      .get();
    expect(audit.size).toBe(1);
    expect(audit.docs[0].data().details.originalMessage).toBe('reportable message');
    expect(audit.docs[0].data().details.resolvedReports).toBe(1);

    // Re-removal is a failed precondition.
    expect(
      await callableErrorCode(
        call('events-removeChatMessage', {
          eventId,
          messageId: reportedMessageId,
          reason: 'Again',
        }),
      ),
    ).toBe('functions/failed-precondition');
  });

  it('re-reporting after resolution refreshes details but never reopens the report', async () => {
    await signInAs(memberGoing);
    await call('events-reportChatMessage', {
      eventId,
      messageId: reportedMessageId,
      reason: 'spam',
      details: 'third — filed after moderation resolved it',
    });

    const reports = await adminDb
      .collection('events')
      .doc(eventId)
      .collection('messageReports')
      .where('messageId', '==', reportedMessageId)
      .get();
    expect(reports.size).toBe(1);
    const report = reports.docs[0].data();
    expect(report.details).toBe('third — filed after moderation resolved it');
    // Review metadata survives the repeat report.
    expect(report.status).toBe('resolved');
    expect(report.reviewedByUserId).toBe(adminUser.uid);
    expect(report.reviewedAt).not.toBeNull();
  });
});
