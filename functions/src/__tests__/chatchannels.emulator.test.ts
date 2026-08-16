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
 * - `convoyChat-markRead` (per-convoy last-read marker map + its eviction cap)
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
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  CONVOY_CHAT_NOTIFY_WINDOW_MS,
  CONVOY_LAST_READ_MAX_ENTRIES,
} from '../chatchannels/chat-core';

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
  it('rejects unauthenticated callers, but ADMITS a non-member', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('communityChat-list', {}))).toBe(
      'functions/unauthenticated',
    );
    // Was: permission-denied. Member gating is disabled (memberGating.ts).
    const free = await newFreeUser();
    await signInAs(free);
    const posted = (await call('communityChat-post', { text: 'hi' })).data as {
      messageId: string;
    };
    expect(typeof posted.messageId).toBe('string');
  });

  it('STILL rejects a suspended caller', async () => {
    const suspended = await newFreeUser();
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
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

    // A non-member (free user) CAN now read the channel messages — the
    // firestore.rules isActiveMember() switch. Re-locking restores the denial.
    const free = await newFreeUser();
    await signInAs(free);
    const freeSnap = await getDocs(
      query(collection(firestore, 'communityChat', 'global', 'messages')),
    );
    expect(freeSnap.empty).toBe(false);
  });

  it('rules: STILL deny a suspended user the channel messages', async () => {
    // Teeth: isActiveMember() keeps isNotSuspended() after the unlock.
    const suspended = await newFreeUser();
    await adminAuth.setCustomUserClaims(suspended.uid, { suspended: true });
    await signInAs(suspended);
    await auth.currentUser!.getIdToken(true);
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

  it('markRead stamps a PER-CONVOY marker in the caller\'s userPrivate, and caps the map', async () => {
    const member = await newMember('MarkReadOwnerChat');
    await signInAs(member);
    // Plain ids, not created convoys: markRead deliberately carries no convoy
    // membership gate (see the callable's KDoc), and the marker's whole job is to
    // be a key in the CALLER's own private document. Using bare ids is therefore
    // the honest exercise of the contract — and the next test pins that skipping
    // the gate grants nothing.
    const convoyId = `markread-a-${Date.now()}`;
    const otherConvoyId = `markread-b-${Date.now()}`;
    const privateRef = adminDb.collection('userPrivate').doc(member.uid);

    // Nothing stamped yet: the field is absent, which the client reads as "never
    // opened" (every message from someone else then counts as unread).
    const before = (await privateRef.get()).data();
    expect((before?.convoyChatLastReadAt as Record<string, unknown> | undefined)?.[convoyId]).toBe(
      undefined,
    );

    expect((await call('convoyChat-markRead', { convoyId })).data).toEqual({ convoyId });

    const stamped = await pollUntil(async () => {
      const map = (await privateRef.get()).data()?.convoyChatLastReadAt as
        | Record<string, Timestamp>
        | undefined;
      return map?.[convoyId];
    });
    expect(stamped.toMillis()).toBeGreaterThan(0);

    // Keyed by convoy: marking one convoy read leaves another's marker alone.
    await call('convoyChat-markRead', { convoyId: otherConvoyId });
    const both = (await privateRef.get()).data()!.convoyChatLastReadAt as Record<string, Timestamp>;
    expect(both[convoyId]!.toMillis()).toBe(stamped.toMillis());
    expect(both[otherConvoyId]).toBeTruthy();

    // A convoy id is required (it is the map key) and must be a document id.
    expect(await callableErrorCode(call('convoyChat-markRead', {}))).toBe(
      'functions/invalid-argument',
    );
    expect(await callableErrorCode(call('convoyChat-markRead', { convoyId: 'bad/id' }))).toBe(
      'functions/invalid-argument',
    );

    // The map is CAPPED: seed it exactly AT the cap with markers older than the
    // real ones, then a fresh stamp must evict the oldest rather than grow.
    const filler = Object.fromEntries(
      Array.from({ length: CONVOY_LAST_READ_MAX_ENTRIES }, (_, i) => [
        `filler-${String(i).padStart(3, '0')}`,
        Timestamp.fromMillis(1_000 + i),
      ]),
    );
    // Replaces the field outright (no merge), so the two markers above don't
    // count against the cap and the expected eviction is exactly one filler.
    await privateRef.set({ convoyChatLastReadAt: filler });
    await call('convoyChat-markRead', { convoyId });

    const capped = (await privateRef.get()).data()!.convoyChatLastReadAt as Record<
      string,
      Timestamp
    >;
    expect(Object.keys(capped).length).toBe(CONVOY_LAST_READ_MAX_ENTRIES);
    // The just-stamped convoy survives; the OLDEST filler is the one evicted.
    expect(capped[convoyId]).toBeTruthy();
    expect(capped['filler-000']).toBe(undefined);
    expect(capped['filler-001']).toBeTruthy();
  });

  it('markRead admits a NON-member (an inert marker in their own private doc)', async () => {
    // Deliberate: the write lands in the caller's own userPrivate, grants nothing
    // and returns only the id it was handed, so it cannot probe a convoy — and
    // skipping the membership read keeps the hot path (one call per incoming
    // message per watching member) at a single read.
    const owner = await newMember('MarkReadGateOwner');
    const friend = await newMember('MarkReadGateFriend');
    await makeFriends(owner, friend);
    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [friend.uid] })).data as {
      convoy: ConvoySummary;
    };
    const convoyId = created.convoy.convoyId;

    const outsider = await newMember('MarkReadOutsider');
    await signInAs(outsider);
    expect((await call('convoyChat-markRead', { convoyId })).data).toEqual({ convoyId });
    // ...and it still buys them nothing: the chat itself stays not-found.
    expect(await callableErrorCode(call('convoyChat-list', { convoyId }))).toBe(
      'functions/not-found',
    );

    // Unauthenticated is still rejected.
    await auth.signOut();
    expect(await callableErrorCode(call('convoyChat-markRead', { convoyId }))).toBe(
      'functions/unauthenticated',
    );
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

  it('stamps the "any convoy unread" aggregate for recipients, never the poster', async () => {
    const owner = await newMember('AggOwner');
    const accepted = await newMember('AggAccepted');
    const invited = await newMember('AggInvited');
    const convoyId = await seedConvoy(owner, accepted, invited);

    const latestFor = async (uid: string) =>
      (await adminDb.collection('userPrivate').doc(uid).get()).data()?.convoyChatLatestAt as
        | Record<string, Timestamp>
        | undefined;

    // Owner posts: the accepted member's private doc gains a convoyChatLatestAt
    // stamp for THIS convoy — the owner-only readable signal the client's
    // aggregate dot derives from, keyed by convoy exactly like the last-read
    // marker. This is the same recipient set as the notify fan-out.
    await call('convoyChat-post', { convoyId, text: 'är alla här?' });

    const latest = await pollUntil(async () => (await latestFor(accepted.uid))?.[convoyId]);
    expect(latest.toMillis()).toBeGreaterThan(0);

    // The poster never lights their OWN aggregate, and a still-invited member —
    // who cannot even read the channel — is never stamped.
    expect((await latestFor(owner.uid))?.[convoyId]).toBe(undefined);
    expect((await latestFor(invited.uid))?.[convoyId]).toBe(undefined);

    // Reading the convoy stamps a last-read marker AT/AFTER the latest message
    // time, so latest <= lastRead → the client derives "not unread" and clears the
    // dot. The two markers are what the pure client derivation compares.
    await signInAs(accepted);
    await call('convoyChat-markRead', { convoyId });
    const lastRead = await pollUntil(async () => {
      const map = (await adminDb.collection('userPrivate').doc(accepted.uid).get()).data()
        ?.convoyChatLastReadAt as Record<string, Timestamp> | undefined;
      return map?.[convoyId];
    });
    expect(lastRead.toMillis()).toBeGreaterThanOrEqual(latest.toMillis());
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

  it('drops mentions of an unknown uid but KEEPS a non-member (gating disabled), still posting', async () => {
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

    // The unknown uid is still dropped; the non-member is now deliverable.
    // Re-locking drops the non-member again (resolveMentions).
    expect(posted.mentionedUids.sort()).toEqual([free.uid, mentioned.uid].sort());
    const stored = await awaitCommunityMessage(posted.messageId);
    // Stored Firestore field is string[]; awaitCommunityMessage returns Record<string, unknown>.
    expect((stored.mentionedUids as string[]).sort()).toEqual([free.uid, mentioned.uid].sort());
    expect(await inboxFor(free.uid, 'community_chat')).toHaveLength(1);
  });

  it('STILL drops a mention of a suspended user', async () => {
    // Teeth: resolveMentions drops suspended/deleted regardless of gating.
    const poster = await newMember('SuspMentionPoster');
    const suspended = await newFreeUser();
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    const mentioned = await newMember('SuspMentionTarget');

    await signInAs(poster);
    const posted = (
      await call('communityChat-post', {
        text: 'hej @SuspMentionTarget',
        mentionedUids: [suspended.uid, mentioned.uid],
      })
    ).data as PostedCommunity;

    expect(posted.mentionedUids).toEqual([mentioned.uid]);
    expect(await inboxFor(suspended.uid, 'community_chat')).toHaveLength(0);
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

describe('keyed optimistic send is EXACTLY-ONCE (clientId idempotency)', () => {
  // The optimistic composer keeps a bubble pending until the server acks, and
  // retries the send when that ack never arrives. A retry must therefore land on
  // the SAME message rather than posting a second one — the clientId is used
  // verbatim as the doc id, and the write is a `create()` so the arbitration is
  // Firestore's, not a read-then-write that two racing retries both clear.

  it('community: a sequential retry returns the same message and rewrites nothing', async () => {
    const poster = await newMember('IdemCommPoster');
    await signInAs(poster);
    const clientId = `idem-comm-${Date.now()}`;

    const first = (await call('communityChat-post', { text: 'hej', clientId }))
      .data as PostedCommunity;
    expect(first.messageId).toBe(clientId);
    const stored = await awaitCommunityMessage(clientId);

    const retry = (await call('communityChat-post', { text: 'hej', clientId }))
      .data as PostedCommunity;
    expect(retry.messageId).toBe(clientId);

    // Not merely "one doc with the right id" — the ORIGINAL doc, untouched. A
    // re-write would move createdAt, which is the channel's sort key, so the
    // message would jump position under a client that already reconciled it.
    const after = await awaitCommunityMessage(clientId);
    expect((after.createdAt as { toMillis(): number }).toMillis()).toBe(
      (stored.createdAt as { toMillis(): number }).toMillis(),
    );
  });

  it('community: CONCURRENT retries all succeed and produce exactly one message', async () => {
    const poster = await newMember('IdemCommRacer');
    await signInAs(poster);
    const clientId = `idem-comm-race-${Date.now()}`;
    const marker = `race-${clientId}`;

    // The race the read-then-write guard could not win: every attempt reads
    // "missing" before any of them writes. Exactly one `create()` commits; the
    // losers must replay it as a normal success (a misread ALREADY_EXISTS would
    // surface here as an internal error rather than a messageId).
    const results = await Promise.all(
      Array.from({ length: 4 }, () => call('communityChat-post', { text: marker, clientId })),
    );
    for (const result of results) {
      expect((result.data as PostedCommunity).messageId).toBe(clientId);
    }

    await awaitCommunityMessage(clientId);
    const matching = (
      await adminDb.collection('communityChat').doc('global').collection('messages').get()
    ).docs.filter((doc) => doc.data().text === marker);
    expect(matching).toHaveLength(1);
    expect(matching[0]!.id).toBe(clientId);
    expect(matching[0]!.data().senderUid).toBe(poster.uid);
  });

  it('community: a DIFFERENT sender reusing the key is rejected, never swallowed', async () => {
    const owner = await newMember('IdemCommOwner');
    const thief = await newMember('IdemCommThief');
    const clientId = `idem-comm-collide-${Date.now()}`;

    await signInAs(owner);
    await call('communityChat-post', { text: 'mine', clientId });
    await awaitCommunityMessage(clientId);

    // Swallowing this as success would silently DROP the second sender's message
    // while telling them it was delivered.
    await signInAs(thief);
    expect(
      await callableErrorCode(call('communityChat-post', { text: 'theirs', clientId })),
    ).toBe('functions/already-exists');
    const stored = await awaitCommunityMessage(clientId);
    expect(stored.text).toBe('mine');
  });

  it('convoy: CONCURRENT retries all succeed and produce exactly one message', async () => {
    const owner = await newMember('IdemConvoyOwner');
    const accepted = await newMember('IdemConvoyAccepted');
    const invited = await newMember('IdemConvoyInvited');
    const convoyId = await seedConvoy(owner, accepted, invited);
    const clientId = `idem-convoy-race-${Date.now()}`;

    const results = await Promise.all(
      Array.from({ length: 4 }, () =>
        call('convoyChat-post', { convoyId, text: 'vi rullar', clientId }),
      ),
    );
    for (const result of results) {
      expect((result.data as { messageId: string }).messageId).toBe(clientId);
    }

    const messages = await pollUntil(async () => {
      const snap = await adminDb
        .collection('convoyChats')
        .doc(convoyId)
        .collection('messages')
        .get();
      return snap.docs.length > 0 ? snap.docs : undefined;
    });
    expect(messages).toHaveLength(1);
    expect(messages[0]!.id).toBe(clientId);
    expect(messages[0]!.data().clientId).toBe(clientId);
  });

  it('convoy: a key-less (legacy) send still posts and stores no clientId', async () => {
    const owner = await newMember('IdemLegacyOwner');
    const accepted = await newMember('IdemLegacyAccepted');
    const invited = await newMember('IdemLegacyInvited');
    const convoyId = await seedConvoy(owner, accepted, invited);

    const posted = (await call('convoyChat-post', { convoyId, text: 'utan nyckel' })).data as {
      messageId: string;
    };
    const stored = await pollUntil(async () => {
      const snap = await adminDb
        .collection('convoyChats')
        .doc(convoyId)
        .collection('messages')
        .doc(posted.messageId)
        .get();
      return snap.exists ? snap.data()! : undefined;
    });
    expect(stored.clientId).toBeUndefined();
  });
});
