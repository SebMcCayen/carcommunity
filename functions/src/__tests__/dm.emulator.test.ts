/**
 * Direct-messaging (1:1 friend DM) emulator integration tests.
 *
 * Exercises the deployed-in-emulator callables end-to-end plus the
 * conversations + messages Firestore rules:
 * - `dm-sendMessage` (member gating, self, not-friends, blocked-either-way,
 *   conversation create + reuse, recipient unread bump, dmUnreadTotal aggregate)
 * - `dm-listConversations` (denormalized other-user + lastMessage, per-caller
 *   unread, totalUnread)
 * - `dm-getMessages` (member gate, newest-first pagination via `before`)
 * - `dm-markRead` (clears unread + decrements the aggregate by the exact delta)
 * - rules: member-only read of conversations + messages, no client writes.
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
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'dm-emulator-tests');
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
  const email = `dm-${userSeq}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
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
    .set({ activeMember: true, displayName, avatarPath: `profileImages/${uid}/a.jpg` }, { merge: true });
  return { uid, email, password };
}

async function newFreeUser(): Promise<TestUser> {
  userSeq += 1;
  const email = `dm-free-${userSeq}-${Date.now()}@example.com`;
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

async function dmUnreadTotal(uid: string): Promise<number> {
  const snap = await adminDb.collection('userPrivate').doc(uid).get();
  const value = snap.data()?.dmUnreadTotal;
  return typeof value === 'number' ? value : 0;
}

function pairId(a: string, b: string): string {
  return [a, b].sort().join('__');
}

/** In-app inbox items for a uid in one category (backend-only collection). */
async function inboxFor(uid: string, category: string): Promise<Array<Record<string, unknown>>> {
  const snap = await adminDb.collection('notifications').doc(uid).collection('items').get();
  return snap.docs.map((d) => d.data()).filter((item) => item.category === category);
}

/** Opts `uid` out of one notification category (the owner-writable prefs map). */
async function optOutOf(uid: string, category: string): Promise<void> {
  await adminDb
    .collection('userPrivate')
    .doc(uid)
    .set({ notificationPreferences: { [category]: { inApp: false } } }, { merge: true });
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'dm-emulator-client',
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

describe('dm-sendMessage gating', () => {
  it('rejects unauthenticated callers', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('dm-listConversations', {}))).toBe(
      'functions/unauthenticated',
    );
  });

  it('admits a non-member while member gating is disabled', async () => {
    // Was: permission-denied on the member gate. Now it reaches the real
    // check — you may only DM a friend (failed-precondition).
    const free = await newFreeUser();
    await signInAs(free);
    expect(await callableErrorCode(call('dm-sendMessage', { toUid: 'someone', text: 'hi' }))).toBe(
      'functions/failed-precondition',
    );
  });

  it('STILL rejects a suspended caller', async () => {
    const suspended = await newFreeUser();
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
    expect(await callableErrorCode(call('dm-sendMessage', { toUid: 'someone', text: 'hi' }))).toBe(
      'functions/permission-denied',
    );
  });

  it('rejects self-messaging and non-friends', async () => {
    const alice = await newMember('AliceDM');
    const stranger = await newMember('StrangerDM');
    await signInAs(alice);
    expect(await callableErrorCode(call('dm-sendMessage', { toUid: alice.uid, text: 'hi' }))).toBe(
      'functions/invalid-argument',
    );
    // Not friends → failed-precondition.
    expect(
      await callableErrorCode(call('dm-sendMessage', { toUid: stranger.uid, text: 'hi' })),
    ).toBe('functions/failed-precondition');
  });

  it('honours blocking in both directions with a neutral error', async () => {
    const kim = await newMember('KimDM');
    const leo = await newMember('LeoDM');
    await makeFriends(kim, leo);

    await signInAs(kim);
    await call('blocking-block', { targetUserId: leo.uid });
    expect(await callableErrorCode(call('dm-sendMessage', { toUid: leo.uid, text: 'hi' }))).toBe(
      'functions/failed-precondition',
    );

    // The blocked side is equally denied — same neutral code.
    await signInAs(leo);
    expect(await callableErrorCode(call('dm-sendMessage', { toUid: kim.uid, text: 'hi' }))).toBe(
      'functions/failed-precondition',
    );
  });
});

describe('dm messaging lifecycle', () => {
  it('sends, lists, paginates, and marks read with a consistent unread aggregate', async () => {
    const alice = await newMember('AliceDMLC');
    const bob = await newMember('BobDMLC');
    await makeFriends(alice, bob);
    const conversationId = pairId(alice.uid, bob.uid);

    // Alice sends 3 messages to Bob.
    await signInAs(alice);
    const first = (await call('dm-sendMessage', { toUid: bob.uid, text: 'hello 1' })).data as {
      conversationId: string;
      messageId: string;
    };
    expect(first.conversationId).toBe(conversationId);
    await call('dm-sendMessage', { toUid: bob.uid, text: 'hello 2' });
    await call('dm-sendMessage', { toUid: bob.uid, text: 'hello 3' });

    // Alice authored them → her aggregate is still 0.
    expect(await dmUnreadTotal(alice.uid)).toBe(0);
    // Bob has 3 unread + aggregate 3.
    expect(await dmUnreadTotal(bob.uid)).toBe(3);

    // Bob lists conversations: sees Alice, unread 3, denormalized lastMessage.
    await signInAs(bob);
    const bobList = (await call('dm-listConversations', {})).data as {
      conversations: Array<{
        conversationId: string;
        otherUser: { uid: string; displayName: string | null };
        lastMessage: { text: string; senderUid: string } | null;
        unreadCount: number;
      }>;
      totalUnread: number;
    };
    expect(bobList.totalUnread).toBe(3);
    const convo = bobList.conversations.find((c) => c.conversationId === conversationId)!;
    expect(convo.otherUser.uid).toBe(alice.uid);
    expect(convo.otherUser.displayName).toBe('AliceDMLC');
    expect(convo.unreadCount).toBe(3);
    expect(convo.lastMessage?.text).toBe('hello 3');
    expect(convo.lastMessage?.senderUid).toBe(alice.uid);

    // Bob paginates messages newest-first (page size honoured via before cursor).
    const firstPage = (await call('dm-getMessages', { conversationId })).data as {
      messages: Array<{ id: string; senderUid: string; text: string; createdAt: string }>;
      hasMore: boolean;
      nextBefore: string | null;
    };
    expect(firstPage.messages.length).toBe(3);
    expect(firstPage.messages[0]!.text).toBe('hello 3');
    expect(firstPage.hasMore).toBe(false);

    // A cursor before the oldest message returns nothing.
    const oldest = firstPage.messages[firstPage.messages.length - 1]!.createdAt;
    const older = (await call('dm-getMessages', { conversationId, before: oldest })).data as {
      messages: unknown[];
    };
    expect(older.messages.length).toBe(0);

    // Bob replies → Alice's unread + aggregate go to 1.
    await call('dm-sendMessage', { toUid: alice.uid, text: 'reply' });
    expect(await dmUnreadTotal(alice.uid)).toBe(1);

    // Bob marks the conversation read → clears his 3, aggregate back to 0.
    const marked = (await call('dm-markRead', { conversationId })).data as { cleared: number };
    expect(marked.cleared).toBe(3);
    expect(await dmUnreadTotal(bob.uid)).toBe(0);

    // Idempotent second mark clears nothing.
    const markedAgain = (await call('dm-markRead', { conversationId })).data as { cleared: number };
    expect(markedAgain.cleared).toBe(0);
    expect(await dmUnreadTotal(bob.uid)).toBe(0);
  });

  it('getMessages is not-found for non-members', async () => {
    const carol = await newMember('CarolDM');
    const dave = await newMember('DaveDM');
    const outsider = await newMember('OutsiderDM');
    await makeFriends(carol, dave);
    const conversationId = pairId(carol.uid, dave.uid);

    await signInAs(carol);
    await call('dm-sendMessage', { toUid: dave.uid, text: 'private' });

    await signInAs(outsider);
    expect(await callableErrorCode(call('dm-getMessages', { conversationId }))).toBe(
      'functions/not-found',
    );
    expect(await callableErrorCode(call('dm-markRead', { conversationId }))).toBe(
      'functions/not-found',
    );
  });
});

describe('conversations Firestore rules', () => {
  it('members read their conversation + messages; outsiders cannot; no client writes', async () => {
    const mona = await newMember('MonaDM');
    const nils = await newMember('NilsDM');
    const outsider = await newMember('OutsiderDMR');
    await makeFriends(mona, nils);
    const conversationId = pairId(mona.uid, nils.uid);

    await signInAs(mona);
    await call('dm-sendMessage', { toUid: nils.uid, text: 'hi nils' });

    // Both members read the conversation doc + messages subcollection.
    const asMona = await getDoc(doc(firestore, 'conversations', conversationId));
    expect(asMona.exists()).toBe(true);
    const monaMsgs = await getDocs(collection(firestore, 'conversations', conversationId, 'messages'));
    expect(monaMsgs.docs.length).toBe(1);

    await signInAs(nils);
    const asNils = await getDoc(doc(firestore, 'conversations', conversationId));
    expect(asNils.exists()).toBe(true);

    // A third party cannot read the conversation or its messages.
    await signInAs(outsider);
    await expect(getDoc(doc(firestore, 'conversations', conversationId))).rejects.toMatchObject({
      code: 'permission-denied',
    });
    await expect(
      getDocs(collection(firestore, 'conversations', conversationId, 'messages')),
    ).rejects.toMatchObject({ code: 'permission-denied' });

    // No direct client writes to the conversation or its messages.
    await signInAs(mona);
    await expect(
      setDoc(doc(firestore, 'conversations', conversationId), { members: [mona.uid, nils.uid] }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    await expect(
      setDoc(doc(firestore, 'conversations', conversationId, 'messages', 'forged'), {
        senderUid: mona.uid,
        text: 'forged',
      }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
  });
});

describe('dm-sendMessage in-app notification producer', () => {
  it('notifies the recipient with the sender name, preview, and conversation link', async () => {
    const sender = await newMember('NotifSender');
    const recipient = await newMember('NotifRecipient');
    await makeFriends(sender, recipient);

    await signInAs(sender);
    await call('dm-sendMessage', { toUid: recipient.uid, text: 'hej hej' });

    const items = await pollUntil(async () => {
      const found = await inboxFor(recipient.uid, 'direct_message');
      return found.length > 0 ? found : undefined;
    });
    expect(items).toHaveLength(1);
    expect(items[0]!.title).toBe('Nytt meddelande från NotifSender');
    expect(items[0]!.previewText).toBe('hej hej');
    // Deep-link target: the conversation id, so the client opens the thread.
    expect(items[0]!.relatedEntityId).toBe(pairId(sender.uid, recipient.uid));
    expect(items[0]!.read).toBe(false);
  });

  it('never notifies the SENDER of their own message', async () => {
    const sender = await newMember('SelfNotifSender');
    const recipient = await newMember('SelfNotifRecipient');
    await makeFriends(sender, recipient);

    await signInAs(sender);
    await call('dm-sendMessage', { toUid: recipient.uid, text: 'hej' });
    await pollUntil(async () => {
      const found = await inboxFor(recipient.uid, 'direct_message');
      return found.length > 0 ? found : undefined;
    });

    expect(await inboxFor(sender.uid, 'direct_message')).toHaveLength(0);
  });

  it('writes NO notification when the recipient opted out of direct_message', async () => {
    const sender = await newMember('OptOutSender');
    const recipient = await newMember('OptOutRecipient');
    await makeFriends(sender, recipient);
    await optOutOf(recipient.uid, 'direct_message');

    await signInAs(sender);
    await call('dm-sendMessage', { toUid: recipient.uid, text: 'hej' });

    // The message itself must still be delivered — only the notification is
    // suppressed. Waiting on the unread bump proves the send completed, so the
    // empty inbox below isn't just a race.
    await pollUntil(async () => ((await dmUnreadTotal(recipient.uid)) === 1 ? true : undefined));
    expect(await inboxFor(recipient.uid, 'direct_message')).toHaveLength(0);
  });

  it('writes NO notification when the pair is blocked — in EITHER direction', async () => {
    const kim = await newMember('BlockNotifKim');
    const leo = await newMember('BlockNotifLeo');
    await makeFriends(kim, leo);

    // Kim blocks Leo. The friendship itself survives (onBlockWrite only mirrors
    // the block to RTDB), so the send below reaches — and must be stopped by —
    // the both-ways block gate rather than the not-friends gate.
    await signInAs(kim);
    await call('blocking-block', { targetUserId: leo.uid });

    // Pins the invariant that today holds only by construction: the producer
    // sits BEHIND the block gate. Each leg asserts the send was actually
    // attempted and observed to be rejected, so the empty inbox is real
    // suppression and not a race. Move the producer above the gate and the
    // rejection still fires but the inbox is no longer empty — this fails.
    await signInAs(leo);
    expect(await callableErrorCode(call('dm-sendMessage', { toUid: kim.uid, text: 'hej' }))).toBe(
      'functions/failed-precondition',
    );
    expect(await inboxFor(kim.uid, 'direct_message')).toHaveLength(0);

    // The blocker cannot notify the person they blocked either.
    await signInAs(kim);
    expect(await callableErrorCode(call('dm-sendMessage', { toUid: leo.uid, text: 'hej' }))).toBe(
      'functions/failed-precondition',
    );
    expect(await inboxFor(leo.uid, 'direct_message')).toHaveLength(0);
  });

  it('does not restack a notice while the recipient still has unread, and notifies again after markRead', async () => {
    const sender = await newMember('RunSender');
    const recipient = await newMember('RunRecipient');
    await makeFriends(sender, recipient);

    await signInAs(sender);
    await call('dm-sendMessage', { toUid: recipient.uid, text: 'first' });
    await pollUntil(async () => {
      const found = await inboxFor(recipient.uid, 'direct_message');
      return found.length > 0 ? found : undefined;
    });

    // Further messages in the same unread run must NOT add inbox items.
    await call('dm-sendMessage', { toUid: recipient.uid, text: 'second' });
    await call('dm-sendMessage', { toUid: recipient.uid, text: 'third' });
    await pollUntil(async () => ((await dmUnreadTotal(recipient.uid)) === 3 ? true : undefined));
    expect(await inboxFor(recipient.uid, 'direct_message')).toHaveLength(1);

    // Once the recipient reads, the run ends and the next message notifies again.
    await signInAs(recipient);
    await call('dm-markRead', { conversationId: pairId(sender.uid, recipient.uid) });
    await signInAs(sender);
    await call('dm-sendMessage', { toUid: recipient.uid, text: 'fourth' });

    const items = await pollUntil(async () => {
      const found = await inboxFor(recipient.uid, 'direct_message');
      return found.length === 2 ? found : undefined;
    });
    expect(items).toHaveLength(2);
    expect(items.map((i) => i.previewText).sort()).toEqual(['first', 'fourth']);
  });
});
