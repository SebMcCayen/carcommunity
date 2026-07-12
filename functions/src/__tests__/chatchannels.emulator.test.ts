/**
 * Chat-channels (community + convoy) emulator integration tests.
 *
 * Exercises the deployed-in-emulator callables end-to-end plus the
 * communityChat / convoyChats Firestore rules:
 * - `communityChat-post` (member gating, denormalized sender profile)
 * - `communityChat-list` (newest-first pagination, per-user lastReadAt)
 * - `communityChat-markRead` (userPrivate last-read marker)
 * - `convoyChat-post` (accepted-member-only; non-member not-found, still-invited
 *   failed-precondition)
 * - `convoyChat-list` (accepted-member-only pagination)
 * - rules: any active member reads community messages; only accepted convoy
 *   members read convoy messages; outsiders denied; no client writes.
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
  addDoc,
  collection,
  connectFirestoreEmulator,
  getDocs,
  getFirestore,
  query,
  type Firestore,
} from 'firebase/firestore';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'chatchannels-emulator-tests');
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

let userSeq = 0;

async function newMember(displayName: string): Promise<TestUser> {
  userSeq += 1;
  const email = `chat-${userSeq}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
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

async function newFreeUser(): Promise<TestUser> {
  userSeq += 1;
  const email = `chat-free-${userSeq}-${Date.now()}@example.com`;
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

/** Seeds a two-sided established friendship directly (bypasses friend.* flow). */
async function makeFriends(a: TestUser, b: TestUser): Promise<void> {
  await adminDb
    .collection('users')
    .doc(a.uid)
    .collection('friends')
    .doc(b.uid)
    .set({ friendUid: b.uid, displayName: 'X', avatarPath: null, createdAt: new Date() });
  await adminDb
    .collection('users')
    .doc(b.uid)
    .collection('friends')
    .doc(a.uid)
    .set({ friendUid: a.uid, displayName: 'Y', avatarPath: null, createdAt: new Date() });
}

interface ChatMessage {
  id: string;
  senderUid: string;
  text: string;
  senderDisplayName: string | null;
  senderAvatarPath: string | null;
  createdAt: string;
}
interface ConvoySummary {
  convoyId: string;
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'chatchannels-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
  firestore = getFirestore(app);
  connectFirestoreEmulator(firestore, EMULATOR_HOST, 8080);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('communityChat callables + rules', () => {
  it('rejects unauthenticated + non-member callers', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('communityChat-list', {}))).toBe(
      'functions/unauthenticated',
    );
    const free = await newFreeUser();
    await signInAs(free);
    expect(await callableErrorCode(call('communityChat-post', { text: 'hi' }))).toBe(
      'functions/permission-denied',
    );
  });

  it('posts, lists newest-first with denormalized sender, and tracks lastReadAt', async () => {
    const alice = await newMember('AliceComm');
    const bob = await newMember('BobComm');

    await signInAs(alice);
    const marker = `comm-${Date.now()}-${Math.random()}`;
    const posted = (await call('communityChat-post', { text: `${marker} one` })).data as {
      messageId: string;
    };
    expect(posted.messageId).toBeTruthy();
    await call('communityChat-post', { text: `${marker} two` });

    // Bob (a different active member) reads the shared channel and sees Alice's
    // denormalized profile on the message.
    await signInAs(bob);
    const listed = (await call('communityChat-list', {})).data as {
      messages: ChatMessage[];
      lastReadAt: string | null;
      hasMore: boolean;
    };
    const mine = listed.messages.filter((m) => m.text.startsWith(marker));
    expect(mine.length).toBe(2);
    // Newest-first ordering.
    expect(mine[0]!.text).toBe(`${marker} two`);
    expect(mine[0]!.senderUid).toBe(alice.uid);
    expect(mine[0]!.senderDisplayName).toBe('AliceComm');
    expect(mine[0]!.senderAvatarPath).toBe(`profileImages/${alice.uid}/a.jpg`);
    // Bob has never marked read.
    expect(listed.lastReadAt).toBeNull();

    // markRead stamps the per-user marker; a subsequent list returns it.
    const read = (await call('communityChat-markRead', {})).data as { lastReadAt: string };
    expect(read.lastReadAt).toBeTruthy();
    const relisted = (await call('communityChat-list', {})).data as { lastReadAt: string | null };
    expect(relisted.lastReadAt).toBe(read.lastReadAt);
  });

  it('rules: active member reads messages; free user + client writes denied', async () => {
    const member = await newMember('RuleMemberComm');
    await signInAs(member);
    await call('communityChat-post', { text: 'rule-check' });

    // Active member reads the messages subcollection directly.
    const snap = await getDocs(query(collection(firestore, 'communityChat', 'global', 'messages')));
    expect(snap.empty).toBe(false);

    // No client writes (not even an active member).
    await expect(
      addDoc(collection(firestore, 'communityChat', 'global', 'messages'), {
        senderUid: member.uid,
        text: 'forged',
      }),
    ).rejects.toMatchObject({ code: 'permission-denied' });

    // A non-member (free user) cannot read the channel messages.
    const free = await newFreeUser();
    await signInAs(free);
    await expect(
      getDocs(query(collection(firestore, 'communityChat', 'global', 'messages'))),
    ).rejects.toMatchObject({ code: 'permission-denied' });
  });
});

describe('convoyChat callables + rules', () => {
  it('only accepted members post/read; invited + outsiders rejected', async () => {
    const owner = await newMember('OwnerChat');
    const accepted = await newMember('AcceptedChat');
    const invited = await newMember('InvitedChat');
    const outsider = await newMember('OutsiderChat');
    await makeFriends(owner, accepted);
    await makeFriends(owner, invited);

    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [accepted.uid, invited.uid] }))
      .data as { convoy: ConvoySummary };
    const convoyId = created.convoy.convoyId;

    // The accepted member accepts; the invited member stays pending.
    await signInAs(accepted);
    await call('convoy-respond', { convoyId, action: 'accept' });

    // Owner (seeded accepted) can post.
    await signInAs(owner);
    const posted = (await call('convoyChat-post', { convoyId, text: 'owner hello' })).data as {
      messageId: string;
    };
    expect(posted.messageId).toBeTruthy();

    // Accepted member can post + list.
    await signInAs(accepted);
    await call('convoyChat-post', { convoyId, text: 'accepted hi' });
    const listed = (await call('convoyChat-list', { convoyId })).data as {
      convoyId: string;
      messages: ChatMessage[];
    };
    expect(listed.convoyId).toBe(convoyId);
    expect(listed.messages.map((m) => m.text).sort()).toEqual(['accepted hi', 'owner hello']);
    expect(listed.messages.find((m) => m.text === 'owner hello')!.senderDisplayName).toBe(
      'OwnerChat',
    );

    // A still-invited member is a convoy member but not accepted → failed-precondition.
    await signInAs(invited);
    expect(await callableErrorCode(call('convoyChat-post', { convoyId, text: 'nope' }))).toBe(
      'functions/failed-precondition',
    );
    expect(await callableErrorCode(call('convoyChat-list', { convoyId }))).toBe(
      'functions/failed-precondition',
    );

    // A total outsider gets not-found (can't probe the convoy's existence).
    await signInAs(outsider);
    expect(await callableErrorCode(call('convoyChat-post', { convoyId, text: 'nope' }))).toBe(
      'functions/not-found',
    );
    expect(
      await callableErrorCode(call('convoyChat-list', { convoyId: 'does-not-exist' })),
    ).toBe('functions/not-found');
  });

  it('rules: accepted members read convoy messages; invited/outsiders denied; no client writes', async () => {
    const owner = await newMember('RuleOwnerChat');
    const accepted = await newMember('RuleAcceptedChat');
    const invited = await newMember('RuleInvitedChat');
    const outsider = await newMember('RuleOutsiderChat');
    await makeFriends(owner, accepted);
    await makeFriends(owner, invited);

    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [accepted.uid, invited.uid] }))
      .data as { convoy: ConvoySummary };
    const convoyId = created.convoy.convoyId;

    await signInAs(accepted);
    await call('convoy-respond', { convoyId, action: 'accept' });
    await call('convoyChat-post', { convoyId, text: 'hi convoy' });

    const messagesPath = () => collection(firestore, 'convoyChats', convoyId, 'messages');

    // Owner (accepted) + accepted member read the channel.
    await signInAs(owner);
    expect((await getDocs(query(messagesPath()))).empty).toBe(false);
    await signInAs(accepted);
    expect((await getDocs(query(messagesPath()))).empty).toBe(false);

    // Still-invited member cannot read (not accepted).
    await signInAs(invited);
    await expect(getDocs(query(messagesPath()))).rejects.toMatchObject({
      code: 'permission-denied',
    });

    // Outsider cannot read.
    await signInAs(outsider);
    await expect(getDocs(query(messagesPath()))).rejects.toMatchObject({
      code: 'permission-denied',
    });

    // No client writes, not even an accepted member.
    await signInAs(accepted);
    await expect(
      addDoc(messagesPath(), { senderUid: accepted.uid, text: 'forged' }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
  });
});
