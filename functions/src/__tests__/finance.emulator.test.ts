/**
 * finance-estimate emulator integration test.
 *
 * Verifies the admin-only cost-estimate callable end-to-end:
 *  - a non-admin is rejected (permission-denied),
 *  - an admin gets an estimate whose variable half used the LIVE member count
 *    read from the latest metrics/{date} snapshot,
 *  - the returned figures are internally consistent (grand total = the three
 *    separable sections), and Mapbox / fixed subscriptions are separate.
 *
 * The heavy arithmetic is unit-tested in finance/model.test.ts; this test is
 * about the wiring (auth gate + snapshot read + callable transport).
 *
 * Requires the Functions + Firestore + Auth emulators — run via:
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
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { RECURRING_COSTS_COLLECTION } from '../finance/recurringCosts-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';
// Unique per-file suffix so seeded ids/displayNames never collide with other
// emulator files sharing the same Firestore instance.
const S = 'fin';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'finance-emulator-tests');
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;

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

let adminUser: TestUser;
let memberUser: TestUser;

const SNAPSHOT_MEMBER_COUNT = 137;
// A far-future date so this snapshot is deterministically the LATEST one the
// callable's `orderBy('date','desc')` picks, even though the emulator suite
// shares one Firestore across files and OTHER files seed real-dated metrics
// docs into the same collection (e.g. security-rules seeds metrics/2026-07-31)
// without cleaning them up. '2999-12-31' outranks every real date.
const SNAPSHOT_DATE = '2999-12-31';

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'finance-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser(`${S}-admin`);
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set(
    { role: 'admin', displayName: `Finance Admin ${S}` },
    { merge: true },
  );
  memberUser = await createProvisionedUser(`${S}-member`);
  await adminDb.collection('users').doc(memberUser.uid).set(
    { displayName: `Finance Member ${S}` },
    { merge: true },
  );

  // Seed a metrics snapshot so the variable half reads a live member count.
  await adminDb.collection('metrics').doc(SNAPSHOT_DATE).set({
    date: SNAPSHOT_DATE,
    capturedAtMs: Date.parse('2026-07-30T02:30:00Z'),
    totalUsers: SNAPSHOT_MEMBER_COUNT,
    convoysCreated: 0,
    totalDistanceMeters: 0,
    eventsHeld: 0,
    drivesSaved: 0,
    crownsCollected: 0,
    friendConnections: 0,
    activeConvoys: 0,
    vehicleProfiles: 0,
    brandDistribution: {},
  });
}, 120_000);

afterAll(async () => {
  await adminDb.collection('metrics').doc(SNAPSHOT_DATE).delete();
  await deleteApp(app);
});

interface RecurringCostLineResult {
  id: string;
  label: string;
  description: string;
  amount: number;
  currency: string;
  period: string;
  sekPerMonth: number;
  annualSek: number;
}

interface EstimateResult {
  member: { count: number; source: string; asOf: string | null };
  googleCloud: { totalSekPerMonth: number; trafikverketWritesSekPerMonth: number };
  mapbox: { sekPerMonth: number };
  recurringCosts: {
    items: RecurringCostLineResult[];
    totalSekPerMonth: number;
    count: number;
  };
  grandTotalSekPerMonth: number;
  fx: { usdToSek: number };
}

describe('finance-estimate', () => {
  it('rejects a non-admin caller', async () => {
    await signInAs(memberUser);
    expect(await callableErrorCode(call('finance-estimate', {}))).toBe('functions/permission-denied');
  });

  it('returns an estimate that used the live member count from the latest snapshot', async () => {
    await signInAs(adminUser);
    const result = (await call('finance-estimate', {})).data as EstimateResult;

    expect(result.member.count).toBe(SNAPSHOT_MEMBER_COUNT);
    expect(result.member.source).toBe('metrics-snapshot');
    expect(result.member.asOf).toBe(SNAPSHOT_DATE);

    // Trafikverket committed writes are the dominant Google Cloud line.
    expect(result.googleCloud.trafikverketWritesSekPerMonth).toBeGreaterThan(0);
    expect(result.fx.usdToSek).toBeGreaterThan(0);

    // Grand total is the three separable sections summed.
    expect(result.grandTotalSekPerMonth).toBeCloseTo(
      result.googleCloud.totalSekPerMonth +
        result.mapbox.sekPerMonth +
        result.recurringCosts.totalSekPerMonth,
      4,
    );

    // The recurring-costs section is data-backed now (the hardcoded Claude
    // placeholder was removed). The emulator suite shares ONE Firestore across
    // files, so other files (e.g. security-rules) may have seeded rows — assert
    // the section is well-formed and self-consistent rather than exactly empty.
    // The empty-list → 0 case is pinned in the pure finance/model.test.ts.
    expect(result.recurringCosts.count).toBe(result.recurringCosts.items.length);
    expect(result.recurringCosts.totalSekPerMonth).toBeGreaterThanOrEqual(0);
    expect(Number.isFinite(result.recurringCosts.totalSekPerMonth)).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Recurring-costs CRUD callables (admin-only, audited)
// ---------------------------------------------------------------------------

interface AddResult {
  id: string;
  label: string;
  amount: number;
  currency: string;
  period: string;
}

async function latestAuditFor(action: string, targetId: string): Promise<Record<string, unknown> | undefined> {
  const snap = await adminDb
    .collection('adminAuditEvents')
    .where('action', '==', action)
    .where('targetId', '==', targetId)
    .limit(1)
    .get();
  return snap.empty ? undefined : snap.docs[0]!.data();
}

describe('finance recurring-costs CRUD', () => {
  const created: string[] = [];

  afterAll(async () => {
    for (const id of created) {
      await adminDb.collection(RECURRING_COSTS_COLLECTION).doc(id).delete().catch(() => undefined);
    }
  });

  it('rejects a non-admin caller on every mutation', async () => {
    await signInAs(memberUser);
    const add = { label: 'X', description: 'y', amount: 10, currency: 'SEK', period: 'monthly' };
    expect(await callableErrorCode(call('finance-addRecurringCost', add))).toBe(
      'functions/permission-denied',
    );
    expect(
      await callableErrorCode(call('finance-updateRecurringCost', { id: 'nope', ...add })),
    ).toBe('functions/permission-denied');
    expect(await callableErrorCode(call('finance-deleteRecurringCost', { id: 'nope' }))).toBe(
      'functions/permission-denied',
    );
  });

  it('adds a cost, persists the shape, and writes an audit event', async () => {
    await signInAs(adminUser);
    const add = {
      label: `Claude ${S}`,
      description: 'Max plan — operator actual',
      amount: 200,
      currency: 'USD',
      period: 'monthly',
    };
    const result = (await call('finance-addRecurringCost', add)).data as AddResult;
    created.push(result.id);

    expect(result.id).toBeTruthy();
    expect(result.label).toBe(add.label);

    const doc = await adminDb.collection(RECURRING_COSTS_COLLECTION).doc(result.id).get();
    expect(doc.exists).toBe(true);
    const data = doc.data()!;
    expect(data.label).toBe(add.label);
    expect(data.amount).toBe(200);
    expect(data.currency).toBe('USD');
    expect(data.period).toBe('monthly');
    expect(data.createdByUid).toBe(adminUser.uid);
    expect(data.createdAt).toBeTruthy();

    const audit = await latestAuditFor('finance.addRecurringCost', result.id);
    expect(audit).toBeDefined();
    expect(audit!.adminId).toBe(adminUser.uid);
    expect(audit!.targetType).toBe('financeRecurringCost');
  });

  it('rejects invalid input with invalid-argument', async () => {
    await signInAs(adminUser);
    const bad = { label: '', description: 'y', amount: 10, currency: 'SEK', period: 'monthly' };
    expect(await callableErrorCode(call('finance-addRecurringCost', bad))).toBe(
      'functions/invalid-argument',
    );
    const badAmount = { label: 'Z', description: 'y', amount: -1, currency: 'SEK', period: 'monthly' };
    expect(await callableErrorCode(call('finance-addRecurringCost', badAmount))).toBe(
      'functions/invalid-argument',
    );
    const badCurrency = { label: 'Z', description: 'y', amount: 1, currency: 'EUR', period: 'monthly' };
    expect(await callableErrorCode(call('finance-addRecurringCost', badCurrency))).toBe(
      'functions/invalid-argument',
    );
  });

  it('the added cost is folded into the estimate grand total', async () => {
    await signInAs(adminUser);
    const add = {
      label: `Domän ${S}`,
      description: 'annual domain — SEK',
      amount: 1200,
      currency: 'SEK',
      period: 'yearly',
    };
    const added = (await call('finance-addRecurringCost', add)).data as AddResult;
    created.push(added.id);

    const est = (await call('finance-estimate', {})).data as EstimateResult;
    const line = est.recurringCosts.items.find((l) => l.id === added.id);
    expect(line).toBeDefined();
    // 1200 SEK/yr normalises to 100 SEK/month.
    expect(line!.sekPerMonth).toBeCloseTo(100, 4);
    expect(line!.annualSek).toBeCloseTo(1200, 4);
    expect(est.recurringCosts.totalSekPerMonth).toBeGreaterThan(0);
    expect(est.grandTotalSekPerMonth).toBeCloseTo(
      est.googleCloud.totalSekPerMonth + est.mapbox.sekPerMonth + est.recurringCosts.totalSekPerMonth,
      4,
    );
  });

  it('updates an existing cost and 404s on a missing id', async () => {
    await signInAs(adminUser);
    const added = (
      await call('finance-addRecurringCost', {
        label: `Tool ${S}`,
        description: 'before',
        amount: 50,
        currency: 'SEK',
        period: 'monthly',
      })
    ).data as AddResult;
    created.push(added.id);

    await call('finance-updateRecurringCost', {
      id: added.id,
      label: `Tool ${S} v2`,
      description: 'after',
      amount: 75,
      currency: 'SEK',
      period: 'monthly',
    });
    const doc = await adminDb.collection(RECURRING_COSTS_COLLECTION).doc(added.id).get();
    expect(doc.data()!.label).toBe(`Tool ${S} v2`);
    expect(doc.data()!.amount).toBe(75);
    expect(doc.data()!.createdByUid).toBe(adminUser.uid); // preserved

    expect(await latestAuditFor('finance.updateRecurringCost', added.id)).toBeDefined();

    expect(
      await callableErrorCode(
        call('finance-updateRecurringCost', {
          id: 'does-not-exist',
          label: 'X',
          description: '',
          amount: 1,
          currency: 'SEK',
          period: 'monthly',
        }),
      ),
    ).toBe('functions/not-found');
    // A failed update must NOT resurrect the missing doc (update(), not
    // set(merge:true)) — the id stays absent.
    const ghost = await adminDb.collection(RECURRING_COSTS_COLLECTION).doc('does-not-exist').get();
    expect(ghost.exists).toBe(false);
  });

  it('updating a cost deleted mid-flight fails and does not resurrect it', async () => {
    await signInAs(adminUser);
    const added = (
      await call('finance-addRecurringCost', {
        label: `Race ${S}`,
        description: 'to be deleted before update',
        amount: 42,
        currency: 'SEK',
        period: 'monthly',
      })
    ).data as AddResult;

    // Simulate the delete landing before the update commits.
    await adminDb.collection(RECURRING_COSTS_COLLECTION).doc(added.id).delete();

    expect(
      await callableErrorCode(
        call('finance-updateRecurringCost', {
          id: added.id,
          label: `Race ${S} v2`,
          description: 'resurrected?',
          amount: 99,
          currency: 'SEK',
          period: 'monthly',
        }),
      ),
    ).toBe('functions/not-found');

    const doc = await adminDb.collection(RECURRING_COSTS_COLLECTION).doc(added.id).get();
    expect(doc.exists).toBe(false); // update() did not recreate it
  });

  it('read-side defence: skips an over-max amount and clamps an over-long label', async () => {
    await signInAs(adminUser);

    // A row that could only arrive via the console/Admin SDK (client writes are
    // rules-denied): amount above the sane cap. It must be SKIPPED, never
    // inflating the total.
    const overMaxId = `rc-overmax-${S}`;
    await adminDb.collection(RECURRING_COSTS_COLLECTION).doc(overMaxId).set({
      label: `OverMax ${S}`,
      description: 'too big',
      amount: 999_999_999,
      currency: 'SEK',
      period: 'monthly',
    });
    created.push(overMaxId);

    // A row with an over-long label — must be CLAMPED (truncated), not dropped.
    const longLabelId = `rc-longlabel-${S}`;
    const longLabel = `L${S}`.padEnd(300, 'x');
    await adminDb.collection(RECURRING_COSTS_COLLECTION).doc(longLabelId).set({
      label: longLabel,
      description: 'd'.repeat(1000),
      amount: 10,
      currency: 'SEK',
      period: 'monthly',
    });
    created.push(longLabelId);

    const est = (await call('finance-estimate', {})).data as EstimateResult;
    expect(est.recurringCosts.items.some((l) => l.id === overMaxId)).toBe(false);
    const clamped = est.recurringCosts.items.find((l) => l.id === longLabelId);
    expect(clamped).toBeDefined();
    expect(clamped!.label.length).toBeLessThanOrEqual(80);
    expect(clamped!.description.length).toBeLessThanOrEqual(500);
    expect(Number.isFinite(est.recurringCosts.totalSekPerMonth)).toBe(true);
  });

  it('deletes an existing cost and 404s on a missing id', async () => {
    await signInAs(adminUser);
    const added = (
      await call('finance-addRecurringCost', {
        label: `Temp ${S}`,
        description: 'to delete',
        amount: 9,
        currency: 'SEK',
        period: 'monthly',
      })
    ).data as AddResult;

    await call('finance-deleteRecurringCost', { id: added.id });
    const doc = await adminDb.collection(RECURRING_COSTS_COLLECTION).doc(added.id).get();
    expect(doc.exists).toBe(false);
    expect(await latestAuditFor('finance.deleteRecurringCost', added.id)).toBeDefined();

    expect(await callableErrorCode(call('finance-deleteRecurringCost', { id: 'does-not-exist' }))).toBe(
      'functions/not-found',
    );
  });
});
