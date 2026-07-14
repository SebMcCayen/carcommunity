/**
 * Convoy (friend convoy) emulator integration tests.
 *
 * Exercises the deployed-in-emulator callables end-to-end plus the convoys
 * Firestore rules:
 * - `convoy-create` (member gating, friend-only invites with non-friend/blocked
 *   silently skipped, owner seeded accepted, invitees seeded invited)
 * - `convoy-respond` (invitee accept → green-dot accepted; decline; guards:
 *   non-member not-found, already-answered / ended failed-precondition)
 * - `convoy-start` (owner forming → active; non-owner not-found)
 * - `convoy-end` (owner → ended + computed/stored summary readable by members)
 * - `convoy-list` (caller convoys + pending invites)
 * - rules: member-only read of convoys, outsiders denied, no client writes.
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
  doc,
  getDoc,
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'convoy-emulator-tests');
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
  const email = `convoy-${userSeq}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
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
  const email = `convoy-free-${userSeq}-${Date.now()}@example.com`;
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

interface ConvoyMember {
  uid: string;
  role: 'owner' | 'member';
  inviteStatus: 'invited' | 'accepted' | 'declined';
}
interface ConvoySummary {
  convoyId: string;
  ownerUid: string;
  status: 'forming' | 'active' | 'ended';
  members: ConvoyMember[];
  memberUids: string[];
  viewer: { role: string; inviteStatus: string } | null;
  livePositionUids: string[];
  summary: { durationSeconds: number; participantUids: string[]; participantCount: number; distanceMeters: number | null } | null;
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'convoy-emulator-client',
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

describe('convoy-create gating + friend-only invites', () => {
  it('rejects unauthenticated callers', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('convoy-list', {}))).toBe('functions/unauthenticated');
  });

  it('requires an active member', async () => {
    const free = await newFreeUser();
    await signInAs(free);
    expect(await callableErrorCode(call('convoy-create', { inviteeUids: ['someone'] }))).toBe(
      'functions/permission-denied',
    );
  });

  it('invites friends, skips non-friends, blocked, and self', async () => {
    const owner = await newMember('OwnerC');
    const friend = await newMember('FriendC');
    const stranger = await newMember('StrangerC');
    const blockedFriend = await newMember('BlockedFriendC');
    await makeFriends(owner, friend);
    await makeFriends(owner, blockedFriend);

    await signInAs(owner);
    // Block a friend → they must be skipped.
    await call('blocking-block', { targetUserId: blockedFriend.uid });

    const result = (
      await call('convoy-create', {
        title: 'Sunday Run',
        inviteeUids: [friend.uid, stranger.uid, blockedFriend.uid, owner.uid, friend.uid],
      })
    ).data as { convoy: ConvoySummary; invited: string[]; skipped: Array<{ uid: string; reason: string }> };

    expect(result.invited).toEqual([friend.uid]);
    const reasons = Object.fromEntries(result.skipped.map((s) => [s.uid, s.reason]));
    expect(reasons[stranger.uid]).toBe('not_friend');
    expect(reasons[blockedFriend.uid]).toBe('blocked');
    expect(reasons[owner.uid]).toBe('self');
    // Second occurrence of friend.uid is a duplicate.
    expect(result.skipped.some((s) => s.uid === friend.uid && s.reason === 'duplicate')).toBe(true);

    expect(result.convoy.status).toBe('forming');
    expect(result.convoy.memberUids.sort()).toEqual([friend.uid, owner.uid].sort());
    const ownerEntry = result.convoy.members.find((m) => m.uid === owner.uid)!;
    expect(ownerEntry.role).toBe('owner');
    expect(ownerEntry.inviteStatus).toBe('accepted');
    const inviteeEntry = result.convoy.members.find((m) => m.uid === friend.uid)!;
    expect(inviteeEntry.inviteStatus).toBe('invited');
    // Only the owner (accepted) is in the live-position set so far.
    expect(result.convoy.livePositionUids).toEqual([owner.uid]);

    // The invitee received an in-app notification.
    const items = await adminDb.collection('notifications').doc(friend.uid).collection('items').get();
    expect(items.docs.some((d) => d.data().relatedEntityId === result.convoy.convoyId)).toBe(true);
  });

  it('skips a friend who is not an active member (not_found)', async () => {
    const owner = await newMember('MemberOwnerC');
    const friendMember = await newMember('ActiveFriendC');
    // A friend of the owner who has no active-member entitlement: they could
    // never accept/see a convoy, so convoy.create must skip them as not_found
    // rather than writing them into memberUids/members and notifying them.
    const freeFriend = await newFreeUser();
    await makeFriends(owner, friendMember);
    await makeFriends(owner, freeFriend);

    await signInAs(owner);
    const result = (
      await call('convoy-create', { inviteeUids: [friendMember.uid, freeFriend.uid] })
    ).data as { convoy: ConvoySummary; invited: string[]; skipped: Array<{ uid: string; reason: string }> };

    expect(result.invited).toEqual([friendMember.uid]);
    expect(result.skipped).toEqual([{ uid: freeFriend.uid, reason: 'not_found' }]);
    // The non-member is not written into the convoy...
    expect(result.convoy.memberUids).not.toContain(freeFriend.uid);
    expect(result.convoy.members.some((m) => m.uid === freeFriend.uid)).toBe(false);
    // ...and receives no invite notification.
    const items = await adminDb
      .collection('notifications')
      .doc(freeFriend.uid)
      .collection('items')
      .get();
    expect(items.docs.some((d) => d.data().relatedEntityId === result.convoy.convoyId)).toBe(false);
  });

  it('fails when no invitee is valid', async () => {
    const owner = await newMember('LonelyC');
    const stranger = await newMember('NoFriendC');
    await signInAs(owner);
    expect(await callableErrorCode(call('convoy-create', { inviteeUids: [stranger.uid] }))).toBe(
      'functions/failed-precondition',
    );
  });
});

describe('convoy lifecycle: respond / start / end / list', () => {
  it('runs a full convoy through accept, start, and end with a summary', async () => {
    const owner = await newMember('LeadC');
    const a = await newMember('AlphaC');
    const b = await newMember('BravoC');
    await makeFriends(owner, a);
    await makeFriends(owner, b);

    await signInAs(owner);
    const created = (await call('convoy-create', { title: 'Cruise', inviteeUids: [a.uid, b.uid] }))
      .data as { convoy: ConvoySummary };
    const convoyId = created.convoy.convoyId;

    // Alpha accepts → green-dot accepted + in the live-position set.
    await signInAs(a);
    const accepted = (await call('convoy-respond', { convoyId, action: 'accept' })).data as {
      convoy: ConvoySummary;
      inviteStatus: string;
    };
    expect(accepted.inviteStatus).toBe('accepted');
    expect(accepted.convoy.viewer).toEqual({ role: 'member', inviteStatus: 'accepted' });
    expect(accepted.convoy.livePositionUids.sort()).toEqual([a.uid, owner.uid].sort());

    // Bravo declines.
    await signInAs(b);
    await call('convoy-respond', { convoyId, action: 'decline' });
    // Re-answering an already-handled invite fails.
    expect(await callableErrorCode(call('convoy-respond', { convoyId, action: 'accept' }))).toBe(
      'functions/failed-precondition',
    );

    // A non-member cannot respond (not-found, no probing).
    const outsider = await newMember('OutsiderC');
    await signInAs(outsider);
    expect(await callableErrorCode(call('convoy-respond', { convoyId, action: 'accept' }))).toBe(
      'functions/not-found',
    );
    // A member cannot start (owner-only → not-found).
    await signInAs(a);
    expect(await callableErrorCode(call('convoy-start', { convoyId }))).toBe('functions/not-found');

    // Owner starts (forming → active), then a second start fails.
    await signInAs(owner);
    const started = (await call('convoy-start', { convoyId })).data as { convoy: ConvoySummary };
    expect(started.convoy.status).toBe('active');
    expect(await callableErrorCode(call('convoy-start', { convoyId }))).toBe(
      'functions/failed-precondition',
    );

    // Owner ends → summary computed (accepted participants = owner + Alpha).
    const ended = (await call('convoy-end', { convoyId })).data as { convoy: ConvoySummary };
    expect(ended.convoy.status).toBe('ended');
    expect(ended.convoy.summary).not.toBeNull();
    expect(ended.convoy.summary!.participantCount).toBe(2);
    expect(ended.convoy.summary!.participantUids.sort()).toEqual([a.uid, owner.uid].sort());
    expect(ended.convoy.summary!.distanceMeters).toBeNull();
    // Re-ending fails.
    expect(await callableErrorCode(call('convoy-end', { convoyId }))).toBe(
      'functions/failed-precondition',
    );
    // Responding to an ended convoy fails (still a member — Alpha).
    await signInAs(a);
    expect(await callableErrorCode(call('convoy-respond', { convoyId, action: 'decline' }))).toBe(
      'functions/failed-precondition',
    );

    // The summary is readable by ALL members (Alpha sees it via convoy-list).
    const alphaList = (await call('convoy-list', {})).data as {
      convoys: ConvoySummary[];
      pendingInvites: ConvoySummary[];
    };
    const alphaConvoy = alphaList.convoys.find((c) => c.convoyId === convoyId)!;
    expect(alphaConvoy.summary!.participantCount).toBe(2);
    expect(alphaList.pendingInvites.some((c) => c.convoyId === convoyId)).toBe(false);
  });

  it('surfaces pending invites in convoy-list', async () => {
    const owner = await newMember('HostC');
    const guest = await newMember('GuestC');
    await makeFriends(owner, guest);

    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [guest.uid] })).data as {
      convoy: ConvoySummary;
    };

    await signInAs(guest);
    const guestList = (await call('convoy-list', {})).data as {
      convoys: ConvoySummary[];
      pendingInvites: ConvoySummary[];
    };
    expect(guestList.pendingInvites.some((c) => c.convoyId === created.convoy.convoyId)).toBe(true);
    const pending = guestList.pendingInvites.find((c) => c.convoyId === created.convoy.convoyId)!;
    expect(pending.viewer).toEqual({ role: 'member', inviteStatus: 'invited' });
  });
});

describe('convoys Firestore rules', () => {
  it('members read their convoy; outsiders cannot; no client writes', async () => {
    const owner = await newMember('RuleOwnerC');
    const member = await newMember('RuleMemberC');
    const outsider = await newMember('RuleOutsiderC');
    await makeFriends(owner, member);

    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [member.uid] })).data as {
      convoy: ConvoySummary;
    };
    const convoyId = created.convoy.convoyId;

    // Owner + invited member both read the convoy doc.
    const asOwner = await getDoc(doc(firestore, 'convoys', convoyId));
    expect(asOwner.exists()).toBe(true);
    await signInAs(member);
    const asMember = await getDoc(doc(firestore, 'convoys', convoyId));
    expect(asMember.exists()).toBe(true);

    // A third party cannot read it.
    await signInAs(outsider);
    await expect(getDoc(doc(firestore, 'convoys', convoyId))).rejects.toMatchObject({
      code: 'permission-denied',
    });

    // No direct client writes (not even the owner).
    await signInAs(owner);
    await expect(
      setDoc(doc(firestore, 'convoys', convoyId), { status: 'ended' }, { merge: true }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    await expect(
      setDoc(doc(firestore, 'convoys', 'forged'), { ownerUid: owner.uid, memberUids: [owner.uid] }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
  });

  it('denies a suspended member (still in memberUids) from reading', async () => {
    const owner = await newMember('SuspOwnerC');
    const member = await newMember('SuspMemberC');
    await makeFriends(owner, member);

    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [member.uid] })).data as {
      convoy: ConvoySummary;
    };
    const convoyId = created.convoy.convoyId;

    // The member can read while active.
    await signInAs(member);
    expect((await getDoc(doc(firestore, 'convoys', convoyId))).exists()).toBe(true);

    // Suspend the member: isActiveMember() (suspension override) must now deny
    // the read even though they remain listed in memberUids.
    await adminAuth.setCustomUserClaims(member.uid, { activeMember: true, suspended: true });
    await signInAs(member); // forces a fresh ID token carrying the suspended claim
    await expect(getDoc(doc(firestore, 'convoys', convoyId))).rejects.toMatchObject({
      code: 'permission-denied',
    });
  });
});
