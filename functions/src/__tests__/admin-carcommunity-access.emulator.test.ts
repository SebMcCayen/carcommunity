process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';
import { initializeApp, deleteApp, type FirebaseApp } from 'firebase/app';
import {
  getAuth,
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  signInWithCredential,
  GoogleAuthProvider,
  OAuthProvider,
  signOut,
  type Auth,
} from 'firebase/auth';
import {
  getFunctions,
  connectFunctionsEmulator,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import {
  getFirestore as clientFirestore,
  connectFirestoreEmulator,
  doc,
  getDoc,
  setDoc,
} from 'firebase/firestore';
import { getDatabase, connectDatabaseEmulator, ref, get, set } from 'firebase/database';
import {
  getStorage,
  connectStorageEmulator,
  ref as storageRef,
  uploadBytes,
} from 'firebase/storage';
import { getApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';
import { beforeAll, beforeEach, afterAll, describe, it, expect } from 'vitest';
const adminApp =
  getApps()[0] ?? initializeAdminApp({ projectId: 'demo-test' }, 'access-test-admin');
const db = getFirestore(adminApp),
  adminAuth = getAdminAuth(adminApp);
let ownerApp: FirebaseApp,
  memberApp: FirebaseApp,
  ownerAuth: Auth,
  memberAuth: Auth,
  ownerFunctions: Functions,
  memberFunctions: Functions;
let owner: { uid: string; email: string; password: string };
type AccessView = {
  uid: string;
  version: number;
  blacklistIds: string[];
  suspended: boolean;
  blacklisted: boolean;
  ready: boolean;
};
const call = async (name: string, data: unknown = {}) =>
  (await httpsCallable(name.startsWith('admin-') ? ownerFunctions : memberFunctions, name)(data))
    .data as AccessView & {
    alreadyApplied: boolean;
    cases: unknown[];
    userId: string;
    warnings: unknown[];
  };
const view = (targetUid: string) => call('admin-getAccessTarget', { targetUid });
async function decide(targetUid: string, action: string, key = crypto.randomUUID()) {
  const current = await view(targetUid);
  const input = {
    targetUid,
    action,
    reason: 'Lokalt integrationstest',
    expectedActorUid: owner.uid,
    expectedVersion: current.version,
    expectedBlacklistIds: current.blacklistIds,
    idempotencyKey: key,
  };
  return { input, result: await call('admin-updateAccess', input) };
}
async function waitProfile(uid: string) {
  const until = Date.now() + 45000;
  while (!(await db.collection('users').doc(uid).get()).exists) {
    if (Date.now() > until) throw Error('Provisioning timeout');
    await new Promise((r) => setTimeout(r, 100));
  }
}
const jwt = (subject: string, provider: string) =>
  [
    Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url'),
    Buffer.from(
      JSON.stringify({
        iss:
          provider === 'google.com' ? 'https://accounts.google.com' : 'https://appleid.apple.com',
        aud: 'demo-test',
        sub: subject,
        email: `${subject}@example.com`,
        email_verified: true,
        iat: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + 3600,
      }),
    ).toString('base64url'),
    '',
  ].join('.');
async function sso(provider: string, subject: string) {
  await signOut(memberAuth);
  const credential =
    provider === 'google.com'
      ? GoogleAuthProvider.credential(jwt(subject, provider))
      : new OAuthProvider('apple.com').credential({ idToken: jwt(subject, provider) });
  const user = (await signInWithCredential(memberAuth, credential)).user;
  await waitProfile(user.uid);
  await call('account-getAccess');
  return user.uid;
}
async function driverReadable(uid: string) {
  await db
    .collection('driverUsers')
    .doc(uid)
    .set({ onboardingComplete: true, status: 'active', role: 'user' });
  expect((await getDoc(doc(clientFirestore(memberApp), 'driverUsers', uid))).exists()).toBe(true);
  const authRecord = await adminAuth.getUser(uid);
  expect(authRecord.disabled).toBe(false);
  expect(authRecord.customClaims?.suspended).not.toBe(true);
  const canonical = (await db.collection('users').doc(uid).get()).data();
  expect(canonical?.suspended).toBe(false);
  expect(canonical?.deleted).toBe(false);
  await memberAuth.currentUser!.getIdToken(true);
}
beforeAll(async () => {
  if (process.env.GCLOUD_PROJECT !== 'demo-test') throw Error('Disposable demo emulators required');
  const setup = (name: string) => {
    const app = initializeApp(
      {
        projectId: 'demo-test',
        apiKey: 'demo-key',
        appId: 'demo-app',
        storageBucket: 'demo-test.appspot.com',
        databaseURL: 'https://demo-test-default-rtdb.firebaseio.com',
      },
      name,
    );
    const auth = getAuth(app);
    connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
    const fn = getFunctions(app, 'europe-west1');
    connectFunctionsEmulator(fn, '127.0.0.1', 5001);
    connectFirestoreEmulator(clientFirestore(app), '127.0.0.1', 8080);
    connectDatabaseEmulator(getDatabase(app), '127.0.0.1', 9000);
    connectStorageEmulator(getStorage(app), '127.0.0.1', 9199);
    return { app, auth, fn };
  };
  const a = setup('cc-access-owner'),
    b = setup('cc-access-member');
  ownerApp = a.app;
  ownerAuth = a.auth;
  ownerFunctions = a.fn;
  memberApp = b.app;
  memberAuth = b.auth;
  memberFunctions = b.fn;
  const email = `cc-owner-${Date.now()}@example.com`,
    password = 'cc-owner-test-12345';
  const user = await createUserWithEmailAndPassword(ownerAuth, email, password);
  owner = { uid: user.user.uid, email, password };
  await waitProfile(owner.uid);
  await adminAuth.setCustomUserClaims(owner.uid, { admin: true });
  await db
    .collection('users')
    .doc(owner.uid)
    .set({ role: 'owner', suspended: false, deleted: false }, { merge: true });
  await signInWithEmailAndPassword(ownerAuth, email, password);
  await ownerAuth.currentUser!.getIdToken(true);
}, 120000);
beforeEach(async () => {
  await db
    .collection('carCommunityPolicy')
    .doc('access')
    .set({ enforceInitialized: true, rolloutState: 'ready' });
  await db
    .collection('users')
    .doc(owner.uid)
    .set({ role: 'owner', suspended: false, deleted: false }, { merge: true });
  await db.collection('communityChat').doc('global').set({ title: 'Publik testkanal' });
});
afterAll(async () => {
  await deleteApp(ownerApp);
  await deleteApp(memberApp);
  await db.collection('carCommunityPolicy').doc('access').delete();
});
describe('CarCommunity-only access with shared Firebase identity', () => {
  it.each(['google.com', 'apple.com'])(
    'retains %s blacklist across real account erasure and re-registration without blocking Driver',
    async (provider) => {
      const subject = `cc-${provider.replace('.', '-')}-${Date.now()}`;
      const oldUid = await sso(provider, subject);
      await driverReadable(oldUid);
      const { input, result } = await decide(oldUid, 'blacklist');
      expect(result.alreadyApplied).toBe(false);
      expect((await call('admin-updateAccess', input)).alreadyApplied).toBe(true);
      expect((await call('account-getAccess')).blacklisted).toBe(true);
      await expect(call('communityChat-list', {})).rejects.toMatchObject({
        code: 'functions/permission-denied',
      });
      await expect(call('garage-addVehicle', {})).rejects.toMatchObject({
        code: 'functions/permission-denied',
      });
      await expect(
        getDoc(doc(clientFirestore(memberApp), 'communityChat', 'global')),
      ).rejects.toMatchObject({ code: 'permission-denied' });
      expect((await call('account-listWarnings')).warnings).toEqual([]);
      await driverReadable(oldUid);
      await call('admin-deleteUser', {
        targetUid: oldUid,
        reason: 'Explicit disposable erasure after blacklist',
      });
      expect((await db.collection('carCommunityAccess').doc(oldUid).get()).exists).toBe(false);
      const newUid = await sso(provider, subject);
      expect(newUid).not.toBe(oldUid);
      expect((await call('account-getAccess')).blacklisted).toBe(true);
      await driverReadable(newUid);
      await expect(call('points-recordDailyOpen', {})).rejects.toMatchObject({
        code: 'functions/permission-denied',
      });
      await decide(newUid, 'unlist');
      expect((await call('account-getAccess')).blacklisted).toBe(false);
      expect(
        (await getDoc(doc(clientFirestore(memberApp), 'communityChat', 'global'))).exists(),
      ).toBe(true);
      await driverReadable(newUid);
    },
    120000,
  );
  it('enforces strict RTDB and Storage access while preserving Driver media on the same session', async () => {
    const uid = await sso('google.com', `cc-direct-${Date.now()}`);
    await driverReadable(uid);
    const presence = ref(getDatabase(memberApp), `presence/${uid}`);
    await set(presence, { online: true, lastSeen: Date.now() });
    const route = storageRef(getStorage(memberApp), `rideRoutes/${uid}/local/route.bin`);
    await uploadBytes(route, new Uint8Array([1]), { contentType: 'application/octet-stream' });
    await decide(uid, 'blacklist');
    await expect(set(presence, { online: true, lastSeen: Date.now() })).rejects.toMatchObject({
      code: 'PERMISSION_DENIED',
    });
    await expect(get(presence)).rejects.toBeTruthy();
    await expect(
      uploadBytes(route, new Uint8Array([2]), { contentType: 'application/octet-stream' }),
    ).rejects.toMatchObject({ code: 'storage/unauthorized' });
    await expect(
      get(ref(getDatabase(memberApp), `carCommunityAccess/${uid}`)),
    ).rejects.toBeTruthy();
    await uploadBytes(
      storageRef(getStorage(memberApp), `driverThreadMedia/${uid}/native-test`),
      new Uint8Array([1]),
      { contentType: 'image/jpeg' },
    );
    await driverReadable(uid);
    await decide(uid, 'unlist');
    await set(presence, { online: true, lastSeen: Date.now() });
    await uploadBytes(route, new Uint8Array([3]), { contentType: 'application/octet-stream' });
  }, 120000);
  it('guards self, current owner status and demoted actor before replay; concurrent identical decisions commit once', async () => {
    const uid = await sso('apple.com', `cc-boundary-${Date.now()}`);
    await expect(decide(owner.uid, 'suspend')).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
    const reviewed = await view(uid);
    const input = {
      targetUid: uid,
      action: 'suspend',
      reason: 'Concurrent decision',
      expectedActorUid: owner.uid,
      expectedVersion: reviewed.version,
      expectedBlacklistIds: reviewed.blacklistIds,
      idempotencyKey: crypto.randomUUID(),
    };
    const results = await Promise.all([
      call('admin-updateAccess', input),
      call('admin-updateAccess', input),
    ]);
    expect(results.filter((v) => v.alreadyApplied === false)).toHaveLength(1);
    await expect(
      call('admin-updateAccess', { ...input, reason: 'Changed payload' }),
    ).rejects.toMatchObject({ code: 'functions/failed-precondition' });
    await db.collection('users').doc(owner.uid).update({ role: 'admin' });
    await db.collection('users').doc(uid).update({ role: 'owner' });
    await expect(call('admin-updateAccess', input)).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
    await db.collection('users').doc(uid).update({ role: 'user' });
    await db.collection('users').doc(owner.uid).update({ role: 'user' });
    await expect(call('admin-updateAccess', input)).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
  }, 120000);
  it('unlists a retained case after erasure without recreating access for the erased UID', async () => {
    const uid = await sso('google.com', `cc-erased-${Date.now()}`);
    await decide(uid, 'blacklist');
    await call('admin-deleteUser', { targetUid: uid, reason: 'Explicit disposable erasure' });
    expect((await db.collection('carCommunityAccess').doc(uid).get()).exists).toBe(false);
    expect((await view(uid)).blacklisted).toBe(true);
    await decide(uid, 'unlist');
    expect((await view(uid)).blacklisted).toBe(false);
    expect((await db.collection('carCommunityAccess').doc(uid).get()).exists).toBe(false);
  }, 120000);
  it('keeps suspension and blacklist independent; stale reviewed state and conflicting retries cannot change the decision', async () => {
    const uid = await sso('google.com', `cc-independent-${Date.now()}`);
    await decide(uid, 'suspend');
    await decide(uid, 'blacklist');
    await decide(uid, 'restore');
    let access = await call('account-getAccess');
    expect(access.suspended).toBe(false);
    expect(access.blacklisted).toBe(true);
    await expect(call('crownHunt-submitClaim', {})).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
    await decide(uid, 'suspend');
    await decide(uid, 'unlist');
    access = await call('account-getAccess');
    expect(access.blacklisted).toBe(false);
    expect(access.suspended).toBe(true);
    const current = await view(uid);
    await decide(uid, 'restore');
    await expect(
      call('admin-updateAccess', {
        targetUid: uid,
        action: 'suspend',
        reason: 'stale',
        expectedActorUid: owner.uid,
        expectedVersion: current.version,
        expectedBlacklistIds: current.blacklistIds,
        idempotencyKey: crypto.randomUUID(),
      }),
    ).rejects.toMatchObject({ code: 'functions/failed-precondition' });
    await driverReadable(uid);
  });
  it('legacy suspension/restore payloads affect only CarCommunity', async () => {
    const uid = await sso('apple.com', `cc-legacy-${Date.now()}`);
    await call('admin-suspendUser', { targetUid: uid, reason: 'Legacy admin client' });
    expect((await call('account-getAccess')).suspended).toBe(true);
    await driverReadable(uid);
    await call('admin-restoreAccess', { targetUid: uid, reason: 'Legacy admin client' });
    expect((await call('account-getAccess')).suspended).toBe(false);
    await driverReadable(uid);
  });
  it('refuses client identity/time selectors, own metadata writes and private identity registry reads', async () => {
    const uid = await sso('google.com', `cc-private-${Date.now()}`);
    await expect(call('account-getAccess', { targetUid: owner.uid })).rejects.toMatchObject({
      code: 'functions/invalid-argument',
    });
    await expect(
      setDoc(doc(clientFirestore(memberApp), 'carCommunityAccess', uid), { initialized: true }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    await decide(uid, 'blacklist');
    const internal = (await db.collection('carCommunityAccess').doc(uid).get()).data()!;
    await expect(
      getDoc(doc(clientFirestore(memberApp), 'carCommunityIdentityBlocks', internal.googleKey)),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    expect((await call('admin-listAccessBlocks')).cases.length).toBeGreaterThan(0);
  });
});
