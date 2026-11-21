/** Real client Auth tokens and callable HTTP transport; never inject request.auth. */
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= '127.0.0.1:9199';
process.env.GCLOUD_PROJECT ??= 'demo-test';
process.env.FUNCTIONS_EMULATOR = 'true';

import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, expect, it } from 'vitest';
import { initializeApp as initializeClientApp, deleteApp } from 'firebase/app';
import { connectAuthEmulator, getAuth as clientAuth, signInWithCustomToken } from 'firebase/auth';
import { connectFunctionsEmulator, getFunctions, httpsCallable } from 'firebase/functions';
import { getApps, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { latLngToCell } from 'h3-js';
import { FieldValue, Timestamp, getFirestore } from 'firebase-admin/firestore';

const projectId = 'demo-test';
if (
  process.env.GCLOUD_PROJECT !== projectId ||
  [
    'FIREBASE_AUTH_EMULATOR_HOST',
    'FIRESTORE_EMULATOR_HOST',
    'FIREBASE_DATABASE_EMULATOR_HOST',
    'FIREBASE_STORAGE_EMULATOR_HOST',
  ].some((key) => !/^127\.0\.0\.1:\d+$/.test(process.env[key] ?? ''))
)
  throw Error('This integration test requires the complete disposable loopback demo stack.');
if (!getApps().length)
  initializeApp({
    projectId,
    storageBucket: `${projectId}.appspot.com`,
    databaseURL: `https://${projectId}-default-rtdb.firebaseio.com`,
  });
const db = getFirestore();
const auth = getAuth();
const prefix = `transport-${randomUUID().slice(0, 8)}`;
const apps: ReturnType<typeof initializeClientApp>[] = [];
let previousFlag: unknown;

type Result = Record<string, unknown>;
type Reservation = { reservationId: string; status: string };
async function account(role = 'member') {
  const uid = `${prefix}-${randomUUID()}`;
  const imported = await auth.importUsers([
    { uid, email: `${uid}@example.test`, emailVerified: true },
  ]);
  expect(imported.errors).toEqual([]);
  if (role === 'admin') await auth.setCustomUserClaims(uid, { admin: true });
  await db
    .collection('users')
    .doc(uid)
    .set({ role, suspended: false, deleted: false, activeMember: true });
  await db
    .collection('subscriptions')
    .doc(uid)
    .set({ userId: uid, entitlement: 'member_monthly', tier: 'plus', status: 'active' });
  return uid;
}
async function client(uid?: string) {
  const app = initializeClientApp(
    { projectId, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    `${prefix}-${apps.length}`,
  );
  apps.push(app);
  const identity = clientAuth(app);
  connectAuthEmulator(identity, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, {
    disableWarnings: true,
  });
  if (uid) {
    // Admin SDK uses its emulator signer here. No cloud credential or production
    // token is minted; the Functions emulator verifies the resulting client JWT.
    const credential = await signInWithCustomToken(identity, await auth.createCustomToken(uid));
    expect(credential.user.uid).toBe((await auth.getUser(uid)).uid);
  }
  const functions = getFunctions(app, 'europe-west1');
  connectFunctionsEmulator(functions, '127.0.0.1', 5001);
  return {
    async call<T = Result>(endpoint: string, data: Result): Promise<T> {
      return (await httpsCallable<Result, T>(functions, endpoint)(data)).data;
    },
  };
}
beforeAll(async () => {
  previousFlag = (await db.collection('config').doc('featureFlags').get()).data()
    ?.partnerSelfService;
  await db
    .collection('config')
    .doc('featureFlags')
    .set({ partnerSelfService: true }, { merge: true });
});
afterAll(async () => {
  await Promise.all(apps.map((app) => deleteApp(app)));
  await db
    .collection('config')
    .doc('featureFlags')
    .set({ partnerSelfService: previousFlag ?? FieldValue.delete() }, { merge: true });
});

it('carries real client identities through approval, reviewed capacity, one-use redemption and aggregate-only reports', async () => {
  const [managerUid, adminUid, ...customers] = await Promise.all([
    account(),
    account('admin'),
    ...Array.from({ length: 4 }, () => account()),
  ]);
  const [manager, admin, ...buyers] = await Promise.all([
    client(managerUid),
    client(adminUid),
    ...customers.map((uid) => client(uid)),
  ]);
  const anonymous = await client();
  await expect(anonymous.call('partners-portal', { action: 'workspace' })).rejects.toMatchObject({
    code: 'functions/unauthenticated',
  });

  const application = await manager.call<{ applicationId: string }>('partners-portal', {
    action: 'submitApplication',
    companyName: `SDK Partner ${prefix}`,
    category: 'car_care',
    contactName: 'Demo Contact',
    contactEmail: `${prefix}@example.test`,
    description: 'Disposable integration company',
    address: 'Demo address',
    selfServiceRequested: true,
    billboardRequested: false,
  });
  const published = await admin.call<{ companyId: string }>('partners-portal', {
    action: 'reviewApplication',
    applicationId: application.applicationId,
    decision: 'publish',
    expectedRevision: 1,
    grantSelfService: true,
    latitude: 57,
    longitude: 12,
  });
  const companyId = published.companyId;
  // Read through real Auth/HTTP transport. The raw aggregate remains private.
  const heatRef = db.collection('partnerDriveHeat').doc('current');
  const previousHeat = (await heatRef.get()).data();
  try {
    await heatRef.set({
      consentVersion: 1,
      generatedAt: Timestamp.now(),
      resolution: 10,
      windowDays: 90,
      cells: [{ h3Index: latLngToCell(57.49, 12.08, 10), contributorCount: 10, weight: 30 }],
    });
    const snapshot = await manager.call<{ cells: Record<string, unknown>[] }>('partners-portal', {
      action: 'driveHeat',
      companyId,
    });
    expect(snapshot.cells).toHaveLength(1);
    expect(Object.keys(snapshot.cells[0]!).sort()).toEqual([
      'band',
      'boundary',
      'cellId',
      'latitude',
      'longitude',
    ]);
    expect(JSON.stringify(snapshot)).not.toMatch(
      /contributorCount|weight|uid|userId|route|customer/i,
    );
    await expect(
      buyers[0]!.call('partners-portal', { action: 'driveHeat', companyId }),
    ).rejects.toMatchObject({ code: 'functions/permission-denied' });
    await expect(
      anonymous.call('partners-portal', { action: 'driveHeat', companyId }),
    ).rejects.toMatchObject({ code: 'functions/unauthenticated' });
    const { hash } = await import('../partners/portal-core');
    const member = db.collection('partnerMembers').doc(hash(`${companyId}:${managerUid}`));
    await member.update({ role: 'redeemer' });
    await expect(
      manager.call('partners-portal', { action: 'driveHeat', companyId }),
    ).rejects.toMatchObject({ code: 'functions/permission-denied' });
    await member.update({ role: 'manager' });
  } finally {
    if (previousHeat) await heatRef.set(previousHeat);
    else await heatRef.delete();
  }
  const offer = await manager.call<{ offerId: string }>('partners-portal', {
    action: 'submitOffer',
    companyId,
    title: 'SDK capacity offer',
    teaserText: 'Three places',
    description: 'No payment or appointment booking',
    terms: 'One redemption per app account.',
    capacity: 3,
    reservationDays: 7,
  });
  await admin.call('partners-setOfferStatus', {
    offerId: offer.offerId,
    action: 'activate',
    expectedVersion: 1,
  });

  const reservations: Reservation[] = [];
  for (const buyer of buyers.slice(0, 3)) {
    reservations.push(
      await buyer.call<Reservation>('partners-portal', {
        action: 'reserve',
        offerId: offer.offerId,
        expectedVersion: 1,
        requestId: randomUUID(),
      }),
    );
  }
  await expect(
    buyers[3]!.call('partners-portal', {
      action: 'reserve',
      offerId: offer.offerId,
      expectedVersion: 1,
      requestId: randomUUID(),
    }),
  ).rejects.toMatchObject({ code: 'functions/resource-exhausted' });
  const token = await buyers[0]!.call<{ token: string }>('partners-portal', {
    action: 'token',
    reservationId: reservations[0]!.reservationId,
  });
  expect(token.token).toMatch(/^kcc1\.[A-Za-z0-9_-]{43}$/);
  for (const uid of [managerUid, adminUid, ...customers]) expect(token.token).not.toContain(uid);
  const inspection = await manager.call<{ reservation: Reservation }>('partners-portal', {
    action: 'inspect',
    token: token.token,
  });
  expect(inspection.reservation.status).toBe('reserved');
  expect(
    (await db.collection('partnerOfferQuotas').doc(offer.offerId).get()).data()?.redeemedCount ?? 0,
  ).toBe(0);

  const requestId = randomUUID();
  const redemption = await manager.call<{ receiptId: string; reservation: Reservation }>(
    'partners-portal',
    { action: 'redeem', token: token.token, requestId },
  );
  expect(redemption.reservation.status).toBe('redeemed');
  const retry = await manager.call<{ receiptId: string }>('partners-portal', {
    action: 'redeem',
    token: token.token,
    requestId,
  });
  expect(retry.receiptId).toBe(redemption.receiptId);
  await expect(
    manager.call('partners-portal', {
      action: 'redeem',
      token: token.token,
      requestId: randomUUID(),
    }),
  ).rejects.toMatchObject({ code: 'functions/failed-precondition' });
  const quota = (await db.collection('partnerOfferQuotas').doc(offer.offerId).get()).data()!;
  expect(quota).toMatchObject({ reservedCount: 2, redeemedCount: 1 });

  // Maintenance is server-controlled, separate from every client RPC above.
  const { publishPortalStatistics } = await import('../partners/portal-statistics');
  await publishPortalStatistics();
  for (const actor of [manager, admin]) {
    const report = await actor.call<{
      totalRedeemed: number;
      partners: { companyId: string; totalRedeemed: number }[];
    }>('partners-portal', {
      action: actor === admin ? 'adminStatistics' : 'statistics',
      companyId,
      period: 'all',
    });
    expect(report.totalRedeemed).toBe(1);
    expect(report.partners).toHaveLength(1);
    expect(report.partners[0]).toMatchObject({ companyId, totalRedeemed: 1 });
    const json = JSON.stringify(report);
    expect(json).not.toMatch(/"(?:uid|userId|reservationId|receiptId|tokenHash|redeemedAt)"/);
    for (const uid of [managerUid, adminUid, ...customers]) expect(json).not.toContain(uid);
  }
  await expect(
    buyers[0]!.call('partners-portal', { action: 'statistics', companyId }),
  ).rejects.toMatchObject({ code: 'functions/permission-denied' });
}, 90000);
