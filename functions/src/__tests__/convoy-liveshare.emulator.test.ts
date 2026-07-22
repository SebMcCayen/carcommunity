/**
 * Convoy ↔ live-share auto-session integration tests (item 2 — "starting a
 * convoy auto-starts a live session so everyone in the convoy can see you").
 *
 * Exercises the convoy callables' effect on the live-location PRODUCER
 * (RTDB liveLocation/{uid}/session):
 *  - convoy.start auto-starts a convoy-tagged session for EVERY accepted member;
 *  - a member accepting into an ALREADY-active convoy auto-starts on accept;
 *  - the auto-started session is a real active session (updatePosition works
 *    without a separate live.startSession call);
 *  - convoy.end / convoy.leave STOP the convoy-auto session — but NEVER a
 *    session the user started MANUALLY (that one keeps running).
 *
 * Requires the Auth + Functions + Firestore + Database emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { deleteApp, initializeApp, type FirebaseApp } from 'firebase/app';
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
import { FieldValue, getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { getDatabase as getAdminDatabase } from 'firebase-admin/database';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

// The functions runtime writes RTDB under the single-project-mode default
// namespace, which is the PROJECT ID itself (demo-test) — not demo-test-default-rtdb.
// The test's admin RTDB must point at the SAME namespace or every liveLocation
// read comes back null even though the function wrote the node.
const adminApp =
  getAdminApps().find((a) => a.name === 'convoy-live-tests') ??
  initializeAdminApp(
    {
      projectId: PROJECT_ID,
      databaseURL: `http://${EMULATOR_HOST}:9000?ns=${PROJECT_ID}`,
    },
    'convoy-live-tests',
  );
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);
const adminRtdb = getAdminDatabase(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;
/** The config/featureFlags.liveLocation value before this file forced it on. */
let priorLiveLocation: boolean | undefined;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

interface LiveSessionNode {
  id: string;
  status: string;
  convoyAutoStarted?: boolean;
  convoyId?: string;
}

async function pollUntil<T>(read: () => Promise<T | undefined>, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
}

let userSeq = 0;
async function newMember(displayName: string): Promise<TestUser> {
  userSeq += 1;
  const email = `convoy-live-${userSeq}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
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
    .set({ activeMember: true, displayName, avatarPath: null }, { merge: true });
  return { uid, email, password };
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

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);
const sessionOf = (uid: string) =>
  adminRtdb.ref(`liveLocation/${uid}/session`).get().then((s) => s.val() as LiveSessionNode | null);
const latestExists = (uid: string) =>
  adminRtdb.ref(`liveLocation/${uid}/latest`).get().then((s) => s.exists());

const coordinate = () => ({
  latitude: 59.334,
  longitude: 18.063,
  accuracyMeters: 12,
  recordedAt: new Date().toISOString(),
});

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'convoy-live-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
  // The auto-session honours the liveLocation flag; force it on for this file,
  // but CAPTURE the prior value first and RESTORE it in afterAll so this file
  // does not couple the shared-Firestore emulator suite (another file may rely
  // on the flag being absent/false).
  const priorFlags = (await adminDb.collection('config').doc('featureFlags').get()).data();
  priorLiveLocation = Object.hasOwn(priorFlags ?? {}, 'liveLocation')
    ? (priorFlags!.liveLocation as boolean)
    : undefined;
  await adminDb.collection('config').doc('featureFlags').set({ liveLocation: true }, { merge: true });
}, 120_000);

afterAll(async () => {
  // Restore the flag to exactly what it was (delete it if it was absent), so the
  // next file in the suite sees the state it expects.
  await adminDb
    .collection('config')
    .doc('featureFlags')
    .set(
      { liveLocation: priorLiveLocation === undefined ? FieldValue.delete() : priorLiveLocation },
      { merge: true },
    );
  await deleteApp(app);
});

/** Owner + one accepted member; returns both and the convoy id (still forming). */
async function convoyWithAcceptedMember(
  ownerName: string,
  memberName: string,
): Promise<{ owner: TestUser; member: TestUser; convoyId: string }> {
  const owner = await newMember(ownerName);
  const member = await newMember(memberName);
  await makeFriends(owner, member);
  await signInAs(owner);
  const created = (await call('convoy-create', { inviteeUids: [member.uid] })).data as {
    convoy: { convoyId: string };
  };
  const convoyId = created.convoy.convoyId;
  await signInAs(member);
  await call('convoy-respond', { convoyId, action: 'accept' });
  return { owner, member, convoyId };
}

describe('convoy auto-start live session (item 2)', () => {
  it('convoy.start auto-starts a convoy-tagged session for EVERY accepted member', async () => {
    const { owner, member, convoyId } = await convoyWithAcceptedMember('AutoOwnerCL', 'AutoMemberCL');

    await signInAs(owner);
    await call('convoy-start', { convoyId });

    // Both the owner and the accepted member get an active, convoy-tagged
    // session — nobody tapped "share live".
    for (const uid of [owner.uid, member.uid]) {
      const session = await pollUntil(async () => {
        const s = await sessionOf(uid);
        return s && s.status === 'active' ? s : undefined;
      });
      expect(session.convoyAutoStarted).toBe(true);
      expect(session.convoyId).toBe(convoyId);
    }

    // TEETH: it is a REAL active session — the member can push a position with
    // no separate live.startSession call, and the marker appears.
    await signInAs(member);
    await call('live-updatePosition', { coordinate: coordinate() });
    expect(await latestExists(member.uid)).toBe(true);
  }, 60_000);

  it('a member accepting into an ALREADY-active convoy auto-starts on accept', async () => {
    const { owner, member, convoyId } = await convoyWithAcceptedMember('LateOwnerCL', 'LateMemberCL');
    // Grow + start the convoy with the first member, then a newcomer joins late.
    const newcomer = await newMember('LateNewcomerCL');
    await makeFriends(member, newcomer);
    await signInAs(member);
    await call('convoy-invite', { convoyId, inviteeUids: [newcomer.uid] });
    await signInAs(owner);
    await call('convoy-start', { convoyId });

    // Newcomer accepts AFTER the convoy is active → auto-starts on accept.
    await signInAs(newcomer);
    await call('convoy-respond', { convoyId, action: 'accept' });
    const session = await pollUntil(async () => {
      const s = await sessionOf(newcomer.uid);
      return s && s.status === 'active' ? s : undefined;
    });
    expect(session.convoyAutoStarted).toBe(true);
    expect(session.convoyId).toBe(convoyId);
  }, 60_000);

  it('convoy.end STOPS the auto session but LEAVES a manual session running', async () => {
    const { owner, member, convoyId } = await convoyWithAcceptedMember('EndOwnerCL', 'EndMemberCL');

    // The member starts a MANUAL live session (their own reasons) before the
    // convoy rolls. convoy.start must NOT clobber or re-tag it.
    await signInAs(member);
    const manual = (await call('live-startSession', { duration: '1h' })).data as {
      sessionId: string;
    };

    await signInAs(owner);
    await call('convoy-start', { convoyId });

    // The owner got an auto session; the member's stays their untouched manual one.
    const ownerSession = await pollUntil(async () => {
      const s = await sessionOf(owner.uid);
      return s && s.status === 'active' ? s : undefined;
    });
    expect(ownerSession.convoyAutoStarted).toBe(true);
    const memberSession = await sessionOf(member.uid);
    expect(memberSession!.id).toBe(manual.sessionId); // not replaced
    expect(memberSession!.convoyAutoStarted).toBeFalsy(); // not tagged

    // End the convoy: the OWNER's auto session stops; the member's MANUAL session
    // keeps broadcasting.
    await call('convoy-end', { convoyId });
    const endedOwner = await pollUntil(async () => {
      const s = await sessionOf(owner.uid);
      return s && s.status === 'stopped' ? s : undefined;
    });
    expect(endedOwner.status).toBe('stopped');
    const stillManual = await sessionOf(member.uid);
    expect(stillManual!.status).toBe('active'); // manual session untouched
    expect(stillManual!.id).toBe(manual.sessionId);
  }, 60_000);

  it('convoy.leave stops the LEAVER’s auto session', async () => {
    const { owner, member, convoyId } = await convoyWithAcceptedMember('LeaveOwnerCL', 'LeaveMemberCL');
    await signInAs(owner);
    await call('convoy-start', { convoyId });
    // Member's auto session is active...
    await pollUntil(async () => {
      const s = await sessionOf(member.uid);
      return s && s.status === 'active' && s.convoyAutoStarted ? true : undefined;
    });

    // ...leaving stops it.
    await signInAs(member);
    await call('convoy-leave', { convoyId });
    const stopped = await pollUntil(async () => {
      const s = await sessionOf(member.uid);
      return s && s.status === 'stopped' ? s : undefined;
    });
    expect(stopped.status).toBe('stopped');
    expect(await latestExists(member.uid)).toBe(false);

    // The owner is still broadcasting (they did not leave).
    const ownerSession = await sessionOf(owner.uid);
    expect(ownerSession!.status).toBe('active');
  }, 60_000);

  it('convoy.start LEAVES a pre-existing manual session untouched (auto-start aborts)', async () => {
    // Start-path counterpart to the convoy.end test above: proves the guarantee
    // at the auto-start boundary itself. The member already has a MANUAL session
    // when the convoy rolls; startConvoyAutoSession must ABORT (the RTDB
    // transaction's commit-time check finds an active session) and leave that
    // session byte-for-byte as-is — same id, untagged, still active, its marker
    // untouched — rather than clobbering it with a convoy-auto session.
    const { owner, member, convoyId } = await convoyWithAcceptedMember('KeepOwnerCL', 'KeepMemberCL');

    // Manual session + a real marker (the member is actively broadcasting).
    await signInAs(member);
    const manual = (await call('live-startSession', { duration: '1h' })).data as {
      sessionId: string;
    };
    await call('live-updatePosition', { coordinate: coordinate() });
    expect(await latestExists(member.uid)).toBe(true);

    // Convoy starts → owner auto-starts, but the member's manual session is kept.
    await signInAs(owner);
    await call('convoy-start', { convoyId });
    await pollUntil(async () => {
      const s = await sessionOf(owner.uid);
      return s && s.status === 'active' && s.convoyAutoStarted ? true : undefined;
    });

    const kept = await sessionOf(member.uid);
    expect(kept!.id).toBe(manual.sessionId); // not replaced by an auto session
    expect(kept!.convoyAutoStarted).toBeFalsy(); // not tagged → teardown won't stop it
    expect(kept!.status).toBe('active'); // still broadcasting
    // The abort must NOT have run latestRef().remove() (only a committed take-over
    // clears the marker) — the manual broadcast's marker survives.
    expect(await latestExists(member.uid)).toBe(true);
  }, 60_000);
});
