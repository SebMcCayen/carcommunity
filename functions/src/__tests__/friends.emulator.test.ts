/**
 * Friends (friend-graph) emulator integration tests.
 *
 * Exercises the deployed-in-emulator callables end-to-end plus the
 * friendRequests + users/{uid}/friends Firestore rules:
 * - `friend-sendRequest` (member gating, self, nickname not-found / ambiguous,
 *   already-friends, already-requested, blocked-either-way, reverse auto-accept)
 * - `friend-respondRequest` (accept writes both friendships, decline, guards)
 * - `friend-remove` (idempotent two-sided delete)
 * - `friend-list` (friends + incoming/outgoing pending requests)
 * - rules: owner-only read of friends + own requests, no client writes.
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
import {
  REASON_ALREADY_FRIENDS,
  REASON_AMBIGUOUS_NICKNAME,
  REASON_NICKNAME_NOT_FOUND,
  REASON_NOT_ADDABLE,
  REASON_REQUEST_ALREADY_SENT,
  REASON_SELF_REQUEST,
  friendRequestId,
  toSearchKey,
} from '../friends/friends-core';
import { NICKNAME_SCAN_LIMIT } from '../friends/manageFriends';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'friends-emulator-tests');
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

/**
 * The `details.reason` discriminator of a failed callable, or 'no-error'. The
 * CODE alone cannot identify a failure (already-exists covers two outcomes,
 * failed-precondition covers two more), so the client maps on this — which
 * makes it a contract these tests must pin.
 */
async function callableErrorReason(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
    return 'no-error';
  } catch (error) {
    if (error instanceof FirebaseError) {
      return (error as unknown as { details?: { reason?: unknown } }).details?.reason;
    }
    throw error;
  }
}

let userSeq = 0;

async function newMember(displayName: string): Promise<TestUser> {
  userSeq += 1;
  const email = `friend-${userSeq}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
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
    // displayNameLower mirrors what the real write paths (auth/provisioning.ts,
    // auth/onboarding-core.ts) persist alongside displayName. Nickname
    // resolution queries ONLY this key, so a helper that wrote displayName
    // alone would make every seeded member unfindable.
    .set({ activeMember: true, displayName, displayNameLower: toSearchKey(displayName) }, { merge: true });
  return { uid, email, password };
}

async function newFreeUser(): Promise<TestUser> {
  userSeq += 1;
  const email = `friend-free-${userSeq}-${Date.now()}@example.com`;
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

async function friendshipExists(ownerUid: string, friendUid: string): Promise<boolean> {
  const snap = await adminDb
    .collection('users')
    .doc(ownerUid)
    .collection('friends')
    .doc(friendUid)
    .get();
  return snap.exists;
}

/** Status of a directional friendRequests doc, or undefined when it doesn't exist. */
async function requestStatus(fromUid: string, toUid: string): Promise<string | undefined> {
  const snap = await adminDb
    .collection('friendRequests')
    .doc(friendRequestId(fromUid, toUid))
    .get();
  return snap.exists ? (snap.data()?.status as string | undefined) : undefined;
}

/** Seeds a pending directional friendRequests doc (used to simulate the mutual-send race). */
async function seedPendingRequest(from: TestUser, to: TestUser, fromName: string, toName: string): Promise<void> {
  await adminDb
    .collection('friendRequests')
    .doc(friendRequestId(from.uid, to.uid))
    .set({
      fromUid: from.uid,
      toUid: to.uid,
      status: 'pending',
      fromDisplayName: fromName,
      fromAvatarPath: null,
      toDisplayName: toName,
      toAvatarPath: null,
      createdAt: new Date(),
      updatedAt: new Date(),
    });
}

/**
 * Seeds `count` restricted (suspended) users/{uid} docs that all share the same
 * displayName. No auth account is created — nickname resolution only reads the
 * user documents by displayName — so this cheaply fills the raw scan page with
 * rows that get filtered out, used to exercise the limit-before-filter guard.
 */
async function seedRestrictedProfiles(displayName: string, count: number): Promise<void> {
  await Promise.all(
    Array.from({ length: count }, (_, i) =>
      adminDb
        .collection('users')
        .doc(`restricted-${displayName}-${i}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`)
        .set({ displayName, displayNameLower: toSearchKey(displayName), suspended: true }),
    ),
  );
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'friends-emulator-client',
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

describe('friend-sendRequest gating + resolution', () => {
  it('rejects unauthenticated callers', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('friend-list', {}))).toBe('functions/unauthenticated');
  });

  it('admits a non-member while member gating is disabled', async () => {
    // Was: permission-denied on the member gate. Now the call gets PAST the
    // gate and fails on the real reason — no such nickname. Seb's "Something
    // went wrong" on a friend request was this gate.
    const free = await newFreeUser();
    await signInAs(free);
    expect(await callableErrorCode(call('friend-sendRequest', { nickname: 'Nobody' }))).toBe(
      'functions/not-found',
    );
  });

  it('STILL rejects a suspended caller', async () => {
    const suspended = await newFreeUser();
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
    expect(await callableErrorCode(call('friend-sendRequest', { nickname: 'Nobody' }))).toBe(
      'functions/permission-denied',
    );
  });

  it('rejects self and unknown nicknames', async () => {
    const alice = await newMember('Alice');
    await signInAs(alice);
    // Self by resolved uid → invalid-argument.
    expect(await callableErrorCode(call('friend-sendRequest', { toUid: alice.uid }))).toBe(
      'functions/invalid-argument',
    );
    // No such nickname → not-found.
    expect(
      await callableErrorCode(call('friend-sendRequest', { nickname: 'DoesNotExistXYZ' })),
    ).toBe('functions/not-found');
    // Neither/both fields → invalid-argument.
    expect(await callableErrorCode(call('friend-sendRequest', {}))).toBe('functions/invalid-argument');
  });

  it('returns AMBIGUOUS_NICKNAME with candidates when a nickname is shared', async () => {
    const searcher = await newMember('Searcher');
    await newMember('SharedNick');
    await newMember('SharedNick');
    await signInAs(searcher);
    try {
      await call('friend-sendRequest', { nickname: 'SharedNick' });
      throw new Error('expected ambiguous error');
    } catch (error) {
      expect(error).toBeInstanceOf(FirebaseError);
      const fe = error as FirebaseError & { details?: { reason?: string; candidates?: unknown[] } };
      expect(fe.code).toBe('functions/failed-precondition');
      expect(fe.details?.reason).toBe('AMBIGUOUS_NICKNAME');
      expect(Array.isArray(fe.details?.candidates)).toBe(true);
      expect(fe.details?.candidates?.length).toBe(2);
    }
  });

  it('rejects a request to a suspended target the same as a deleted/unknown one', async () => {
    const sender = await newMember('SenderSusp');
    const suspended = await newMember('SuspendedTarget');
    // Restrict the target AFTER creation (suspended, not deleted).
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });

    await signInAs(sender);
    // By resolved uid → not-found (neutral; never reveals the account exists or
    // is suspended), exactly as for a soft-deleted or nonexistent user.
    expect(await callableErrorCode(call('friend-sendRequest', { toUid: suspended.uid }))).toBe(
      'functions/not-found',
    );
    // By nickname, when the ONLY match is suspended → not-found (no request can
    // be created for an account respondRequest's accept guard would reject).
    expect(
      await callableErrorCode(call('friend-sendRequest', { nickname: 'SuspendedTarget' })),
    ).toBe('functions/not-found');
  });

  it('excludes suspended users from nickname resolution candidates', async () => {
    const searcher = await newMember('NickSearcher');
    const active = await newMember('SharedNickSusp');
    const suspended = await newMember('SharedNickSusp');
    // Suspend one of the two shared-nickname holders; only the active one
    // remains a resolvable candidate, so resolution is no longer ambiguous.
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });

    await signInAs(searcher);
    const sent = (await call('friend-sendRequest', { nickname: 'SharedNickSusp' })).data as {
      status: string;
      request: { otherUser: { uid: string } };
    };
    expect(sent.status).toBe('requested');
    expect(sent.request.otherUser.uid).toBe(active.uid);
    expect(sent.request.otherUser.uid).not.toBe(suspended.uid);
  });

  it('returns the self error (not not-found) when the caller enters their OWN nickname', async () => {
    const solo = await newMember('SoloSelfNick');
    await signInAs(solo);
    // By nickname, the only match is the caller themselves. The caller is
    // filtered out, but this must surface the dedicated self error
    // (invalid-argument, mirroring the by-uid self case) rather than a generic
    // not-found — you can't friend yourself.
    expect(await callableErrorCode(call('friend-sendRequest', { nickname: 'SoloSelfNick' }))).toBe(
      'functions/invalid-argument',
    );
  });

  it('returns AMBIGUOUS_NICKNAME when a saturated page of restricted same-name accounts could hide active matches', async () => {
    const searcher = await newMember('SatSearcher');
    const nickname = 'SaturatedNick';
    // One genuinely addressable active member holds the shared nickname...
    await newMember(nickname);
    // ...plus enough restricted (suspended) same-name accounts to fill the raw
    // scan page. Because Firestore applies `.limit()` BEFORE the restricted
    // filter, the page can be packed with these filtered-out rows — previously
    // leaving a single active row that was mistaken for a UNIQUE match (a
    // request sent to the wrong person) or a false not-found. With a saturated
    // page uniqueness is unprovable, so resolution must degrade to ambiguous.
    await seedRestrictedProfiles(nickname, NICKNAME_SCAN_LIMIT);

    await signInAs(searcher);
    expect(await callableErrorCode(call('friend-sendRequest', { nickname }))).toBe(
      'functions/failed-precondition',
    );
  });
});

/**
 * Seb's v0.8.0 report: a member named 'Gt86_swe' could not be found by
 * searching 'gt86_swe' (resolution was an exact, CASE-SENSITIVE match on
 * `displayName`), and 'gt86' found nobody either. These pin the matching
 * contract the module KDoc now states — including what is deliberately NOT
 * matched, so the capability can't be silently overclaimed later.
 */
describe('friend-sendRequest nickname matching (case-insensitive + prefix)', () => {
  it('resolves a nickname typed in ANY case', async () => {
    const target = await newMember('Gt86_swe');
    const searcher = await newMember('CaseSearcher');
    await signInAs(searcher);

    // Seb typed 'gt86_swe' for a member named 'Gt86_swe' and got not-found.
    for (const typed of ['gt86_swe', 'GT86_SWE', 'Gt86_Swe', '  gt86_swe  ']) {
      const sent = (await call('friend-sendRequest', { nickname: typed })).data as {
        status: string;
        request: { toUid: string };
      };
      expect(sent.status).toBe('requested');
      expect(sent.request.toUid).toBe(target.uid);
      // Clear the pending request so each casing exercises a fresh resolution
      // rather than tripping the already-sent guard.
      await adminDb.collection('friendRequests').doc(friendRequestId(searcher.uid, target.uid)).delete();
    }
  });

  // NOTE (test isolation): every emulator test file shares ONE Firestore, and
  // resolution now matches by PREFIX — so display names must be unique by
  // PREFIX, not merely distinct. Reusing 'Gt86_*' here would make these searches
  // ambiguous against the case test's 'Gt86_swe' above. Hence the deliberately
  // unrelated 'Zqx99_*' / 'Wvy77_*' stems.
  it('resolves a PREFIX of a nickname (Seb: typing "zqx99" must find "Zqx99_swe")', async () => {
    const target = await newMember('Zqx99_swe');
    const searcher = await newMember('PrefixSearcher');
    await signInAs(searcher);

    const sent = (await call('friend-sendRequest', { nickname: 'zqx99' })).data as {
      status: string;
      request: { toUid: string };
    };
    expect(sent.status).toBe('requested');
    expect(sent.request.toUid).toBe(target.uid);
  });

  it('does NOT match a mid-word or trailing substring (documented non-capability)', async () => {
    await newMember('Wvy77_swe');
    const searcher = await newMember('SubSearcher');
    await signInAs(searcher);

    // Firestore has no substring/contains operator: matching is prefix-only.
    // If this ever starts passing, the module KDoc's "NOT matched" claim and the
    // errorNotFound copy ("try the first few letters") are both wrong.
    for (const typed of ['77_swe', 'vy77_swe', '_swe']) {
      expect(await callableErrorCode(call('friend-sendRequest', { nickname: typed }))).toBe(
        'functions/not-found',
      );
    }
  });

  it('prefers an EXACT match over longer prefix matches instead of asking to disambiguate', async () => {
    // 'exactnick' is an exact match; 'exactnickextra' also starts with it.
    const exact = await newMember('ExactNick');
    await newMember('ExactNickExtra');
    const searcher = await newMember('ExactSearcher');
    await signInAs(searcher);

    const sent = (await call('friend-sendRequest', { nickname: 'exactnick' })).data as {
      status: string;
      request: { toUid: string };
    };
    expect(sent.status).toBe('requested');
    expect(sent.request.toUid).toBe(exact.uid);
  });

  it('returns AMBIGUOUS_NICKNAME with candidates when a PREFIX matches several members', async () => {
    await newMember('PfxAmbigOne');
    await newMember('PfxAmbigTwo');
    const searcher = await newMember('PfxAmbigSearcher');
    await signInAs(searcher);

    try {
      await call('friend-sendRequest', { nickname: 'pfxambig' });
      throw new Error('expected AMBIGUOUS_NICKNAME');
    } catch (error) {
      const details = (error as unknown as { details?: { reason?: string; candidates?: unknown[] } })
        .details;
      expect(details?.reason).toBe(REASON_AMBIGUOUS_NICKNAME);
      // The client re-calls with a resolved { toUid } from this list.
      expect(details?.candidates?.length).toBe(2);
    }
  });
});


/**
 * Every sendRequest failure must carry a `details.reason`. The Android client
 * maps on it to render a SPECIFIC message; without it, distinct outcomes
 * collapse into one hedged string (already-friends vs request-already-sent) or
 * into the generic "Something went wrong".
 */
describe('friend-sendRequest failure reasons', () => {
  it('tags not-found, self, already-sent and already-friends distinctly', async () => {
    const target = await newMember('ReasonTarget');
    const caller = await newMember('ReasonCaller');
    await signInAs(caller);

    expect(await callableErrorReason(call('friend-sendRequest', { nickname: 'NoSuchNickReason' })))
      .toBe(REASON_NICKNAME_NOT_FOUND);
    expect(await callableErrorReason(call('friend-sendRequest', { nickname: 'ReasonCaller' })))
      .toBe(REASON_SELF_REQUEST);
    expect(await callableErrorReason(call('friend-sendRequest', { toUid: caller.uid })))
      .toBe(REASON_SELF_REQUEST);

    // First send succeeds; the second is a DISTINCT outcome from already-friends.
    await call('friend-sendRequest', { nickname: 'ReasonTarget' });
    expect(await callableErrorReason(call('friend-sendRequest', { nickname: 'ReasonTarget' })))
      .toBe(REASON_REQUEST_ALREADY_SENT);

    // Accept, then re-send → now it IS already-friends.
    await signInAs(target);
    const listed = (await call('friend-list', {})).data as { incoming: { requestId: string }[] };
    await call('friend-respondRequest', { requestId: listed.incoming[0]!.requestId, action: 'accept' });
    await signInAs(caller);
    expect(await callableErrorReason(call('friend-sendRequest', { nickname: 'ReasonTarget' })))
      .toBe(REASON_ALREADY_FRIENDS);
  });

  it('uses the SAME opaque NOT_ADDABLE reason in both block directions', async () => {
    // PRIVACY: the blocked party must never be able to tell that they were
    // blocked, nor which side blocked. Both directions must be indistinguishable
    // in code, message AND reason — otherwise the client could infer it.
    const blocker = await newMember('BlockReasonA');
    const blocked = await newMember('BlockReasonB');
    await adminDb.collection('userBlocks').doc(blocker.uid).collection('blocked').doc(blocked.uid).set({
      createdAt: new Date(),
    });

    // The BLOCKED party sends to the blocker (they must learn nothing).
    await signInAs(blocked);
    const blockedSideReason = await callableErrorReason(
      call('friend-sendRequest', { toUid: blocker.uid }),
    );
    const blockedSideCode = await callableErrorCode(call('friend-sendRequest', { toUid: blocker.uid }));

    // The BLOCKER sends to the party they blocked.
    await signInAs(blocker);
    const blockerSideReason = await callableErrorReason(
      call('friend-sendRequest', { toUid: blocked.uid }),
    );
    const blockerSideCode = await callableErrorCode(call('friend-sendRequest', { toUid: blocked.uid }));

    expect(blockedSideReason).toBe(REASON_NOT_ADDABLE);
    expect(blockerSideReason).toBe(REASON_NOT_ADDABLE);
    expect(blockedSideReason).toBe(blockerSideReason);
    expect(blockedSideCode).toBe(blockerSideCode);
  });
});

describe('friend request lifecycle', () => {
  it('sends by nickname, accepts, lists, and removes', async () => {
    const alice = await newMember('AliceLC');
    const bob = await newMember('BobLC');

    await signInAs(alice);
    const sent = (await call('friend-sendRequest', { nickname: 'BobLC' })).data as {
      status: string;
      request: { requestId: string; direction: string; otherUser: { uid: string } };
    };
    expect(sent.status).toBe('requested');
    expect(sent.request.requestId).toBe(friendRequestId(alice.uid, bob.uid));
    expect(sent.request.direction).toBe('outgoing');
    expect(sent.request.otherUser.uid).toBe(bob.uid);

    // Duplicate outgoing request → already-exists.
    expect(await callableErrorCode(call('friend-sendRequest', { toUid: bob.uid }))).toBe(
      'functions/already-exists',
    );

    // Alice sees the outgoing pending request.
    const aliceList = (await call('friend-list', {})).data as {
      friends: unknown[];
      incoming: unknown[];
      outgoing: Array<{ otherUser: { uid: string } }>;
    };
    expect(aliceList.outgoing.map((r) => r.otherUser.uid)).toContain(bob.uid);

    // Bob sees it as incoming, then accepts.
    await signInAs(bob);
    const bobList = (await call('friend-list', {})).data as {
      incoming: Array<{ requestId: string; otherUser: { uid: string } }>;
    };
    expect(bobList.incoming.map((r) => r.otherUser.uid)).toContain(alice.uid);

    const accepted = (await call('friend-respondRequest', {
      requestId: friendRequestId(alice.uid, bob.uid),
      action: 'accept',
    })).data as { status: string; friend: { uid: string } };
    expect(accepted.status).toBe('accepted');
    expect(accepted.friend.uid).toBe(alice.uid);

    // Both sides now have a friendship doc.
    expect(await friendshipExists(alice.uid, bob.uid)).toBe(true);
    expect(await friendshipExists(bob.uid, alice.uid)).toBe(true);

    // Both list each other as friends.
    const bobFriends = (await call('friend-list', {})).data as {
      friends: Array<{ uid: string }>;
    };
    expect(bobFriends.friends.map((f) => f.uid)).toContain(alice.uid);

    // Alice removes Bob → both sides gone (idempotent second call → removed:false).
    await signInAs(alice);
    const removed = (await call('friend-remove', { friendUid: bob.uid })).data as {
      removed: boolean;
    };
    expect(removed.removed).toBe(true);
    expect(await friendshipExists(alice.uid, bob.uid)).toBe(false);
    expect(await friendshipExists(bob.uid, alice.uid)).toBe(false);
    const removedAgain = (await call('friend-remove', { friendUid: bob.uid })).data as {
      removed: boolean;
    };
    expect(removedAgain.removed).toBe(false);
  });

  it('declines a request', async () => {
    const carol = await newMember('CarolLC');
    const dave = await newMember('DaveLC');
    await signInAs(carol);
    await call('friend-sendRequest', { toUid: dave.uid });

    await signInAs(dave);
    const carolDaveRequestId = friendRequestId(carol.uid, dave.uid);
    const declined = (await call('friend-respondRequest', {
      requestId: carolDaveRequestId,
      action: 'decline',
    })).data as { status: string };
    expect(declined.status).toBe('declined');
    expect(await friendshipExists(carol.uid, dave.uid)).toBe(false);

    // A handled request can't be responded to again.
    expect(
      await callableErrorCode(
        call('friend-respondRequest', { requestId: carolDaveRequestId, action: 'accept' }),
      ),
    ).toBe('functions/failed-precondition');

    // A non-recipient cannot respond (not-found, never permission-denied).
    await signInAs(carol);
    expect(
      await callableErrorCode(
        call('friend-respondRequest', { requestId: carolDaveRequestId, action: 'accept' }),
      ),
    ).toBe('functions/not-found');
  });

  it('rejects accept when the requester became restricted, writing no friendship', async () => {
    const rob = await newMember('RobLC');
    const sue = await newMember('SueLC');

    await signInAs(rob);
    await call('friend-sendRequest', { toUid: sue.uid });

    // Rob is soft-deleted/suspended after sending but before Sue accepts.
    await adminDb.collection('users').doc(rob.uid).set({ suspended: true }, { merge: true });

    await signInAs(sue);
    expect(
      await callableErrorCode(
        call('friend-respondRequest', {
          requestId: friendRequestId(rob.uid, sue.uid),
          action: 'accept',
        }),
      ),
    ).toBe('functions/failed-precondition');

    // No friendship doc was created on either side.
    expect(await friendshipExists(rob.uid, sue.uid)).toBe(false);
    expect(await friendshipExists(sue.uid, rob.uid)).toBe(false);
  });

  it('auto-befriends when the reverse request is already pending', async () => {
    const ivy = await newMember('IvyLC');
    const jack = await newMember('JackLC');

    await signInAs(ivy);
    await call('friend-sendRequest', { toUid: jack.uid });

    // Jack sends back → immediate friendship rather than a stacked request.
    await signInAs(jack);
    const result = (await call('friend-sendRequest', { toUid: ivy.uid })).data as {
      status: string;
      friend?: { uid: string };
    };
    expect(result.status).toBe('friends');
    expect(result.friend?.uid).toBe(ivy.uid);
    expect(await friendshipExists(ivy.uid, jack.uid)).toBe(true);
    expect(await friendshipExists(jack.uid, ivy.uid)).toBe(true);

    // Re-sending to an established friend → already-exists.
    expect(await callableErrorCode(call('friend-sendRequest', { toUid: ivy.uid }))).toBe(
      'functions/already-exists',
    );
  });

  it('mutual-pending race auto-accept leaves NO pending request docs for either direction', async () => {
    const mia = await newMember('MiaLC');
    const nia = await newMember('NiaLC');

    // Simulate the "both users send at the same time" race: BOTH directional
    // request docs already exist as pending before the auto-accept runs.
    await seedPendingRequest(mia, nia, 'MiaLC', 'NiaLC'); // Mia → Nia (caller's outgoing)
    await seedPendingRequest(nia, mia, 'NiaLC', 'MiaLC'); // Nia → Mia (caller's incoming)

    // Mia sends to Nia → reverse-pending auto-accept branch fires.
    await signInAs(mia);
    const result = (await call('friend-sendRequest', { toUid: nia.uid })).data as {
      status: string;
      friend?: { uid: string };
    };
    expect(result.status).toBe('friends');
    expect(result.friend?.uid).toBe(nia.uid);

    // Friendship exists on both sides.
    expect(await friendshipExists(mia.uid, nia.uid)).toBe(true);
    expect(await friendshipExists(nia.uid, mia.uid)).toBe(true);

    // Neither directional request doc is still pending — the incoming (Nia→Mia)
    // AND the caller's own outgoing (Mia→Nia) were both resolved in-transaction,
    // so friend.list (pending-only) can't surface a stale request.
    expect(await requestStatus(mia.uid, nia.uid)).not.toBe('pending');
    expect(await requestStatus(nia.uid, mia.uid)).not.toBe('pending');

    // And neither user's friend.list shows a lingering pending request.
    const miaList = (await call('friend-list', {})).data as {
      incoming: unknown[];
      outgoing: unknown[];
    };
    expect(miaList.outgoing).toHaveLength(0);
    expect(miaList.incoming).toHaveLength(0);

    await signInAs(nia);
    const niaList = (await call('friend-list', {})).data as {
      incoming: unknown[];
      outgoing: unknown[];
    };
    expect(niaList.outgoing).toHaveLength(0);
    expect(niaList.incoming).toHaveLength(0);
  });

  it('honours blocking in both directions with a neutral error', async () => {
    const kim = await newMember('KimLC');
    const leo = await newMember('LeoLC');

    // Kim blocks Leo.
    await signInAs(kim);
    await call('blocking-block', { targetUserId: leo.uid });
    expect(await callableErrorCode(call('friend-sendRequest', { toUid: leo.uid }))).toBe(
      'functions/failed-precondition',
    );

    // Leo (the blocked side) also cannot add Kim — same neutral code.
    await signInAs(leo);
    expect(await callableErrorCode(call('friend-sendRequest', { toUid: kim.uid }))).toBe(
      'functions/failed-precondition',
    );
  });
});

/**
 * friend-cancelRequest: the SENDER's withdrawal of a still-pending request.
 *
 * The callable takes the RECIPIENT ({ toUid }) and derives the document id
 * server-side, so these tests pin the two properties that shape buys us: only
 * the sender can ever delete a request, and every non-cancellable case is the
 * same silent { cancelled: false } no-op (never an error, never an oracle).
 *
 * Display names carry a `CX` suffix: the emulator suite shares ONE Firestore
 * across test files and nickname resolution is by displayName, so a name reused
 * from another file would make these members ambiguous.
 */
/**
 * REGRESSION (2026-07-27): "when opening the Friends page I don't always see a
 * picture on all friends, but on that friend's profile page I can see it".
 * users/{uid}/friends/{friendUid} carries a COPY of the friend's
 * displayName/avatarPath frozen at accept time, and nothing rewrites it — so a
 * member who set or changed their avatar afterwards stayed picture-less in the
 * list while the member-profile screen (which reads live users/{uid}) showed
 * the real one. friend-list now re-reads the live profiles.
 */
describe('friend-list live profile hydration', () => {
  const friendEdge = (ownerUid: string, friendUid: string) =>
    adminDb.collection('users').doc(ownerUid).collection('friends').doc(friendUid);

  it('serves an avatar + rename applied AFTER the friendship was established', async () => {
    const per = await newMember('PerLH');
    const eva = await newMember('EvaLH');

    // Befriend while Eva has NO avatar: the friendship documents freeze
    // avatarPath: null onto both sides.
    await signInAs(per);
    await call('friend-sendRequest', { toUid: eva.uid });
    await signInAs(eva);
    await call('friend-respondRequest', {
      requestId: friendRequestId(per.uid, eva.uid),
      action: 'accept',
    });
    expect((await friendEdge(per.uid, eva.uid).get()).data()?.avatarPath ?? null).toBeNull();

    // Eva then uploads an avatar and renames herself. This is exactly the write
    // the Android profile screen makes — users/{uid} only, no friend edge is
    // (or can be) touched by a client.
    await adminDb
      .collection('users')
      .doc(eva.uid)
      .set(
        { avatarPath: 'profileImages/eva/new.jpg', displayName: 'EvaRenamedLH' },
        { merge: true },
      );

    await signInAs(per);
    const list = (await call('friend-list', {})).data as {
      friends: Array<{ uid: string; displayName: string | null; avatarPath: string | null }>;
    };
    const evaRow = list.friends.find((f) => f.uid === eva.uid);
    expect(evaRow?.avatarPath).toBe('profileImages/eva/new.jpg');
    expect(evaRow?.displayName).toBe('EvaRenamedLH');

    // Hydration happens on READ: the stored copy is left exactly as written, so
    // the fix needs no backfill and rewrites nobody's documents.
    expect((await friendEdge(per.uid, eva.uid).get()).data()?.avatarPath ?? null).toBeNull();
  });

  it('serves the current avatar of the OTHER party of a pending request', async () => {
    const olle = await newMember('OlleLH');
    const tina = await newMember('TinaLH');

    await signInAs(olle);
    await call('friend-sendRequest', { toUid: tina.uid });

    // Tina uploads an avatar while the request is still sitting in her inbox.
    await adminDb
      .collection('users')
      .doc(tina.uid)
      .set({ avatarPath: 'profileImages/tina/new.jpg' }, { merge: true });

    // The sender sees it on the outgoing row...
    const outgoing = (await call('friend-list', {})).data as {
      outgoing: Array<{ otherUser: { uid: string; avatarPath: string | null } }>;
    };
    expect(outgoing.outgoing.find((r) => r.otherUser.uid === tina.uid)?.otherUser.avatarPath).toBe(
      'profileImages/tina/new.jpg',
    );

    // ...and the recipient sees the sender's live profile on the incoming row.
    await adminDb
      .collection('users')
      .doc(olle.uid)
      .set({ avatarPath: 'profileImages/olle/new.jpg' }, { merge: true });
    await signInAs(tina);
    const incoming = (await call('friend-list', {})).data as {
      incoming: Array<{ otherUser: { uid: string; avatarPath: string | null } }>;
    };
    expect(incoming.incoming.find((r) => r.otherUser.uid === olle.uid)?.otherUser.avatarPath).toBe(
      'profileImages/olle/new.jpg',
    );
  });

  it('falls back to the stored copy when the member has no user document left', async () => {
    const gustav = await newMember('GustavLH');
    const hanna = await newMember('HannaLH');

    await signInAs(gustav);
    await call('friend-sendRequest', { toUid: hanna.uid });
    await signInAs(hanna);
    await call('friend-respondRequest', {
      requestId: friendRequestId(gustav.uid, hanna.uid),
      action: 'accept',
    });

    // Hanna's profile disappears (deleted account). An absent live profile must
    // leave the last known name in place rather than blanking the row.
    await adminDb.collection('users').doc(hanna.uid).delete();

    await signInAs(gustav);
    const list = (await call('friend-list', {})).data as {
      friends: Array<{ uid: string; displayName: string | null }>;
    };
    expect(list.friends.find((f) => f.uid === hanna.uid)?.displayName).toBe('HannaLH');
  });
});

describe('friend-cancelRequest', () => {
  it('lets the sender withdraw a pending request, and is idempotent', async () => {
    const olle = await newMember('OlleCX');
    const petra = await newMember('PetraCX');

    await signInAs(olle);
    await call('friend-sendRequest', { toUid: petra.uid });
    expect(await requestStatus(olle.uid, petra.uid)).toBe('pending');

    // Petra sees it as incoming before the withdrawal.
    await signInAs(petra);
    const before = (await call('friend-list', {})).data as {
      incoming: Array<{ otherUser: { uid: string } }>;
    };
    expect(before.incoming.map((r) => r.otherUser.uid)).toContain(olle.uid);

    await signInAs(olle);
    const cancelled = (await call('friend-cancelRequest', { toUid: petra.uid })).data as {
      cancelled: boolean;
    };
    expect(cancelled.cancelled).toBe(true);
    // Deleted, not moved to a terminal status: a withdrawn request reads as
    // never sent, which is what lets the pair start over below.
    expect(await requestStatus(olle.uid, petra.uid)).toBeUndefined();

    const olleList = (await call('friend-list', {})).data as { outgoing: unknown[] };
    expect(olleList.outgoing).toHaveLength(0);

    // The recipient's pending row is gone too.
    await signInAs(petra);
    const after = (await call('friend-list', {})).data as {
      incoming: Array<{ otherUser: { uid: string } }>;
    };
    expect(after.incoming.map((r) => r.otherUser.uid)).not.toContain(olle.uid);

    // Idempotent: a double-tap (or a retry after a dropped response) is a
    // successful no-op, never a user-visible failure.
    await signInAs(olle);
    const again = (await call('friend-cancelRequest', { toUid: petra.uid })).data as {
      cancelled: boolean;
    };
    expect(again.cancelled).toBe(false);

    // And the pair can start over.
    const resent = (await call('friend-sendRequest', { toUid: petra.uid })).data as {
      status: string;
    };
    expect(resent.status).toBe('requested');
    expect(await requestStatus(olle.uid, petra.uid)).toBe('pending');
  });

  it('does not let the RECIPIENT cancel the request sent to them', async () => {
    const rune = await newMember('RuneCX');
    const sara = await newMember('SaraCX');

    await signInAs(rune);
    await call('friend-sendRequest', { toUid: sara.uid });

    // Sara points the callable at Rune: that resolves to the (sara → rune)
    // document, which does not exist. Her own INBOUND request is untouched —
    // only friend-respondRequest can act on it.
    await signInAs(sara);
    const attempt = (await call('friend-cancelRequest', { toUid: rune.uid })).data as {
      cancelled: boolean;
    };
    expect(attempt.cancelled).toBe(false);
    expect(await requestStatus(rune.uid, sara.uid)).toBe('pending');

    const saraList = (await call('friend-list', {})).data as {
      incoming: Array<{ otherUser: { uid: string } }>;
    };
    expect(saraList.incoming.map((r) => r.otherUser.uid)).toContain(rune.uid);
  });

  it('is a no-op once the request has been accepted — the friendship stands', async () => {
    const tova = await newMember('TovaCX');
    const uno = await newMember('UnoCX');

    await signInAs(tova);
    await call('friend-sendRequest', { toUid: uno.uid });

    await signInAs(uno);
    await call('friend-respondRequest', {
      requestId: friendRequestId(tova.uid, uno.uid),
      action: 'accept',
    });

    // Tova cancels too late: the request is 'accepted', so nothing is deleted
    // and — crucially — the established friendship is untouched.
    await signInAs(tova);
    const late = (await call('friend-cancelRequest', { toUid: uno.uid })).data as {
      cancelled: boolean;
    };
    expect(late.cancelled).toBe(false);
    expect(await requestStatus(tova.uid, uno.uid)).toBe('accepted');
    expect(await friendshipExists(tova.uid, uno.uid)).toBe(true);
    expect(await friendshipExists(uno.uid, tova.uid)).toBe(true);
  });

  it('no-ops for an unrelated member and for self, and rejects a malformed payload', async () => {
    const vera = await newMember('VeraCX');
    const stranger = await newMember('WilmaCX');

    // A member the caller never wrote a request to, and the caller themselves:
    // both answer exactly like "already handled", so neither can be used to
    // probe another account.
    await signInAs(vera);
    expect(
      ((await call('friend-cancelRequest', { toUid: stranger.uid })).data as { cancelled: boolean })
        .cancelled,
    ).toBe(false);
    expect(
      ((await call('friend-cancelRequest', { toUid: vera.uid })).data as { cancelled: boolean })
        .cancelled,
    ).toBe(false);

    expect(await callableErrorCode(call('friend-cancelRequest', {}))).toBe(
      'functions/invalid-argument',
    );
    // The schema is .strict(): a requestId-shaped payload (the OTHER friend
    // callables' input) is rejected rather than silently ignored.
    expect(await callableErrorCode(call('friend-cancelRequest', { requestId: 'whatever' }))).toBe(
      'functions/invalid-argument',
    );
  });

  it('refuses a document whose BODY disagrees with its own id', async () => {
    // Belt-and-braces: the id derivation already implies BOTH ends of the pair,
    // so this can only arise from a future write path or a botched migration.
    // Deleting it would mean acting on a request addressed to somebody else, so
    // the guard re-asserts toUid as well as fromUid.
    const xena = await newMember('XenaCX');
    const yrsa = await newMember('YrsaCX');
    const zack = await newMember('ZackCX');

    await adminDb
      .collection('friendRequests')
      .doc(friendRequestId(xena.uid, yrsa.uid))
      .set({
        fromUid: xena.uid,
        // Disagrees with the id, which encodes (xena -> yrsa).
        toUid: zack.uid,
        status: 'pending',
        fromDisplayName: 'XenaCX',
        fromAvatarPath: null,
        toDisplayName: 'ZackCX',
        toAvatarPath: null,
        createdAt: new Date(),
        updatedAt: new Date(),
      });

    await signInAs(xena);
    const attempt = (await call('friend-cancelRequest', { toUid: yrsa.uid })).data as {
      cancelled: boolean;
    };
    expect(attempt.cancelled).toBe(false);
    // Still there: refused, not silently deleted.
    expect(await requestStatus(xena.uid, yrsa.uid)).toBe('pending');

    await adminDb.collection('friendRequests').doc(friendRequestId(xena.uid, yrsa.uid)).delete();
  });

  it('rejects unauthenticated and suspended callers', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('friend-cancelRequest', { toUid: 'anyone' }))).toBe(
      'functions/unauthenticated',
    );

    const suspended = await newFreeUser();
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
    expect(await callableErrorCode(call('friend-cancelRequest', { toUid: 'anyone' }))).toBe(
      'functions/permission-denied',
    );
  });
});

describe('friends Firestore rules', () => {
  it('owner reads own friends + requests; others cannot; no client writes', async () => {
    const mona = await newMember('MonaR');
    const nils = await newMember('NilsR');

    await signInAs(mona);
    await call('friend-sendRequest', { toUid: nils.uid });
    const requestId = friendRequestId(mona.uid, nils.uid);

    // Sender reads their own request.
    const asMona = await getDoc(doc(firestore, 'friendRequests', requestId));
    expect(asMona.exists()).toBe(true);

    // Recipient accepts, then both read their own friends subcollection.
    await signInAs(nils);
    const asNils = await getDoc(doc(firestore, 'friendRequests', requestId));
    expect(asNils.exists()).toBe(true);
    await call('friend-respondRequest', { requestId, action: 'accept' });

    const nilsFriends = await getDocs(collection(firestore, 'users', nils.uid, 'friends'));
    expect(nilsFriends.docs.some((d) => d.id === mona.uid)).toBe(true);

    // Nils cannot read Mona's friends list (owner-only).
    await expect(
      getDoc(doc(firestore, 'users', mona.uid, 'friends', nils.uid)),
    ).rejects.toMatchObject({ code: 'permission-denied' });

    // A third party cannot read the request.
    const outsider = await newMember('OutsiderR');
    await signInAs(outsider);
    await expect(getDoc(doc(firestore, 'friendRequests', requestId))).rejects.toMatchObject({
      code: 'permission-denied',
    });

    // No direct client writes to either collection.
    await signInAs(mona);
    await expect(
      setDoc(doc(firestore, 'friendRequests', `${mona.uid}__${outsider.uid}`), {
        fromUid: mona.uid,
        toUid: outsider.uid,
        status: 'pending',
      }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    // Legacy-forge vector: a removed senderId/receiverId scaffold rule once
    // let a client create a friendRequests doc by setting senderId to itself.
    // The callable-only model must reject this even with those fields present.
    await expect(
      setDoc(doc(firestore, 'friendRequests', `forged__${outsider.uid}`), {
        senderId: mona.uid,
        receiverId: outsider.uid,
        fromUid: mona.uid,
        toUid: outsider.uid,
        status: 'pending',
      }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    await expect(
      setDoc(doc(firestore, 'users', mona.uid, 'friends', outsider.uid), { friendUid: outsider.uid }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
  });
});

// ---------------------------------------------------------------------------
// friend_request in-app notification producers
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

describe('friend_request in-app notification producers', () => {
  it('notifies the INVITEE on a new request, and never the requester', async () => {
    const requester = await newMember('NotifRequester');
    const invitee = await newMember('NotifInvitee');

    await signInAs(requester);
    await call('friend-sendRequest', { toUid: invitee.uid });

    const items = await pollUntil(async () => {
      const found = await inboxFor(invitee.uid, 'friend_request');
      return found.length > 0 ? found : undefined;
    });
    expect(items).toHaveLength(1);
    expect(items[0]!.title).toBe('Ny vänförfrågan');
    expect(items[0]!.previewText).toBe('NotifRequester vill bli din vän.');
    // Deep-link target: the requester's profile.
    expect(items[0]!.relatedEntityId).toBe(requester.uid);

    expect(await inboxFor(requester.uid, 'friend_request')).toHaveLength(0);
  });

  it('writes NO notification when the invitee opted out of friend_request', async () => {
    const requester = await newMember('OptOutRequester');
    const invitee = await newMember('OptOutInvitee');
    await optOutOf(invitee.uid, 'friend_request');

    await signInAs(requester);
    await call('friend-sendRequest', { toUid: invitee.uid });

    // The request itself must still be created — only the notification is
    // suppressed. Waiting on it proves the send completed, so the empty inbox
    // below isn't just a race.
    await pollUntil(async () =>
      (await requestStatus(requester.uid, invitee.uid)) === 'pending' ? true : undefined,
    );
    expect(await inboxFor(invitee.uid, 'friend_request')).toHaveLength(0);
  });

  it('notifies the REQUESTER when their request is accepted', async () => {
    const requester = await newMember('AcceptedRequester');
    const accepter = await newMember('AcceptingFriend');

    await signInAs(requester);
    await call('friend-sendRequest', { toUid: accepter.uid });

    await signInAs(accepter);
    await call('friend-respondRequest', {
      requestId: friendRequestId(requester.uid, accepter.uid),
      action: 'accept',
    });

    const items = await pollUntil(async () => {
      const found = await inboxFor(requester.uid, 'friend_request');
      return found.length > 0 ? found : undefined;
    });
    expect(items).toHaveLength(1);
    expect(items[0]!.title).toBe('Vänförfrågan accepterad');
    expect(items[0]!.previewText).toBe('AcceptingFriend accepterade din vänförfrågan.');
    expect(items[0]!.relatedEntityId).toBe(accepter.uid);
  });

  it('stays SILENT on a decline — the requester is never told they were turned down', async () => {
    const requester = await newMember('DeclinedRequester');
    const decliner = await newMember('DecliningUser');

    await signInAs(requester);
    await call('friend-sendRequest', { toUid: decliner.uid });
    // Drain the invitee's new-request notice so only the response is in play.
    await pollUntil(async () => {
      const found = await inboxFor(decliner.uid, 'friend_request');
      return found.length > 0 ? found : undefined;
    });

    await signInAs(decliner);
    await call('friend-respondRequest', {
      requestId: friendRequestId(requester.uid, decliner.uid),
      action: 'decline',
    });

    // Wait for the decline to land, then assert the requester learned nothing.
    await pollUntil(async () =>
      (await requestStatus(requester.uid, decliner.uid)) === 'declined' ? true : undefined,
    );
    expect(await inboxFor(requester.uid, 'friend_request')).toHaveLength(0);
  });

  it('writes NO notification when the pair is blocked — in EITHER direction', async () => {
    const kim = await newMember('BlockReqKim');
    const leo = await newMember('BlockReqLeo');

    // Kim blocks Leo.
    await signInAs(kim);
    await call('blocking-block', { targetUserId: leo.uid });

    // Pins the invariant that today holds only by construction: the producer
    // sits BEHIND the block gates (the pre-transaction check and the
    // in-transaction re-read). Each leg asserts the request was actually
    // attempted and observed to be rejected, so the empty inbox is real
    // suppression and not a race. A blocked user must not be able to use a
    // friend request to put anything in the inbox of the person who blocked
    // them — move the producer above the gate and this fails.
    await signInAs(leo);
    expect(await callableErrorCode(call('friend-sendRequest', { toUid: kim.uid }))).toBe(
      'functions/failed-precondition',
    );
    expect(await inboxFor(kim.uid, 'friend_request')).toHaveLength(0);

    // The blocker cannot notify the person they blocked either.
    await signInAs(kim);
    expect(await callableErrorCode(call('friend-sendRequest', { toUid: leo.uid }))).toBe(
      'functions/failed-precondition',
    );
    expect(await inboxFor(leo.uid, 'friend_request')).toHaveLength(0);
  });

  it('notifies the other party on the reverse-pending auto-accept path', async () => {
    // They already sent a request to us; our send befriends immediately, which
    // from their side reads as "your request was accepted".
    const other = await newMember('RaceOther');
    const caller = await newMember('RaceCaller');
    await seedPendingRequest(other, caller, 'RaceOther', 'RaceCaller');

    await signInAs(caller);
    const result = await call('friend-sendRequest', { toUid: other.uid });
    expect((result.data as { status: string }).status).toBe('friends');

    const items = await pollUntil(async () => {
      const found = await inboxFor(other.uid, 'friend_request');
      return found.length > 0 ? found : undefined;
    });
    expect(items).toHaveLength(1);
    expect(items[0]!.title).toBe('Vänförfrågan accepterad');
    expect(items[0]!.previewText).toBe('RaceCaller accepterade din vänförfrågan.');
  });
});
