/**
 * Block INVISIBILITY emulator integration tests — the mutual, bidirectional
 * hiding a block produces across the map and the chat surfaces.
 *
 * The property under test is MUTUALITY: it must not matter which side pressed
 * block. For every surface, both of these are asserted:
 *   - the BLOCKER no longer sees the blocked user, and
 *   - the BLOCKED user no longer sees the blocker.
 * A one-directional filter would pass half of each pair, so every case here
 * checks both and an "unblock restores it" case guards the release path.
 *
 * Surfaces covered:
 *   - live map discovery      (live-listNearby, server-side filter)
 *   - blockVisibility mirror  (blocking-onBlockWrite, symmetric + edge-counted)
 *   - community chat          (communityChat-list, server-side filter)
 *   - convoy chat             (convoyChat-list, server-side filter)
 *   - direct messages         (dm-listConversations / dm-getMessages /
 *                              dm-markRead + the conversations/messages RULES)
 *
 * Requires the Auth + Functions + Firestore + Database emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
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
import { getDatabase as getAdminDatabase } from 'firebase-admin/database';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

// Unique-per-file suffix: the emulator suite shares ONE Firestore across files
// with no isolation between them, so every displayName and message body created
// here is suffixed to avoid colliding with another file's fixtures.
const SFX = 'blockinvis';

const adminApp =
  getAdminApps()[0] ??
  initializeAdminApp(
    {
      projectId: PROJECT_ID,
      databaseURL: `http://${EMULATOR_HOST}:9000?ns=${PROJECT_ID}-default-rtdb`,
    },
    `block-invisibility-emulator-${SFX}`,
  );
const adminDb = getAdminFirestore(adminApp);
const adminAuth = getAdminAuth(adminApp);

// RTDB needs its OWN named app rather than `getAdminApps()[0]`: the emulator
// suite shares one process across files, so the first-registered admin app may
// be one created WITHOUT a databaseURL (Firestore does not need one). Reusing it
// leaves getDatabase() pointing nowhere and the mirror read silently times out.
const RTDB_APP_NAME = `block-invisibility-rtdb-${SFX}`;
const rtdbApp =
  getAdminApps().find((candidate) => candidate.name === RTDB_APP_NAME) ??
  initializeAdminApp(
    {
      projectId: PROJECT_ID,
      databaseURL: `http://${EMULATOR_HOST}:9000?ns=${PROJECT_ID}-default-rtdb`,
    },
    RTDB_APP_NAME,
  );
const adminRtdb = getAdminDatabase(rtdbApp);

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
  const email = `${SFX}-${userSeq}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
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
    .set({ activeMember: true, displayName: `${displayName}-${SFX}` }, { merge: true });
  return { uid, email, password };
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

/** Seeds a two-sided established friendship directly (bypasses the friend.* flow). */
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

/**
 * Blocks via the real callable and WAITS for the blocking-onBlockWrite fan-out
 * to land in the blockVisibility mirror on both sides.
 *
 * The wait is what makes the chat assertions deterministic: the DM gate reads
 * the authoritative userBlocks edges (immediate), but the channel `list`
 * callables read the mirror, which is trigger-maintained and therefore
 * eventually consistent.
 */
async function blockAndAwaitMirror(blocker: TestUser, blocked: TestUser): Promise<void> {
  await signInAs(blocker);
  await call('blocking-block', { targetUserId: blocked.uid });
  await pollUntil(async () => (await hiddenUids(blocker.uid)).includes(blocked.uid) || undefined);
  await pollUntil(async () => (await hiddenUids(blocked.uid)).includes(blocker.uid) || undefined);
}

/** Unblocks via the real callable and waits for the mirror to be cleared on both sides. */
async function unblockAndAwaitMirror(blocker: TestUser, blocked: TestUser): Promise<void> {
  await signInAs(blocker);
  await call('blocking-unblock', { targetUserId: blocked.uid });
  await pollUntil(async () => !(await hiddenUids(blocker.uid)).includes(blocked.uid) || undefined);
  await pollUntil(async () => !(await hiddenUids(blocked.uid)).includes(blocker.uid) || undefined);
}

async function hiddenUids(uid: string): Promise<string[]> {
  const snap = await adminDb.collection('blockVisibility').doc(uid).get();
  const raw = snap.data()?.hiddenUids;
  return Array.isArray(raw) ? (raw as string[]) : [];
}

interface ChatMessage {
  id: string;
  senderUid: string;
  text: string;
}

async function communityTexts(): Promise<string[]> {
  const res = (await call('communityChat-list', {})).data as { messages: ChatMessage[] };
  return res.messages.map((m) => m.text);
}

async function convoyTexts(convoyId: string): Promise<string[]> {
  const res = (await call('convoyChat-list', { convoyId })).data as { messages: ChatMessage[] };
  return res.messages.map((m) => m.text);
}

interface ConversationSummary {
  conversationId: string;
  otherUser: { uid: string };
  unreadCount: number;
  lastMessage: { text: string } | null;
}

async function conversationIds(): Promise<string[]> {
  const res = (await call('dm-listConversations', {})).data as {
    conversations: ConversationSummary[];
  };
  return res.conversations.map((c) => c.conversationId);
}

let userA: TestUser;
let userB: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    `block-invisibility-client-${SFX}`,
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
  firestore = getFirestore(app);
  connectFirestoreEmulator(firestore, EMULATOR_HOST, 8080);

  userA = await newMember('Ainvis');
  userB = await newMember('Binvis');
}, 180_000);

afterAll(async () => {
  await deleteApp(app);
});

// ---------------------------------------------------------------------------
// The mirror itself
// ---------------------------------------------------------------------------

describe('blockVisibility mirror (blocking-onBlockWrite)', () => {
  it('is SYMMETRIC: one directional block hides the pair from each other', async () => {
    const one = await newMember('MirrorOne');
    const two = await newMember('MirrorTwo');

    await blockAndAwaitMirror(one, two);

    // The block was one-directional (one → two), but BOTH mirrors carry the
    // other party: that is what makes the invisibility mutual downstream.
    expect(await hiddenUids(one.uid)).toContain(two.uid);
    expect(await hiddenUids(two.uid)).toContain(one.uid);

    await unblockAndAwaitMirror(one, two);
    expect(await hiddenUids(one.uid)).not.toContain(two.uid);
    expect(await hiddenUids(two.uid)).not.toContain(one.uid);
  });

  it('is EDGE-COUNTED: unblocking one side keeps the pair hidden while the other side still blocks', async () => {
    const one = await newMember('EdgeOne');
    const two = await newMember('EdgeTwo');

    await blockAndAwaitMirror(one, two);
    await blockAndAwaitMirror(two, one);

    // one unblocks two — but two still blocks one, so neither mirror may clear.
    await signInAs(one);
    await call('blocking-unblock', { targetUserId: two.uid });
    await pollUntil(async () => {
      const edge = await adminDb
        .collection('userBlocks')
        .doc(one.uid)
        .collection('blocked')
        .doc(two.uid)
        .get();
      return edge.exists ? undefined : true;
    });
    // Give the trigger a chance to (wrongly) clear before asserting it did not.
    await new Promise((resolve) => setTimeout(resolve, 1500));

    expect(await hiddenUids(one.uid)).toContain(two.uid);
    expect(await hiddenUids(two.uid)).toContain(one.uid);

    // Releasing the remaining edge finally clears both sides.
    await unblockAndAwaitMirror(two, one);
    expect(await hiddenUids(one.uid)).not.toContain(two.uid);
    expect(await hiddenUids(two.uid)).not.toContain(one.uid);
  });

  it('is owner-readable only and never client-writable', async () => {
    const owner = await newMember('MirrorOwner');
    const other = await newMember('MirrorOther');
    await blockAndAwaitMirror(owner, other);

    // The owner reads their own mirror — the client filter depends on this.
    await signInAs(owner);
    const own = await getDoc(doc(firestore, 'blockVisibility', owner.uid));
    expect(own.exists()).toBe(true);
    expect(own.data()?.hiddenUids).toContain(other.uid);

    // Nobody else can read it, and nobody can forge one: the mirror is written
    // solely by the blocking-onBlockWrite trigger.
    await expect(getDoc(doc(firestore, 'blockVisibility', other.uid))).rejects.toThrow();
    await expect(
      setDoc(doc(firestore, 'blockVisibility', owner.uid), { hiddenUids: [] }),
    ).rejects.toThrow();
  });
});

// ---------------------------------------------------------------------------
// Live map
// ---------------------------------------------------------------------------

describe('live map discovery is mutually invisible', () => {
  const HERE = { latitude: 59.334, longitude: 18.063 };
  const coordinateAt = (lat: number, lng: number) => ({
    latitude: lat,
    longitude: lng,
    accuracyMeters: 12,
    recordedAt: new Date().toISOString(),
  });

  async function shareAt(user: TestUser): Promise<void> {
    await signInAs(user);
    await call('live-startSession', { duration: '1h' });
    await call('live-updatePosition', {
      coordinate: coordinateAt(HERE.latitude, HERE.longitude),
    });
    await pollUntil(async () => {
      const snap = await adminDb.collection('liveSessions').doc(user.uid).get();
      return snap.exists ? true : undefined;
    });
  }

  async function seesOnMap(viewer: TestUser, target: TestUser): Promise<boolean> {
    await signInAs(viewer);
    const res = (await call('live-listNearby', { ...HERE, radiusMeters: 5000 })).data as {
      sessions: Array<{ uid: string }>;
    };
    return res.sessions.some((s) => s.uid === target.uid);
  }

  it('hides BOTH parties from each other after one of them blocks', async () => {
    const sharerOne = await newMember('MapOne');
    const sharerTwo = await newMember('MapTwo');
    await shareAt(sharerOne);
    await shareAt(sharerTwo);

    // Baseline: both are discoverable to each other.
    expect(await seesOnMap(sharerOne, sharerTwo)).toBe(true);
    expect(await seesOnMap(sharerTwo, sharerOne)).toBe(true);

    await blockAndAwaitMirror(sharerOne, sharerTwo);

    // The BLOCKER stops seeing the blocked user...
    expect(await seesOnMap(sharerOne, sharerTwo)).toBe(false);
    // ...and the BLOCKED user stops seeing the blocker, though they never acted.
    expect(await seesOnMap(sharerTwo, sharerOne)).toBe(false);

    // Unblocking restores discovery both ways.
    await unblockAndAwaitMirror(sharerOne, sharerTwo);
    expect(await seesOnMap(sharerOne, sharerTwo)).toBe(true);
    expect(await seesOnMap(sharerTwo, sharerOne)).toBe(true);

    await signInAs(sharerOne);
    await call('live-hideMeNow', {});
    await signInAs(sharerTwo);
    await call('live-hideMeNow', {});
  });

  it('mirrors the block into RTDB so the live-marker read rule can enforce it', async () => {
    // The marker STREAM lives in RTDB, whose rules cannot read Firestore; the
    // liveLocationBlocks node is what database.rules.json checks, in both
    // directions, to deny liveLocation/{uid}/latest.
    const one = await newMember('RtdbOne');
    const two = await newMember('RtdbTwo');
    await blockAndAwaitMirror(one, two);

    const mirrored = await pollUntil(async () => {
      const snap = await adminRtdb.ref(`liveLocationBlocks/${one.uid}/${two.uid}`).get();
      return snap.val() === true ? true : undefined;
    });
    expect(mirrored).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Community chat (the app-wide town square)
// ---------------------------------------------------------------------------

describe('community chat is mutually invisible', () => {
  it('hides each party’s messages from the other, whichever side blocked', async () => {
    const one = await newMember('CommOne');
    const two = await newMember('CommTwo');
    const fromOne = `hello-from-one-${SFX}-${Date.now()}`;
    const fromTwo = `hello-from-two-${SFX}-${Date.now()}`;

    await signInAs(one);
    await call('communityChat-post', { text: fromOne });
    await signInAs(two);
    await call('communityChat-post', { text: fromTwo });

    // Baseline: each sees the other's message.
    await signInAs(one);
    expect(await communityTexts()).toContain(fromTwo);
    await signInAs(two);
    expect(await communityTexts()).toContain(fromOne);

    await blockAndAwaitMirror(one, two);

    // The BLOCKER no longer sees the blocked user's message, but still sees
    // their own (the filter drops the counterparty, not the thread).
    await signInAs(one);
    const oneSees = await communityTexts();
    expect(oneSees).not.toContain(fromTwo);
    expect(oneSees).toContain(fromOne);

    // The BLOCKED user no longer sees the blocker's message either.
    await signInAs(two);
    const twoSees = await communityTexts();
    expect(twoSees).not.toContain(fromOne);
    expect(twoSees).toContain(fromTwo);

    // Unblock restores visibility both ways.
    await unblockAndAwaitMirror(one, two);
    await signInAs(one);
    expect(await communityTexts()).toContain(fromTwo);
    await signInAs(two);
    expect(await communityTexts()).toContain(fromOne);
  });

  it('leaves an unrelated member’s view of both parties untouched', async () => {
    // The filter is per-VIEWER: a block between two members must not remove
    // their messages from anybody else's channel.
    const one = await newMember('BystanderOne');
    const two = await newMember('BystanderTwo');
    const bystander = await newMember('Bystander');
    const fromOne = `bystander-one-${SFX}-${Date.now()}`;
    const fromTwo = `bystander-two-${SFX}-${Date.now()}`;

    await signInAs(one);
    await call('communityChat-post', { text: fromOne });
    await signInAs(two);
    await call('communityChat-post', { text: fromTwo });
    await blockAndAwaitMirror(one, two);

    await signInAs(bystander);
    const seen = await communityTexts();
    expect(seen).toContain(fromOne);
    expect(seen).toContain(fromTwo);
  });
});

// ---------------------------------------------------------------------------
// Convoy chat
// ---------------------------------------------------------------------------

describe('convoy chat is mutually invisible', () => {
  it('hides each party’s messages from the other inside a shared convoy', async () => {
    const owner = await newMember('ConvoyOwner');
    const rider = await newMember('ConvoyRider');
    await makeFriends(owner, rider);

    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [rider.uid] })).data as {
      convoy: { convoyId: string };
    };
    const convoyId = created.convoy.convoyId;
    await signInAs(rider);
    await call('convoy-respond', { convoyId, action: 'accept' });

    const fromOwner = `convoy-owner-${SFX}-${Date.now()}`;
    const fromRider = `convoy-rider-${SFX}-${Date.now()}`;
    await signInAs(owner);
    await call('convoyChat-post', { convoyId, text: fromOwner });
    await signInAs(rider);
    await call('convoyChat-post', { convoyId, text: fromRider });

    // Baseline: both messages visible to both members.
    await signInAs(owner);
    expect(await convoyTexts(convoyId)).toContain(fromRider);
    await signInAs(rider);
    expect(await convoyTexts(convoyId)).toContain(fromOwner);

    // The RIDER blocks the owner — the convoy membership is unaffected, only
    // the mutual visibility inside it.
    await blockAndAwaitMirror(rider, owner);

    await signInAs(rider);
    const riderSees = await convoyTexts(convoyId);
    expect(riderSees).not.toContain(fromOwner);
    expect(riderSees).toContain(fromRider);

    await signInAs(owner);
    const ownerSees = await convoyTexts(convoyId);
    expect(ownerSees).not.toContain(fromRider);
    expect(ownerSees).toContain(fromOwner);

    // Unblock restores both directions.
    await unblockAndAwaitMirror(rider, owner);
    await signInAs(owner);
    expect(await convoyTexts(convoyId)).toContain(fromRider);
    await signInAs(rider);
    expect(await convoyTexts(convoyId)).toContain(fromOwner);
  });
});

// ---------------------------------------------------------------------------
// Direct messages — the whole thread disappears for BOTH parties
// ---------------------------------------------------------------------------

describe('DM threads are hidden and inert for a blocked pair', () => {
  it('removes the conversation from both inboxes and refuses both read callables', async () => {
    const one = await newMember('DmOne');
    const two = await newMember('DmTwo');
    await makeFriends(one, two);

    await signInAs(one);
    const sent = (await call('dm-sendMessage', { toUid: two.uid, text: `dm-hello-${SFX}` }))
      .data as { conversationId: string };
    const conversationId = sent.conversationId;

    // Baseline: the thread is in both inboxes and readable by both.
    await signInAs(one);
    expect(await conversationIds()).toContain(conversationId);
    await signInAs(two);
    expect(await conversationIds()).toContain(conversationId);
    expect(
      ((await call('dm-getMessages', { conversationId })).data as { messages: unknown[] }).messages
        .length,
    ).toBe(1);

    await blockAndAwaitMirror(two, one);

    // BLOCKER side: gone from the inbox, and both read callables answer
    // not-found — the same answer a stranger's conversation id gets, so being
    // blocked is indistinguishable from the thread not existing.
    await signInAs(two);
    expect(await conversationIds()).not.toContain(conversationId);
    expect(await callableErrorCode(call('dm-getMessages', { conversationId }))).toBe(
      'functions/not-found',
    );
    expect(await callableErrorCode(call('dm-markRead', { conversationId }))).toBe(
      'functions/not-found',
    );

    // BLOCKED side: identical treatment, though they never blocked anyone.
    await signInAs(one);
    expect(await conversationIds()).not.toContain(conversationId);
    expect(await callableErrorCode(call('dm-getMessages', { conversationId }))).toBe(
      'functions/not-found',
    );
    expect(await callableErrorCode(call('dm-markRead', { conversationId }))).toBe(
      'functions/not-found',
    );

    // Unblock restores the whole thread, both ways, with its messages intact.
    await unblockAndAwaitMirror(two, one);
    await signInAs(one);
    expect(await conversationIds()).toContain(conversationId);
    expect(
      ((await call('dm-getMessages', { conversationId })).data as { messages: unknown[] }).messages
        .length,
    ).toBe(1);
    await signInAs(two);
    expect(await conversationIds()).toContain(conversationId);
  });

  it('redacts the stored lastMessage preview and clears unread while blocked, restoring on unblock', async () => {
    const sender = await newMember('RedactSender');
    const recipient = await newMember('RedactRecipient');
    await makeFriends(sender, recipient);

    const text = `redact-me-${SFX}-${Date.now()}`;
    await signInAs(sender);
    const sent = (await call('dm-sendMessage', { toUid: recipient.uid, text })).data as {
      conversationId: string;
    };
    const convRef = adminDb.collection('conversations').doc(sent.conversationId);

    // The preview and the recipient's unread are present before the block.
    const before = (await convRef.get()).data() ?? {};
    expect((before.lastMessage as { text?: string } | null)?.text).toBe(text);
    expect((before.unread as Record<string, number>)[recipient.uid]).toBe(1);
    const aggBefore =
      ((await adminDb.collection('userPrivate').doc(recipient.uid).get()).data()
        ?.dmUnreadTotal as number) ?? 0;
    expect(aggBefore).toBeGreaterThanOrEqual(1);

    await blockAndAwaitMirror(recipient, sender);

    // The inbox is a LIST query, so the conversation DOCUMENT is still delivered
    // to a client listener — rules cannot filter a list query per document. What
    // it must no longer carry is the counterparty's message text.
    const redacted = await pollUntil(async () => {
      const data = (await convRef.get()).data() ?? {};
      return data.blockedPair === true ? data : undefined;
    });
    expect(redacted.lastMessage).toBeNull();
    expect((redacted.unread as Record<string, number>)[recipient.uid]).toBe(0);
    expect((redacted.unread as Record<string, number>)[sender.uid]).toBe(0);

    // The owner-only unread aggregate was unwound by exactly what was cleared,
    // so a hidden thread cannot keep the chat badge lit.
    const aggAfter =
      ((await adminDb.collection('userPrivate').doc(recipient.uid).get()).data()
        ?.dmUnreadTotal as number) ?? 0;
    expect(aggAfter).toBe(aggBefore - 1);

    // Unblock re-derives the preview from the newest surviving message. Unread
    // is deliberately NOT restored — by then the message is old news.
    await unblockAndAwaitMirror(recipient, sender);
    const restored = await pollUntil(async () => {
      const data = (await convRef.get()).data() ?? {};
      return data.blockedPair === false ? data : undefined;
    });
    expect((restored.lastMessage as { text?: string } | null)?.text).toBe(text);
  });

  it('denies the messages subcollection at the RULES layer, in both directions', async () => {
    // This is the one chat surface a Firestore rule CAN gate: every message in a
    // conversation shares the same pair, so the block condition is constant
    // across the query and the whole listen is denied rather than the query
    // failing to filter. Asserted with a CLIENT read (rules apply) rather than
    // the Admin SDK (which bypasses them).
    const one = await newMember('RulesOne');
    const two = await newMember('RulesTwo');
    await makeFriends(one, two);

    await signInAs(one);
    const sent = (await call('dm-sendMessage', { toUid: two.uid, text: `rules-${SFX}` })).data as {
      conversationId: string;
    };
    const messagesPath = ['conversations', sent.conversationId, 'messages'] as const;

    // Baseline: both members can read the messages subcollection.
    await signInAs(one);
    expect((await getDocs(collection(firestore, ...messagesPath))).size).toBe(1);
    await signInAs(two);
    expect((await getDocs(collection(firestore, ...messagesPath))).size).toBe(1);

    await blockAndAwaitMirror(one, two);

    // BLOCKER is denied...
    await signInAs(one);
    await expect(getDocs(collection(firestore, ...messagesPath))).rejects.toThrow();
    // ...and so is the BLOCKED user.
    await signInAs(two);
    await expect(getDocs(collection(firestore, ...messagesPath))).rejects.toThrow();

    // Unblock restores the read for both.
    await unblockAndAwaitMirror(one, two);
    await signInAs(one);
    expect((await getDocs(collection(firestore, ...messagesPath))).size).toBe(1);
    await signInAs(two);
    expect((await getDocs(collection(firestore, ...messagesPath))).size).toBe(1);
  });

  it('still refuses to SEND between a blocked pair (unchanged pre-existing gate)', async () => {
    await makeFriends(userA, userB);
    await blockAndAwaitMirror(userA, userB);
    await signInAs(userA);
    expect(
      await callableErrorCode(call('dm-sendMessage', { toUid: userB.uid, text: `nope-${SFX}` })),
    ).toBe('functions/failed-precondition');
    await signInAs(userB);
    expect(
      await callableErrorCode(call('dm-sendMessage', { toUid: userA.uid, text: `nope-${SFX}` })),
    ).toBe('functions/failed-precondition');
    await unblockAndAwaitMirror(userA, userB);
  });
});
