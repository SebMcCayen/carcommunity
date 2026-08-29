/**
 * chatchannels.adminDeleteMessage emulator integration test.
 *
 * End-to-end on the deployed-in-emulator callables + Firestore:
 *  - a member reports a community message (chatchannels-reportMessage) → a
 *    moderationReports doc lands (status 'pending');
 *  - a non-admin is DENIED chatchannels-adminDeleteMessage (permission-denied);
 *  - an admin hard-deletes the community message: the message is gone from the
 *    communityChat-list read path AND from the raw messages subcollection, the
 *    open report resolves to 'reviewed', and an adminAuditEvents record preserves
 *    the original text;
 *  - a re-delete is idempotent (deleted:false, no throw);
 *  - an invalid messageId is invalid-argument.
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
import { connectFirestoreEmulator, getFirestore, type Firestore } from 'firebase/firestore';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';
/** File-unique displayName suffix (the emulator Firestore is shared across files). */
const SFX = 'admdel';

const adminApp =
  getAdminApps()[0] ??
  initializeAdminApp({ projectId: PROJECT_ID }, 'chatchannels-admin-delete-emulator-tests');
const adminDb = getAdminFirestore(adminApp);
const adminAuth = getAdminAuth(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;
let firestore: Firestore;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

async function pollUntil<T>(read: () => Promise<T | undefined>, timeoutMs = 50_000): Promise<T> {
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

let userSeq = 0;

async function newMember(displayName: string): Promise<TestUser> {
  userSeq += 1;
  const email = `admdel-${userSeq}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'password-123';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  const uid = credential.user.uid;
  await pollUntil(async () => {
    const snap = await adminDb.collection('users').doc(uid).get();
    return snap.exists ? true : undefined;
  });
  await adminAuth.setCustomUserClaims(uid, { activeMember: true });
  await adminDb
    .collection('users')
    .doc(uid)
    .set(
      { activeMember: true, displayName, avatarPath: `profileImages/${uid}/a.jpg` },
      { merge: true },
    );
  return { uid, email, password };
}

async function makeAdmin(user: TestUser): Promise<void> {
  await adminAuth.setCustomUserClaims(user.uid, { admin: true });
  await adminDb.collection('users').doc(user.uid).set({ role: 'admin' }, { merge: true });
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  // Force a token refresh so server-set custom claims are present.
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

interface DeleteResponse {
  messageId: string;
  deleted: boolean;
  resolvedReports: number;
}
interface ListResponse {
  messages: { id: string }[];
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'chatchannels-admin-delete-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
  firestore = getFirestore(app);
  connectFirestoreEmulator(firestore, EMULATOR_HOST, 8080);
  // Warm the auth.onUserCreate trigger once here (its cold start on a slow /
  // thrashing local runner can exceed a single test's users-doc poll). After
  // this the trigger is hot and the per-test newMember() calls resolve quickly.
  await newMember(`Warmup-${SFX}`);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('chatchannels.adminDeleteMessage', () => {
  it('lets an admin hard-delete a reported community message, resolving its report and auditing', async () => {
    const author = await newMember(`Author-${SFX}-1`);
    const reporter = await newMember(`Reporter-${SFX}-1`);
    const admin = await newMember(`Admin-${SFX}-1`);
    await makeAdmin(admin);

    // Author posts a community message.
    await signInAs(author);
    const posted = (await call('communityChat-post', { text: 'delete me please' })).data as {
      messageId: string;
    };
    const messageId = posted.messageId;
    expect(typeof messageId).toBe('string');

    // Reporter reports it.
    await signInAs(reporter);
    await call('chatchannels-reportMessage', {
      channel: 'community',
      messageId,
      reason: 'spam',
    });

    // A pending report now exists for this message.
    const reportDocs = await pollUntil(async () => {
      const snap = await adminDb
        .collection('moderationReports')
        .where('surface', '==', 'community')
        .where('targetId', '==', messageId)
        .get();
      return snap.empty ? undefined : snap.docs;
    });
    expect(reportDocs).toHaveLength(1);
    expect(reportDocs[0]!.data().status).toBe('pending');

    // A non-admin (the reporter) is denied.
    expect(
      await callableErrorCode(call('chatchannels-adminDeleteMessage', { messageId })),
    ).toBe('functions/permission-denied');

    // The admin deletes it.
    await signInAs(admin);
    const result = (await call('chatchannels-adminDeleteMessage', { messageId, reason: 'spam' }))
      .data as DeleteResponse;
    expect(result).toMatchObject({ messageId, deleted: true, resolvedReports: 1 });

    // The message is gone from the raw messages subcollection.
    const rawSnap = await adminDb
      .collection('communityChat')
      .doc('global')
      .collection('messages')
      .doc(messageId)
      .get();
    expect(rawSnap.exists).toBe(false);

    // ...and from the member-facing read path.
    await signInAs(reporter);
    const list = (await call('communityChat-list', {})).data as ListResponse;
    expect(list.messages.some((m) => m.id === messageId)).toBe(false);

    // The report resolved to 'reviewed'.
    const resolved = await adminDb.collection('moderationReports').doc(reportDocs[0]!.id).get();
    expect(resolved.data()?.status).toBe('reviewed');

    // An audit record preserves the original text.
    const audit = await pollUntil(async () => {
      const snap = await adminDb
        .collection('adminAuditEvents')
        .where('action', '==', 'communityChat.deleteMessage')
        .where('targetId', '==', messageId)
        .get();
      return snap.empty ? undefined : snap.docs;
    });
    expect(audit).toHaveLength(1);
    const auditData = audit[0]!.data();
    expect(auditData.adminId).toBe(admin.uid);
    expect(auditData.details?.originalText).toBe('delete me please');
    expect(auditData.details?.authorUserId).toBe(author.uid);
    expect(auditData.details?.resolvedReports).toBe(1);

    // A re-delete is idempotent — no throw, nothing removed.
    await signInAs(admin);
    const again = (await call('chatchannels-adminDeleteMessage', { messageId })).data as DeleteResponse;
    expect(again).toMatchObject({ messageId, deleted: false, resolvedReports: 0 });
  });

  it('rejects an invalid messageId with invalid-argument', async () => {
    const admin = await newMember(`Admin-${SFX}-2`);
    await makeAdmin(admin);
    await signInAs(admin);
    expect(
      await callableErrorCode(call('chatchannels-adminDeleteMessage', { messageId: 'bad/id' })),
    ).toBe('functions/invalid-argument');
  });

  it('rejects an unauthenticated caller', async () => {
    await auth.signOut();
    expect(
      await callableErrorCode(call('chatchannels-adminDeleteMessage', { messageId: 'x' })),
    ).toBe('functions/unauthenticated');
  });
});
