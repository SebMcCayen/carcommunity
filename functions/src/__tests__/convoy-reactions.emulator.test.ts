/**
 * Convoy REACTIONS emulator integration tests (convoy-sendReaction).
 *
 * Exercises the deployed-in-emulator callable end-to-end plus the reactions
 * Firestore rules:
 * - accepted-member gating (a non-member / unknown convoy → not-found so a convoy
 *   can't be probed) and the reaction document it writes to
 *   convoyChats/{convoyId}/reactions;
 * - the SERVER-ENFORCED anti-spam cooldown: a second police alert inside the 60s
 *   window is resource-exhausted (with retryAfterMs), while a DIFFERENT kind is
 *   unaffected (independent per-kind windows);
 * - idempotency on clientId (a retry replays the same reactionId without a second
 *   doc or a second cooldown charge);
 * - rules: an accepted member may READ the reactions subcollection; the cooldown
 *   doc is backend-only (client read denied).
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
  getDocs,
  getFirestore,
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
  initializeAdminApp({ projectId: PROJECT_ID }, 'convoy-reactions-emulator-tests');
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
  const email = `convoy-react-${userSeq}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
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
    throw new Error('expected the callable to reject');
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

interface ConvoySummary {
  convoyId: string;
  memberUids: string[];
}

/** Owner creates an ACTIVE convoy with one invited friend; returns the convoy. */
async function ownerConvoy(owner: TestUser, friend: TestUser): Promise<ConvoySummary> {
  await makeFriends(owner, friend);
  await signInAs(owner);
  const result = (await call('convoy-create', { inviteeUids: [friend.uid] })).data as {
    convoy: ConvoySummary;
  };
  return result.convoy;
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'convoy-reactions-emulator-client',
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

describe('convoy-sendReaction gating', () => {
  it('rejects an unauthenticated caller', async () => {
    await auth.signOut();
    expect((await callableError(call('convoy-sendReaction', { convoyId: 'x', kind: 'police' }))).code).toBe(
      'functions/unauthenticated',
    );
  });

  it('an accepted member broadcasts a reaction to the convoy channel', async () => {
    const owner = await newMember('ReactOwner');
    const friend = await newMember('ReactFriend');
    const convoy = await ownerConvoy(owner, friend);

    const res = (await call('convoy-sendReaction', { convoyId: convoy.convoyId, kind: 'police' }))
      .data as { reactionId: string };
    expect(res.reactionId).toBeTruthy();

    const reactions = await adminDb
      .collection('convoyChats')
      .doc(convoy.convoyId)
      .collection('reactions')
      .get();
    expect(reactions.docs).toHaveLength(1);
    const doc = reactions.docs[0]!.data();
    expect(doc.kind).toBe('police');
    expect(doc.senderUid).toBe(owner.uid);
    expect(doc.senderDisplayName).toBe('ReactOwner');
    expect(doc.expireAt).toBeDefined();
  });

  it('an outsider gets not-found (a convoy cannot be probed)', async () => {
    const owner = await newMember('ReactOwner2');
    const friend = await newMember('ReactFriend2');
    const outsider = await newMember('ReactOutsider');
    const convoy = await ownerConvoy(owner, friend);

    await signInAs(outsider);
    expect(
      (await callableError(call('convoy-sendReaction', { convoyId: convoy.convoyId, kind: 'hello' })))
        .code,
    ).toBe('functions/not-found');
  });

  it('rejects an unknown reaction kind (invalid-argument)', async () => {
    const owner = await newMember('ReactOwner3');
    const friend = await newMember('ReactFriend3');
    const convoy = await ownerConvoy(owner, friend);
    expect(
      (await callableError(call('convoy-sendReaction', { convoyId: convoy.convoyId, kind: 'wave' })))
        .code,
    ).toBe('functions/invalid-argument');
  });
});

describe('convoy-sendReaction anti-spam cooldown (server-enforced)', () => {
  it('refuses a second police alert inside the 60s window with retryAfterMs', async () => {
    const owner = await newMember('CooldownOwner');
    const friend = await newMember('CooldownFriend');
    const convoy = await ownerConvoy(owner, friend);

    await call('convoy-sendReaction', { convoyId: convoy.convoyId, kind: 'police' });
    const err = await callableError(
      call('convoy-sendReaction', { convoyId: convoy.convoyId, kind: 'police' }),
    );
    expect(err.code).toBe('functions/resource-exhausted');
    const details = err.details as { kind?: string; retryAfterMs?: number };
    expect(details.kind).toBe('police');
    expect(details.retryAfterMs).toBeGreaterThan(0);

    // Only the FIRST reaction was written — the throttled send wrote nothing.
    const reactions = await adminDb
      .collection('convoyChats')
      .doc(convoy.convoyId)
      .collection('reactions')
      .get();
    expect(reactions.docs).toHaveLength(1);
  });

  it('a DIFFERENT kind is unaffected by the police cooldown (independent windows)', async () => {
    const owner = await newMember('IndepOwner');
    const friend = await newMember('IndepFriend');
    const convoy = await ownerConvoy(owner, friend);

    await call('convoy-sendReaction', { convoyId: convoy.convoyId, kind: 'police' });
    // hello has its own window, so it succeeds despite the police cooldown.
    const res = (await call('convoy-sendReaction', { convoyId: convoy.convoyId, kind: 'hello' }))
      .data as { reactionId: string };
    expect(res.reactionId).toBeTruthy();
  });
});

describe('convoy-sendReaction idempotency', () => {
  it('replays the same reactionId for a repeated clientId without a second doc or cooldown charge', async () => {
    const owner = await newMember('IdemOwner');
    const friend = await newMember('IdemFriend');
    const convoy = await ownerConvoy(owner, friend);

    const first = (
      await call('convoy-sendReaction', {
        convoyId: convoy.convoyId,
        kind: 'police',
        clientId: 'idem-key-1',
      })
    ).data as { reactionId: string };
    const second = (
      await call('convoy-sendReaction', {
        convoyId: convoy.convoyId,
        kind: 'police',
        clientId: 'idem-key-1',
      })
    ).data as { reactionId: string };

    expect(second.reactionId).toBe(first.reactionId);
    const reactions = await adminDb
      .collection('convoyChats')
      .doc(convoy.convoyId)
      .collection('reactions')
      .get();
    expect(reactions.docs).toHaveLength(1);

    // The replay did NOT burn the cooldown: a fresh police alert (different key)
    // is still refused, proving the first send set the cooldown and the replay
    // neither reset nor double-charged it.
    expect(
      (await callableError(call('convoy-sendReaction', { convoyId: convoy.convoyId, kind: 'police' })))
        .code,
    ).toBe('functions/resource-exhausted');
  });
});

describe('reactions Firestore rules', () => {
  it('an accepted member may read the reactions subcollection; the cooldown doc is backend-only', async () => {
    const owner = await newMember('RulesOwner');
    const friend = await newMember('RulesFriend');
    const convoy = await ownerConvoy(owner, friend);
    await call('convoy-sendReaction', { convoyId: convoy.convoyId, kind: 'follow_me' });

    // Owner (accepted member) reads the reactions channel.
    await signInAs(owner);
    const snap = await getDocs(
      collection(firestore, 'convoyChats', convoy.convoyId, 'reactions'),
    );
    expect(snap.docs.length).toBeGreaterThanOrEqual(1);

    // The cooldown doc is backend-only — a client read is denied.
    const err = await callableError(
      getDocs(collection(firestore, 'convoyReactionCooldowns')),
    );
    expect(err.code).toContain('permission-denied');
  });
});
