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
import { friendRequestId } from '../friends/friends-core';

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
    .set({ activeMember: true, displayName }, { merge: true });
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

  it('requires an active member', async () => {
    const free = await newFreeUser();
    await signInAs(free);
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
