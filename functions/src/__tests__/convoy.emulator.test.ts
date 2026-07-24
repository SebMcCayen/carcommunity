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
import {
  FieldValue,
  Timestamp,
  getFirestore as getAdminFirestore,
} from 'firebase-admin/firestore';
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
interface ConvoyDestination {
  latitude: number;
  longitude: number;
  label: string | null;
  setByUid: string;
  setByDisplayName: string | null;
  setAt: string | null;
}
interface ConvoySummary {
  convoyId: string;
  ownerUid: string;
  status: 'forming' | 'active' | 'ended';
  members: ConvoyMember[];
  memberUids: string[];
  viewer: { role: string; inviteStatus: string } | null;
  livePositionUids: string[];
  destination: ConvoyDestination | null;
  summary: { durationSeconds: number; participantUids: string[]; participantCount: number; distanceMeters: number | null } | null;
  startedAt: string | null;
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

  it('admits a non-member while member gating is disabled', async () => {
    // Was: permission-denied on the member gate. The call now gets PAST the
    // gate and fails on the real reason instead: the only invitee is an
    // unknown uid, so there is no one to put in the convoy.
    const free = await newFreeUser();
    await signInAs(free);
    expect(await callableErrorCode(call('convoy-create', { inviteeUids: ['someone'] }))).toBe(
      'functions/failed-precondition',
    );
  });

  it('lets a non-member actually CREATE a convoy with a real friend', async () => {
    const freeOwner = await newFreeUser();
    const friend = await newMember('FreeOwnerFriendC');
    await makeFriends(freeOwner, friend);
    await signInAs(freeOwner);
    const result = (await call('convoy-create', { inviteeUids: [friend.uid] })).data as {
      convoy: ConvoySummary;
      invited: string[];
    };
    expect(result.invited).toEqual([friend.uid]);
    expect(result.convoy.memberUids).toContain(friend.uid);
  });

  it('STILL rejects a suspended caller', async () => {
    const suspended = await newFreeUser();
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
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
    // A block edge (either direction) is surfaced as the neutral `not_found`,
    // never a distinct `blocked` reason, so the inviter can't infer who blocked
    // whom (privacy parity with friends/dm).
    expect(reasons[blockedFriend.uid]).toBe('not_found');
    expect(reasons[owner.uid]).toBe('self');
    // Second occurrence of friend.uid is a duplicate.
    expect(result.skipped.some((s) => s.uid === friend.uid && s.reason === 'duplicate')).toBe(true);

    // A convoy is born ACTIVE — creating it is the act of going live.
    expect(result.convoy.status).toBe('active');
    expect(result.convoy.startedAt).not.toBeNull();
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

  it('INVITES a non-member friend while member gating is disabled', async () => {
    const owner = await newMember('MemberOwnerC');
    const friendMember = await newMember('ActiveFriendC');
    // Was: skipped as not_found, because a non-member could never accept/see a
    // convoy. With gating disabled they can, so they are invited normally.
    // Re-locking restores the skip (loadProfile in convoy/manageConvoy.ts).
    const freeFriend = await newFreeUser();
    await makeFriends(owner, friendMember);
    await makeFriends(owner, freeFriend);

    await signInAs(owner);
    const result = (
      await call('convoy-create', { inviteeUids: [friendMember.uid, freeFriend.uid] })
    ).data as { convoy: ConvoySummary; invited: string[]; skipped: Array<{ uid: string; reason: string }> };

    expect(result.invited.sort()).toEqual([friendMember.uid, freeFriend.uid].sort());
    expect(result.skipped).toEqual([]);
    expect(result.convoy.memberUids).toContain(freeFriend.uid);
  });

  it('STILL skips a SUSPENDED friend (not_found)', async () => {
    // Teeth: loadProfile drops suspended/deleted invitees regardless of the
    // gating switch — they must never be written in or notified.
    const owner = await newMember('SuspOwnerC');
    const friendMember = await newMember('ActiveFriendC2');
    const suspendedFriend = await newFreeUser();
    await makeFriends(owner, friendMember);
    await makeFriends(owner, suspendedFriend);
    await adminDb
      .collection('users')
      .doc(suspendedFriend.uid)
      .set({ suspended: true }, { merge: true });

    await signInAs(owner);
    const result = (
      await call('convoy-create', { inviteeUids: [friendMember.uid, suspendedFriend.uid] })
    ).data as { convoy: ConvoySummary; invited: string[]; skipped: Array<{ uid: string; reason: string }> };

    expect(result.invited).toEqual([friendMember.uid]);
    expect(result.skipped).toEqual([{ uid: suspendedFriend.uid, reason: 'not_found' }]);
    const freeFriend = suspendedFriend;
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
    // The convoy is ACTIVE from create (no separate owner start step).
    expect(created.convoy.status).toBe('active');

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

    // The convoy is ALREADY active from create, so the legacy owner start is a
    // no-op that fails (the convoy is no longer `forming`).
    await signInAs(owner);
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

// ---------------------------------------------------------------------------
// convoy-leave / convoy-invite / convoy-setDestination / convoy-clearDestination
//
// displayNames in this file all carry the `C` suffix: the emulator suite shares
// ONE Firestore with no isolation between files, so a bare "Owner" would collide
// with another file's fixtures and flake.
// ---------------------------------------------------------------------------

/** Creates an owner + one accepted member, returning both and the convoy id. */
async function convoyWithAcceptedMember(
  ownerName: string,
  memberName: string,
): Promise<{ owner: TestUser; member: TestUser; convoyId: string }> {
  const owner = await newMember(ownerName);
  const member = await newMember(memberName);
  await makeFriends(owner, member);
  await signInAs(owner);
  const created = (await call('convoy-create', { inviteeUids: [member.uid] })).data as {
    convoy: ConvoySummary;
  };
  const convoyId = created.convoy.convoyId;
  await signInAs(member);
  await call('convoy-respond', { convoyId, action: 'accept' });
  return { owner, member, convoyId };
}

describe('convoy-leave', () => {
  it('removes an ACCEPTED member from every membership collection', async () => {
    const { owner, member, convoyId } = await convoyWithAcceptedMember('LeaveOwnerC', 'LeaverC');

    await signInAs(member);
    const left = (await call('convoy-leave', { convoyId })).data as {
      convoy: ConvoySummary;
      remainingMemberCount: number;
    };
    // Only the owner is left, and the convoy is NOT auto-ended.
    expect(left.remainingMemberCount).toBe(1);
    expect(left.convoy.status).not.toBe('ended');
    expect(left.convoy.memberUids).not.toContain(member.uid);
    expect(left.convoy.members.some((m) => m.uid === member.uid)).toBe(false);
    expect(left.convoy.livePositionUids).toEqual([owner.uid]);
    // The caller is no longer a member, so viewer is null rather than a lie.
    expect(left.convoy.viewer).toBeNull();

    // The stored doc agrees (memberUids is what the rules read gate uses).
    const stored = await adminDb.collection('convoys').doc(convoyId).get();
    expect(stored.data()!.memberUids).not.toContain(member.uid);
    expect(stored.data()!.memberProfiles[member.uid]).toBeUndefined();

    // The leaver has actually lost their read on the convoy doc...
    await expect(getDoc(doc(firestore, 'convoys', convoyId))).rejects.toMatchObject({
      code: 'permission-denied',
    });
    // ...and the convoy no longer appears in their list.
    const list = (await call('convoy-list', {})).data as { convoys: ConvoySummary[] };
    expect(list.convoys.some((c) => c.convoyId === convoyId)).toBe(false);

    // Leaving twice is not-found (they are no longer a member) — never a
    // silent success that would imply they were still in it.
    expect(await callableErrorCode(call('convoy-leave', { convoyId }))).toBe('functions/not-found');
  });

  it('refuses the OWNER (they must end the convoy for everyone instead)', async () => {
    const { owner, convoyId } = await convoyWithAcceptedMember('OwnerStaysC', 'StayerC');
    await signInAs(owner);
    expect(await callableErrorCode(call('convoy-leave', { convoyId }))).toBe(
      'functions/failed-precondition',
    );
    // The convoy is untouched — the owner is still in it.
    const stored = await adminDb.collection('convoys').doc(convoyId).get();
    expect(stored.data()!.memberUids).toContain(owner.uid);
    expect(stored.data()!.status).not.toBe('ended');
    // convoy-end is the owner's actual path, and it still works.
    const ended = (await call('convoy-end', { convoyId })).data as { convoy: ConvoySummary };
    expect(ended.convoy.status).toBe('ended');
  });

  it('refuses a still-INVITED member, an outsider, and an ended convoy', async () => {
    const owner = await newMember('InviteOnlyOwnerC');
    const invitee = await newMember('NeverAnsweredC');
    await makeFriends(owner, invitee);
    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [invitee.uid] })).data as {
      convoy: ConvoySummary;
    };
    const convoyId = created.convoy.convoyId;

    // Invited but not accepted: there is nothing to leave — respond is the path.
    await signInAs(invitee);
    expect(await callableErrorCode(call('convoy-leave', { convoyId }))).toBe(
      'functions/failed-precondition',
    );

    // A total outsider gets not-found, so a convoy cannot be probed.
    const outsider = await newMember('LeaveOutsiderC');
    await signInAs(outsider);
    expect(await callableErrorCode(call('convoy-leave', { convoyId }))).toBe('functions/not-found');

    // Ended convoy: nothing to leave.
    await signInAs(invitee);
    await call('convoy-respond', { convoyId, action: 'accept' });
    await signInAs(owner);
    await call('convoy-end', { convoyId });
    await signInAs(invitee);
    expect(await callableErrorCode(call('convoy-leave', { convoyId }))).toBe(
      'functions/failed-precondition',
    );
  });
});

describe('convoy-invite', () => {
  it('lets ANY accepted member grow the convoy with THEIR OWN friend', async () => {
    const { owner, member, convoyId } = await convoyWithAcceptedMember('GrowOwnerC', 'GrowMemberC');
    // A friend of the MEMBER, and deliberately NOT a friend of the owner — the
    // friend edge is checked against the inviter, not the owner.
    const newcomer = await newMember('NewcomerC');
    await makeFriends(member, newcomer);

    await signInAs(member);
    const result = (await call('convoy-invite', { convoyId, inviteeUids: [newcomer.uid] })).data as {
      convoy: ConvoySummary;
      invited: string[];
      skipped: Array<{ uid: string; reason: string }>;
    };
    expect(result.invited).toEqual([newcomer.uid]);
    expect(result.skipped).toEqual([]);
    expect(result.convoy.memberUids).toContain(newcomer.uid);
    const entry = result.convoy.members.find((m) => m.uid === newcomer.uid)!;
    expect(entry.role).toBe('member');
    expect(entry.inviteStatus).toBe('invited');
    // Not accepted yet, so not in the live-position set.
    expect(result.convoy.livePositionUids).not.toContain(newcomer.uid);

    // Same 'convoy_invite' notification path as convoy-create.
    const items = await adminDb
      .collection('notifications')
      .doc(newcomer.uid)
      .collection('items')
      .get();
    expect(items.docs.some((d) => d.data().relatedEntityId === convoyId)).toBe(true);

    // The newcomer can accept and join for real.
    await signInAs(newcomer);
    const accepted = (await call('convoy-respond', { convoyId, action: 'accept' })).data as {
      convoy: ConvoySummary;
    };
    expect(accepted.convoy.livePositionUids.sort()).toEqual(
      [owner.uid, member.uid, newcomer.uid].sort(),
    );
  });

  it('skips non-friends, self, duplicates, and people ALREADY in the convoy', async () => {
    const { owner, member, convoyId } = await convoyWithAcceptedMember('SkipOwnerC', 'SkipMemberC');
    const stranger = await newMember('InviteStrangerC');
    const friend = await newMember('InviteFriendC');
    await makeFriends(owner, friend);

    await signInAs(owner);
    const result = (
      await call('convoy-invite', {
        convoyId,
        inviteeUids: [friend.uid, stranger.uid, member.uid, owner.uid, friend.uid],
      })
    ).data as { invited: string[]; skipped: Array<{ uid: string; reason: string }> };

    expect(result.invited).toEqual([friend.uid]);
    const reasons = Object.fromEntries(result.skipped.map((s) => [s.uid, s.reason]));
    expect(reasons[stranger.uid]).toBe('not_friend');
    // Already in the convoy — distinct from `duplicate` (listed twice in THIS
    // request), which is what the second friend.uid gets.
    expect(reasons[member.uid]).toBe('already_member');
    expect(reasons[owner.uid]).toBe('self');
    expect(result.skipped.some((s) => s.uid === friend.uid && s.reason === 'duplicate')).toBe(true);
  });

  it('honours blocks against the INVITER and against every other accepted member', async () => {
    const { owner, member, convoyId } = await convoyWithAcceptedMember('BlockOwnerC', 'BlockMemberC');
    // A friend of the member who has blocked the OWNER: they must not be pulled
    // into a convoy with someone they blocked, even though the inviter is fine.
    const blocker = await newMember('BlockerC');
    await makeFriends(member, blocker);
    await signInAs(blocker);
    await call('blocking-block', { targetUserId: owner.uid });

    await signInAs(member);
    // The only requested uid is dropped, so there is no one left to add →
    // failed-precondition. The block is never surfaced as its own error or
    // reason (in a mixed batch it is the neutral `not_found`), so the inviter
    // cannot infer who blocked whom.
    expect(
      await callableErrorCode(call('convoy-invite', { convoyId, inviteeUids: [blocker.uid] })),
    ).toBe('functions/failed-precondition');

    // ...and they are genuinely not in the convoy.
    const stored = await adminDb.collection('convoys').doc(convoyId).get();
    expect(stored.data()!.memberUids).not.toContain(blocker.uid);
  }, 60_000);

  it('is IDEMPOTENT when everyone requested is already in the convoy', async () => {
    const { owner, convoyId } = await convoyWithAcceptedMember('IdemOwnerC', 'IdemMemberC');
    const friend = await newMember('IdemFriendC');
    const stranger = await newMember('IdemStrangerC');
    await makeFriends(owner, friend);

    await signInAs(owner);
    const first = (await call('convoy-invite', { convoyId, inviteeUids: [friend.uid] }))
      .data as { invited: string[] };
    expect(first.invited).toEqual([friend.uid]);

    // TEETH: this second call previously threw failed-precondition ("No one
    // could be added"), even though the post-state the caller asked for is
    // exactly the post-state that exists. Nothing to add is a SUCCESS with an
    // empty `invited`, not an error the client has to re-interpret.
    const again = (await call('convoy-invite', { convoyId, inviteeUids: [friend.uid] })).data as {
      convoy: ConvoySummary;
      invited: string[];
      skipped: Array<{ uid: string; reason: string }>;
    };
    expect(again.invited).toEqual([]);
    expect(again.skipped).toEqual([{ uid: friend.uid, reason: 'already_member' }]);
    // Idempotent for real: no duplicate roster entry, and the existing invite
    // was not reset.
    expect(again.convoy.memberUids.filter((uid) => uid === friend.uid)).toHaveLength(1);
    expect(again.convoy.members.find((m) => m.uid === friend.uid)!.inviteStatus).toBe('invited');

    // A batch mixing an existing member with someone who cannot be added is
    // still a success — `skipped` carries both reasons, in REQUEST order.
    const mixed = (
      await call('convoy-invite', { convoyId, inviteeUids: [friend.uid, stranger.uid] })
    ).data as { invited: string[]; skipped: Array<{ uid: string; reason: string }> };
    expect(mixed.invited).toEqual([]);
    expect(mixed.skipped).toEqual([
      { uid: friend.uid, reason: 'already_member' },
      { uid: stranger.uid, reason: 'not_friend' },
    ]);

    // ...but a request naming NOBODY who is already in still fails: the caller
    // asked for something that genuinely did not happen.
    expect(
      await callableErrorCode(call('convoy-invite', { convoyId, inviteeUids: [stranger.uid] })),
    ).toBe('functions/failed-precondition');

    // ...and it stays idempotent on a FULL convoy. The cap rejects GROWTH, so
    // a re-invite of someone already aboard must not be answered with "convoy
    // is full". Padded with synthetic uids rather than 23 real accounts: the
    // cap is read off memberUids.length, which is what the pre-check gates on.
    const current = await adminDb.collection('convoys').doc(convoyId).get();
    const padding = Array.from(
      { length: 25 - (current.data()!.memberUids as string[]).length },
      (_, i) => `filler-uid-${i}`,
    );
    await adminDb
      .collection('convoys')
      .doc(convoyId)
      .update({ memberUids: FieldValue.arrayUnion(...padding) });
    const full = await adminDb.collection('convoys').doc(convoyId).get();
    expect((full.data()!.memberUids as string[]).length).toBe(25); // MAX_CONVOY_SIZE

    const onFull = (await call('convoy-invite', { convoyId, inviteeUids: [friend.uid] }))
      .data as { invited: string[]; skipped: Array<{ uid: string; reason: string }> };
    expect(onFull.invited).toEqual([]);
    expect(onFull.skipped).toEqual([{ uid: friend.uid, reason: 'already_member' }]);

    // The cap itself is NOT relaxed: real growth against a full convoy is
    // still refused.
    const newFriend = await newMember('IdemFullFriendC');
    await makeFriends(owner, newFriend);
    await signInAs(owner);
    expect(
      await callableErrorCode(call('convoy-invite', { convoyId, inviteeUids: [newFriend.uid] })),
    ).toBe('functions/failed-precondition');
    const after = await adminDb.collection('convoys').doc(convoyId).get();
    expect(after.data()!.memberUids).not.toContain(newFriend.uid);
  }, 60_000);

  it('refuses a still-invited caller, an outsider, and an ended convoy', async () => {
    const owner = await newMember('InvGateOwnerC');
    const pending = await newMember('InvGatePendingC');
    const theirFriend = await newMember('InvGateFriendC');
    await makeFriends(owner, pending);
    await makeFriends(pending, theirFriend);

    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [pending.uid] })).data as {
      convoy: ConvoySummary;
    };
    const convoyId = created.convoy.convoyId;

    // Invited-but-unanswered: not in the convoy yet, so cannot grow it.
    await signInAs(pending);
    expect(
      await callableErrorCode(call('convoy-invite', { convoyId, inviteeUids: [theirFriend.uid] })),
    ).toBe('functions/failed-precondition');

    // Outsider → not-found (no probing).
    const outsider = await newMember('InvGateOutsiderC');
    await signInAs(outsider);
    expect(
      await callableErrorCode(call('convoy-invite', { convoyId, inviteeUids: [theirFriend.uid] })),
    ).toBe('functions/not-found');

    // Ended convoy cannot grow.
    await signInAs(pending);
    await call('convoy-respond', { convoyId, action: 'accept' });
    await signInAs(owner);
    await call('convoy-end', { convoyId });
    await signInAs(pending);
    expect(
      await callableErrorCode(call('convoy-invite', { convoyId, inviteeUids: [theirFriend.uid] })),
    ).toBe('functions/failed-precondition');
  }, 60_000);
});

describe('convoy shared destination', () => {
  it('any accepted member sets it; every member reads it off the summary', async () => {
    const { owner, member, convoyId } = await convoyWithAcceptedMember('DestOwnerC', 'DestMemberC');

    // A MEMBER (not the owner) sets it — the peer-group decision.
    await signInAs(member);
    const set = (
      await call('convoy-setDestination', {
        convoyId,
        latitude: 57.4879,
        longitude: 12.076,
        label: '  Kungsbacka torg  ',
      })
    ).data as { convoy: ConvoySummary };
    expect(set.convoy.destination).toMatchObject({
      latitude: 57.4879,
      longitude: 12.076,
      label: 'Kungsbacka torg', // trimmed
      setByUid: member.uid, // server-stamped from auth, never client-supplied
      setByDisplayName: 'DestMemberC', // denormalized, no profile fetch needed
    });
    expect(set.convoy.destination!.setAt).not.toBeNull();

    // The OWNER receives it through the convoy read path they already use —
    // no second listener, no separate read.
    await signInAs(owner);
    const list = (await call('convoy-list', {})).data as { convoys: ConvoySummary[] };
    const seen = list.convoys.find((c) => c.convoyId === convoyId)!;
    expect(seen.destination!.latitude).toBe(57.4879);
    expect(seen.destination!.setByUid).toBe(member.uid);

    // Setting REPLACES (last write wins, one destination per convoy).
    const replaced = (
      await call('convoy-setDestination', { convoyId, latitude: 57.7, longitude: 11.97 })
    ).data as { convoy: ConvoySummary };
    expect(replaced.convoy.destination).toMatchObject({
      latitude: 57.7,
      longitude: 11.97,
      label: null, // no label on the new pick — not inherited from the old one
      setByUid: owner.uid,
    });
  });

  it('validates coordinates, label length, and rejects a still-invited caller', async () => {
    const owner = await newMember('DestGateOwnerC');
    const pending = await newMember('DestGatePendingC');
    await makeFriends(owner, pending);
    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [pending.uid] })).data as {
      convoy: ConvoySummary;
    };
    const convoyId = created.convoy.convoyId;

    for (const bad of [
      { latitude: 91, longitude: 0 },
      { latitude: 0, longitude: 181 },
      { latitude: 'x', longitude: 0 },
      { latitude: 0, longitude: 0, label: 'x'.repeat(121) },
    ]) {
      expect(await callableErrorCode(call('convoy-setDestination', { convoyId, ...bad }))).toBe(
        'functions/invalid-argument',
      );
    }

    // An active (non-ended) convoy may have a destination set.
    const onActive = (
      await call('convoy-setDestination', { convoyId, latitude: 57.5, longitude: 12.0 })
    ).data as { convoy: ConvoySummary };
    expect(onActive.convoy.status).toBe('active');
    expect(onActive.convoy.destination).not.toBeNull();

    // Invited-but-unanswered cannot set it.
    await signInAs(pending);
    expect(
      await callableErrorCode(call('convoy-setDestination', { convoyId, latitude: 0, longitude: 0 })),
    ).toBe('functions/failed-precondition');

    // Outsider → not-found (no probing).
    const outsider = await newMember('DestOutsiderC');
    await signInAs(outsider);
    expect(
      await callableErrorCode(call('convoy-setDestination', { convoyId, latitude: 0, longitude: 0 })),
    ).toBe('functions/not-found');
  }, 60_000);

  it('clears only for the SETTER or the OWNER, and is an idempotent no-op when empty', async () => {
    const owner = await newMember('ClearOwnerC');
    const setter = await newMember('ClearSetterC');
    const other = await newMember('ClearOtherC');
    await makeFriends(owner, setter);
    await makeFriends(owner, other);
    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [setter.uid, other.uid] })).data as {
      convoy: ConvoySummary;
    };
    const convoyId = created.convoy.convoyId;
    await signInAs(setter);
    await call('convoy-respond', { convoyId, action: 'accept' });
    await signInAs(other);
    await call('convoy-respond', { convoyId, action: 'accept' });

    await signInAs(setter);
    await call('convoy-setDestination', { convoyId, latitude: 57.4, longitude: 12.0 });

    // A third accepted member may NOT wipe a plan the group is following. They
    // know the convoy exists, so permission-denied (not not-found) is honest.
    await signInAs(other);
    expect(await callableErrorCode(call('convoy-clearDestination', { convoyId }))).toBe(
      'functions/permission-denied',
    );

    // The setter can clear their own.
    await signInAs(setter);
    const cleared = (await call('convoy-clearDestination', { convoyId })).data as {
      convoy: ConvoySummary;
    };
    expect(cleared.convoy.destination).toBeNull();
    // Clearing nothing is a no-op, not an error (two people tapping at once).
    const again = (await call('convoy-clearDestination', { convoyId })).data as {
      convoy: ConvoySummary;
    };
    expect(again.convoy.destination).toBeNull();

    // The OWNER can clear someone else's (the moderation path).
    await signInAs(other);
    await call('convoy-setDestination', { convoyId, latitude: 57.6, longitude: 12.1 });
    await signInAs(owner);
    const ownerCleared = (await call('convoy-clearDestination', { convoyId })).data as {
      convoy: ConvoySummary;
    };
    expect(ownerCleared.convoy.destination).toBeNull();
  }, 60_000);

  it('stores a missing/blank label ABSENT and stamps setAt from the SERVER clock', async () => {
    const { owner, convoyId } = await convoyWithAcceptedMember('LabelOwnerC', 'LabelMemberC');
    await signInAs(owner);

    const before = Date.now();
    await call('convoy-setDestination', {
      convoyId,
      latitude: 57.4,
      longitude: 12.0,
      label: '   ', // blank AFTER TRIM
    });
    const stored = () =>
      adminDb
        .collection('convoys')
        .doc(convoyId)
        .get()
        .then((snap) => snap.data()!.destination as Record<string, unknown>);

    // TEETH: the field is ABSENT, as the schema and the field docs say — not
    // stored as null. `Object.keys` is the assertion because reading
    // `dest.label` is undefined either way.
    expect(Object.keys(await stored())).not.toContain('label');

    // setAt is a real server Timestamp, not the function instance's wall clock.
    const setAt = (await stored()).setAt;
    expect(setAt).toBeInstanceOf(Timestamp);
    expect((setAt as Timestamp).toMillis()).toBeGreaterThanOrEqual(before - 60_000);

    // A labelled pick REPLACED by an unlabelled one must not inherit the old
    // label: the destination map is written whole, never merged field-by-field.
    await call('convoy-setDestination', { convoyId, latitude: 57.5, longitude: 12.1, label: 'Torget' });
    expect((await stored()).label).toBe('Torget');
    const replaced = (
      await call('convoy-setDestination', { convoyId, latitude: 57.6, longitude: 12.2 })
    ).data as { convoy: ConvoySummary };
    expect(Object.keys(await stored())).not.toContain('label');
    // ...and the wire shape is unchanged either way: absent reads back as null.
    expect(replaced.convoy.destination!.label).toBeNull();
    expect(replaced.convoy.destination!.setAt).not.toBeNull();
  }, 60_000);

  it('SURVIVES convoy-end untouched (a record of where the convoy was headed)', async () => {
    const { owner, convoyId } = await convoyWithAcceptedMember('EndDestOwnerC', 'EndDestMemberC');
    await signInAs(owner);
    await call('convoy-setDestination', {
      convoyId,
      latitude: 57.4879,
      longitude: 12.076,
      label: 'Slutmål',
    });
    const ended = (await call('convoy-end', { convoyId })).data as { convoy: ConvoySummary };
    expect(ended.convoy.status).toBe('ended');
    // Reaching it did NOT end the convoy — the owner did — and ending did not
    // wipe the destination.
    expect(ended.convoy.destination).toMatchObject({
      latitude: 57.4879,
      longitude: 12.076,
      label: 'Slutmål',
    });

    // An ended convoy's destination is inert: it can be neither set nor cleared.
    expect(
      await callableErrorCode(call('convoy-setDestination', { convoyId, latitude: 0, longitude: 0 })),
    ).toBe('functions/failed-precondition');
    expect(await callableErrorCode(call('convoy-clearDestination', { convoyId }))).toBe(
      'functions/failed-precondition',
    );
  });

  it('keeps the destination out of reach of direct client writes', async () => {
    const { owner, convoyId } = await convoyWithAcceptedMember('RulesDestOwnerC', 'RulesDestMemberC');
    await signInAs(owner);
    // The convoy doc stays callable-only — a client cannot forge a destination
    // (or an attribution) by writing the field directly.
    await expect(
      setDoc(
        doc(firestore, 'convoys', convoyId),
        { destination: { latitude: 0, longitude: 0, setByUid: 'someone-else' } },
        { merge: true },
      ),
    ).rejects.toMatchObject({ code: 'permission-denied' });
  });
});

// ---------------------------------------------------------------------------
// ITEM 1 — one convoy at a time. A user who is an ACTIVE PARTICIPANT (owner, or
// an accepted member) of a non-ended convoy cannot create OR accept into a
// second one until they leave/end the first.
// ---------------------------------------------------------------------------
describe('convoy one-at-a-time enforcement', () => {
  it('blocks the OWNER of an active convoy from creating another', async () => {
    const owner = await newMember('OneOwnerC');
    const f1 = await newMember('OneF1C');
    const f2 = await newMember('OneF2C');
    await makeFriends(owner, f1);
    await makeFriends(owner, f2);

    await signInAs(owner);
    await call('convoy-create', { inviteeUids: [f1.uid] });
    // Already the owner (accepted participant) of an active convoy → the second
    // create is refused.
    expect(await callableErrorCode(call('convoy-create', { inviteeUids: [f2.uid] }))).toBe(
      'functions/failed-precondition',
    );
  });

  it('blocks an ACCEPTED member from creating their own convoy', async () => {
    const { member } = await convoyWithAcceptedMember('OneAccOwnerC', 'OneAccMemberC');
    const theirFriend = await newMember('OneAccFriendC');
    await makeFriends(member, theirFriend);

    // The member is an accepted participant of the first convoy, so they cannot
    // spin up a second one of their own.
    await signInAs(member);
    expect(
      await callableErrorCode(call('convoy-create', { inviteeUids: [theirFriend.uid] })),
    ).toBe('functions/failed-precondition');
  });

  it('blocks accepting a SECOND convoy while already accepted in one (decline is fine)', async () => {
    const { member, convoyId: firstConvoyId } = await convoyWithAcceptedMember(
      'OneSecOwnerC',
      'OneSecMemberC',
    );
    // A second owner invites the same member into a second convoy.
    const owner2 = await newMember('OneSecOwner2C');
    await makeFriends(owner2, member);
    await signInAs(owner2);
    const second = (await call('convoy-create', { inviteeUids: [member.uid] })).data as {
      convoy: ConvoySummary;
    };
    const secondConvoyId = second.convoy.convoyId;

    // The member is already accepted in the first convoy → accepting the second
    // is refused...
    await signInAs(member);
    expect(
      await callableErrorCode(call('convoy-respond', { convoyId: secondConvoyId, action: 'accept' })),
    ).toBe('functions/failed-precondition');
    // ...but DECLINING it is always allowed (it commits to nothing).
    const declined = (
      await call('convoy-respond', { convoyId: secondConvoyId, action: 'decline' })
    ).data as { inviteStatus: string };
    expect(declined.inviteStatus).toBe('declined');
    // The first convoy membership is untouched.
    const stored = await adminDb.collection('convoys').doc(firstConvoyId).get();
    expect(stored.data()!.members[member.uid].inviteStatus).toBe('accepted');
  });

  it('a still-PENDING invite does NOT count — the invitee may still create/accept', async () => {
    const owner1 = await newMember('PendOwner1C');
    const user = await newMember('PendUserC');
    const theirFriend = await newMember('PendFriendC');
    await makeFriends(owner1, user);
    await makeFriends(user, theirFriend);

    // owner1 invites `user`, who does NOT answer — a pending invite.
    await signInAs(owner1);
    await call('convoy-create', { inviteeUids: [user.uid] });

    // `user` is only INVITED (not accepted) anywhere, so they can create their
    // own convoy...
    await signInAs(user);
    const created = (await call('convoy-create', { inviteeUids: [theirFriend.uid] })).data as {
      convoy: ConvoySummary;
    };
    expect(created.convoy.ownerUid).toBe(user.uid);
  });

  it('LEAVING frees an accepted member to join another convoy', async () => {
    const { member, convoyId } = await convoyWithAcceptedMember('LeaveFreeOwnerC', 'LeaveFreeMemberC');
    const owner2 = await newMember('LeaveFreeOwner2C');
    await makeFriends(owner2, member);
    await signInAs(owner2);
    const second = (await call('convoy-create', { inviteeUids: [member.uid] })).data as {
      convoy: ConvoySummary;
    };

    // Blocked while still in the first...
    await signInAs(member);
    expect(
      await callableErrorCode(
        call('convoy-respond', { convoyId: second.convoy.convoyId, action: 'accept' }),
      ),
    ).toBe('functions/failed-precondition');
    // ...leave the first, and now accepting the second succeeds.
    await call('convoy-leave', { convoyId });
    const accepted = (
      await call('convoy-respond', { convoyId: second.convoy.convoyId, action: 'accept' })
    ).data as { inviteStatus: string };
    expect(accepted.inviteStatus).toBe('accepted');
  });

  it('ENDING frees the owner to create another convoy', async () => {
    const owner = await newMember('EndFreeOwnerC');
    const f1 = await newMember('EndFreeF1C');
    const f2 = await newMember('EndFreeF2C');
    await makeFriends(owner, f1);
    await makeFriends(owner, f2);

    await signInAs(owner);
    const first = (await call('convoy-create', { inviteeUids: [f1.uid] })).data as {
      convoy: ConvoySummary;
    };
    // End it, then a fresh create is allowed.
    await call('convoy-end', { convoyId: first.convoy.convoyId });
    const second = (await call('convoy-create', { inviteeUids: [f2.uid] })).data as {
      convoy: ConvoySummary;
    };
    expect(second.convoy.convoyId).not.toBe(first.convoy.convoyId);
    expect(second.convoy.status).toBe('active');
  });

  it('races two simultaneous creates — exactly ONE wins (transaction serializes them)', async () => {
    const owner = await newMember('RaceOwnerC');
    const f1 = await newMember('RaceF1C');
    const f2 = await newMember('RaceF2C');
    await makeFriends(owner, f1);
    await makeFriends(owner, f2);

    await signInAs(owner);
    // Fire both creates concurrently. The "am I already in a convoy" check runs
    // inside each create's transaction, so at most one can commit — the other
    // sees the winner's convoy on retry and is rejected with failed-precondition.
    const results = await Promise.allSettled([
      call('convoy-create', { inviteeUids: [f1.uid] }),
      call('convoy-create', { inviteeUids: [f2.uid] }),
    ]);
    const fulfilled = results.filter((r) => r.status === 'fulfilled');
    const rejected = results.filter((r) => r.status === 'rejected');
    expect(fulfilled).toHaveLength(1);
    expect(rejected).toHaveLength(1);

    // Exactly one active convoy exists for this owner.
    const active = await adminDb
      .collection('convoys')
      .where('memberUids', 'array-contains', owner.uid)
      .where('status', 'in', ['forming', 'active'])
      .get();
    expect(active.docs).toHaveLength(1);
  }, 60_000);
});
