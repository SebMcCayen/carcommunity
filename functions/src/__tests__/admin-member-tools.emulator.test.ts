process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';
import { initializeApp, deleteApp, type FirebaseApp } from 'firebase/app';
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
  getFirestore as clientFirestore,
  getDoc,
  doc,
} from 'firebase/firestore';
import { getApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { beforeAll, beforeEach, afterAll, describe, it, expect } from 'vitest';
const adminApp =
  getApps()[0] ?? initializeAdminApp({ projectId: 'demo-test' }, 'member-tools-test');
const db = getFirestore(adminApp);
const adminAuth = getAdminAuth(adminApp);
let app: FirebaseApp, auth: Auth, functions: Functions;
type User = { uid: string; email: string; password: string };
let actor: User, member: User, other: User;
const call = async (name: string, data: unknown = {}) =>
  (await httpsCallable(functions, name)(data)).data as any;
async function user(prefix: string): Promise<User> {
  const email = `${prefix}-${Date.now()}-${Math.random()}@example.com`,
    password = 'member-tools-12345';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  const uid = credential.user.uid;
  const deadline = Date.now() + 30000;
  while (!(await db.collection('users').doc(uid).get()).exists) {
    if (Date.now() > deadline) throw Error('Provisioning timeout');
    await new Promise((r) => setTimeout(r, 100));
  }
  return { uid, email, password };
}
async function login(who: User) {
  await signInWithEmailAndPassword(auth, who.email, who.password);
  await auth.currentUser!.getIdToken(true);
}
const warningKey = 'warning-key-00000001';
const warning = (extra: Record<string, unknown> = {}) => ({
  targetUid: member.uid,
  reason: 'Respektera andra medlemmar',
  internalNote: 'PRIVATE-ADMIN-NOTE',
  idempotencyKey: warningKey,
  durationDays: 7,
  ...extra,
});
beforeAll(async () => {
  if (process.env.GCLOUD_PROJECT !== 'demo-test') throw Error('Disposable demo project required');
  app = initializeApp(
    { projectId: 'demo-test', apiKey: 'demo-key', appId: 'demo-id' },
    'member-tools-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
  functions = getFunctions(app, 'europe-west1');
  connectFunctionsEmulator(functions, '127.0.0.1', 5001);
  connectFirestoreEmulator(clientFirestore(app), '127.0.0.1', 8080);
  actor = await user('tools-admin');
  member = await user('tools-member');
  other = await user('tools-other');
  await adminAuth.setCustomUserClaims(actor.uid, { admin: true });
}, 120000);
beforeEach(async () => {
  for (const who of [actor, member, other])
    await db
      .collection('users')
      .doc(who.uid)
      .set(
        {
          role: who === actor ? 'admin' : 'user',
          suspended: false,
          deleted: false,
          displayName: who === member ? 'Original' : 'Other',
        },
        { merge: true },
      );
  await db.recursiveDelete(db.collection('userWarnings').doc(member.uid));
  await db.recursiveDelete(db.collection('adminMemberOperations').doc(member.uid));
  for (const action of (
    await db.collection('moderationActions').where('targetUserId', '==', member.uid).get()
  ).docs)
    await action.ref.delete();
  await db.recursiveDelete(db.collection('pointsLedger').doc(member.uid));
  await db.collection('pointsLedger').doc(member.uid).set({ balance: 500 });
  await login(actor);
});
afterAll(async () => deleteApp(app));
describe('member tools contracts', () => {
  it('returns only safe member fields and truthful available activity', async () => {
    await db.collection('userPrivate').doc(member.uid).set({ secret: 'PRIVATE-PII' });
    await db
      .collection('userLifecycle')
      .doc(member.uid)
      .set({ lastLoginAt: Timestamp.fromMillis(1700000000000) });
    const { user } = await call('admin-getMember', { targetUid: member.uid });
    expect(user.pointsBalance).toBe(500);
    expect(user.actorUid).toBe(actor.uid);
    expect(user.lastLoginAt).toBe('2023-11-14T22:13:20.000Z');
    expect(user.loginFrequency).toBeNull();
    expect(JSON.stringify(user)).not.toContain('PRIVATE-PII');
  });
  it('renames atomically, rejects stale reviewed names and safely replays', async () => {
    const input = {
      targetUid: member.uid,
      displayName: 'Nytt namn',
      previousDisplayName: 'Original',
      expectedActorUid: actor.uid,
      reason: 'Olämpligt namn',
      idempotencyKey: 'rename-key-00000001',
    };
    expect((await call('admin-updateDisplayName', input)).alreadyApplied).toBe(false);
    expect((await call('admin-updateDisplayName', input)).alreadyApplied).toBe(true);
    await expect(
      call('admin-updateDisplayName', { ...input, idempotencyKey: 'rename-key-00000002' }),
    ).rejects.toMatchObject({ code: 'functions/failed-precondition' });
    await expect(
      call('admin-updateDisplayName', { ...input, displayName: 'Conflicting' }),
    ).rejects.toMatchObject({ code: 'functions/failed-precondition' });
    await expect(
      call('admin-updateDisplayName', { ...input, expectedActorUid: other.uid }),
    ).rejects.toMatchObject({ code: 'functions/permission-denied' });
    expect((await db.collection('users').doc(member.uid).get()).get('displayName')).toBe(
      'Nytt namn',
    );
  });
  it('creates one warning and notification for identical concurrent requests; notes stay admin only', async () => {
    const results = await Promise.all([
      call('admin-warnUser', warning()),
      call('admin-warnUser', warning()),
    ]);
    expect(results[0].actionId).toBe(results[1].actionId);
    const history = await call('admin-listWarnings', { targetUid: member.uid });
    expect(history.warnings).toHaveLength(1);
    expect(history.warnings[0].internalNote).toBe('PRIVATE-ADMIN-NOTE');
    await expect(call('admin-warnUser', warning({ reason: 'Changed' }))).rejects.toMatchObject({
      code: 'functions/failed-precondition',
    });
    await login(member);
    const own = await call('account-listWarnings');
    expect(own.warnings).toHaveLength(1);
    expect(own.warnings[0].remainingMillis).toBeGreaterThan(0);
    expect(JSON.stringify(own)).not.toMatch(/PRIVATE-ADMIN-NOTE|actorUserId|requestFingerprint/);
    expect(
      (await getDoc(doc(clientFirestore(app), 'accountWarningSignals', member.uid))).exists(),
    ).toBe(true);
    await expect(
      getDoc(
        doc(clientFirestore(app), 'userWarnings', member.uid, 'warnings', results[0].actionId),
      ),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    await expect(
      getDoc(doc(clientFirestore(app), 'accountWarningSignals', other.uid)),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    await expect(call('account-listWarnings', { targetUid: other.uid })).rejects.toMatchObject({
      code: 'functions/invalid-argument',
    });
    await login(other);
    expect((await call('account-listWarnings')).warnings).toHaveLength(0);
  });
  it('updates warning expiry with version check, revokes and prevents reactivation', async () => {
    const { actionId } = await call('admin-warnUser', warning());
    const update = {
      targetUid: member.uid,
      warningId: actionId,
      reason: 'Ny bedömning',
      expectedVersion: 1,
      idempotencyKey: 'expiry-key-00000001',
      expiresAt: null,
    };
    expect((await call('admin-setWarningExpiry', update)).version).toBe(2);
    expect((await call('admin-setWarningExpiry', update)).alreadyApplied).toBe(true);
    await expect(
      call('admin-setWarningExpiry', { ...update, idempotencyKey: 'expiry-key-00000002' }),
    ).rejects.toMatchObject({ code: 'functions/failed-precondition' });
    const revoke = { ...update, expectedVersion: 2, idempotencyKey: 'revoke-key-00000001' };
    await call('admin-revokeWarning', revoke);
    await expect(
      call('admin-setWarningExpiry', {
        ...update,
        expectedVersion: 3,
        idempotencyKey: 'expiry-key-00000003',
      }),
    ).rejects.toMatchObject({ code: 'functions/failed-precondition' });
    await login(member);
    expect((await call('account-listWarnings')).warnings).toHaveLength(0);
    await login(actor);
    expect((await call('admin-listWarnings', { targetUid: member.uid })).warnings[0].status).toBe(
      'revoked',
    );
  });
  it('uses server time and keeps expired reasons unavailable, even under suspension', async () => {
    const { actionId } = await call('admin-warnUser', warning());
    await db
      .collection('userWarnings')
      .doc(member.uid)
      .collection('warnings')
      .doc(actionId)
      .update({ expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
    await db.collection('users').doc(member.uid).update({ suspended: true });
    await login(member);
    expect((await call('account-listWarnings')).warnings).toHaveLength(0);
    await expect(
      call('account-listWarnings', { serverNow: '2000-01-01T00:00:00Z' }),
    ).rejects.toMatchObject({ code: 'functions/invalid-argument' });
  });
  it('pages active own warnings beyond 20 without duplicates and binds member identity', async () => {
    const collection = db.collection('userWarnings').doc(member.uid).collection('warnings');
    const batch = db.batch();
    for (let i = 0; i < 43; i++)
      batch.set(collection.doc(`fixture-${i}`), {
        reason: `Warning ${i}`,
        internalNote: 'PRIVATE',
        createdAt: Timestamp.fromMillis(1700000000000 + i),
        expiresAt: i < 23 ? Timestamp.fromMillis(Date.now() + 86400000 + i) : null,
        revokedAt: null,
        version: 1,
      });
    await batch.commit();
    await login(member);
    const ids: string[] = [];
    let cursor = null;
    do {
      const page = await call('account-listWarnings', { cursor });
      ids.push(...page.warnings.map((w: any) => w.id));
      cursor = page.meta.nextCursor;
      if (ids.length > 60) throw Error('Nonterminating cursor');
    } while (cursor);
    expect(ids).toHaveLength(43);
    expect(new Set(ids).size).toBe(43);
    const first = await call('account-listWarnings');
    await login(other);
    await expect(
      call('account-listWarnings', { cursor: first.meta.nextCursor }),
    ).rejects.toMatchObject({ code: 'functions/invalid-argument' });
  });
  it('fresh authorization protects owner targets and denies stale admin on new and replayed operations', async () => {
    const { actionId } = await call('admin-warnUser', warning());
    await db.collection('users').doc(member.uid).update({ role: 'owner' });
    await expect(call('admin-warnUser', warning())).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
    await expect(
      call('admin-revokeWarning', {
        targetUid: member.uid,
        warningId: actionId,
        reason: 'x',
        expectedVersion: 1,
        idempotencyKey: 'revoke-key-00000001',
      }),
    ).rejects.toMatchObject({ code: 'functions/permission-denied' });
    await db.collection('users').doc(actor.uid).update({ role: 'user' });
    for (const [name, data] of [
      ['admin-getMember', { targetUid: member.uid }],
      ['admin-listWarnings', { targetUid: member.uid }],
      ['admin-warnUser', warning()],
    ] as const)
      await expect(call(name, data)).rejects.toMatchObject({ code: 'functions/permission-denied' });
  });
  it('applies a concurrent points adjustment once and rejects conflicting reuse and fractional amounts', async () => {
    const input = {
      targetUid: member.uid,
      type: 'adjustment_credit',
      amount: 40,
      reason: 'Rättelse',
      idempotencyKey: 'points-key-00000001',
      expectedActorUid: actor.uid,
    };
    const results = await Promise.all([
      call('points-adminAdjust', input),
      call('points-adminAdjust', input),
    ]);
    expect(results[0].entryId).toBe(results[1].entryId);
    expect((await db.collection('pointsLedger').doc(member.uid).get()).get('balance')).toBe(540);
    await expect(call('points-adminAdjust', { ...input, amount: 41 })).rejects.toMatchObject({
      code: 'functions/failed-precondition',
    });
    for (const amount of [1.5, 0, 100001])
      await expect(
        call('points-adminAdjust', { ...input, amount, idempotencyKey: 'points-key-00000002' }),
      ).rejects.toMatchObject({ code: 'functions/invalid-argument' });
    const entries = await db.collection('pointsLedger').doc(member.uid).collection('entries').get();
    expect(entries.size).toBe(1);
  });
});
