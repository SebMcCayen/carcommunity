/** Real Firestore transactions, Auth identities and rules; no mock quota implementation. */
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= '127.0.0.1:9199';
process.env.GCLOUD_PROJECT ??= 'demo-test';
process.env.FUNCTIONS_EMULATOR = 'true';

import { randomUUID } from 'node:crypto';
import sharp from 'sharp';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { initializeApp as initializeClientApp, deleteApp as deleteClientApp } from 'firebase/app';
import { getFunctions, connectFunctionsEmulator, httpsCallable } from 'firebase/functions';
import { getApps, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore, Timestamp, FieldValue } from 'firebase-admin/firestore';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc } from 'firebase/firestore';
import type { CallableRequest } from 'firebase-functions/v2/https';

const projectId = 'demo-test';
if (!getApps().length)
  initializeApp({
    projectId,
    storageBucket: `${projectId}.appspot.com`,
    databaseURL: `https://${projectId}-default-rtdb.firebaseio.com`,
  });
const db = getFirestore(),
  auth = getAuth();
const prefix = `pp-${randomUUID().slice(0, 8)}`;
const clientApp = initializeClientApp(
  { projectId, apiKey: 'demo-api-key', appId: 'demo-app-id' },
  `${prefix}-transport`,
);
const functions = getFunctions(clientApp, 'europe-west1');
connectFunctionsEmulator(functions, '127.0.0.1', 5001);
let portal: typeof import('../partners/portal').portal;
let expireReservations: typeof import('../partners/portal').expireReservations;
let publishPortalStatistics: typeof import('../partners/portal-statistics').publishPortalStatistics;
let manager: string, other: string, admin: string, offerId: string, companyId: string;
const users: string[] = [];
let previousPortalFlag: unknown;
afterAll(async () => {
  await deleteClientApp(clientApp);
  await db
    .collection('config')
    .doc('featureFlags')
    .set({ partnerSelfService: previousPortalFlag ?? FieldValue.delete() }, { merge: true });
});
async function seed(role = 'member') {
  const uid = `${prefix}-${randomUUID()}`;
  // Explicit fixture provisioning below owns the account state. Import keeps
  // the 100-customer contention test from dispatching unrelated auth triggers.
  const imported = await auth.importUsers([
    { uid, email: `${uid}@example.test`, emailVerified: true },
  ]);
  expect(imported.errors).toEqual([]);
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
async function call<T = Record<string, unknown>>(
  uid: string,
  action: string,
  fields: Record<string, unknown> = {},
): Promise<T> {
  return (await portal.run({
    auth: { uid, token: { admin: uid === admin } },
    data: { action, ...fields },
  } as unknown as CallableRequest)) as T;
}
const offerInput = () => ({
  companyId,
  title: 'Test rekond',
  teaserText: 'Three places',
  description: 'Full description',
  terms: 'One redemption per account. No appointment included.',
  capacity: 3,
  reservationDays: 7,
});
async function campaign(capacity = 3) {
  const result = await call<{ offerId: string }>(manager, 'submitOffer', {
    ...offerInput(),
    capacity,
  });
  await db.collection('offers').doc(result.offerId).update({ status: 'active' });
  return result.offerId;
}
beforeAll(async () => {
  ({ portal, expireReservations } = await import('../partners/portal'));
  ({ publishPortalStatistics } = await import('../partners/portal-statistics'));
  previousPortalFlag = (await db.collection('config').doc('featureFlags').get()).data()
    ?.partnerSelfService;
  await db
    .collection('config')
    .doc('featureFlags')
    .set({ partnerSelfService: true }, { merge: true });
  [manager, other, admin] = await Promise.all([seed(), seed(), seed('admin')]);
  companyId = `${prefix}-company`;
  await db
    .collection('companies')
    .doc(companyId)
    .set({ name: 'Portal Company', category: 'car_care', status: 'active' });
  const { hash } = await import('../partners/portal-core');
  await db
    .collection('partnerMembers')
    .doc(hash(`${companyId}:${manager}`))
    .set({ companyId, uid: manager, enabled: true, role: 'manager' });
  users.push(...(await Promise.all(Array.from({ length: 100 }, () => seed()))));
  offerId = await campaign();
}, 120000);

describe('partner reservations under real contention', () => {
  it('100 parallel customers get at most three places, never duplicate capacity', async () => {
    const requests = users.map((uid) => ({ uid, requestId: randomUUID() }));
    const result = await Promise.allSettled(
      requests.map((r) =>
        call(r.uid, 'reserve', { offerId, expectedVersion: 1, requestId: r.requestId }),
      ),
    );
    const successful = result.filter((r) => r.status === 'fulfilled');
    expect(successful.length).toBeLessThanOrEqual(3);
    // Firestore explicitly allows ABORTED after finite contention retries. Retry
    // the same command after the burst; availability must recover and fill 3.
    for (const attempt of result) {
      if (attempt.status === 'rejected')
        expect(['aborted', 'resource-exhausted']).toContain(attempt.reason.code);
    }
    let accepted = successful.length;
    for (let i = 0; i < requests.length && accepted < 3; i++) {
      if (result[i]!.status === 'fulfilled') continue;
      const r = requests[i]!;
      await call(r.uid, 'reserve', { offerId, expectedVersion: 1, requestId: r.requestId });
      accepted++;
    }
    expect(accepted).toBe(3);
    const quota = (await db.collection('partnerOfferQuotas').doc(offerId).get()).data()!;
    expect(quota.reservedCount).toBe(3);
    expect(quota.redeemedCount).toBe(0);
    const publicOffer = (await db.collection('offers').doc(offerId).get()).data()!;
    expect(publicOffer.remainingCount).toBe(0);
    expect(publicOffer).not.toHaveProperty('redeemedCount');
  }, 120000);
  it('retries are idempotent and cannot reuse a request key for another campaign', async () => {
    const id = await campaign(),
      requestId = randomUUID(),
      uid = users[0]!;
    const a = await call(uid, 'reserve', { offerId: id, requestId, expectedVersion: 1 });
    const b = await call(uid, 'reserve', { offerId: id, requestId, expectedVersion: 1 });
    expect(b.reservationId).toBe(a.reservationId);
    await call(uid, 'cancel', { reservationId: a.reservationId });
    expect(
      (await call(uid, 'reserve', { offerId: id, requestId, expectedVersion: 1 })).status,
    ).toBe('cancelled');
    const second = await campaign();
    await expect(
      call(uid, 'reserve', { offerId: second, requestId, expectedVersion: 1 }),
    ).rejects.toThrow();
  });
  it('two scanners produce one redemption and one aggregate contribution', async () => {
    const id = await campaign(),
      uid = users[1]!;
    const reservation = await call(uid, 'reserve', {
      offerId: id,
      requestId: randomUUID(),
      expectedVersion: 1,
    });
    const token = await call(uid, 'token', { reservationId: reservation.reservationId });
    const inspect = await call(manager, 'inspect', { token: token.token });
    expect(JSON.stringify(inspect)).not.toContain(uid);
    const requestId = randomUUID();
    const attempts = await Promise.allSettled([
      call(manager, 'redeem', { token: token.token, requestId }),
      call(manager, 'redeem', { token: token.token, requestId: randomUUID() }),
    ]);
    expect(attempts.filter((r) => r.status === 'fulfilled')).toHaveLength(1);
    const success = attempts.find((r) => r.status === 'fulfilled');
    expect(success).toBeDefined();
    await expect(
      call(uid, 'reserve', { offerId: id, requestId: randomUUID(), expectedVersion: 1 }),
    ).rejects.toThrow();
    const q = (await db.collection('partnerOfferQuotas').doc(id).get()).data()!;
    expect(q).toMatchObject({ reservedCount: 0, redeemedCount: 1 });
    await publishPortalStatistics();
    await publishPortalStatistics();
    const stats = await call(manager, 'statistics', { companyId });
    expect(stats.totalRedeemed).toBe(1);
    expect(JSON.stringify(stats)).not.toContain(uid);
    expect(JSON.stringify(stats)).not.toMatch(/reservationId|tokenHash|receiptId|userId/);
    const all = await call(admin, 'adminStatistics');
    expect(all.totalRedeemed).toBeGreaterThanOrEqual(1);
  });
  it('cross-company staff and revoked roles cannot inspect or redeem', async () => {
    const { hash } = await import('../partners/portal-core');
    const id = await campaign(),
      r = await call(users[2]!, 'reserve', {
        offerId: id,
        requestId: randomUUID(),
        expectedVersion: 1,
      });
    const token = await call(users[2]!, 'token', { reservationId: r.reservationId });
    await expect(call(other, 'inspect', { token: token.token })).rejects.toThrow();
    await db
      .collection('partnerMembers')
      .doc(hash(`${companyId}:${manager}`))
      .update({ enabled: false });
    await expect(call(manager, 'inspect', { token: token.token })).rejects.toThrow();
    await db
      .collection('partnerMembers')
      .doc(hash(`${companyId}:${manager}`))
      .update({ enabled: true });
    await expect(call(other, 'statistics', { companyId })).rejects.toThrow();
  });
  it('invalidates the previous QR generation and rejects changed terms', async () => {
    const id = await campaign(),
      uid = users[3]!;
    await expect(
      call(uid, 'reserve', { offerId: id, requestId: randomUUID(), expectedVersion: 2 }),
    ).rejects.toThrow();
    const r = await call(uid, 'reserve', {
      offerId: id,
      requestId: randomUUID(),
      expectedVersion: 1,
    });
    const first = await call(uid, 'token', { reservationId: r.reservationId });
    const next = await call(uid, 'token', { reservationId: r.reservationId });
    await expect(call(manager, 'inspect', { token: first.token })).rejects.toThrow();
    expect((await call(manager, 'inspect', { token: next.token })).reservation).toBeDefined();
    await expect(call(other, 'token', { reservationId: r.reservationId })).rejects.toThrow();
  });
  it('expiry/cancel races release capacity exactly once', async () => {
    const id = await campaign(),
      uid = users[4]!;
    const r = await call(uid, 'reserve', {
      offerId: id,
      requestId: randomUUID(),
      expectedVersion: 1,
    });
    await db
      .collection('partnerReservations')
      .doc(r.reservationId as string)
      .update({ expiresAt: Timestamp.fromMillis(Date.now() - 1) });
    await Promise.all([
      expireReservations(),
      call(uid, 'cancel', { reservationId: r.reservationId }),
      call(uid, 'cancel', { reservationId: r.reservationId }),
    ]);
    expect((await db.collection('partnerOfferQuotas').doc(id).get()).data()!.reservedCount).toBe(0);
    expect((await db.collection('offers').doc(id).get()).data()!.remainingCount).toBe(3);
  });
  it('free/admin preview cannot consume customer quota; company suspension overrides QR', async () => {
    const id = await campaign(),
      uid = users[5]!;
    await db.collection('subscriptions').doc(admin).delete();
    await expect(
      call(admin, 'reserve', { offerId: id, requestId: randomUUID(), expectedVersion: 1 }),
    ).rejects.toThrow();
    const r = await call(uid, 'reserve', {
      offerId: id,
      requestId: randomUUID(),
      expectedVersion: 1,
    });
    await db.collection('companies').doc(companyId).update({ status: 'paused' });
    await expect(call(uid, 'token', { reservationId: r.reservationId })).rejects.toThrow();
    await db.collection('companies').doc(companyId).update({ status: 'active' });
  });
});

describe('portal publication and private storage rules', () => {
  it('the deployed callable rejects unauthenticated transport requests', async () => {
    await expect(
      httpsCallable(functions, 'partners-portal')({ action: 'workspace' }),
    ).rejects.toMatchObject({ code: 'functions/unauthenticated' });
  });
  it('rechecks customer subscription and CarCommunity restrictions for QR and redemption', async () => {
    const id = await campaign(),
      uid = users[10]!;
    const r = await call(uid, 'reserve', {
      offerId: id,
      requestId: randomUUID(),
      expectedVersion: 1,
    });
    const token = await call(uid, 'token', { reservationId: r.reservationId });
    await db.collection('subscriptions').doc(uid).update({ entitlement: 'none' });
    await expect(call(uid, 'token', { reservationId: r.reservationId })).rejects.toThrow();
    await expect(
      call(manager, 'redeem', { token: token.token, requestId: randomUUID() }),
    ).rejects.toThrow();
    await db.collection('subscriptions').doc(uid).update({ entitlement: 'member_monthly' });
    await db.collection('carCommunityAccess').doc(uid).update({ suspended: true });
    await expect(call(manager, 'inspect', { token: token.token })).rejects.toThrow();
    await db.collection('carCommunityAccess').doc(uid).update({ suspended: false });
  });
  it('retention removes the usage pointer and cannot delete a concurrently re-reserved owner', async () => {
    const { hash } = await import('../partners/portal-core');
    const id = await campaign(),
      uid = users[11]!;
    const r = await call(uid, 'reserve', {
      offerId: id,
      requestId: randomUUID(),
      expectedVersion: 1,
    });
    await call(uid, 'cancel', { reservationId: r.reservationId });
    await db
      .collection('partnerReservations')
      .doc(r.reservationId as string)
      .update({ closedAt: Timestamp.fromMillis(Date.now() - 31 * 86400000) });
    await Promise.all([
      expireReservations(),
      call(uid, 'reserve', { offerId: id, requestId: randomUUID(), expectedVersion: 1 }),
    ]);
    const claim = await db
      .collection('partnerClaimLocks')
      .doc(hash(`${uid}:${id}`))
      .get();
    const current = await db
      .collection('partnerReservations')
      .doc(claim.data()!.reservationId)
      .get();
    expect(current.data()).toMatchObject({ uid, status: 'reserved' });
    const t = await call(uid, 'token', { reservationId: current.id });
    await call(manager, 'redeem', { token: t.token, requestId: randomUUID() });
    await current.ref.update({ closedAt: Timestamp.fromMillis(Date.now() - 31 * 86400000) });
    await expireReservations();
    expect((await current.ref.get()).exists).toBe(false);
    const tombstone = (await claim.ref.get()).data()!;
    expect(tombstone.used).toBe(true);
    expect(tombstone).not.toHaveProperty('reservationId');
    expect(tombstone).not.toHaveProperty('redeemedAt');
    expect(tombstone).not.toHaveProperty('uid');
    await expect(
      call(uid, 'reserve', { offerId: id, requestId: randomUUID(), expectedVersion: 1 }),
    ).rejects.toThrow();
  });
  it('publishes once, grants only requested company-scoped access and keeps contacts private', async () => {
    const applicant = users[6]!;
    const application = await call(applicant, 'submitApplication', {
      companyName: 'New Partner',
      category: 'car_care',
      contactName: 'Private Contact',
      contactEmail: `${applicant}@example.test`,
      description: 'Public description',
      address: 'Public address',
      selfServiceRequested: true,
      billboardRequested: true,
      billboardPlacementRequest: 'E6',
      logoBase64: (
        await sharp({ create: { width: 32, height: 32, channels: 3, background: 'white' } })
          .png()
          .toBuffer()
      ).toString('base64'),
      logoRightsConfirmed: true,
    });
    const result = await call(admin, 'reviewApplication', {
      applicationId: application.applicationId,
      decision: 'publish',
      expectedRevision: 1,
      grantSelfService: true,
      latitude: 57.5,
      longitude: 12.1,
    });
    const again = await call(admin, 'reviewApplication', {
      applicationId: application.applicationId,
      decision: 'publish',
      expectedRevision: 1,
      grantSelfService: true,
      latitude: 57.5,
      longitude: 12.1,
    });
    expect(again.companyId).toBe(result.companyId);
    const company = (
      await db
        .collection('companies')
        .doc(result.companyId as string)
        .get()
    ).data()!;
    expect(company.status).toBe('active');
    expect(JSON.stringify(company)).not.toContain('Private Contact');
    expect(company.phone).toBeNull();
    const workspace = await call<{ companies: unknown[] }>(applicant, 'workspace');
    expect(workspace.companies).toHaveLength(1);
    await expect(
      call(applicant, 'membership', {
        companyId: result.companyId,
        uid: other,
        role: 'manager',
        enabled: true,
      }),
    ).rejects.toThrow();
  });
  it('requests changes and permits a revised application without granting rights', async () => {
    const applicant = users[7]!;
    const fields = {
      companyName: 'Revision Partner',
      category: 'tires',
      contactName: 'Contact',
      contactEmail: `${applicant}@example.test`,
      selfServiceRequested: false,
      billboardRequested: false,
    };
    const a = await call(applicant, 'submitApplication', fields);
    await call(admin, 'reviewApplication', {
      applicationId: a.applicationId,
      decision: 'request_changes',
      reason: 'Add address',
    });
    const revised = await call(applicant, 'submitApplication', {
      ...fields,
      address: 'New address',
    });
    expect(revised.applicationId).toBe(a.applicationId);
    expect((await call<{ companies: unknown[] }>(applicant, 'workspace')).companies).toHaveLength(
      0,
    );
  });
  it('keeps application ownership scoped to UID when a business contact email is shared', async () => {
    const { hash } = await import('../partners/portal-core');
    const contactEmail = `${users[21]}@example.test`;
    const fields = {
      companyName: 'Shared Contact Partner',
      category: 'car_care',
      contactName: 'Business contact',
      contactEmail,
      selfServiceRequested: false,
      billboardRequested: false,
    };
    // Older private email-lock records must not establish contact ownership.
    const staleLock = db.collection('partnerApplicationEmails').doc(hash(contactEmail));
    await staleLock.set({ uid: users[20], applicationId: 'older-application' });
    const first = await call(users[20]!, 'submitApplication', fields);
    const second = await call(users[21]!, 'submitApplication', fields);
    expect(first.status).toBe('submitted');
    expect(second.status).toBe('submitted');
    expect(first.applicationId).not.toBe(second.applicationId);
    await expect(call(users[20]!, 'submitApplication', fields)).rejects.toMatchObject({
      code: 'already-exists',
    });
    // A rejected application does not give its applicant ownership of the
    // business contact address either. Review remains an administrator task.
    await db
      .collection('partnerApplications')
      .doc(first.applicationId as string)
      .update({ status: 'rejected' });
    const third = await call(users[22]!, 'submitApplication', fields);
    expect(third.status).toBe('submitted');
    expect(third.applicationId).not.toBe(second.applicationId);
    expect((await staleLock.get()).data()).toEqual({
      uid: users[20],
      applicationId: 'older-application',
    });
  });
  it('denies direct reads and writes even for admin claims', async () => {
    const env = await initializeTestEnvironment({
      projectId,
      firestore: {
        host: '127.0.0.1',
        port: 8080,
        rules: readFileSync('../firebase/firestore.rules', 'utf8'),
      },
    });
    const client = env.authenticatedContext(admin, { admin: true }).firestore();
    for (const collection of [
      'partnerReservations',
      'partnerOfferQuotas',
      'partnerMembers',
      'partnerRedemptionOutbox',
      'partnerRedemptionStats',
      'partnerRedemptionStatsPublic',
    ]) {
      await assertFails(getDoc(doc(client, collection, 'private')));
      await assertFails(setDoc(doc(client, collection, 'private'), { uid: 'victim' }));
    }
    await env.cleanup();
  });
  it('shares the daily campaign budget across staff and permits edits after the budget is used', async () => {
    const { hash } = await import('../partners/portal-core');
    const limitedCompany = `${prefix}-daily-budget`;
    await db.collection('companies').doc(limitedCompany).set({
      name: 'Budget Company',
      category: 'car_care',
      status: 'active',
    });
    for (const uid of [manager, other]) {
      await db
        .collection('partnerMembers')
        .doc(hash(`${limitedCompany}:${uid}`))
        .set({
          companyId: limitedCompany,
          uid,
          enabled: true,
          role: 'manager',
        });
    }
    let first: string | undefined;
    for (let i = 0; i < 10; i++) {
      const result = await call<{ offerId: string }>(i % 2 ? other : manager, 'submitOffer', {
        ...offerInput(),
        companyId: limitedCompany,
      });
      first ??= result.offerId;
    }
    await expect(
      call(other, 'submitOffer', {
        ...offerInput(),
        companyId: limitedCompany,
      }),
    ).rejects.toMatchObject({ code: 'resource-exhausted' });
    await call(manager, 'submitOffer', {
      ...offerInput(),
      companyId: limitedCompany,
      offerId: first,
      title: 'Revised draft',
    });
    expect((await db.collection('offers').doc(first!).get()).data()).toMatchObject({
      title: 'Revised draft',
      version: 2,
    });
    expect(
      (await db.collection('offers').where('companyId', '==', limitedCompany).get()).size,
    ).toBe(10);
  });
  it('enforces the workspace cap and reports oversized legacy workspaces without truncating', async () => {
    const { hash } = await import('../partners/portal-core');
    const limitedCompany = `${prefix}-workspace-budget`;
    await db.collection('companies').doc(limitedCompany).set({
      name: 'Workspace Company',
      category: 'car_care',
      status: 'active',
    });
    await db
      .collection('partnerMembers')
      .doc(hash(`${limitedCompany}:${manager}`))
      .set({
        companyId: limitedCompany,
        uid: manager,
        enabled: true,
        role: 'manager',
      });
    const batch = db.batch();
    for (let i = 0; i < 100; i++) {
      batch.set(db.collection('offers').doc(`${limitedCompany}-${i}`), {
        companyId: limitedCompany,
        title: `Legacy ${i}`,
        status: 'draft',
        createdAt: Timestamp.fromMillis(Date.now() - 86400000),
      });
    }
    await batch.commit();
    await expect(
      call(manager, 'submitOffer', {
        ...offerInput(),
        companyId: limitedCompany,
      }),
    ).rejects.toMatchObject({ code: 'resource-exhausted' });
    expect(
      (
        await call<{ offers: unknown[] }>(manager, 'listOffers', {
          companyId: limitedCompany,
        })
      ).offers,
    ).toHaveLength(100);
    await db.collection('offers').doc(`${limitedCompany}-overflow`).set({
      companyId: limitedCompany,
      title: 'Legacy overflow',
      status: 'draft',
    });
    await expect(
      call(manager, 'listOffers', {
        companyId: limitedCompany,
      }),
    ).rejects.toMatchObject({ code: 'resource-exhausted' });
  });
  it('blocks new reservations and campaign writes when self-service is disabled', async () => {
    const flagRef = db.collection('config').doc('featureFlags');
    await flagRef.set({ partnerSelfService: false }, { merge: true });
    try {
      await expect(
        call(users[12]!, 'reserve', {
          offerId,
          expectedVersion: 1,
          requestId: randomUUID(),
        }),
      ).rejects.toMatchObject({
        code: 'failed-precondition',
        message: 'Partner self-service is not enabled.',
      });
      await expect(call(manager, 'submitOffer', offerInput())).rejects.toMatchObject({
        code: 'failed-precondition',
        message: 'Partner self-service is not enabled.',
      });
    } finally {
      await flagRef.set({ partnerSelfService: true }, { merge: true });
    }
  });
});
