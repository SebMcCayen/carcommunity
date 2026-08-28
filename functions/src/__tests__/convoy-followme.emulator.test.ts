/**
 * Convoy FOLLOW-ME leader-trail emulator integration tests (convoy-setFollowMe).
 *
 * Exercises the deployed-in-emulator callable end-to-end plus the followMe
 * Firestore rules:
 * - accepted-member gating (a non-member / unknown convoy → not-found so a convoy
 *   can't be probed; a still-invited member → failed-precondition);
 * - EXCLUSIVITY + TAKEOVER + TOGGLE: activation sets the caller as the sole
 *   leader (resetting the polyline), a second member's activation takes it over,
 *   the leader can toggle their own trail off, and a non-leader toggling off is a
 *   no-op that leaves the trail alone;
 * - RULES (security-sensitive): an accepted member may READ the followMe doc; the
 *   CURRENT leader may UPDATE the polyline directly; a non-leader member's update
 *   is denied; a client create/delete is denied; a non-member read is denied;
 * - CLEANUP: convoy.end deletes the trail; the leader leaving deletes it while a
 *   non-leader leaving leaves it intact.
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
  connectFirestoreEmulator,
  deleteDoc,
  doc,
  getDoc,
  getFirestore,
  serverTimestamp,
  setDoc,
  updateDoc,
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
  getAdminApps()[0] ??
  initializeAdminApp({ projectId: PROJECT_ID }, 'convoy-followme-emulator-tests');
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

let userSeq = 0;

async function pollUntil<T>(read: () => Promise<T | undefined>, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
}

async function newMember(displayName: string): Promise<TestUser> {
  userSeq += 1;
  const email = `convoy-follow-${userSeq}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
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

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

async function callableError(promise: Promise<unknown>): Promise<{ code: string; details: unknown }> {
  try {
    await promise;
    throw new Error('expected the call to reject');
  } catch (error) {
    if (error instanceof FirebaseError) {
      return { code: error.code, details: (error as unknown as { details?: unknown }).details };
    }
    throw error;
  }
}

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

/** Owner creates an ACTIVE convoy inviting `friends`, who then each accept. */
async function acceptedConvoy(owner: TestUser, friends: TestUser[]): Promise<string> {
  for (const f of friends) await makeFriends(owner, f);
  await signInAs(owner);
  const result = (await call('convoy-create', { inviteeUids: friends.map((f) => f.uid) })).data as {
    convoy: { convoyId: string };
  };
  const convoyId = result.convoy.convoyId;
  for (const f of friends) {
    await signInAs(f);
    await call('convoy-respond', { convoyId, action: 'accept' });
  }
  return convoyId;
}

function followMeAdminRef(convoyId: string) {
  return adminDb.collection('convoys').doc(convoyId).collection('followMe').doc('current');
}

async function readFollowMe(convoyId: string): Promise<Record<string, unknown> | undefined> {
  const snap = await followMeAdminRef(convoyId).get();
  return snap.exists ? (snap.data() as Record<string, unknown>) : undefined;
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'convoy-followme-emulator-client',
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

describe('convoy-setFollowMe gating', () => {
  it('rejects an unauthenticated caller', async () => {
    await auth.signOut();
    expect(
      (await callableError(call('convoy-setFollowMe', { convoyId: 'x', active: true }))).code,
    ).toBe('functions/unauthenticated');
  });

  it('an outsider gets not-found (a convoy cannot be probed)', async () => {
    const owner = await newMember('FollowOwner');
    const friend = await newMember('FollowFriend');
    const outsider = await newMember('FollowOutsider');
    const convoyId = await acceptedConvoy(owner, [friend]);

    await signInAs(outsider);
    expect(
      (await callableError(call('convoy-setFollowMe', { convoyId, active: true }))).code,
    ).toBe('functions/not-found');
  });

  it('a still-invited (not accepted) member gets failed-precondition', async () => {
    const owner = await newMember('FollowOwner2');
    const invitee = await newMember('FollowInvitee2');
    await makeFriends(owner, invitee);
    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [invitee.uid] })).data as {
      convoy: { convoyId: string };
    };
    // invitee has NOT accepted.
    await signInAs(invitee);
    expect(
      (await callableError(call('convoy-setFollowMe', { convoyId: created.convoy.convoyId, active: true })))
        .code,
    ).toBe('functions/failed-precondition');
  });

  it('rejects a non-boolean active (invalid-argument)', async () => {
    const owner = await newMember('FollowOwner3');
    const friend = await newMember('FollowFriend3');
    const convoyId = await acceptedConvoy(owner, [friend]);
    await signInAs(owner);
    expect(
      (await callableError(call('convoy-setFollowMe', { convoyId, active: 'yes' }))).code,
    ).toBe('functions/invalid-argument');
  });
});

describe('convoy-setFollowMe exclusivity / takeover / toggle', () => {
  it('activation makes the caller the sole leader with a reset polyline', async () => {
    const owner = await newMember('ActOwner');
    const friend = await newMember('ActFriend');
    const convoyId = await acceptedConvoy(owner, [friend]);

    await signInAs(owner);
    const res = (await call('convoy-setFollowMe', { convoyId, active: true })).data as {
      leading: boolean;
      leaderUid: string | null;
    };
    expect(res).toEqual({ leading: true, leaderUid: owner.uid });

    const doc0 = await readFollowMe(convoyId);
    expect(doc0?.leaderUid).toBe(owner.uid);
    expect(doc0?.polyline).toBe('');
    expect(doc0?.updatedAt).toBeDefined();
  });

  it('a second accepted member TAKES OVER the trail (only one leader)', async () => {
    const owner = await newMember('TakeoverOwner');
    const friend = await newMember('TakeoverFriend');
    const convoyId = await acceptedConvoy(owner, [friend]);

    await signInAs(owner);
    await call('convoy-setFollowMe', { convoyId, active: true });
    // Leader writes a polyline so we can prove takeover resets it.
    await updateDoc(doc(firestore, 'convoys', convoyId, 'followMe', 'current'), {
      polyline: 'SEED',
      updatedAt: serverTimestamp(),
    });

    await signInAs(friend);
    const res = (await call('convoy-setFollowMe', { convoyId, active: true })).data as {
      leaderUid: string | null;
    };
    expect(res.leaderUid).toBe(friend.uid);
    const doc0 = await readFollowMe(convoyId);
    expect(doc0?.leaderUid).toBe(friend.uid);
    expect(doc0?.polyline).toBe(''); // reset on takeover
  });

  it('a non-leader toggling OFF is a no-op (cannot wipe another member trail)', async () => {
    const owner = await newMember('NoopOwner');
    const friend = await newMember('NoopFriend');
    const convoyId = await acceptedConvoy(owner, [friend]);

    await signInAs(friend);
    await call('convoy-setFollowMe', { convoyId, active: true }); // friend leads

    await signInAs(owner);
    const res = (await call('convoy-setFollowMe', { convoyId, active: false })).data as {
      leading: boolean;
      leaderUid: string | null;
    };
    expect(res).toEqual({ leading: false, leaderUid: friend.uid });
    const doc0 = await readFollowMe(convoyId);
    expect(doc0?.leaderUid).toBe(friend.uid); // untouched
  });

  it('the leader toggles their own trail off (doc deleted)', async () => {
    const owner = await newMember('OffOwner');
    const friend = await newMember('OffFriend');
    const convoyId = await acceptedConvoy(owner, [friend]);

    await signInAs(owner);
    await call('convoy-setFollowMe', { convoyId, active: true });
    const res = (await call('convoy-setFollowMe', { convoyId, active: false })).data as {
      leading: boolean;
    };
    expect(res.leading).toBe(false);
    expect(await readFollowMe(convoyId)).toBeUndefined();
  });
});

describe('followMe Firestore rules', () => {
  it('an accepted member reads it; the CURRENT leader may update the polyline; a non-leader cannot', async () => {
    const owner = await newMember('RulesFollowOwner');
    const friend = await newMember('RulesFollowFriend');
    const convoyId = await acceptedConvoy(owner, [friend]);

    await signInAs(owner);
    await call('convoy-setFollowMe', { convoyId, active: true }); // owner leads

    const ref = () => doc(firestore, 'convoys', convoyId, 'followMe', 'current');

    // Accepted member (friend) may READ the trail.
    await signInAs(friend);
    const readSnap = await getDoc(ref());
    expect(readSnap.exists()).toBe(true);

    // A non-leader member may NOT update it.
    const nonLeaderErr = await callableError(
      updateDoc(ref(), { polyline: 'x', updatedAt: new Date() }),
    );
    expect(nonLeaderErr.code).toContain('permission-denied');

    // The current leader (owner) MAY update the polyline directly — with a SERVER
    // timestamp for updatedAt.
    await signInAs(owner);
    await updateDoc(ref(), { polyline: 'ABCD', updatedAt: serverTimestamp() });
    const after = await readFollowMe(convoyId);
    expect(after?.polyline).toBe('ABCD');
    expect(after?.leaderUid).toBe(owner.uid);

    // …but the leader may NOT stamp a client-chosen updatedAt (which could set a
    // future time and keep the freshness gate green forever) — updatedAt must be
    // the server clock.
    const clientClockErr = await callableError(
      updateDoc(ref(), { polyline: 'EFGH', updatedAt: new Date(Date.now() + 86_400_000) }),
    );
    expect(clientClockErr.code).toContain('permission-denied');
  });

  it('a client cannot CREATE a trail naming itself leader, nor change leaderUid', async () => {
    const owner = await newMember('CreateFollowOwner');
    const friend = await newMember('CreateFollowFriend');
    const convoyId = await acceptedConvoy(owner, [friend]);

    const ref = () => doc(firestore, 'convoys', convoyId, 'followMe', 'current');

    // No trail yet — a client create is denied (callable-only).
    await signInAs(friend);
    const createErr = await callableError(
      setDoc(ref(), { leaderUid: friend.uid, polyline: '', updatedAt: new Date() }),
    );
    expect(createErr.code).toContain('permission-denied');

    // Owner activates, then even the leader cannot re-point leaderUid via a write.
    await signInAs(owner);
    await call('convoy-setFollowMe', { convoyId, active: true });
    const hijackErr = await callableError(
      updateDoc(ref(), { leaderUid: friend.uid, polyline: 'x', updatedAt: new Date() }),
    );
    expect(hijackErr.code).toContain('permission-denied');
  });

  it('a client cannot DELETE the trail, and a non-member cannot read it', async () => {
    const owner = await newMember('DelFollowOwner');
    const friend = await newMember('DelFollowFriend');
    const outsider = await newMember('DelFollowOutsider');
    const convoyId = await acceptedConvoy(owner, [friend]);

    await signInAs(owner);
    await call('convoy-setFollowMe', { convoyId, active: true });

    const ref = () => doc(firestore, 'convoys', convoyId, 'followMe', 'current');

    // Even the leader cannot delete via a client write (toggle-off is callable-only).
    const delErr = await callableError(deleteDoc(ref()));
    expect(delErr.code).toContain('permission-denied');

    // A non-member cannot read the trail.
    await signInAs(outsider);
    const readErr = await callableError(getDoc(ref()));
    expect(readErr.code).toContain('permission-denied');
  });
});

describe('followMe cleanup on leave / end', () => {
  it('convoy.end tears the trail down', async () => {
    const owner = await newMember('EndOwner');
    const friend = await newMember('EndFriend');
    const convoyId = await acceptedConvoy(owner, [friend]);

    await signInAs(owner);
    await call('convoy-setFollowMe', { convoyId, active: true });
    expect(await readFollowMe(convoyId)).toBeDefined();

    await call('convoy-end', { convoyId });
    await pollUntil(async () => ((await readFollowMe(convoyId)) === undefined ? true : undefined));
  });

  it('the trail LEADER leaving clears it, but a NON-leader leaving leaves it intact', async () => {
    const owner = await newMember('LeaveOwner');
    const a = await newMember('LeaveA');
    const b = await newMember('LeaveB');
    const convoyId = await acceptedConvoy(owner, [a, b]);

    // Case 1: a NON-leader (b) leaves while owner leads -> trail stays.
    await signInAs(owner);
    await call('convoy-setFollowMe', { convoyId, active: true }); // owner leads
    await signInAs(b);
    await call('convoy-leave', { convoyId }); // owner + a remain (2), survives
    // Give the best-effort post-commit cleanup a beat; it must NOT have deleted.
    await new Promise((r) => setTimeout(r, 750));
    expect((await readFollowMe(convoyId))?.leaderUid).toBe(owner.uid);

    // Case 2: the trail leader (a) takes over then leaves -> trail cleared.
    await signInAs(a);
    await call('convoy-setFollowMe', { convoyId, active: true }); // a leads now
    await call('convoy-leave', { convoyId }); // owner remains alone -> convoy ends anyway
    await pollUntil(async () => ((await readFollowMe(convoyId)) === undefined ? true : undefined));
  });
});
