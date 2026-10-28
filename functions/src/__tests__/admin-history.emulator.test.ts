process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';
import { initializeApp, deleteApp, type FirebaseApp } from 'firebase/app';
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  signInWithEmailAndPassword,
  signOut,
  type Auth,
} from 'firebase/auth';
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import { getApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { beforeAll, beforeEach, afterAll, describe, it, expect } from 'vitest';
const adminApp = getApps()[0] ?? initializeAdminApp({ projectId: 'demo-test' }, 'history-admin');
const db = getFirestore(adminApp),
  adminAuth = getAdminAuth(adminApp);
let app: FirebaseApp, auth: Auth, functions: Functions;
let admin: { uid: string; email: string; password: string }, member: typeof admin;
type Page = {
  ok: true;
  data: {
    points?: { pointId: string }[];
    claims?: { claimId: string; claimedAt: string; source: string }[];
    transactions?: { transactionId: string }[];
    balance?: number;
  };
  meta: { total: number; hasNext: boolean; nextCursor: string | null; asOf: string };
};
const endpoints = ['admin-listCrownPoints', 'admin-listCrownClaims', 'admin-listPointsLedger'];
const call = async (name: string, data: unknown = {}) =>
  (await httpsCallable(functions, name)(data)).data as Page;
async function user(prefix: string) {
  const email = `${prefix}-${Date.now()}@example.com`,
    password = 'history-password-123';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  const uid = credential.user.uid;
  const deadline = Date.now() + 30000;
  while (!(await db.collection('users').doc(uid).get()).exists) {
    if (Date.now() > deadline) throw new Error('Provisioning timed out');
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return { uid, email, password };
}
async function signIn(who: typeof admin) {
  await signInWithEmailAndPassword(auth, who.email, who.password);
  await auth.currentUser!.getIdToken(true);
}
const request = (name: string) =>
  name === 'admin-listPointsLedger' ? { targetUid: member.uid } : {};
beforeAll(async () => {
  if (process.env.GCLOUD_PROJECT !== 'demo-test' || !process.env.FIRESTORE_EMULATOR_HOST)
    throw new Error('This suite requires disposable demo emulators');
  app = initializeApp(
    { projectId: 'demo-test', apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'history-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
  functions = getFunctions(app, 'europe-west1');
  connectFunctionsEmulator(functions, '127.0.0.1', 5001);
  admin = await user('history-admin');
  member = await user('history-member');
  await adminAuth.setCustomUserClaims(admin.uid, { admin: true });
}, 120000);
beforeEach(async () => {
  await db
    .collection('users')
    .doc(admin.uid)
    .set({ role: 'admin', suspended: false, deleted: false }, { merge: true });
  await signIn(admin);
  // Never runs against a live project; fresh fixtures make global history deterministic.
  for (const collection of ['crownHuntPoints', 'crownHuntClaims', 'crownSpawnClaims']) {
    const docs = await db.collection(collection).get();
    const batch = db.batch();
    docs.docs.forEach((doc) => batch.delete(doc.ref));
    if (docs.size) await batch.commit();
  }
});
afterAll(async () => deleteApp(app));
async function seed(
  collection: string,
  count: number,
  fields: (i: number) => Record<string, unknown>,
) {
  const batch = db.batch();
  for (let i = 0; i < count; i++)
    batch.set(db.collection(collection).doc(`row-${String(i).padStart(3, '0')}`), fields(i));
  await batch.commit();
}
async function allPages(
  name: string,
  input: Record<string, unknown>,
  key: 'points' | 'claims' | 'transactions',
) {
  let cursor: string | null = typeof input.cursor === 'string' ? input.cursor : null;
  const rows: Record<string, unknown>[] = [];
  const pages: Page[] = [];
  do {
    const page = await call(name, { ...input, cursor });
    pages.push(page);
    rows.push(...page.data[key]!);
    cursor = page.meta.nextCursor;
    if (pages.length > 20) throw new Error('Cursor did not terminate');
  } while (cursor);
  return { rows, pages };
}
describe('reauthorized admin history', () => {
  it('reaches all 45 points with tied timestamps and correct final-page metadata', async () => {
    await seed('crownHuntPoints', 45, () => ({
      createdAt: new Timestamp(1700000000, 123),
      status: 'draft',
      rewardPoints: 25,
    }));
    const first = await call('admin-listCrownPoints');
    const rest = await allPages(
      'admin-listCrownPoints',
      { cursor: first.meta.nextCursor },
      'points',
    );
    const rows = [...first.data.points!, ...rest.rows];
    const pages = [first, ...rest.pages];
    expect(rows).toHaveLength(45);
    expect(new Set(rows.map((row) => row.pointId)).size).toBe(45);
    expect(rows[0]!.pointId).toBe('row-044');
    expect(rows[44]!.pointId).toBe('row-000');
    expect(pages.map((page) => page.data.points!.length)).toEqual([20, 20, 5]);
    expect(pages.map((page) => page.meta.total)).toEqual([45, 45, 45]);
    expect(pages[2]!.meta.hasNext).toBe(false);
  });
  it('filters older risk results on both sources before the page limit and excludes private telemetry', async () => {
    await seed('crownHuntClaims', 30, () => ({
      claimedAt: new Timestamp(1700000100, 0),
      result: 'awarded',
      userId: member.uid,
      pointId: 'missing',
    }));
    await db
      .collection('crownHuntClaims')
      .doc('old-risk')
      .set({
        claimedAt: new Timestamp(1700000000, 1),
        result: 'risk_review',
        userId: member.uid,
        pointId: 'missing',
        latitude: 57.123456,
        rawRisk: 'DO-NOT-EXPOSE',
      });
    await db
      .collection('crownSpawnClaims')
      .doc('old-risk')
      .set({
        claimedAt: new Timestamp(1700000000, 2),
        result: 'risk_review',
        userId: member.uid,
        spawnId: 'spawn',
        longitude: 12.123456,
        accuracyMeters: 0.123456,
        reportedSpeedMetersPerSecond: 999,
      });
    const page = await call('admin-listCrownClaims', { result: 'risk_review' });
    expect(page.meta.total).toBe(2);
    expect(page.data.claims!.map((row) => row.claimId)).toEqual([
      'automatic:old-risk',
      'hand_placed:old-risk',
    ]);
    expect(JSON.stringify(page)).not.toMatch(
      /latitude|longitude|accuracyMeters|reportedSpeed|rawRisk|DO-NOT-EXPOSE/,
    );
    expect(
      (await call('admin-listCrownClaims', { source: 'automatic', result: 'risk_review' })).data
        .claims,
    ).toHaveLength(1);
  });
  it('merges both sources without losing nanosecond ties, a deleted cursor document or a new insertion', async () => {
    await seed('crownHuntClaims', 23, (i) => ({
      claimedAt: new Timestamp(1700000000, i * 2000),
      result: 'awarded',
      userId: member.uid,
      pointId: 'missing',
    }));
    await seed('crownSpawnClaims', 23, (i) => ({
      claimedAt: new Timestamp(1700000000, i * 2000 + 1000),
      result: 'awarded',
      userId: member.uid,
      spawnId: 'spawn',
    }));
    const first = await call('admin-listCrownClaims');
    const last = first.data.claims![19]!;
    await db
      .collection(last.source === 'automatic' ? 'crownSpawnClaims' : 'crownHuntClaims')
      .doc(last.claimId.split(':')[1]!)
      .delete();
    await db
      .collection('crownSpawnClaims')
      .doc('new-after-first-page')
      .set({
        claimedAt: new Timestamp(Math.floor(Date.now() / 1000) + 10, 0),
        result: 'awarded',
        userId: member.uid,
        spawnId: 'new',
      });
    const remainder = await allPages(
      'admin-listCrownClaims',
      { cursor: first.meta.nextCursor },
      'claims',
    );
    const rows = [...first.data.claims!, ...remainder.rows];
    expect(rows).toHaveLength(46);
    expect(rows.slice(0, 4).map((row) => row.claimId)).toEqual([
      'automatic:row-022',
      'hand_placed:row-022',
      'automatic:row-021',
      'hand_placed:row-021',
    ]);
    expect(new Set(rows.map((row) => row.claimId)).size).toBe(46);
    expect(rows[0]!.claimId).toBe('automatic:row-022');
    expect(rows.at(-1)!.claimId).toBe('hand_placed:row-000');
    expect(rows.some((row) => String(row.claimId).includes('new-after'))).toBe(false);
    expect(remainder.pages.length).toBeGreaterThan(0);
  });
  it('pages the ledger without computing the authoritative balance from its entries', async () => {
    const ledgerUser = await user('history-ledger-only');
    await signIn(admin);
    const wallet = db.collection('pointsLedger').doc(ledgerUser.uid);
    await wallet.set({ balance: 777 });
    const batch = db.batch();
    for (let i = 0; i < 45; i++)
      batch.set(wallet.collection('entries').doc(`e-${String(i).padStart(3, '0')}`), {
        createdAt: new Timestamp(1700000000, 1),
        amount: 1,
        balanceAfter: i + 1,
        transactionType: 'adjustment_credit',
        source: 'admin_adjustment',
        description: 'fixture',
      });
    await batch.commit();
    const { rows, pages } = await allPages(
      'admin-listPointsLedger',
      { targetUid: ledgerUser.uid },
      'transactions',
    );
    expect(rows).toHaveLength(45);
    expect(new Set(rows.map((row) => row.transactionId)).size).toBe(45);
    expect(pages.every((page) => page.data.balance === 777)).toBe(true);
    await expect(
      call('admin-listPointsLedger', { targetUid: admin.uid, cursor: pages[0]!.meta.nextCursor }),
    ).rejects.toMatchObject({ code: 'functions/invalid-argument' });
  });
  it('binds cursors to result, origin and page size and rejects malformed requests', async () => {
    await seed('crownSpawnClaims', 21, () => ({
      claimedAt: new Timestamp(1700000000, 1),
      result: 'risk_review',
      userId: member.uid,
      spawnId: 'x',
    }));
    const page = await call('admin-listCrownClaims', { result: 'risk_review' });
    for (const input of [
      { cursor: page.meta.nextCursor, source: 'automatic', result: 'risk_review' },
      { cursor: page.meta.nextCursor },
      { cursor: page.meta.nextCursor, pageSize: 10, result: 'risk_review' },
      { cursor: 'not a cursor' },
      { pageSize: 101 },
      { result: 'made-up' },
    ])
      await expect(call('admin-listCrownClaims', input)).rejects.toMatchObject({
        code: 'functions/invalid-argument',
      });
  });
  it('rejects signed-out and ordinary callers on every endpoint', async () => {
    await signOut(auth);
    for (const endpoint of endpoints)
      await expect(call(endpoint, request(endpoint))).rejects.toMatchObject({
        code: 'functions/unauthenticated',
      });
    await signIn(member);
    for (const endpoint of endpoints)
      await expect(call(endpoint, request(endpoint))).rejects.toMatchObject({
        code: 'functions/permission-denied',
      });
  });
  it.each(['demoted', 'suspended', 'deleted', 'missing'])(
    'rejects the same stale admin session after its profile is %s',
    async (state) => {
      const profile = db.collection('users').doc(admin.uid);
      if (state === 'missing') await profile.delete();
      else
        await profile.set(state === 'demoted' ? { role: 'user' } : { [state]: true }, {
          merge: true,
        });
      for (const endpoint of endpoints)
        await expect(call(endpoint, request(endpoint))).rejects.toMatchObject({
          code: 'functions/permission-denied',
        });
    },
  );
});
