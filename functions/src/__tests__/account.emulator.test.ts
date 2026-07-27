/**
 * Account deletion emulator integration tests (Phase 9p).
 *
 * Exercises both stages: the immediate soft delete via
 * account-deleteAccount (Auth user disabled, users/{uid}.deleted, request
 * record, idempotency, callables closed) and the hard purge via the
 * exported runAccountPurge runner (Firestore trees, owned documents,
 * storage prefixes, Auth user, processed request record retained).
 *
 * Requires the Functions emulator — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= '127.0.0.1:9199';
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
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { getStorage as getAdminStorage } from 'firebase-admin/storage';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runAccountPurge } from '../account/scheduled';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'account-emulator-tests');
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);
const adminBucket = getAdminStorage(adminApp).bucket(`${PROJECT_ID}.appspot.com`);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

async function pollUntil<T>(
  read: () => Promise<T | undefined>,
  timeoutMs = 30_000,
  intervalMs = 250,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
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

async function createProvisionedUser(prefix: string): Promise<TestUser> {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
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

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'account-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('account-deleteAccount (soft delete)', () => {
  it('disables the auth user, marks deleted, records the request — idempotently', async () => {
    const user = await createProvisionedUser('del-user');
    await signInAs(user);

    const result = (await call('account-deleteAccount', { reason: 'Vill radera mitt konto.' }))
      .data as { requestId: string; status: string };
    expect(result).toEqual({ requestId: user.uid, status: 'pending' });

    const request = (
      await adminDb.collection('accountDeletionRequests').doc(user.uid).get()
    ).data()!;
    expect(request.status).toBe('pending');
    expect(request.reason).toBe('Vill radera mitt konto.');

    expect((await adminDb.collection('users').doc(user.uid).get()).data()!.deleted).toBe(true);
    expect((await adminAuth.getUser(user.uid)).disabled).toBe(true);

    // Idempotent replay with the still-valid ID token.
    const replay = (await call('account-deleteAccount', {})).data as { status: string };
    expect(replay.status).toBe('pending');

    // The deleted state closes normal callables for the remaining session.
    expect(await callableErrorCode(call('notifications-markAllRead', {}))).toBe(
      'functions/permission-denied',
    );
  });

  it('works while suspended (deletion is a support path)', async () => {
    const user = await createProvisionedUser('del-suspended');
    await adminDb.collection('users').doc(user.uid).set({ suspended: true }, { merge: true });
    await signInAs(user);

    const result = (await call('account-deleteAccount', {})).data as { status: string };
    expect(result.status).toBe('pending');
  });
});

describe('account purge (hard delete after retention)', () => {
  it('purges due requests: trees, owned docs, storage, auth user; keeps the record', async () => {
    const user = await createProvisionedUser('purge-user');
    const uid = user.uid;
    const dayMs = 24 * 60 * 60 * 1000;
    const now = new Date();

    // Seed data across the purge plan.
    await adminDb.collection('userPrivate').doc(uid).collection('pushTokens').doc('t'.repeat(64))
      .set({ platform: 'android', createdAt: Timestamp.now(), lastSeenAt: Timestamp.now() });
    await adminDb.collection('notifications').doc(uid).collection('items').doc('n1')
      .set({ category: 'system_notice', title: 'x', previewText: 'x', read: false, createdAt: Timestamp.now() });
    await adminDb.collection('pointsLedger').doc(uid).set({ balance: 10, updatedAt: Timestamp.now() });
    await adminDb.collection('userLifecycle').doc(uid).set({ lastLoginAt: Timestamp.now() });
    await adminDb.collection('vehicles').add({ userId: uid, make: 'Volvo' });
    await adminDb.collection('rides').add({ userId: uid, distanceMeters: 1000 });
    await adminBucket.file(`profileImages/${uid}/avatar.png`).save(Buffer.from('img'));
    await adminBucket.file(`rideRoutes/${uid}/ride-1/route.bin`).save(Buffer.from('gps'));

    // Chat footprint to be erased.
    // (a) A 1:1 DM conversation the user is a member of + a message in it.
    const convRef = adminDb.collection('conversations').doc(`${uid}__friend`);
    await convRef.set({ members: [uid, 'friend-uid'], createdAt: Timestamp.now() });
    await convRef.collection('messages').add({ senderUid: uid, text: 'dm hi', createdAt: Timestamp.now() });
    await convRef.collection('messages').add({ senderUid: 'friend-uid', text: 'dm reply', createdAt: Timestamp.now() });
    // (b) A community message authored by the user.
    const communityMsg = await adminDb
      .collection('communityChat').doc('global').collection('messages')
      .add({ senderUid: uid, text: 'community hi', createdAt: Timestamp.now() });
    // (c) A convoy message authored by the user.
    const convoyMsg = await adminDb
      .collection('convoyChats').doc('convoy-1').collection('messages')
      .add({ senderUid: uid, text: 'convoy hi', createdAt: Timestamp.now() });

    // Social-graph MIRRORS — rows living on OTHER users' documents.
    // (d) A friendship, stored on BOTH sides.
    const friendUid = 'graph-friend-uid';
    const ownFriendRef = adminDb.collection('users').doc(uid).collection('friends').doc(friendUid);
    const mirrorFriendRef = adminDb
      .collection('users').doc(friendUid).collection('friends').doc(uid);
    await ownFriendRef.set({ friendUid, displayName: 'Vän', avatarPath: null, createdAt: Timestamp.now() });
    await mirrorFriendRef.set({ friendUid: uid, displayName: 'Raderad', avatarPath: null, createdAt: Timestamp.now() });
    // (e) Pending friend requests in BOTH directions (pair-keyed, owned by neither side).
    const outgoingReqRef = adminDb.collection('friendRequests').doc(`${uid}__req-target`);
    const incomingReqRef = adminDb.collection('friendRequests').doc(`req-sender__${uid}`);
    await outgoingReqRef.set({ fromUid: uid, toUid: 'req-target', status: 'pending', createdAt: Timestamp.now() });
    await incomingReqRef.set({ fromUid: 'req-sender', toUid: uid, status: 'pending', createdAt: Timestamp.now() });
    // (f) Convoy membership: one the user merely belongs to, one they OWN (which
    //     must be ended so the survivors aren't stranded), one they are alone in
    //     (deleted outright).
    const memberConvoyRef = adminDb.collection('convoys').doc(`convoy-member-${uid}`);
    await memberConvoyRef.set({
      ownerUid: 'convoy-owner-uid',
      status: 'active',
      memberUids: ['convoy-owner-uid', uid],
      members: {
        'convoy-owner-uid': { uid: 'convoy-owner-uid', role: 'owner', inviteStatus: 'accepted' },
        [uid]: { uid, role: 'member', inviteStatus: 'accepted' },
      },
      memberProfiles: {
        'convoy-owner-uid': { displayName: 'Ägare', avatarPath: null },
        [uid]: { displayName: 'Raderad', avatarPath: null },
      },
      summary: null,
      createdAt: Timestamp.now(),
      startedAt: Timestamp.now(),
      endedAt: null,
    });
    const ownedConvoyRef = adminDb.collection('convoys').doc(`convoy-owned-${uid}`);
    await ownedConvoyRef.set({
      ownerUid: uid,
      status: 'active',
      memberUids: [uid, 'convoy-passenger-uid'],
      members: {
        [uid]: { uid, role: 'owner', inviteStatus: 'accepted' },
        'convoy-passenger-uid': { uid: 'convoy-passenger-uid', role: 'member', inviteStatus: 'accepted' },
      },
      memberProfiles: {
        [uid]: { displayName: 'Raderad', avatarPath: null },
        'convoy-passenger-uid': { displayName: 'Passagerare', avatarPath: null },
      },
      summary: null,
      createdAt: Timestamp.now(),
      startedAt: Timestamp.now(),
      endedAt: null,
    });
    const soloConvoyRef = adminDb.collection('convoys').doc(`convoy-solo-${uid}`);
    await soloConvoyRef.set({
      ownerUid: uid,
      status: 'active',
      memberUids: [uid],
      members: { [uid]: { uid, role: 'owner', inviteStatus: 'accepted' } },
      memberProfiles: { [uid]: { displayName: 'Raderad', avatarPath: null } },
      summary: null,
      createdAt: Timestamp.now(),
      startedAt: Timestamp.now(),
      endedAt: null,
    });
    // (g) An ALREADY-ENDED convoy: the stored summary names the deleted user in
    //     participantUids (convoy.end wrote it from the membership as it stood
    //     then, so stripping the maps alone would leave it), and they set the
    //     shared destination, whose setByDisplayName is a denormalized name just
    //     like memberProfiles'.
    const endedConvoyRef = adminDb.collection('convoys').doc(`convoy-ended-${uid}`);
    await endedConvoyRef.set({
      ownerUid: 'convoy-owner-uid',
      status: 'ended',
      memberUids: ['convoy-owner-uid', uid],
      members: {
        'convoy-owner-uid': { uid: 'convoy-owner-uid', role: 'owner', inviteStatus: 'accepted' },
        [uid]: { uid, role: 'member', inviteStatus: 'accepted' },
      },
      memberProfiles: {
        'convoy-owner-uid': { displayName: 'Ägare', avatarPath: null },
        [uid]: { displayName: 'Raderad', avatarPath: null },
      },
      destination: {
        latitude: 57.49,
        longitude: 12.07,
        label: 'Kungsbacka',
        setByUid: uid,
        setByDisplayName: 'Raderad',
        setAt: Timestamp.now(),
      },
      summary: {
        durationSeconds: 600,
        participantUids: ['convoy-owner-uid', uid],
        participantCount: 2,
        distanceMeters: null,
      },
      createdAt: Timestamp.now(),
      startedAt: Timestamp.now(),
      endedAt: Timestamp.now(),
    });

    // Controls that must SURVIVE the purge:
    // - another user's community message,
    const otherCommunityMsg = await adminDb
      .collection('communityChat').doc('global').collection('messages')
      .add({ senderUid: 'other-uid', text: 'keep me', createdAt: Timestamp.now() });
    // - a DM conversation the user is NOT part of,
    const otherConvRef = adminDb.collection('conversations').doc('x__y');
    await otherConvRef.set({ members: ['x-uid', 'y-uid'], createdAt: Timestamp.now() });
    // - an EVENT chat message authored by the user (keyed on authorUserId; retained),
    const eventMsg = await adminDb
      .collection('events').doc('event-1').collection('messages')
      .add({ authorUserId: uid, text: 'event hi', createdAt: Timestamp.now() });
    // - a friendship between two OTHER members (the collection-group mirror
    //   sweep must not touch friend rows that merely live next to the deleted
    //   user's),
    const bystanderFriendRef = adminDb
      .collection('users').doc(friendUid).collection('friends').doc('bystander-uid');
    await bystanderFriendRef.set({
      friendUid: 'bystander-uid', displayName: 'Kvar', avatarPath: null, createdAt: Timestamp.now(),
    });
    // - a friend request between two other members.
    const bystanderReqRef = adminDb.collection('friendRequests').doc('req-sender__req-target');
    await bystanderReqRef.set({
      fromUid: 'req-sender', toUid: 'req-target', status: 'pending', createdAt: Timestamp.now(),
    });

    // A due (31-day-old pending) request and soft-delete state.
    await adminDb.collection('accountDeletionRequests').doc(uid).set({
      userId: uid,
      reason: null,
      status: 'pending',
      createdAt: Timestamp.fromDate(new Date(now.getTime() - 31 * dayMs)),
    });

    // A NOT-due request for another user must be untouched.
    const fresh = await createProvisionedUser('purge-fresh');
    await adminDb.collection('accountDeletionRequests').doc(fresh.uid).set({
      userId: fresh.uid,
      reason: null,
      status: 'pending',
      createdAt: Timestamp.fromDate(new Date(now.getTime() - 1 * dayMs)),
    });

    const result = await runAccountPurge(now);
    expect(result.purgedUids).toContain(uid);
    expect(result.purgedUids).not.toContain(fresh.uid);

    // Firestore trees and owned docs gone.
    expect((await adminDb.collection('users').doc(uid).get()).exists).toBe(false);
    expect((await adminDb.collection('userPrivate').doc(uid).collection('pushTokens').get()).size).toBe(0);
    expect((await adminDb.collection('notifications').doc(uid).collection('items').get()).size).toBe(0);
    expect((await adminDb.collection('pointsLedger').doc(uid).get()).exists).toBe(false);
    expect((await adminDb.collection('userLifecycle').doc(uid).get()).exists).toBe(false);
    expect((await adminDb.collection('vehicles').where('userId', '==', uid).get()).size).toBe(0);
    expect((await adminDb.collection('rides').where('userId', '==', uid).get()).size).toBe(0);

    // Chat erased: DM conversation (+messages) gone, authored community + convoy
    // messages gone.
    expect((await convRef.get()).exists).toBe(false);
    expect((await convRef.collection('messages').get()).size).toBe(0);
    expect((await communityMsg.get()).exists).toBe(false);
    expect((await convoyMsg.get()).exists).toBe(false);

    // Friend graph erased on BOTH sides: the deleted user's own row went with the
    // users/{uid} tree, and the MIRROR row on the remaining friend — which
    // friend.list would otherwise still return with the deleted displayName — is
    // gone too. Pending requests in both directions are swept.
    expect((await ownFriendRef.get()).exists).toBe(false);
    expect((await mirrorFriendRef.get()).exists).toBe(false);
    expect((await outgoingReqRef.get()).exists).toBe(false);
    expect((await incomingReqRef.get()).exists).toBe(false);

    // Convoy membership: stripped from the convoy the user belonged to (the
    // convoy itself and the other member survive)...
    const memberConvoy = (await memberConvoyRef.get()).data()!;
    expect(memberConvoy.memberUids).toEqual(['convoy-owner-uid']);
    expect(memberConvoy.members).not.toHaveProperty(uid);
    expect(memberConvoy.memberProfiles).not.toHaveProperty(uid);
    expect(memberConvoy.status).toBe('active');
    // ...the convoy they OWNED is ended rather than left owner-less and
    // un-endable, with the deleted user out of the summary's participants...
    const ownedConvoy = (await ownedConvoyRef.get()).data()!;
    expect(ownedConvoy.status).toBe('ended');
    expect(ownedConvoy.endedAt).toBeInstanceOf(Timestamp);
    expect(ownedConvoy.memberUids).toEqual(['convoy-passenger-uid']);
    expect(ownedConvoy.members).not.toHaveProperty(uid);
    expect(ownedConvoy.memberProfiles).not.toHaveProperty(uid);
    expect((ownedConvoy.summary as { participantUids: string[] }).participantUids).toEqual([
      'convoy-passenger-uid',
    ]);
    // ...and the convoy they were alone in is deleted outright.
    expect((await soloConvoyRef.get()).exists).toBe(false);
    // An already-ENDED convoy keeps its status and its stored summary, but the
    // deleted user is scrubbed OUT of the summary's participants (count kept in
    // step with the list) and out of the destination's attribution — while the
    // destination the group was driving to survives intact.
    const endedConvoy = (await endedConvoyRef.get()).data()!;
    expect(endedConvoy.status).toBe('ended');
    expect(endedConvoy.memberUids).toEqual(['convoy-owner-uid']);
    expect(endedConvoy.members).not.toHaveProperty(uid);
    expect(endedConvoy.memberProfiles).not.toHaveProperty(uid);
    const endedSummary = endedConvoy.summary as {
      participantUids: string[];
      participantCount: number;
      durationSeconds: number;
    };
    expect(endedSummary.participantUids).toEqual(['convoy-owner-uid']);
    expect(endedSummary.participantCount).toBe(1);
    expect(endedSummary.durationSeconds).toBe(600);
    const endedDestination = endedConvoy.destination as Record<string, unknown>;
    expect(endedDestination.setByDisplayName).toBeNull();
    expect(endedDestination.latitude).toBe(57.49);
    expect(endedDestination.label).toBe('Kungsbacka');

    // Controls survive: another user's community message, a DM the user isn't in,
    // the user's EVENT chat message (authorUserId-keyed, deliberately retained),
    // and the friend row / friend request between two OTHER members.
    expect((await otherCommunityMsg.get()).exists).toBe(true);
    expect((await otherConvRef.get()).exists).toBe(true);
    expect((await eventMsg.get()).exists).toBe(true);
    expect((await bystanderFriendRef.get()).exists).toBe(true);
    expect((await bystanderReqRef.get()).exists).toBe(true);

    // Storage prefixes gone.
    const [profileFiles] = await adminBucket.getFiles({ prefix: `profileImages/${uid}/` });
    expect(profileFiles).toHaveLength(0);

    // Auth user gone; proof-of-deletion record retained as processed.
    await expect(adminAuth.getUser(uid)).rejects.toMatchObject({ code: 'auth/user-not-found' });
    const record = (await adminDb.collection('accountDeletionRequests').doc(uid).get()).data()!;
    expect(record.status).toBe('processed');
    expect(record.processedAt).toBeInstanceOf(Timestamp);

    // Fresh user untouched.
    expect((await adminDb.collection('users').doc(fresh.uid).get()).exists).toBe(true);
  });
});
