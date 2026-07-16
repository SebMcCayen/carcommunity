/**
 * Chat-channels (community + convoy) emulator integration tests.
 *
 * Exercises the deployed-in-emulator callables end-to-end plus the
 * communityChat / convoyChats Firestore rules:
 * - `communityChat-post` (member gating, denormalized sender profile, @mention
 *   validation + the mention-only notification producer)
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
import { CONVOY_CHAT_NOTIFY_WINDOW_MS } from '../chatchannels/chat-core';

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
  mentionedUids: string[];
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

    // TTL: the stored message carries expireAt ≈ createdAt + 120 days.
    const storedComm = (
      await adminDb.collection('communityChat').doc('global').collection('messages').doc(posted.messageId).get()
    ).data()!;
    const commTtlDays =
      (storedComm.expireAt.toDate().getTime() - storedComm.createdAt.toDate().getTime()) /
      (24 * 60 * 60 * 1000);
    expect(commTtlDays).toBeGreaterThan(119.9);
    expect(commTtlDays).toBeLessThan(120.1);

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

    // TTL: the stored convoy message carries expireAt ≈ createdAt + 30 days.
    const storedConvoy = (
      await adminDb.collection('convoyChats').doc(convoyId).collection('messages').doc(posted.messageId).get()
    ).data()!;
    const convoyTtlDays =
      (storedConvoy.expireAt.toDate().getTime() - storedConvoy.createdAt.toDate().getTime()) /
      (24 * 60 * 60 * 1000);
    expect(convoyTtlDays).toBeGreaterThan(29.9);
    expect(convoyTtlDays).toBeLessThan(30.1);

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

// ---------------------------------------------------------------------------
// convoy_chat / community_chat in-app notification producers
// ---------------------------------------------------------------------------

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

/**
 * Owner + two invitees, where `accepted` has accepted and `invited` has not.
 * Returns the convoy id; the caller is left signed in as the owner.
 */
async function seedConvoy(
  owner: TestUser,
  accepted: TestUser,
  invited: TestUser,
  title?: string,
): Promise<string> {
  await makeFriends(owner, accepted);
  await makeFriends(owner, invited);
  await signInAs(owner);
  const created = (
    await call('convoy-create', {
      inviteeUids: [accepted.uid, invited.uid],
      ...(title ? { title } : {}),
    })
  ).data as { convoy: ConvoySummary };
  const convoyId = created.convoy.convoyId;
  await signInAs(accepted);
  await call('convoy-respond', { convoyId, action: 'accept' });
  await signInAs(owner);
  return convoyId;
}

/**
 * Waits until the current convoy-chat notify window has at least `needMs` left,
 * so a burst posted straight after this call cannot straddle a window boundary.
 *
 * convoyChatNotificationId buckets on FIXED, epoch-aligned windows of the
 * server's clock, so two messages either side of a boundary legitimately produce
 * two notices (a documented, accepted imprecision). The functions emulator runs
 * on this process's clock, so the time left in the current bucket is computable
 * here — waiting out the tail end removes that boundary race from the burst test
 * without touching the assertion it makes.
 */
async function awaitRoomInNotifyWindow(needMs = 15_000): Promise<void> {
  const remaining = CONVOY_CHAT_NOTIFY_WINDOW_MS - (Date.now() % CONVOY_CHAT_NOTIFY_WINDOW_MS);
  if (remaining >= needMs) return;
  await new Promise((resolve) => setTimeout(resolve, remaining + 250));
}

describe('convoyChat-post in-app notification fan-out', () => {
  it('notifies the other ACCEPTED members, never the poster or a still-invited member', async () => {
    const owner = await newMember('FanoutOwner');
    const accepted = await newMember('FanoutAccepted');
    const invited = await newMember('FanoutInvited');
    const convoyId = await seedConvoy(owner, accepted, invited, 'Fjälltur');

    await call('convoyChat-post', { convoyId, text: 'vi rullar om 5' });

    const items = await pollUntil(async () => {
      const found = await inboxFor(accepted.uid, 'convoy_chat');
      return found.length > 0 ? found : undefined;
    });
    expect(items).toHaveLength(1);
    expect(items[0]!.title).toBe('Nytt i konvojen: Fjälltur');
    expect(items[0]!.previewText).toBe('FanoutOwner: vi rullar om 5');
    // Deep-link target: the convoy.
    expect(items[0]!.relatedEntityId).toBe(convoyId);

    // The poster never notifies themselves; a still-invited member is not in
    // the fan-out set (they can't even read the channel).
    expect(await inboxFor(owner.uid, 'convoy_chat')).toHaveLength(0);
    expect(await inboxFor(invited.uid, 'convoy_chat')).toHaveLength(0);
  });

  it('writes NO notification to a member who opted out of convoy_chat', async () => {
    const owner = await newMember('CcOptOutOwner');
    const accepted = await newMember('CcOptOutAccepted');
    const invited = await newMember('CcOptOutInvited');
    const convoyId = await seedConvoy(owner, accepted, invited);
    await optOutOf(accepted.uid, 'convoy_chat');

    const posted = (await call('convoyChat-post', { convoyId, text: 'hej' })).data as {
      messageId: string;
    };

    // The message itself must still be posted — only the notification is
    // suppressed. Waiting on it proves the post completed, so the empty inbox
    // below isn't just a race.
    await pollUntil(async () => {
      const snap = await adminDb
        .collection('convoyChats')
        .doc(convoyId)
        .collection('messages')
        .doc(posted.messageId)
        .get();
      return snap.exists ? true : undefined;
    });
    expect(await inboxFor(accepted.uid, 'convoy_chat')).toHaveLength(0);
  });

  it('collapses a burst of messages into ONE notice per member (per-window id)', async () => {
    const owner = await newMember('BurstOwner');
    const accepted = await newMember('BurstAccepted');
    const invited = await newMember('BurstInvited');
    const convoyId = await seedConvoy(owner, accepted, invited);

    // Post the whole burst inside ONE notify window: a boundary straddle would
    // legitimately produce a second notice and flake the assertion below. This
    // only pins WHEN the burst runs — within a window the collapse still has to
    // turn three messages into exactly one notice, so the assertion keeps its
    // teeth: drop the per-window id and this test sees 3 items and fails.
    await awaitRoomInNotifyWindow();

    await call('convoyChat-post', { convoyId, text: 'ett' });
    await pollUntil(async () => {
      const found = await inboxFor(accepted.uid, 'convoy_chat');
      return found.length > 0 ? found : undefined;
    });
    await call('convoyChat-post', { convoyId, text: 'två' });
    await call('convoyChat-post', { convoyId, text: 'tre' });

    // All three messages really land — so the single notice below is a genuine
    // per-window collapse, not posts that silently failed.
    await pollUntil(async () => {
      const snap = await adminDb
        .collection('convoyChats')
        .doc(convoyId)
        .collection('messages')
        .get();
      return snap.size === 3 ? true : undefined;
    });

    // The inbox still holds only the FIRST notice, which keeps its original
    // preview (create-if-absent never overwrites).
    const items = await inboxFor(accepted.uid, 'convoy_chat');
    expect(items).toHaveLength(1);
    expect(items[0]!.previewText).toBe('BurstOwner: ett');
  });
});

/** Waits for a community message to exist — proves the post completed. */
async function awaitCommunityMessage(messageId: string): Promise<Record<string, unknown>> {
  return pollUntil(async () => {
    const snap = await adminDb
      .collection('communityChat')
      .doc('global')
      .collection('messages')
      .doc(messageId)
      .get();
    return snap.exists ? snap.data()! : undefined;
  });
}

interface PostedCommunity {
  messageId: string;
  mentionedUids: string[];
}

describe('communityChat-post @mention notifications', () => {
  it('notifies ONLY the mentioned member, and stores the mention on the message', async () => {
    const poster = await newMember('MentionPoster');
    const mentioned = await newMember('MentionTarget');
    const bystander = await newMember('MentionBystander');

    await signInAs(poster);
    const posted = (
      await call('communityChat-post', {
        text: 'vad tycker du @MentionTarget?',
        mentionedUids: [mentioned.uid],
      })
    ).data as PostedCommunity;
    expect(posted.mentionedUids).toEqual([mentioned.uid]);

    const items = await pollUntil(async () => {
      const found = await inboxFor(mentioned.uid, 'community_chat');
      return found.length > 0 ? found : undefined;
    });
    expect(items).toHaveLength(1);
    expect(items[0]!.title).toBe('Du nämndes i community-chatten');
    expect(items[0]!.previewText).toBe('MentionPoster: vad tycker du @MentionTarget?');
    // Deep-link target: the message itself (the channel is a singleton).
    expect(items[0]!.relatedEntityId).toBe(posted.messageId);

    // The mention is stored for client-side highlighting...
    const stored = await awaitCommunityMessage(posted.messageId);
    expect(stored.mentionedUids).toEqual([mentioned.uid]);
    // ...and list hands it to the client, which resolves nothing itself.
    const listed = (await call('communityChat-list', {})).data as { messages: ChatMessage[] };
    expect(listed.messages.find((m) => m.id === posted.messageId)!.mentionedUids).toEqual([
      mentioned.uid,
    ]);

    // Nobody else is reached — the whole point of mentions over a fan-out.
    expect(await inboxFor(bystander.uid, 'community_chat')).toHaveLength(0);
    expect(await inboxFor(poster.uid, 'community_chat')).toHaveLength(0);
  });

  it('writes NO notification to a mentioned member who opted out of community_chat', async () => {
    const poster = await newMember('OptOutPoster');
    const mentioned = await newMember('OptOutTarget');
    await optOutOf(mentioned.uid, 'community_chat');

    await signInAs(poster);
    const posted = (
      await call('communityChat-post', {
        text: 'hej @OptOutTarget',
        mentionedUids: [mentioned.uid],
      })
    ).data as PostedCommunity;

    // The message still posts and still records the mention — the opt-out is
    // about the inbox, not about whether the highlight renders. Waiting on the
    // message proves the post completed, so the empty inbox isn't just a race.
    const stored = await awaitCommunityMessage(posted.messageId);
    expect(stored.mentionedUids).toEqual([mentioned.uid]);
    expect(await inboxFor(mentioned.uid, 'community_chat')).toHaveLength(0);
  });

  it('drops a self-mention: no notice, not stored', async () => {
    const poster = await newMember('SelfMentionPoster');

    await signInAs(poster);
    const posted = (
      await call('communityChat-post', { text: 'as @me said', mentionedUids: [poster.uid] })
    ).data as PostedCommunity;
    expect(posted.mentionedUids).toEqual([]);

    const stored = await awaitCommunityMessage(posted.messageId);
    expect(stored.mentionedUids).toEqual([]);
    expect(await inboxFor(poster.uid, 'community_chat')).toHaveLength(0);
  });

  it('drops mentions of an unknown uid / a non-member, still posting the message', async () => {
    const poster = await newMember('GhostMentionPoster');
    const free = await newFreeUser(); // a real user, but not an active member
    const mentioned = await newMember('GhostMentionTarget');

    await signInAs(poster);
    const posted = (
      await call('communityChat-post', {
        text: 'hej @GhostMentionTarget @nobody',
        mentionedUids: ['does-not-exist-uid', free.uid, mentioned.uid],
      })
    ).data as PostedCommunity;

    // Only the deliverable member survives; the post itself is unaffected (a
    // stale pick is a race, not a reason to fail someone's message).
    expect(posted.mentionedUids).toEqual([mentioned.uid]);
    const stored = await awaitCommunityMessage(posted.messageId);
    expect(stored.mentionedUids).toEqual([mentioned.uid]);
    expect(await inboxFor(free.uid, 'community_chat')).toHaveLength(0);
  });

  it('drops a mention of someone who blocked the sender (a block cuts directed reach)', async () => {
    const poster = await newMember('BlockedMentionPoster');
    const blocker = await newMember('BlockedMentionTarget');

    // The mentioned member has blocked the poster.
    await signInAs(blocker);
    await call('blocking-block', { targetUserId: poster.uid });

    await signInAs(poster);
    const posted = (
      await call('communityChat-post', {
        text: 'hej @BlockedMentionTarget',
        mentionedUids: [blocker.uid],
      })
    ).data as PostedCommunity;

    // The message still posts to the town square (community reads are NOT
    // block-filtered) — only the directed inbox push is denied.
    expect(posted.mentionedUids).toEqual([]);
    const stored = await awaitCommunityMessage(posted.messageId);
    expect(stored.text).toBe('hej @BlockedMentionTarget');
    expect(stored.mentionedUids).toEqual([]);
    expect(await inboxFor(blocker.uid, 'community_chat')).toHaveLength(0);
  });

  it('drops a mention of someone the SENDER blocked (both directions)', async () => {
    const poster = await newMember('SenderBlockedPoster');
    const target = await newMember('SenderBlockedTarget');

    await signInAs(poster);
    await call('blocking-block', { targetUserId: target.uid });
    const posted = (
      await call('communityChat-post', { text: 'hej @t', mentionedUids: [target.uid] })
    ).data as PostedCommunity;

    expect(posted.mentionedUids).toEqual([]);
    expect(await inboxFor(target.uid, 'community_chat')).toHaveLength(0);
  });

  it('rejects more than the mention cap (invalid-argument)', async () => {
    const poster = await newMember('CapMentionPoster');
    await signInAs(poster);
    expect(
      await callableErrorCode(
        call('communityChat-post', {
          text: 'allihopa',
          mentionedUids: Array.from({ length: 11 }, (_, i) => `uid-${i}`),
        }),
      ),
    ).toBe('functions/invalid-argument');
  });

  it('collapses repeated mentions from the SAME sender into one notice per window', async () => {
    const poster = await newMember('RepeatMentionPoster');
    const mentioned = await newMember('RepeatMentionTarget');

    await signInAs(poster);
    await call('communityChat-post', { text: 'hej @t', mentionedUids: [mentioned.uid] });
    await pollUntil(async () => {
      const found = await inboxFor(mentioned.uid, 'community_chat');
      return found.length > 0 ? found : undefined;
    });
    const second = (
      await call('communityChat-post', { text: 'och igen @t', mentionedUids: [mentioned.uid] })
    ).data as PostedCommunity;

    // Both messages land, but a repeat-mentioner can't stack the inbox: the
    // per-(sender, window) id leaves the FIRST notice untouched.
    await awaitCommunityMessage(second.messageId);
    const items = await inboxFor(mentioned.uid, 'community_chat');
    expect(items).toHaveLength(1);
    expect(items[0]!.previewText).toBe('RepeatMentionPoster: hej @t');
  });
});

describe('communityChat-post writes NO notification without mentions (deliberate)', () => {
  it('does not fan out to other members on a plain community message', async () => {
    const poster = await newMember('TownSquarePoster');
    const other = await newMember('TownSquareOther');

    await signInAs(poster);
    const posted = (await call('communityChat-post', { text: 'hej alla' })).data as PostedCommunity;
    expect(posted.mentionedUids).toEqual([]);

    // Wait for the message to land so this isn't a race, then assert that
    // neither the poster nor any other member got an inbox item. Fanning out to
    // every active member per message is a spam/cost non-starter — @mentions are
    // the ONLY community_chat producer (see communityChat.ts).
    await awaitCommunityMessage(posted.messageId);
    expect(await inboxFor(other.uid, 'community_chat')).toHaveLength(0);
    expect(await inboxFor(poster.uid, 'community_chat')).toHaveLength(0);
  });
});
