/**
 * Firestore security-rules coverage for the SOCIAL GRAPH and the
 * backend-only collections that the original security-rules emulator suite
 * never touched (Phase 16 rules-coverage audit).
 *
 * WHY A SECOND RULES FILE: `security-rules.emulator.test.ts` covers profiles,
 * events, partners, points-ledger, moderation and the storage/RTDB rules. A
 * migration audit found 38 of the ~83 `match` blocks in
 * `firebase/firestore.rules` had NO rules test at all — including the entire
 * social graph (friends, direct messages, convoys, community chat) and
 * `incidents`. Those blocks are covered here rather than by growing the
 * existing 2 900-line file further.
 *
 * WHAT EACH BLOCK ASSERTS. Every match block below gets, at minimum:
 *   1. unauthenticated read AND write are denied;
 *   2. a signed-in NON-participant read is denied where the data is private;
 *   3. client writes are denied (these collections are callable-only, so the
 *      "server-owned fields" requirement collapses to "no client write at
 *      all" — authorship, counters, moderation state and denormalized
 *      profiles are all forgeable if any write lands);
 *   4. the LEGITIMATE case succeeds — without a positive assertion a rule
 *      that denies everyone would pass vacuously.
 *
 * These tests assert the INTENT stated in each rule's comment ("a
 * non-participant cannot read this DM"), not the shape of the documents.
 *
 * The former suspended convoy-chat read gap is closed by the free-social
 * active-account rules; its regression below now runs. One independent
 * query trap remains outside that change (search "KNOWN TRAP"):
 *      convoys/{convoyId} carries `resource.data.memberUids is list`, and a
 *      type assertion on resource.data is false for every `list` operation, so
 *      NO client query on `convoys` can ever succeed. Fails closed, but the
 *      rule's comment reads as if a roster query were permitted.
 *
 * Requires the Firebase Emulator Suite — see security-rules.emulator.test.ts.
 *
 * DISPLAY NAMES / IDS: the emulator suite shares ONE Firestore instance with
 * no isolation between files, so every uid and displayName seeded here carries
 * an `Rc` suffix (rules-coverage) to avoid colliding with another file's
 * fixtures — in particular the displayName range scans in
 * user-search.emulator.test.ts.
 */

import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collection,
  collectionGroup,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
} from 'firebase/firestore';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, it } from 'vitest';

const FIREBASE_DIR = resolve(__dirname, '../../../firebase');

let testEnv: RulesTestEnvironment;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'demo-test',
    firestore: {
      rules: readFileSync(resolve(FIREBASE_DIR, 'firestore.rules'), 'utf8'),
      host: 'localhost',
      port: 8080,
    },
  });
});

afterAll(async () => {
  await testEnv?.cleanup();
});

/** Future timestamp helper — `expiresAt > request.time` gates several blocks. */
const inOneHour = () => Timestamp.fromMillis(Date.now() + 60 * 60 * 1000);
/** Past timestamp helper — proves the expiry half of a double-gated read. */
const anHourAgo = () => Timestamp.fromMillis(Date.now() - 60 * 60 * 1000);

// ---------------------------------------------------------------------------
// conversations – 1:1 direct messages (PRIORITY 1: the most sensitive block)
// ---------------------------------------------------------------------------

describe('Firestore rules – conversations (direct messages)', () => {
  const ALICE = 'rc-dm-alice';
  const BOB = 'rc-dm-bob';
  const STRANGER = 'rc-dm-stranger';
  const BLOCKER = 'rc-dm-blocker';
  // pairId = the two uids sorted + joined by `__` (dm-core pairId).
  const PAIR = `${ALICE}__${BOB}`;
  const BLOCKED_PAIR = [ALICE, BLOCKER].sort().join('__');
  // A conversation whose `members` is malformed. The DM read gate FAILS CLOSED
  // on this by contract (dmWellFormed) — see the rules comment.
  const BROKEN_PAIR = 'rc-dm-broken';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'conversations', PAIR), {
        members: [ALICE, BOB].sort(),
        lastMessage: { text: 'private thing', senderUid: ALICE },
        unread: { [ALICE]: 0, [BOB]: 1 },
      });
      await setDoc(doc(db, 'conversations', PAIR, 'messages', 'm1'), {
        senderUid: ALICE,
        text: 'a secret',
        createdAt: serverTimestamp(),
      });

      // Alice ↔ Blocker: Blocker has blocked Alice (one direction only).
      await setDoc(doc(db, 'conversations', BLOCKED_PAIR), {
        members: [ALICE, BLOCKER].sort(),
        lastMessage: null,
      });
      await setDoc(doc(db, 'conversations', BLOCKED_PAIR, 'messages', 'm1'), {
        senderUid: BLOCKER,
        text: 'before the block',
        createdAt: serverTimestamp(),
      });
      await setDoc(doc(db, 'userBlocks', BLOCKER, 'blocked', ALICE), {
        blockedDisplayName: 'AliceRc',
        createdAt: serverTimestamp(),
      });

      // Malformed: three members. "whichever isn't me" is ambiguous, so the
      // gate must deny rather than derive a possibly-wrong counterparty.
      await setDoc(doc(db, 'conversations', BROKEN_PAIR), {
        members: [ALICE, BOB, STRANGER],
      });
      await setDoc(doc(db, 'conversations', BROKEN_PAIR, 'messages', 'm1'), {
        senderUid: ALICE,
        text: 'ambiguous',
      });
    });
  });

  it('denies unauthenticated read of a conversation and its messages', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'conversations', PAIR)));
    await assertFails(getDoc(doc(db, 'conversations', PAIR, 'messages', 'm1')));
  });

  it('denies unauthenticated write of a conversation and its messages', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(setDoc(doc(db, 'conversations', 'rc-anon-pair'), { members: [] }));
    await assertFails(setDoc(doc(db, 'conversations', PAIR, 'messages', 'anon'), { text: 'hi' }));
  });

  it('a participant can read their own conversation and its messages', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertSucceeds(getDoc(doc(db, 'conversations', PAIR)));
    await assertSucceeds(getDoc(doc(db, 'conversations', PAIR, 'messages', 'm1')));
    // The inbox is a list query on `members array-contains uid`.
    await assertSucceeds(
      getDocs(query(collection(db, 'conversations'), where('members', 'array-contains', ALICE))),
    );
    // And the thread listener is a list over the messages subcollection.
    await assertSucceeds(getDocs(collection(db, 'conversations', PAIR, 'messages')));
  });

  it('both participants can read — the gate is membership, not authorship', async () => {
    const db = testEnv.authenticatedContext(BOB).firestore();
    await assertSucceeds(getDoc(doc(db, 'conversations', PAIR)));
    await assertSucceeds(getDoc(doc(db, 'conversations', PAIR, 'messages', 'm1')));
  });

  it('A NON-PARTICIPANT CANNOT READ THE CONVERSATION', async () => {
    const db = testEnv.authenticatedContext(STRANGER).firestore();
    await assertFails(getDoc(doc(db, 'conversations', PAIR)));
  });

  it('A NON-PARTICIPANT CANNOT READ THE MESSAGES', async () => {
    const db = testEnv.authenticatedContext(STRANGER).firestore();
    await assertFails(getDoc(doc(db, 'conversations', PAIR, 'messages', 'm1')));
    await assertFails(getDocs(collection(db, 'conversations', PAIR, 'messages')));
  });

  it('a non-participant cannot enumerate the conversations collection', async () => {
    const db = testEnv.authenticatedContext(STRANGER).firestore();
    // No filter at all: the rule depends on resource.data, so an unfiltered
    // list must be refused outright rather than returning other people's rows.
    await assertFails(getDocs(collection(db, 'conversations')));
    // Nor may a stranger name someone else in the array-contains filter.
    await assertFails(
      getDocs(query(collection(db, 'conversations'), where('members', 'array-contains', ALICE))),
    );
  });

  it('an admin cannot read a DM either — no client surveillance path', async () => {
    // conversations has no isAdmin() grant, deliberately: DM content is
    // reachable only through the Admin SDK, which bypasses these rules.
    const db = testEnv.authenticatedContext('rc-dm-admin', { admin: true }).firestore();
    await assertFails(getDoc(doc(db, 'conversations', PAIR)));
    await assertFails(getDoc(doc(db, 'conversations', PAIR, 'messages', 'm1')));
  });

  it('no client may write a conversation or forge a message (callable-only)', async () => {
    const aliceDb = testEnv.authenticatedContext(ALICE).firestore();
    // Not even a participant may author a message directly: dm.sendMessage
    // owns authorship, unread accounting and the dmUnreadTotal aggregate.
    await assertFails(
      setDoc(doc(aliceDb, 'conversations', PAIR, 'messages', 'rc-forged'), {
        senderUid: BOB, // forging another member's authorship
        text: 'not from Bob',
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(aliceDb, 'conversations', PAIR, 'messages', 'rc-own'), {
        senderUid: ALICE,
        text: 'still not allowed',
        createdAt: serverTimestamp(),
      }),
    );
    // Nor may they rewrite the unread counters / lastMessage preview.
    await assertFails(updateDoc(doc(aliceDb, 'conversations', PAIR), { unread: { [BOB]: 0 } }));
    await assertFails(
      updateDoc(doc(aliceDb, 'conversations', PAIR), { lastMessage: { text: 'edited' } }),
    );
    // Nor add themselves to someone else's conversation.
    const strangerDb = testEnv.authenticatedContext(STRANGER).firestore();
    await assertFails(
      updateDoc(doc(strangerDb, 'conversations', PAIR), { members: [ALICE, STRANGER] }),
    );
    await assertFails(
      setDoc(doc(strangerDb, 'conversations', 'rc-self-made'), {
        members: [STRANGER, ALICE],
      }),
    );
    await assertFails(deleteDoc(doc(aliceDb, 'conversations', PAIR, 'messages', 'm1')));
    await assertFails(deleteDoc(doc(aliceDb, 'conversations', PAIR)));
  });

  it('a blocked pair cannot read the thread — in EITHER direction', async () => {
    // Blocker blocked Alice. Blocking is mutual invisibility, so the thread is
    // unreadable for both of them even though both are still members.
    const aliceDb = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(getDoc(doc(aliceDb, 'conversations', BLOCKED_PAIR, 'messages', 'm1')));
    const blockerDb = testEnv.authenticatedContext(BLOCKER).firestore();
    await assertFails(getDoc(doc(blockerDb, 'conversations', BLOCKED_PAIR, 'messages', 'm1')));
  });

  it('the conversation DOC stays readable to a blocked pair (documented)', async () => {
    // Deliberate, and load-bearing: the inbox is a list query and Firestore
    // rules are not filters, so a per-document block gate here would fail the
    // WHOLE inbox of anyone with one blocked thread. The blocked row is dropped
    // by dm.listConversations + the client listener, and the trigger blanks the
    // `lastMessage` preview so the delivered copy carries no counterparty
    // content. If this ever flips to a denial, the inbox breaks — which is
    // exactly what this assertion is here to catch.
    const aliceDb = testEnv.authenticatedContext(ALICE).firestore();
    await assertSucceeds(getDoc(doc(aliceDb, 'conversations', BLOCKED_PAIR)));
  });

  it('a malformed conversation fails CLOSED — messages unreadable', async () => {
    // Three members: the counterparty is ambiguous, so the gate denies rather
    // than resolving "whichever isn't me" to someone not actually blocked.
    for (const uid of [ALICE, BOB, STRANGER]) {
      const db = testEnv.authenticatedContext(uid).firestore();
      await assertFails(getDoc(doc(db, 'conversations', BROKEN_PAIR, 'messages', 'm1')));
    }
  });

  it('messages under a non-existent conversation are unreadable', async () => {
    // dmMembers() get()s the parent; a missing parent must deny, not allow.
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(getDoc(doc(db, 'conversations', 'rc-dm-no-such-pair', 'messages', 'm1')));
  });

  it('no client can scan DMs across conversations — collectionGroup is denied', async () => {
    // Firestore authorises a collection group query only from a rule written
    // against a recursive-wildcard path. The only such rule in this file is the
    // deny-all catch-all, so `collectionGroup('messages')` is denied for
    // everyone. Adding a /{path=**}/messages/… grant for any other chat surface
    // would silently make every DM in the database enumerable — this test is
    // the tripwire for that.
    for (const ctx of [
      testEnv.authenticatedContext(ALICE),
      testEnv.authenticatedContext(STRANGER),
      testEnv.authenticatedContext('rc-dm-admin2', { admin: true }),
    ]) {
      await assertFails(getDocs(collectionGroup(ctx.firestore(), 'messages')));
    }
  });
});

// ---------------------------------------------------------------------------
// friends + friendRequests (PRIORITY 2)
// ---------------------------------------------------------------------------

describe('Firestore rules – friends and friendRequests', () => {
  const OWNER = 'rc-fr-owner';
  const FRIEND = 'rc-fr-friend';
  const STRANGER = 'rc-fr-stranger';
  // Directional request: SENDER -> RECIPIENT. The id is a deterministic hash of
  // the ordered pair in production; any id works for a rules test.
  const SENDER = 'rc-fr-sender';
  const RECIPIENT = 'rc-fr-recipient';
  const REQUEST = 'rc-fr-request';
  const OTHER_REQUEST = 'rc-fr-other-request';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'users', OWNER, 'friends', FRIEND), {
        uid: FRIEND,
        displayName: 'FriendRc',
        avatarPath: null,
        friendedAt: serverTimestamp(),
      });
      await setDoc(doc(db, 'users', FRIEND, 'friends', OWNER), {
        uid: OWNER,
        displayName: 'OwnerRc',
        friendedAt: serverTimestamp(),
      });
      await setDoc(doc(db, 'friendRequests', REQUEST), {
        fromUid: SENDER,
        toUid: RECIPIENT,
        status: 'pending',
        createdAt: serverTimestamp(),
      });
      // A request between two other members entirely.
      await setDoc(doc(db, 'friendRequests', OTHER_REQUEST), {
        fromUid: OWNER,
        toUid: FRIEND,
        status: 'pending',
        createdAt: serverTimestamp(),
      });
    });
  });

  // ---- users/{uid}/friends -------------------------------------------------

  it('denies unauthenticated read and write of a friends list', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'users', OWNER, 'friends', FRIEND)));
    await assertFails(setDoc(doc(db, 'users', OWNER, 'friends', 'rc-anon'), { uid: 'rc-anon' }));
  });

  it('the owner can read their own friends list', async () => {
    const db = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(getDoc(doc(db, 'users', OWNER, 'friends', FRIEND)));
    await assertSucceeds(getDocs(collection(db, 'users', OWNER, 'friends')));
  });

  it('A FRIEND CANNOT READ WHO ELSE THEIR FRIEND IS FRIENDS WITH', async () => {
    // The friend graph never leaks sideways: being friends with OWNER does not
    // grant a read of OWNER's roster. "Friends on a profile" is served by a
    // callable, never by a read of the target's subcollection
    // (MemberFriendCoordinator.kt says so explicitly).
    const db = testEnv.authenticatedContext(FRIEND).firestore();
    await assertFails(getDoc(doc(db, 'users', OWNER, 'friends', FRIEND)));
    await assertFails(getDocs(collection(db, 'users', OWNER, 'friends')));
  });

  it('a stranger cannot read someone else’s friends list', async () => {
    const db = testEnv.authenticatedContext(STRANGER).firestore();
    await assertFails(getDoc(doc(db, 'users', OWNER, 'friends', FRIEND)));
  });

  it('an admin has no client read of a friends list', async () => {
    // The isAdmin() grant on /users/{uid} does NOT cascade into
    // subcollections; friend graphs are Admin-SDK-only.
    const db = testEnv.authenticatedContext('rc-fr-admin', { admin: true }).firestore();
    await assertFails(getDoc(doc(db, 'users', OWNER, 'friends', FRIEND)));
  });

  it('nobody can forge, edit or delete a friendship (callable-only)', async () => {
    // Not even into their OWN list: a self-written row would fabricate a
    // one-sided friendship and bypass the two-sided invariant that
    // friend.respondRequest maintains.
    const ownerDb = testEnv.authenticatedContext(OWNER).firestore();
    await assertFails(
      setDoc(doc(ownerDb, 'users', OWNER, 'friends', STRANGER), {
        uid: STRANGER,
        displayName: 'StrangerRc',
      }),
    );
    await assertFails(
      updateDoc(doc(ownerDb, 'users', OWNER, 'friends', FRIEND), {
        displayName: 'Renamed by client',
      }),
    );
    await assertFails(deleteDoc(doc(ownerDb, 'users', OWNER, 'friends', FRIEND)));
    // And certainly not into someone else's list.
    const strangerDb = testEnv.authenticatedContext(STRANGER).firestore();
    await assertFails(
      setDoc(doc(strangerDb, 'users', OWNER, 'friends', STRANGER), { uid: STRANGER }),
    );
  });

  it('no client can scan the friend graph — collectionGroup is denied', async () => {
    for (const ctx of [
      testEnv.authenticatedContext(OWNER),
      testEnv.authenticatedContext('rc-fr-admin2', { admin: true }),
    ]) {
      await assertFails(getDocs(collectionGroup(ctx.firestore(), 'friends')));
    }
  });

  // ---- friendRequests -----------------------------------------------------

  it('denies unauthenticated read and write of a friend request', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'friendRequests', REQUEST)));
    await assertFails(
      setDoc(doc(db, 'friendRequests', 'rc-anon-req'), { fromUid: 'x', toUid: 'y' }),
    );
  });

  it('the sender and the recipient can each read their own request', async () => {
    const senderDb = testEnv.authenticatedContext(SENDER).firestore();
    await assertSucceeds(getDoc(doc(senderDb, 'friendRequests', REQUEST)));
    const recipientDb = testEnv.authenticatedContext(RECIPIENT).firestore();
    await assertSucceeds(getDoc(doc(recipientDb, 'friendRequests', REQUEST)));
    // The inbox query the Android badge runs.
    await assertSucceeds(
      getDocs(query(collection(recipientDb, 'friendRequests'), where('toUid', '==', RECIPIENT))),
    );
  });

  it('A THIRD PARTY CANNOT READ SOMEONE ELSE’S FRIEND REQUEST', async () => {
    const db = testEnv.authenticatedContext(STRANGER).firestore();
    await assertFails(getDoc(doc(db, 'friendRequests', REQUEST)));
    await assertFails(getDoc(doc(db, 'friendRequests', OTHER_REQUEST)));
  });

  it('a member cannot enumerate friend requests they are not party to', async () => {
    const db = testEnv.authenticatedContext(STRANGER).firestore();
    await assertFails(getDocs(collection(db, 'friendRequests')));
    await assertFails(
      getDocs(query(collection(db, 'friendRequests'), where('toUid', '==', RECIPIENT))),
    );
    // Even a legitimate party to ONE request cannot widen the query to all.
    const senderDb = testEnv.authenticatedContext(SENDER).firestore();
    await assertFails(getDocs(collection(senderDb, 'friendRequests')));
  });

  it('A MEMBER CANNOT FORGE A REQUEST FROM SOMEONE ELSE', async () => {
    const db = testEnv.authenticatedContext(STRANGER).firestore();
    await assertFails(
      setDoc(doc(db, 'friendRequests', 'rc-forged-req'), {
        fromUid: OWNER, // impersonating OWNER
        toUid: FRIEND,
        status: 'pending',
        createdAt: serverTimestamp(),
      }),
    );
  });

  it('a member cannot even create a request in their OWN name (callable-only)', async () => {
    // friend.sendRequest owns nickname resolution, block checks and
    // auto-accept; a direct write would skip every one of them.
    const db = testEnv.authenticatedContext(SENDER).firestore();
    await assertFails(
      setDoc(doc(db, 'friendRequests', 'rc-own-req'), {
        fromUid: SENDER,
        toUid: STRANGER,
        status: 'pending',
        createdAt: serverTimestamp(),
      }),
    );
  });

  it('A MEMBER CANNOT ACCEPT A REQUEST ADDRESSED TO ANOTHER MEMBER', async () => {
    // The whole point of friend.respondRequest: only the recipient may accept,
    // and even they go through the callable. A third party self-accepting would
    // insert themselves into two members' friend lists.
    const strangerDb = testEnv.authenticatedContext(STRANGER).firestore();
    await assertFails(
      updateDoc(doc(strangerDb, 'friendRequests', REQUEST), { status: 'accepted' }),
    );
    // …and neither may the legitimate recipient write the status directly.
    const recipientDb = testEnv.authenticatedContext(RECIPIENT).firestore();
    await assertFails(
      updateDoc(doc(recipientDb, 'friendRequests', REQUEST), { status: 'accepted' }),
    );
    // Nor may the sender flip their own request to accepted.
    const senderDb = testEnv.authenticatedContext(SENDER).firestore();
    await assertFails(updateDoc(doc(senderDb, 'friendRequests', REQUEST), { status: 'accepted' }));
    await assertFails(deleteDoc(doc(senderDb, 'friendRequests', REQUEST)));
  });
});

// ---------------------------------------------------------------------------
// userBlocks + blockVisibility (the block graph the DM gate reads)
// ---------------------------------------------------------------------------

describe('Firestore rules – block graph (userBlocks, blockVisibility)', () => {
  const BLOCKER = 'rc-bl-blocker';
  const BLOCKED = 'rc-bl-blocked';
  const STRANGER = 'rc-bl-stranger';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'userBlocks', BLOCKER, 'blocked', BLOCKED), {
        blockedDisplayName: 'BlockedRc',
        createdAt: serverTimestamp(),
      });
      await setDoc(doc(db, 'blockVisibility', BLOCKER), { hiddenUids: [BLOCKED] });
      await setDoc(doc(db, 'blockVisibility', BLOCKED), { hiddenUids: [BLOCKER] });
    });
  });

  it('denies unauthenticated read and write of the block graph', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'userBlocks', BLOCKER, 'blocked', BLOCKED)));
    await assertFails(getDoc(doc(db, 'blockVisibility', BLOCKER)));
    await assertFails(setDoc(doc(db, 'userBlocks', BLOCKER, 'blocked', 'rc-anon'), { x: 1 }));
    await assertFails(setDoc(doc(db, 'blockVisibility', BLOCKER), { hiddenUids: [] }));
  });

  it('the blocker can read their own block list and visibility mirror', async () => {
    const db = testEnv.authenticatedContext(BLOCKER).firestore();
    await assertSucceeds(getDoc(doc(db, 'userBlocks', BLOCKER, 'blocked', BLOCKED)));
    await assertSucceeds(getDocs(collection(db, 'userBlocks', BLOCKER, 'blocked')));
    await assertSucceeds(getDoc(doc(db, 'blockVisibility', BLOCKER)));
  });

  it('A BLOCK NEVER REVEALS ITSELF: the blocked party cannot read the block row', async () => {
    const db = testEnv.authenticatedContext(BLOCKED).firestore();
    await assertFails(getDoc(doc(db, 'userBlocks', BLOCKER, 'blocked', BLOCKED)));
    // Their own mirror says "this pair is mutually hidden" but never who
    // blocked whom — and they cannot read anyone else's mirror.
    await assertSucceeds(getDoc(doc(db, 'blockVisibility', BLOCKED)));
    await assertFails(getDoc(doc(db, 'blockVisibility', BLOCKER)));
  });

  it('a third party cannot read either side of the block graph', async () => {
    const db = testEnv.authenticatedContext(STRANGER).firestore();
    await assertFails(getDoc(doc(db, 'userBlocks', BLOCKER, 'blocked', BLOCKED)));
    await assertFails(getDoc(doc(db, 'blockVisibility', BLOCKER)));
  });

  it('an admin has no client read of the block graph — no surveillance', async () => {
    const db = testEnv.authenticatedContext('rc-bl-admin', { admin: true }).firestore();
    await assertFails(getDoc(doc(db, 'userBlocks', BLOCKER, 'blocked', BLOCKED)));
    await assertFails(getDoc(doc(db, 'blockVisibility', BLOCKER)));
  });

  it('no client may write the block graph (callable + trigger only)', async () => {
    // A client write to blockVisibility would let a user un-hide themselves
    // from someone who blocked them; a userBlocks write would skip the
    // idempotency / self-block / displayName denormalisation in blocking.block.
    const blockerDb = testEnv.authenticatedContext(BLOCKER).firestore();
    await assertFails(
      setDoc(doc(blockerDb, 'userBlocks', BLOCKER, 'blocked', STRANGER), {
        blockedDisplayName: 'StrangerRc',
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(deleteDoc(doc(blockerDb, 'userBlocks', BLOCKER, 'blocked', BLOCKED)));
    await assertFails(updateDoc(doc(blockerDb, 'blockVisibility', BLOCKER), { hiddenUids: [] }));
    const blockedDb = testEnv.authenticatedContext(BLOCKED).firestore();
    await assertFails(updateDoc(doc(blockedDb, 'blockVisibility', BLOCKED), { hiddenUids: [] }));
  });

  it('no client can scan the block graph — collectionGroup is denied', async () => {
    const ctx = testEnv.authenticatedContext(BLOCKER);
    await assertFails(getDocs(collectionGroup(ctx.firestore(), 'blocked')));
  });
});

// ---------------------------------------------------------------------------
// convoys + convoyChats (PRIORITY 3)
// ---------------------------------------------------------------------------

describe('Firestore rules – convoys and convoyChats', () => {
  const OWNER = 'rc-cv-owner';
  const ACCEPTED = 'rc-cv-accepted';
  const INVITED = 'rc-cv-invited';
  const DECLINED = 'rc-cv-declined';
  const OUTSIDER = 'rc-cv-outsider';
  const CONVOY = 'rc-cv-convoy';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'convoys', CONVOY), {
        ownerUid: OWNER,
        status: 'active',
        memberUids: [OWNER, ACCEPTED, INVITED, DECLINED],
        members: {
          [OWNER]: { role: 'owner', inviteStatus: 'accepted' },
          [ACCEPTED]: { role: 'member', inviteStatus: 'accepted' },
          [INVITED]: { role: 'member', inviteStatus: 'invited' },
          [DECLINED]: { role: 'member', inviteStatus: 'declined' },
        },
        memberProfiles: {
          [OWNER]: { displayName: 'ConvoyOwnerRc' },
        },
      });
      await setDoc(doc(db, 'convoyChats', CONVOY, 'messages', 'm1'), {
        senderUid: OWNER,
        senderDisplayName: 'ConvoyOwnerRc',
        text: 'meet at the roundabout',
        createdAt: serverTimestamp(),
      });
    });
  });

  // ---- convoys/{convoyId} -------------------------------------------------

  it('denies unauthenticated read and write of a convoy', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'convoys', CONVOY)));
    await assertFails(setDoc(doc(db, 'convoys', 'rc-anon-convoy'), { memberUids: [] }));
  });

  it('any convoy member reads the roster — including a still-invited one', async () => {
    // The green-dot roster is the point: an invitee sees who else was invited
    // and each member's inviteStatus. This is a per-document GET, which is the
    // only convoy read path the client has (FirebaseConvoyRepository attaches a
    // snapshot listener to convoys/{convoyId}; the management screen goes
    // through the convoy-list CALLABLE) — see the list test below for why.
    for (const uid of [OWNER, ACCEPTED, INVITED, DECLINED]) {
      const db = testEnv.authenticatedContext(uid, { activeMember: true }).firestore();
      await assertSucceeds(getDoc(doc(db, 'convoys', CONVOY)));
    }
  });

  /**
   * KNOWN TRAP, PINNED DELIBERATELY (2026-07-30). No client can run ANY query
   * against `convoys` — not even a member filtering on their own uid.
   *
   * The read rule carries `resource.data.memberUids is list`, and Firestore's
   * rules engine cannot evaluate a TYPE ASSERTION on `resource.data` for a
   * `list` operation: the term is false for every query, so even
   * `where('memberUids','array-contains', myUid)` is denied
   * ("false for 'list' @ …"). Bisected against the live emulator: dropping the
   * `is list` term makes the same query succeed, and value comparisons
   * (`uid in resource.data.members`, `status == 'active'`) are fine for `list`
   * — it is specifically the type assertion.
   *
   * It FAILS CLOSED, so this is not an exposure, and nothing is broken today
   * because the client never queries the collection. It is pinned here because
   * the rule's own comment says "Any member of the convoy … reads it", which
   * reads as if a roster query were permitted, and the next person to add a
   * client-side convoy list will get an opaque PERMISSION_DENIED.
   *
   * The `is list` term is also REDUNDANT for security: verified on the emulator
   * that with the term removed, a convoy whose `memberUids` is a string or is
   * absent is STILL denied, because `in` on a non-list raises an error and an
   * error denies. `resource.data.memberUids != null` or
   * `resource.data.keys().hasAll(['memberUids'])` would keep the shape guard
   * AND work for `list`.
   *
   * IF YOU INTENTIONALLY CHANGE THAT TERM, invert this test to assertSucceeds.
   */
  it('no client can QUERY convoys at all — the `is list` guard denies every list', async () => {
    const ownerDb = testEnv.authenticatedContext(OWNER, { activeMember: true }).firestore();
    await assertFails(
      getDocs(query(collection(ownerDb, 'convoys'), where('memberUids', 'array-contains', OWNER))),
    );
  });

  it('A NON-MEMBER CANNOT READ THE CONVOY’S MEMBER LIST', async () => {
    // memberUids + memberProfiles are the roster, and once ended the doc also
    // carries the route summary — none of it may leak to a third party.
    const db = testEnv.authenticatedContext(OUTSIDER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(db, 'convoys', CONVOY)));
    await assertFails(getDocs(collection(db, 'convoys')));
    await assertFails(
      getDocs(query(collection(db, 'convoys'), where('memberUids', 'array-contains', OWNER))),
    );
  });

  it('a malformed convoy fails CLOSED — a non-list memberUids is unreadable', async () => {
    // The documented intent of the shape guard, which DOES hold for a get():
    // "whichever member array this is" must never resolve into a grant.
    const BROKEN = 'rc-cv-broken';
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'convoys', BROKEN), {
        ownerUid: OWNER,
        status: 'active',
        memberUids: OWNER, // a bare string, not a list
        members: { [OWNER]: { role: 'owner', inviteStatus: 'accepted' } },
      });
    });
    const db = testEnv.authenticatedContext(OWNER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(db, 'convoys', BROKEN)));
  });

  it('a SUSPENDED member loses convoy read access even while in memberUids', async () => {
    const db = testEnv
      .authenticatedContext(ACCEPTED, { activeMember: true, suspended: true })
      .firestore();
    await assertFails(getDoc(doc(db, 'convoys', CONVOY)));
  });

  it('no client may create a convoy, forge membership, or fake a summary', async () => {
    const outsiderDb = testEnv.authenticatedContext(OUTSIDER, { activeMember: true }).firestore();
    // Self-join by rewriting memberUids.
    await assertFails(
      updateDoc(doc(outsiderDb, 'convoys', CONVOY), {
        memberUids: [OWNER, ACCEPTED, INVITED, DECLINED, OUTSIDER],
      }),
    );
    await assertFails(
      setDoc(doc(outsiderDb, 'convoys', 'rc-self-convoy'), {
        ownerUid: OUTSIDER,
        status: 'forming',
        memberUids: [OUTSIDER],
        members: { [OUTSIDER]: { role: 'owner', inviteStatus: 'accepted' } },
      }),
    );
    const ownerDb = testEnv.authenticatedContext(OWNER, { activeMember: true }).firestore();
    // Even the owner cannot promote a pending invite to accepted, flip the
    // lifecycle status, or write the server-computed summary.
    await assertFails(
      updateDoc(doc(ownerDb, 'convoys', CONVOY), {
        members: {
          [OWNER]: { role: 'owner', inviteStatus: 'accepted' },
          [INVITED]: { role: 'member', inviteStatus: 'accepted' },
        },
      }),
    );
    await assertFails(updateDoc(doc(ownerDb, 'convoys', CONVOY), { status: 'ended' }));
    await assertFails(
      updateDoc(doc(ownerDb, 'convoys', CONVOY), { summary: { distanceKm: 9999 } }),
    );
    await assertFails(deleteDoc(doc(ownerDb, 'convoys', CONVOY)));
    // And an invitee cannot self-accept by writing their own members entry.
    const invitedDb = testEnv.authenticatedContext(INVITED, { activeMember: true }).firestore();
    await assertFails(
      updateDoc(doc(invitedDb, 'convoys', CONVOY), {
        [`members.${INVITED}.inviteStatus`]: 'accepted',
      }),
    );
  });

  // ---- convoyChats/{convoyId}/messages ------------------------------------

  it('denies unauthenticated read and write of a convoy chat', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'convoyChats', CONVOY, 'messages', 'm1')));
    await assertFails(
      setDoc(doc(db, 'convoyChats', CONVOY, 'messages', 'rc-anon'), { text: 'hi' }),
    );
  });

  it('an ACCEPTED member can read the convoy chat', async () => {
    for (const uid of [OWNER, ACCEPTED]) {
      const db = testEnv.authenticatedContext(uid, { activeMember: true }).firestore();
      await assertSucceeds(getDoc(doc(db, 'convoyChats', CONVOY, 'messages', 'm1')));
      await assertSucceeds(getDocs(collection(db, 'convoyChats', CONVOY, 'messages')));
    }
  });

  it('A NON-MEMBER CANNOT READ THE CONVOY CHAT', async () => {
    const db = testEnv.authenticatedContext(OUTSIDER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(db, 'convoyChats', CONVOY, 'messages', 'm1')));
    await assertFails(getDocs(collection(db, 'convoyChats', CONVOY, 'messages')));
  });

  it('an INVITED-but-not-accepted member cannot read the convoy chat', async () => {
    const db = testEnv.authenticatedContext(INVITED, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(db, 'convoyChats', CONVOY, 'messages', 'm1')));
  });

  it('a DECLINED member cannot read the convoy chat', async () => {
    const db = testEnv.authenticatedContext(DECLINED, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(db, 'convoyChats', CONVOY, 'messages', 'm1')));
  });

  it('a convoy chat with no parent convoy is unreadable', async () => {
    const db = testEnv.authenticatedContext(OWNER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(db, 'convoyChats', 'rc-cv-no-such-convoy', 'messages', 'm1')));
  });

  // Restricted accounts lose convoy chat reads even while still accepted.
  it('a SUSPENDED accepted member should lose convoy chat read access', async () => {
    const db = testEnv
      .authenticatedContext(ACCEPTED, { activeMember: true, suspended: true })
      .firestore();
    await assertFails(getDoc(doc(db, 'convoyChats', CONVOY, 'messages', 'm1')));
  });

  it('no client may post, edit or delete a convoy chat message', async () => {
    const ownerDb = testEnv.authenticatedContext(OWNER, { activeMember: true }).firestore();
    // Authorship and the denormalized sender profile are server-owned:
    // convoyChat.post re-checks accepted membership and stamps them.
    await assertFails(
      setDoc(doc(ownerDb, 'convoyChats', CONVOY, 'messages', 'rc-own'), {
        senderUid: OWNER,
        senderDisplayName: 'ConvoyOwnerRc',
        text: 'direct write',
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(ownerDb, 'convoyChats', CONVOY, 'messages', 'rc-forged'), {
        senderUid: ACCEPTED, // forged authorship
        text: 'not from them',
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      updateDoc(doc(ownerDb, 'convoyChats', CONVOY, 'messages', 'm1'), { text: 'edited' }),
    );
    await assertFails(deleteDoc(doc(ownerDb, 'convoyChats', CONVOY, 'messages', 'm1')));
    // An outsider cannot inject into a convoy they are not in.
    const outsiderDb = testEnv.authenticatedContext(OUTSIDER, { activeMember: true }).firestore();
    await assertFails(
      setDoc(doc(outsiderDb, 'convoyChats', CONVOY, 'messages', 'rc-inject'), {
        senderUid: OUTSIDER,
        text: 'hello strangers',
      }),
    );
  });
});

// ---------------------------------------------------------------------------
// communityChat (PRIORITY 4)
// ---------------------------------------------------------------------------

describe('Firestore rules – communityChat', () => {
  const MEMBER = 'rc-cc-member';
  const SUSPENDED = 'rc-cc-suspended';
  const CHANNEL = 'global';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'communityChat', CHANNEL), { name: 'Community' });
      await setDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-m1'), {
        senderUid: MEMBER,
        senderDisplayName: 'CommunityMemberRc',
        text: 'hej alla',
        mentionedUids: [],
        createdAt: serverTimestamp(),
      });
    });
  });

  it('denies unauthenticated read and write of the channel and its messages', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'communityChat', CHANNEL)));
    await assertFails(getDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-m1')));
    await assertFails(getDocs(collection(db, 'communityChat', CHANNEL, 'messages')));
    await assertFails(setDoc(doc(db, 'communityChat', 'rc-anon-channel'), { name: 'x' }));
    await assertFails(
      setDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-anon'), { text: 'hi' }),
    );
  });

  it('a member can read the channel and its messages', async () => {
    const db = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertSucceeds(getDoc(doc(db, 'communityChat', CHANNEL)));
    await assertSucceeds(getDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-m1')));
    await assertSucceeds(getDocs(collection(db, 'communityChat', CHANNEL, 'messages')));
  });

  it('A SUSPENDED MEMBER CANNOT READ THE COMMUNITY CHAT', async () => {
    // isActiveMember() folds in isNotSuspended(): suspension always overrides
    // feature access, entitlement claim or not.
    const db = testEnv
      .authenticatedContext(SUSPENDED, { activeMember: true, suspended: true })
      .firestore();
    await assertFails(getDoc(doc(db, 'communityChat', CHANNEL)));
    await assertFails(getDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-m1')));
    await assertFails(getDocs(collection(db, 'communityChat', CHANNEL, 'messages')));
  });

  it('A SUSPENDED MEMBER CANNOT WRITE TO THE COMMUNITY CHAT', async () => {
    const db = testEnv
      .authenticatedContext(SUSPENDED, { activeMember: true, suspended: true })
      .firestore();
    await assertFails(
      setDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-susp'), {
        senderUid: SUSPENDED,
        text: 'let me back in',
        createdAt: serverTimestamp(),
      }),
    );
  });

  it('NO client may post — and mentionedUids in particular is unforgeable', async () => {
    // mentionedUids drives the @mention notification producer, so a client able
    // to write it could notify (or spam) anyone at will. It is server-validated
    // by communityChat.post; the rules must admit no client write at all.
    const db = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertFails(
      setDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-mention'), {
        senderUid: MEMBER,
        senderDisplayName: 'CommunityMemberRc',
        text: 'ping',
        mentionedUids: ['rc-cc-victim-1', 'rc-cc-victim-2'],
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      updateDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-m1'), {
        mentionedUids: ['rc-cc-victim-1'],
      }),
    );
    // Nor forge another member's authorship or edit/delete history.
    await assertFails(
      setDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-forged'), {
        senderUid: 'rc-cc-someone-else',
        senderDisplayName: 'Someone Else',
        text: 'impersonation',
      }),
    );
    await assertFails(
      updateDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-m1'), { text: 'edited' }),
    );
    await assertFails(deleteDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-m1')));
    await assertFails(updateDoc(doc(db, 'communityChat', CHANNEL), { name: 'renamed' }));
  });

  it('an admin has no client write path to the community chat either', async () => {
    const db = testEnv.authenticatedContext('rc-cc-admin', { admin: true }).firestore();
    await assertFails(
      setDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-admin-post'), {
        senderUid: 'rc-cc-admin',
        text: 'announcement',
      }),
    );
    await assertFails(deleteDoc(doc(db, 'communityChat', CHANNEL, 'messages', 'rc-m1')));
  });
});

// ---------------------------------------------------------------------------
// incidents (PRIORITY 5)
// ---------------------------------------------------------------------------

describe('Firestore rules – incidents', () => {
  const REPORTER = 'rc-in-reporter';
  const OTHER = 'rc-in-other';
  const ACTIVE = 'rc-in-active';
  const EXPIRED = 'rc-in-expired';
  const REMOVED = 'rc-in-removed';
  const MIGRATED_IMPORT = 'tv_rc-in-migrated';
  const LEGACY_IMPORT = 'tv_rc-in-legacy';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'incidents', ACTIVE), {
        type: 'police',
        status: 'active',
        reportedBy: REPORTER,
        source: 'member',
        expiresAt: inOneHour(),
        confirmationCount: 1,
        clearedCount: 0,
        location: { latitude: 57.49, longitude: 12.07 },
      });
      await setDoc(doc(db, 'incidents', EXPIRED), {
        type: 'police',
        status: 'active',
        reportedBy: REPORTER,
        source: 'member',
        expiresAt: anHourAgo(),
      });
      await setDoc(doc(db, 'incidents', REMOVED), {
        type: 'roadwork',
        status: 'removed',
        reportedBy: REPORTER,
        source: 'trafikverket',
        expiresAt: inOneHour(),
      });
      await setDoc(doc(db, 'incidentSyncMetadata', 'trafikverket'), {
        source: 'trafikverket',
        freshUntil: inOneHour(),
      });
      await setDoc(doc(db, 'incidents', MIGRATED_IMPORT), {
        type: 'roadwork',
        status: 'active',
        source: 'trafikverket',
        expiresAt: Timestamp.fromMillis(Date.UTC(2100, 0, 1)),
        importFingerprintVersion: 1,
      });
      // Rollout bridge: no fingerprint means an old rolling-TTL import.
      await setDoc(doc(db, 'incidents', LEGACY_IMPORT), {
        type: 'roadwork',
        status: 'active',
        source: 'trafikverket',
        expiresAt: inOneHour(),
      });
      // The one-per-user vote ledgers. clearVotes stores the voter's POSITION
      // as proof of presence — no other member may read that.
      await setDoc(doc(db, 'incidents', ACTIVE, 'confirmations', OTHER), {
        userId: OTHER,
        createdAt: serverTimestamp(),
      });
      await setDoc(doc(db, 'incidents', ACTIVE, 'clearVotes', OTHER), {
        userId: OTHER,
        location: { latitude: 57.49, longitude: 12.07 },
        createdAt: serverTimestamp(),
      });
    });
  });

  it('denies unauthenticated read and write of incidents', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'incidents', ACTIVE)));
    await assertFails(getDocs(collection(db, 'incidents')));
    await assertFails(setDoc(doc(db, 'incidents', 'rc-anon-incident'), { status: 'active' }));
  });

  it('ANY signed-in user reads an ACTIVE, unexpired incident (shared map layer)', async () => {
    // Deliberately not member-gated: the Waze-style layer is visible to all.
    for (const ctx of [
      testEnv.authenticatedContext(REPORTER),
      testEnv.authenticatedContext(OTHER),
      // A free (non-entitled) account still sees the map layer.
      testEnv.authenticatedContext('rc-in-free', { activeMember: false }),
    ]) {
      await assertSucceeds(getDoc(doc(ctx.firestore(), 'incidents', ACTIVE)));
    }
  });

  it('an EXPIRED incident is hidden immediately, before the sweep deletes it', async () => {
    const db = testEnv.authenticatedContext(OTHER).firestore();
    await assertFails(getDoc(doc(db, 'incidents', EXPIRED)));
    // Including from the reporter who filed it.
    const reporterDb = testEnv.authenticatedContext(REPORTER).firestore();
    await assertFails(getDoc(doc(reporterDb, 'incidents', EXPIRED)));
  });

  it('a non-active incident is hidden', async () => {
    const db = testEnv.authenticatedContext(OTHER).firestore();
    await assertFails(getDoc(doc(db, 'incidents', REMOVED)));
  });

  it('requires fresh metadata for migrated imports while preserving the legacy rollout bridge', async () => {
    const reader = testEnv.authenticatedContext(OTHER).firestore();
    await assertSucceeds(getDoc(doc(reader, 'incidents', MIGRATED_IMPORT)));

    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'incidentSyncMetadata', 'trafikverket'), {
        source: 'trafikverket',
        freshUntil: anHourAgo(),
      });
    });
    await assertFails(getDoc(doc(reader, 'incidents', MIGRATED_IMPORT)));
    await assertSucceeds(getDoc(doc(reader, 'incidents', LEGACY_IMPORT)));
    // Crowd-sourced liveness remains independent of importer freshness.
    await assertSucceeds(getDoc(doc(reader, 'incidents', ACTIVE)));

    // Restore freshness so this fixture cannot perturb later assertions.
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'incidentSyncMetadata', 'trafikverket'), {
        source: 'trafikverket',
        freshUntil: inOneHour(),
      });
    });
  });

  it('keeps Trafikverket sync metadata backend-only', async () => {
    const db = testEnv.authenticatedContext(OTHER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(db, 'incidentSyncMetadata', 'trafikverket')));
    await assertFails(
      setDoc(doc(db, 'incidentSyncMetadata', 'trafikverket'), { freshUntil: inOneHour() }),
    );
  });

  it('an unfiltered list of incidents is denied (the rule is not a filter)', async () => {
    // Mixed statuses/expiries exist, so an unfiltered scan must fail rather
    // than silently dropping the hidden rows.
    const db = testEnv.authenticatedContext(OTHER).firestore();
    await assertFails(getDocs(collection(db, 'incidents')));
  });

  it('NOBODY MAY CREATE AN INCIDENT FROM A CLIENT', async () => {
    const db = testEnv.authenticatedContext(REPORTER, { activeMember: true }).firestore();
    await assertFails(
      setDoc(doc(db, 'incidents', 'rc-in-selfmade'), {
        type: 'police',
        status: 'active',
        reportedBy: REPORTER,
        source: 'member',
        expiresAt: inOneHour(),
      }),
    );
    // Spoofing the Trafikverket source would give a member's report the
    // authority of the official feed.
    await assertFails(
      setDoc(doc(db, 'incidents', 'rc-in-spoofed-source'), {
        type: 'roadwork',
        status: 'active',
        reportedBy: REPORTER,
        source: 'trafikverket',
        expiresAt: inOneHour(),
      }),
    );
    // Forging another member's authorship.
    await assertFails(
      setDoc(doc(db, 'incidents', 'rc-in-forged'), {
        type: 'police',
        status: 'active',
        reportedBy: OTHER,
        source: 'member',
        expiresAt: inOneHour(),
      }),
    );
  });

  it('A MEMBER CANNOT MUTATE SOMEONE ELSE’S REPORT', async () => {
    const db = testEnv.authenticatedContext(OTHER, { activeMember: true }).firestore();
    // Take a live hazard off everyone's map…
    await assertFails(updateDoc(doc(db, 'incidents', ACTIVE), { status: 'removed' }));
    // …extend its life…
    await assertFails(
      updateDoc(doc(db, 'incidents', ACTIVE), {
        expiresAt: Timestamp.fromMillis(Date.now() + 30 * 24 * 60 * 60 * 1000),
      }),
    );
    // …inflate the server-owned vote counters…
    await assertFails(updateDoc(doc(db, 'incidents', ACTIVE), { confirmationCount: 999 }));
    await assertFails(updateDoc(doc(db, 'incidents', ACTIVE), { clearedCount: 999 }));
    await assertFails(updateDoc(doc(db, 'incidents', ACTIVE), { reportedCleared: true }));
    // …or delete it outright.
    await assertFails(deleteDoc(doc(db, 'incidents', ACTIVE)));
  });

  it('the REPORTER cannot mutate their own report either (callable-only)', async () => {
    // incidents.remove owns removal so it stays audited and rate-limited.
    const db = testEnv.authenticatedContext(REPORTER, { activeMember: true }).firestore();
    await assertFails(updateDoc(doc(db, 'incidents', ACTIVE), { status: 'removed' }));
    await assertFails(updateDoc(doc(db, 'incidents', ACTIVE), { confirmationCount: 50 }));
    await assertFails(deleteDoc(doc(db, 'incidents', ACTIVE)));
  });

  it('an admin has no client write path to incidents', async () => {
    const db = testEnv.authenticatedContext('rc-in-admin', { admin: true }).firestore();
    await assertFails(updateDoc(doc(db, 'incidents', ACTIVE), { status: 'removed' }));
    await assertFails(deleteDoc(doc(db, 'incidents', ACTIVE)));
  });

  it('the confirm / clear vote ledgers are invisible and unwritable to clients', async () => {
    // The sub-collections are deliberately NOT matched, so the deny-all
    // catch-all governs them. A member must not enumerate who voted what,
    // forge a vote, or read the voter POSITIONS stored in clearVotes.
    for (const ctx of [
      testEnv.authenticatedContext(REPORTER, { activeMember: true }),
      testEnv.authenticatedContext(OTHER, { activeMember: true }),
      testEnv.authenticatedContext('rc-in-admin2', { admin: true }),
    ]) {
      const db = ctx.firestore();
      await assertFails(getDoc(doc(db, 'incidents', ACTIVE, 'confirmations', OTHER)));
      await assertFails(getDocs(collection(db, 'incidents', ACTIVE, 'confirmations')));
      await assertFails(getDoc(doc(db, 'incidents', ACTIVE, 'clearVotes', OTHER)));
      await assertFails(getDocs(collection(db, 'incidents', ACTIVE, 'clearVotes')));
    }
    const voterDb = testEnv.authenticatedContext(OTHER, { activeMember: true }).firestore();
    await assertFails(
      setDoc(doc(voterDb, 'incidents', ACTIVE, 'confirmations', OTHER), {
        userId: OTHER,
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(voterDb, 'incidents', ACTIVE, 'clearVotes', OTHER), {
        userId: OTHER,
        location: { latitude: 0, longitude: 0 },
      }),
    );
    // And no collection-group scan across incidents.
    await assertFails(getDocs(collectionGroup(voterDb, 'clearVotes')));
  });
});

// ---------------------------------------------------------------------------
// userLifecycle – activity signals that must not surface on the public profile
// ---------------------------------------------------------------------------

describe('Firestore rules – userLifecycle', () => {
  const OWNER = 'rc-ul-owner';
  const OTHER = 'rc-ul-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'userLifecycle', OWNER), {
        lastLoginAt: serverTimestamp(),
        inactivityWarnedAt: null,
        inactivityDeleteAfter: null,
      });
    });
  });

  it('denies unauthenticated read and write', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'userLifecycle', OWNER)));
    await assertFails(setDoc(doc(db, 'userLifecycle', OWNER), { lastLoginAt: null }));
  });

  it('owner and admin may read; another member may not', async () => {
    await assertSucceeds(
      getDoc(doc(testEnv.authenticatedContext(OWNER).firestore(), 'userLifecycle', OWNER)),
    );
    await assertSucceeds(
      getDoc(
        doc(
          testEnv.authenticatedContext('rc-ul-admin', { admin: true }).firestore(),
          'userLifecycle',
          OWNER,
        ),
      ),
    );
    // lastLoginAt is exactly the signal kept off the public users/{uid} doc.
    await assertFails(
      getDoc(doc(testEnv.authenticatedContext(OTHER).firestore(), 'userLifecycle', OWNER)),
    );
  });

  it('a suspended admin loses the admin read (suspension overrides)', async () => {
    const db = testEnv
      .authenticatedContext('rc-ul-suspadmin', { admin: true, suspended: true })
      .firestore();
    await assertFails(getDoc(doc(db, 'userLifecycle', OWNER)));
  });

  it('NO client write — the owner cannot defer their own inactivity deletion', async () => {
    const ownerDb = testEnv.authenticatedContext(OWNER).firestore();
    await assertFails(
      updateDoc(doc(ownerDb, 'userLifecycle', OWNER), {
        inactivityDeleteAfter: Timestamp.fromMillis(Date.now() + 10 ** 11),
      }),
    );
    await assertFails(
      updateDoc(doc(ownerDb, 'userLifecycle', OWNER), { lastLoginAt: serverTimestamp() }),
    );
    await assertFails(deleteDoc(doc(ownerDb, 'userLifecycle', OWNER)));
    // Not even an admin: every write is a trusted Admin SDK write.
    const adminDb = testEnv.authenticatedContext('rc-ul-admin2', { admin: true }).firestore();
    await assertFails(
      updateDoc(doc(adminDb, 'userLifecycle', OWNER), { inactivityWarnedAt: null }),
    );
  });
});

// ---------------------------------------------------------------------------
// Crown Hunt / auto-spawn blocks
// ---------------------------------------------------------------------------

describe('Firestore rules – crown spawns and claims', () => {
  const MEMBER = 'rc-cs-member';
  const OTHER = 'rc-cs-other';
  const LIVE = 'rc-cs-live';
  const EXPIRED_SPAWN = 'rc-cs-expired';
  const COLLECTED = 'rc-cs-collected';
  const CLAIM = 'rc-cs-claim';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'crownSpawns', LIVE), {
        status: 'live',
        expiresAt: inOneHour(),
        location: { latitude: 57.49, longitude: 12.07 },
        cellKey: 'rc-cell',
      });
      await setDoc(doc(db, 'crownSpawns', EXPIRED_SPAWN), {
        status: 'live',
        expiresAt: anHourAgo(),
        location: { latitude: 57.49, longitude: 12.07 },
      });
      await setDoc(doc(db, 'crownSpawns', COLLECTED), {
        status: 'collected',
        expiresAt: inOneHour(),
      });
      await setDoc(doc(db, 'crownSpawnCells', 'rc-cell'), { approved: true });
      await setDoc(doc(db, 'crownCellActivity', 'rc-cell'), { recentCount: 3 });
      await setDoc(doc(db, 'crownCellActivity', 'rc-cell', 'recentUsers', 'rc-hash'), {
        lastSeenAt: serverTimestamp(),
      });
      await setDoc(doc(db, 'crownSpawnClaims', CLAIM), {
        userId: MEMBER,
        spawnId: LIVE,
        points: 10,
      });
      await setDoc(doc(db, 'crownSpawnClaimRisk', CLAIM), { score: 0.9, reason: 'teleport' });
      await setDoc(doc(db, 'crownSpawnDailyClaims', `${MEMBER}_2026-07-30`), { count: 2 });
      await setDoc(doc(db, 'crownHuntDailyClaims', `${MEMBER}_2026-07-30`), { count: 1 });
      await setDoc(doc(db, 'crownHuntAwardGuards', 'rc-guard'), { awarded: true });
    });
  });

  it('denies unauthenticated read and write of spawns', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'crownSpawns', LIVE)));
    await assertFails(setDoc(doc(db, 'crownSpawns', 'rc-anon'), { status: 'live' }));
  });

  it('a member reads a LIVE, unexpired spawn; an expired or collected one is hidden', async () => {
    const db = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertSucceeds(getDoc(doc(db, 'crownSpawns', LIVE)));
    await assertFails(getDoc(doc(db, 'crownSpawns', EXPIRED_SPAWN)));
    await assertFails(getDoc(doc(db, 'crownSpawns', COLLECTED)));
  });

  it('a suspended member cannot read spawns', async () => {
    const db = testEnv
      .authenticatedContext(MEMBER, { activeMember: true, suspended: true })
      .firestore();
    await assertFails(getDoc(doc(db, 'crownSpawns', LIVE)));
  });

  it('no client may create a spawn or mark one collected', async () => {
    const db = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertFails(
      setDoc(doc(db, 'crownSpawns', 'rc-cs-selfmade'), {
        status: 'live',
        expiresAt: inOneHour(),
        location: { latitude: 0, longitude: 0 },
      }),
    );
    await assertFails(updateDoc(doc(db, 'crownSpawns', LIVE), { status: 'collected' }));
    await assertFails(updateDoc(doc(db, 'crownSpawns', LIVE), { expiresAt: inOneHour() }));
    await assertFails(deleteDoc(doc(db, 'crownSpawns', LIVE)));
  });

  it('the spawn-cell ALLOW-LIST is admin-only and never member-readable', async () => {
    // Exposing "approved but currently empty" would tell a client exactly where
    // to wait for the next spawn.
    const memberDb = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(memberDb, 'crownSpawnCells', 'rc-cell')));
    await assertFails(getDocs(collection(memberDb, 'crownSpawnCells')));
    const adminDb = testEnv.authenticatedContext('rc-cs-admin', { admin: true }).firestore();
    await assertSucceeds(getDoc(doc(adminDb, 'crownSpawnCells', 'rc-cell')));
    // Approval is a callable so it is always audited — no client write, admin
    // included.
    await assertFails(updateDoc(doc(adminDb, 'crownSpawnCells', 'rc-cell'), { approved: false }));
  });

  it('a member reads only their OWN spawn claim; admins read any', async () => {
    const ownerDb = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertSucceeds(getDoc(doc(ownerDb, 'crownSpawnClaims', CLAIM)));
    const otherDb = testEnv.authenticatedContext(OTHER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(otherDb, 'crownSpawnClaims', CLAIM)));
    const adminDb = testEnv.authenticatedContext('rc-cs-admin2', { admin: true }).firestore();
    await assertSucceeds(getDoc(doc(adminDb, 'crownSpawnClaims', CLAIM)));
    // Claims are minted by the callable; a client write would mint points.
    await assertFails(
      setDoc(doc(ownerDb, 'crownSpawnClaims', 'rc-cs-selfclaim'), {
        userId: MEMBER,
        spawnId: LIVE,
        points: 9999,
      }),
    );
    await assertFails(updateDoc(doc(ownerDb, 'crownSpawnClaims', CLAIM), { points: 9999 }));
  });

  it('anti-fraud, activity and daily-counter docs are closed to every client', async () => {
    // Returning a risk score would tell an abuser which signal tripped;
    // a writable daily counter would reset their own cap.
    const paths: Array<[string, string]> = [
      ['crownSpawnClaimRisk', CLAIM],
      ['crownCellActivity', 'rc-cell'],
      ['crownSpawnDailyClaims', `${MEMBER}_2026-07-30`],
      ['crownHuntDailyClaims', `${MEMBER}_2026-07-30`],
      ['crownHuntAwardGuards', 'rc-guard'],
    ];
    for (const ctx of [
      testEnv.unauthenticatedContext(),
      testEnv.authenticatedContext(MEMBER, { activeMember: true }),
      testEnv.authenticatedContext('rc-cs-admin3', { admin: true }),
    ]) {
      const db = ctx.firestore();
      for (const [coll, id] of paths) {
        await assertFails(getDoc(doc(db, coll, id)));
        await assertFails(setDoc(doc(db, coll, id), { tampered: true }));
      }
      await assertFails(getDoc(doc(db, 'crownCellActivity', 'rc-cell', 'recentUsers', 'rc-hash')));
      await assertFails(
        setDoc(doc(db, 'crownCellActivity', 'rc-cell', 'recentUsers', 'rc-hash'), { x: 1 }),
      );
    }
  });
});

// ---------------------------------------------------------------------------
// Points economy: owner-readable streaks + backend-only counters
// ---------------------------------------------------------------------------

describe('Firestore rules – points economy state', () => {
  const OWNER = 'rc-pt-owner';
  const OTHER = 'rc-pt-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'pointsStreaks', OWNER), { currentStreak: 4, longestStreak: 9 });
      await setDoc(doc(db, 'pointsDailyTotals', `${OWNER}_2026-07-30`), { total: 40 });
      await setDoc(doc(db, 'pointsWeeklyDriving', `${OWNER}_2026-W31`), { km: 120 });
      await setDoc(doc(db, 'pointsRuleCounters', `${OWNER}_drive`), { count: 3 });
      await setDoc(doc(db, 'pointsLedgerFolds', 'rc-fold'), { folded: true });
      await setDoc(doc(db, 'pointsEconomyRateLimit', `${OWNER}_1`), { count: 1 });
    });
  });

  it('denies unauthenticated access to points state', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'pointsStreaks', OWNER)));
    await assertFails(setDoc(doc(db, 'pointsStreaks', OWNER), { currentStreak: 100 }));
  });

  it('the owner reads their own streak; nobody else does', async () => {
    await assertSucceeds(
      getDoc(doc(testEnv.authenticatedContext(OWNER).firestore(), 'pointsStreaks', OWNER)),
    );
    await assertFails(
      getDoc(doc(testEnv.authenticatedContext(OTHER).firestore(), 'pointsStreaks', OWNER)),
    );
    // Not even an admin from a client — no leaderboard-by-stealth.
    await assertFails(
      getDoc(
        doc(
          testEnv.authenticatedContext('rc-pt-admin', { admin: true }).firestore(),
          'pointsStreaks',
          OWNER,
        ),
      ),
    );
  });

  it('the owner cannot inflate their own streak', async () => {
    const db = testEnv.authenticatedContext(OWNER).firestore();
    await assertFails(updateDoc(doc(db, 'pointsStreaks', OWNER), { currentStreak: 365 }));
    await assertFails(deleteDoc(doc(db, 'pointsStreaks', OWNER)));
  });

  it('every points counter / fold / throttle doc is closed to clients', async () => {
    const paths: Array<[string, string]> = [
      ['pointsDailyTotals', `${OWNER}_2026-07-30`],
      ['pointsWeeklyDriving', `${OWNER}_2026-W31`],
      ['pointsRuleCounters', `${OWNER}_drive`],
      ['pointsLedgerFolds', 'rc-fold'],
      ['pointsEconomyRateLimit', `${OWNER}_1`],
    ];
    for (const ctx of [
      testEnv.unauthenticatedContext(),
      testEnv.authenticatedContext(OWNER, { activeMember: true }),
      testEnv.authenticatedContext('rc-pt-admin2', { admin: true }),
    ]) {
      const db = ctx.firestore();
      for (const [coll, id] of paths) {
        await assertFails(getDoc(doc(db, coll, id)));
        // A writable daily cap or rate-limit window is a mintable one.
        await assertFails(setDoc(doc(db, coll, id), { total: 0, count: 0 }));
      }
    }
  });
});

// ---------------------------------------------------------------------------
// eventAttendance + its risk / count ledgers
// ---------------------------------------------------------------------------

describe('Firestore rules – event attendance', () => {
  const OWNER = 'rc-ea-owner';
  const OTHER = 'rc-ea-other';
  const EVENT = 'rc-ea-event';
  const ATTENDANCE = 'rc-ea-attendance';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'eventAttendance', ATTENDANCE), {
        userId: OWNER,
        eventId: EVENT,
        confirmedAt: serverTimestamp(),
      });
      await setDoc(doc(db, 'eventAttendanceRisk', ATTENDANCE), { score: 0.8, reason: 'distance' });
      await setDoc(doc(db, 'eventAttendanceCounts', EVENT), { attended: 12 });
      await setDoc(doc(db, 'eventAttendanceCounts', EVENT, 'counted', OWNER), {
        countedAt: serverTimestamp(),
      });
    });
  });

  it('denies unauthenticated read and write of an attendance record', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'eventAttendance', ATTENDANCE)));
    await assertFails(
      setDoc(doc(db, 'eventAttendance', 'rc-ea-anon'), { userId: OWNER, eventId: EVENT }),
    );
  });

  it('the attendee reads their own record; a stranger cannot', async () => {
    await assertSucceeds(
      getDoc(doc(testEnv.authenticatedContext(OWNER).firestore(), 'eventAttendance', ATTENDANCE)),
    );
    await assertFails(
      getDoc(doc(testEnv.authenticatedContext(OTHER).firestore(), 'eventAttendance', ATTENDANCE)),
    );
  });

  it('an admin reads any attendance record', async () => {
    const db = testEnv.authenticatedContext('rc-ea-admin', { admin: true }).firestore();
    await assertSucceeds(getDoc(doc(db, 'eventAttendance', ATTENDANCE)));
  });

  it('A MEMBER CANNOT SELF-AWARD ATTENDANCE AT AN EVENT THEY DID NOT ATTEND', async () => {
    // Attendance is the input to points + badges, so a forged record mints
    // both. There is no `allow write` anywhere in the block, by design.
    const db = testEnv.authenticatedContext(OTHER, { activeMember: true }).firestore();
    await assertFails(
      setDoc(doc(db, 'eventAttendance', 'rc-ea-forged'), {
        userId: OTHER,
        eventId: EVENT,
        confirmedAt: serverTimestamp(),
      }),
    );
    const ownerDb = testEnv.authenticatedContext(OWNER, { activeMember: true }).firestore();
    await assertFails(
      setDoc(doc(ownerDb, 'eventAttendance', 'rc-ea-own'), {
        userId: OWNER,
        eventId: 'rc-ea-never-went',
        confirmedAt: serverTimestamp(),
      }),
    );
    await assertFails(
      updateDoc(doc(ownerDb, 'eventAttendance', ATTENDANCE), { eventId: 'rc-ea-other-event' }),
    );
    await assertFails(deleteDoc(doc(ownerDb, 'eventAttendance', ATTENDANCE)));
  });

  it('the risk score and the count ledgers are closed to every client', async () => {
    for (const ctx of [
      testEnv.unauthenticatedContext(),
      testEnv.authenticatedContext(OWNER, { activeMember: true }),
      testEnv.authenticatedContext('rc-ea-admin2', { admin: true }),
    ]) {
      const db = ctx.firestore();
      await assertFails(getDoc(doc(db, 'eventAttendanceRisk', ATTENDANCE)));
      await assertFails(setDoc(doc(db, 'eventAttendanceRisk', ATTENDANCE), { score: 0 }));
      await assertFails(getDoc(doc(db, 'eventAttendanceCounts', EVENT)));
      await assertFails(setDoc(doc(db, 'eventAttendanceCounts', EVENT), { attended: 0 }));
      await assertFails(getDoc(doc(db, 'eventAttendanceCounts', EVENT, 'counted', OWNER)));
      await assertFails(
        setDoc(doc(db, 'eventAttendanceCounts', EVENT, 'counted', OWNER), { x: 1 }),
      );
    }
  });
});

// ---------------------------------------------------------------------------
// Admin-only private records + backend-only throttles
// ---------------------------------------------------------------------------

describe('Firestore rules – admin-only records and backend-only throttles', () => {
  const MEMBER = 'rc-mo-member';
  const REPORT = 'rc-mo-feedback';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'moderationUserSummaries', MEMBER), {
        reportCount: 2,
        lastReportedAt: serverTimestamp(),
      });
      await setDoc(doc(db, 'feedbackReports', REPORT), {
        reportedBy: MEMBER,
        text: 'the map is blank',
        githubIssueNumber: 42,
      });
      await setDoc(doc(db, 'signInIssueLinks', 'rc-mo-fingerprint'), {
        issueNumber: 7,
        issueUrl: 'https://example.invalid/7',
        count: 3,
      });
      await setDoc(doc(db, 'incidentListRateLimits', `${MEMBER}_1`), { count: 5 });
      await setDoc(doc(db, 'incidentClearRateLimits', `${MEMBER}_1`), { count: 2 });
      await setDoc(doc(db, 'incidentClearVoteRisk', 'rc-mo-vote'), { score: 0.7 });
      await setDoc(doc(db, 'memberSearchRateLimits', `${MEMBER}_1`), { count: 9 });
      await setDoc(doc(db, 'liveSessions', MEMBER), {
        geoCell: 'rc-cell',
        location: { latitude: 57.49, longitude: 12.07 },
        expiresAt: inOneHour(),
      });
    });
  });

  it('moderationUserSummaries is admin-read, client-write-never', async () => {
    await assertFails(
      getDoc(doc(testEnv.unauthenticatedContext().firestore(), 'moderationUserSummaries', MEMBER)),
    );
    // A member must not learn they have been reported, nor how often.
    await assertFails(
      getDoc(
        doc(
          testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore(),
          'moderationUserSummaries',
          MEMBER,
        ),
      ),
    );
    const adminDb = testEnv.authenticatedContext('rc-mo-admin', { admin: true }).firestore();
    await assertSucceeds(getDoc(doc(adminDb, 'moderationUserSummaries', MEMBER)));
    await assertFails(
      updateDoc(doc(adminDb, 'moderationUserSummaries', MEMBER), { reportCount: 0 }),
    );
  });

  it('feedbackReports is admin-read; the reporter cannot read or file directly', async () => {
    // The record carries the reporter's uid alongside the text that was filed
    // publicly on GitHub without it — a direct client create would bypass that
    // separation.
    const memberDb = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(memberDb, 'feedbackReports', REPORT)));
    await assertFails(
      setDoc(doc(memberDb, 'feedbackReports', 'rc-mo-direct'), {
        reportedBy: MEMBER,
        text: 'bypassing the callable',
      }),
    );
    const adminDb = testEnv.authenticatedContext('rc-mo-admin2', { admin: true }).firestore();
    await assertSucceeds(getDoc(doc(adminDb, 'feedbackReports', REPORT)));
    await assertFails(updateDoc(doc(adminDb, 'feedbackReports', REPORT), { githubIssueNumber: 1 }));
  });

  it('signInIssueLinks is admin-read; no client may forge a dedup link', async () => {
    // The trigger writes it via the Admin SDK. A client write could point a
    // real sign-in fingerprint at an attacker-chosen GitHub issue.
    await assertFails(
      getDoc(
        doc(testEnv.unauthenticatedContext().firestore(), 'signInIssueLinks', 'rc-mo-fingerprint'),
      ),
    );
    const memberDb = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(memberDb, 'signInIssueLinks', 'rc-mo-fingerprint')));
    await assertFails(
      setDoc(doc(memberDb, 'signInIssueLinks', 'rc-mo-forged'), { issueNumber: 1 }),
    );
    const adminDb = testEnv.authenticatedContext('rc-mo-admin4', { admin: true }).firestore();
    await assertSucceeds(getDoc(doc(adminDb, 'signInIssueLinks', 'rc-mo-fingerprint')));
    await assertFails(
      updateDoc(doc(adminDb, 'signInIssueLinks', 'rc-mo-fingerprint'), { issueNumber: 99 }),
    );
  });

  it('a suspended admin loses the admin reads on these private records', async () => {
    const db = testEnv
      .authenticatedContext('rc-mo-suspadmin', { admin: true, suspended: true })
      .firestore();
    await assertFails(getDoc(doc(db, 'moderationUserSummaries', MEMBER)));
    await assertFails(getDoc(doc(db, 'feedbackReports', REPORT)));
    await assertFails(getDoc(doc(db, 'signInIssueLinks', 'rc-mo-fingerprint')));
  });

  it('rate-limit windows, vote-risk scores and liveSessions are fully closed', async () => {
    // A member must not inspect or RESET their own throttle, read the anti-fraud
    // score that rejected their vote, or listen to the live-sharer discovery
    // index (which would leak every nearby sharer's coordinate with none of the
    // self / blocked / expired exclusions live.listNearby applies).
    const paths: Array<[string, string]> = [
      ['incidentListRateLimits', `${MEMBER}_1`],
      ['incidentClearRateLimits', `${MEMBER}_1`],
      ['incidentClearVoteRisk', 'rc-mo-vote'],
      ['memberSearchRateLimits', `${MEMBER}_1`],
      ['liveSessions', MEMBER],
    ];
    for (const ctx of [
      testEnv.unauthenticatedContext(),
      testEnv.authenticatedContext(MEMBER, { activeMember: true }),
      testEnv.authenticatedContext('rc-mo-admin3', { admin: true }),
    ]) {
      const db = ctx.firestore();
      for (const [coll, id] of paths) {
        await assertFails(getDoc(doc(db, coll, id)));
        await assertFails(setDoc(doc(db, coll, id), { count: 0 }));
        await assertFails(deleteDoc(doc(db, coll, id)));
      }
      // Not even their own liveSessions row, and no scan of the index.
      await assertFails(getDocs(collection(db, 'liveSessions')));
    }
  });
});
