/** Real local transactions and the canonical self/admin/inactivity purge hook. */
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= '127.0.0.1:9199';
process.env.GCLOUD_PROJECT ??= 'demo-test';
process.env.FUNCTIONS_EMULATOR = 'true';

import { randomUUID } from 'node:crypto';
import { getApps, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore, Timestamp } from 'firebase-admin/firestore';
import { getAuth } from 'firebase-admin/auth';
import { getStorage } from 'firebase-admin/storage';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { CallableRequest } from 'firebase-functions/v2/https';
import { hash } from '../partners/portal-core';
import { localProductAccount } from './helpers/local-product-account';

const app =
  getApps()[0] ??
  initializeApp({
    projectId: 'demo-test',
    storageBucket: 'demo-test.appspot.com',
    databaseURL: 'https://demo-test-default-rtdb.firebaseio.com',
  });
const db = getFirestore(app);
const prefix = `perase-${randomUUID().slice(0, 8)}`;
const preservedStats = db.doc(`partnerRedemptionStats/${hash(`${prefix}-offer:all`)}`);
const processedRedemption = db.doc(`partnerRedemptionOutbox/${prefix}`);
let erase: typeof import('../partners/portal-erasure').erasePartnerAccountData;
let purge: typeof import('../account/scheduled').purgeUserData;
let portal: typeof import('../partners/portal').portal;
let expire: typeof import('../partners/portal').expireReservations;
let previousFlag: unknown;
beforeAll(async () => {
  ({ erasePartnerAccountData: erase } = await import('../partners/portal-erasure'));
  ({ purgeUserData: purge } = await import('../account/scheduled'));
  ({ portal, expireReservations: expire } = await import('../partners/portal'));
  previousFlag = (await db.doc('config/featureFlags').get()).data()?.partnerSelfService;
  await db.doc('config/featureFlags').set({ partnerSelfService: true }, { merge: true });
});
afterAll(async () => {
  // Aggregate publishers inspect the whole collection in other test files.
  // Remove only this suite's explicitly owned preservation fixtures.
  await Promise.all([preservedStats.delete(), processedRedemption.delete()]);
  await db
    .doc('config/featureFlags')
    .set({ partnerSelfService: previousFlag ?? FieldValue.delete() }, { merge: true });
});
afterEach(() => vi.restoreAllMocks());

async function account(label: string, role = 'user') {
  const uid = `${prefix}-${label}`;
  expect(
    (await getAuth(app).importUsers([{ uid, email: `${uid}@example.test`, emailVerified: true }]))
      .errors,
  ).toEqual([]);
  await db.doc(`users/${uid}`).set({ role, suspended: false, deleted: false });
  await db
    .doc(`subscriptions/${uid}`)
    .set({ userId: uid, status: 'active', entitlement: 'member_monthly', tier: 'plus' });
  await localProductAccount(uid);
  return uid;
}
async function command(
  uid: string,
  action: string,
  fields: Record<string, unknown> = {},
  admin = false,
) {
  return (await portal.run({
    auth: { uid, token: { admin } },
    data: { action, ...fields },
  } as unknown as CallableRequest)) as Record<string, unknown>;
}
function application() {
  return {
    companyName: 'Erasure business',
    category: 'other',
    contactName: 'Business contact',
    contactEmail: 'business@example.test',
    selfServiceRequested: false,
    billboardRequested: false,
  };
}

describe('partner records in canonical account erasure', () => {
  it("releases only the erased customer's outstanding quota once, erases QR/retry references and preserves redemption totals", async () => {
    const uid = `${prefix}-reserved`,
      other = `${prefix}-other`,
      offerId = `${prefix}-offer`;
    const own = `${prefix}-own-reservation`,
      foreign = `${prefix}-foreign-reservation`;
    await db.doc(`offers/${offerId}`).set({ capacity: 5, remainingCount: 1 });
    await db
      .doc(`partnerOfferQuotas/${offerId}`)
      .set({ reservedCount: 2, redeemedCount: 2, issuedCount: 4 });
    for (const [id, owner] of [
      [own, uid],
      [foreign, other],
    ]) {
      await db.doc(`partnerReservations/${id}`).set({ uid: owner, offerId, status: 'reserved' });
      await db.doc(`partnerClaimLocks/${hash(`${owner}:${offerId}`)}`).set({ reservationId: id });
      await db.doc(`partnerRedemptionTokens/${id}`).set({ reservationId: id });
      await db.doc(`partnerPortalRequests/${id}`).set({ reservationId: id });
      await db
        .doc(`partnerPortalRequests/${id}-receipt`)
        .set({ reservation: { reservationId: id } });
    }
    const aggregate = {
      companyId: `${prefix}-company`,
      companyName: 'Erasure business',
      offerId,
      title: 'Erasure offer',
      period: 'all',
      totalRedeemed: 2,
    };
    await preservedStats.set(aggregate);
    await processedRedemption.set({ processed: true });
    await erase(uid);
    await erase(uid);
    expect((await db.doc(`partnerOfferQuotas/${offerId}`).get()).data()).toMatchObject({
      reservedCount: 1,
      redeemedCount: 2,
      issuedCount: 4,
    });
    expect((await db.doc(`offers/${offerId}`).get()).data()!.remainingCount).toBe(2);
    for (const path of [
      `partnerReservations/${own}`,
      `partnerClaimLocks/${hash(`${uid}:${offerId}`)}`,
      `partnerRedemptionTokens/${own}`,
      `partnerPortalRequests/${own}`,
      `partnerPortalRequests/${own}-receipt`,
    ])
      expect((await db.doc(path).get()).exists).toBe(false);
    for (const path of [
      `partnerReservations/${foreign}`,
      `partnerClaimLocks/${hash(`${other}:${offerId}`)}`,
      `partnerRedemptionTokens/${foreign}`,
      `partnerPortalRequests/${foreign}`,
    ])
      expect((await db.doc(path).get()).exists).toBe(true);
    expect((await preservedStats.get()).data()).toEqual(aggregate);
    expect((await processedRedemption.get()).data()).toEqual({ processed: true });
  });

  it('erases used tombstones without an original reservation or offer parent and preserves other customers', async () => {
    const uid = `${prefix}-used`,
      other = `${prefix}-used-other`,
      offerId = `${prefix}-missing-offer`;
    const own = db.doc(`partnerClaimLocks/${hash(`${uid}:${offerId}`)}`);
    const foreign = db.doc(`partnerClaimLocks/${hash(`${other}:${offerId}`)}`);
    await own.set({ offerId, used: true });
    await foreign.set({ offerId, used: true });
    await erase(uid);
    expect((await own.get()).exists).toBe(false);
    expect((await foreign.get()).exists).toBe(true);
  });

  it('canonical hard purge removes private business contacts, owner and staff bindings while retaining the business', async () => {
    const uid = `${prefix}-canonical`,
      other = `${prefix}-staff`,
      companyId = `${prefix}-company`;
    expect(
      (await getAuth(app).importUsers([{ uid, email: `${uid}@example.test`, emailVerified: true }]))
        .errors,
    ).toEqual([]);
    await db.doc(`users/${uid}`).set({ deleted: true });
    await db.doc(`companies/${companyId}`).set({ name: 'Surviving business', status: 'active' });
    await db.doc(`partnerApplicationOwners/${uid}`).set({ applicationId: `${prefix}-application` });
    await db.doc(`partnerApplications/${prefix}-application`).set({
      submittedByUserId: uid,
      contactName: 'Deleted person',
      contactEmail: 'private@example.test',
      logoPreviewBase64: 'private-preview',
    });
    await db.doc(`partnerApplicationEmails/${prefix}-email`).set({ uid });
    await db
      .doc(`partnerMembers/${hash(`${companyId}:${uid}`)}`)
      .set({ uid, companyId, enabled: true, role: 'manager' });
    await db
      .doc(`partnerMembers/${hash(`${companyId}:${other}`)}`)
      .set({ uid: other, companyId, enabled: true, role: 'manager' });
    await getStorage(app)
      .bucket()
      .file(`profileImages/${uid}/test.png`)
      .save(Buffer.from('local fixture'));
    await purge(uid);
    for (const path of [
      `partnerApplicationOwners/${uid}`,
      `partnerApplications/${prefix}-application`,
      `partnerApplicationEmails/${prefix}-email`,
      `partnerMembers/${hash(`${companyId}:${uid}`)}`,
      `accountPurgeProgress/${uid}`,
    ])
      expect((await db.doc(path).get()).exists).toBe(false);
    expect((await db.doc(`companies/${companyId}`).get()).data()!.status).toBe('active');
    expect((await db.doc(`partnerMembers/${hash(`${companyId}:${other}`)}`).get()).exists).toBe(
      true,
    );
    await expect(getAuth(app).getUser(uid)).rejects.toMatchObject({ code: 'auth/user-not-found' });
  });

  it('commits claim-page progress atomically, resumes its budget and retains unrelated purge progress', async () => {
    const uid = `${prefix}-paged`;
    const own = Array.from({ length: 8 }, (_, n) => ({
      offerId: `${prefix}-paged-offer-${n}`,
      id: hash(`${uid}:${prefix}-paged-offer-${n}`),
    }));
    const batch = db.batch();
    for (const row of own)
      batch.set(db.doc(`partnerClaimLocks/${row.id}`), { used: true, offerId: row.offerId });
    batch.set(db.doc(`accountPurgeProgress/${uid}`), {
      rsvpCursor: 'events/example/rsvps/member',
      rsvpComplete: false,
    });
    await batch.commit();
    await expect(erase(uid, { pageSize: 2, maxPages: 1 })).rejects.toThrow('resume');
    expect((await db.doc(`accountPurgeProgress/${uid}`).get()).data()).toMatchObject({
      partnerClaimsComplete: false,
      rsvpComplete: false,
      rsvpCursor: 'events/example/rsvps/member',
    });
    let complete = false;
    for (let attempt = 0; attempt < 100 && !complete; attempt++) {
      try {
        await erase(uid, { pageSize: 2, maxPages: 1 });
        complete = true;
      } catch (error) {
        expect(String(error)).toContain('resume');
      }
    }
    expect(complete).toBe(true);
    for (const row of own)
      expect((await db.doc(`partnerClaimLocks/${row.id}`).get()).exists).toBe(false);
    expect((await db.doc(`accountPurgeProgress/${uid}`).get()).data()).toMatchObject({
      partnerClaimsComplete: true,
      rsvpComplete: false,
    });
  });

  it('does not advance the claim cursor or delete a claim if its atomic batch fails', async () => {
    const uid = `${prefix}-failure`,
      offerId = `${prefix}-failure-offer`;
    const claim = db.doc(`partnerClaimLocks/${hash(`${uid}:${offerId}`)}`);
    await claim.set({ used: true, offerId });
    const original = db.batch.bind(db);
    vi.spyOn(db, 'batch').mockImplementationOnce(() => {
      const batch = original();
      vi.spyOn(batch, 'commit').mockRejectedValueOnce(new Error('injected page failure'));
      return batch;
    });
    await expect(erase(uid)).rejects.toThrow('injected page failure');
    expect((await claim.get()).exists).toBe(true);
    expect((await db.doc(`accountPurgeProgress/${uid}`).get()).data()).toMatchObject({
      partnerErasureActive: true,
    });
    expect((await db.doc(`accountPurgeProgress/${uid}`).get()).data()).not.toHaveProperty(
      'partnerClaimsCursor',
    );
    vi.restoreAllMocks();
    await erase(uid);
    expect((await claim.get()).exists).toBe(false);
  });

  it('preserves partner progress through the event-erasure stage and a later Auth failure', async () => {
    const uid = `${prefix}-cross-phase`;
    expect((await getAuth(app).importUsers([{ uid }])).errors).toEqual([]);
    await db.doc(`users/${uid}`).set({ deleted: true });
    vi.spyOn(getAuth(app), 'deleteUser').mockRejectedValueOnce(
      Object.assign(new Error('injected final Auth failure'), { code: 'auth/internal-error' }),
    );
    await expect(purge(uid)).rejects.toThrow('injected final Auth failure');
    expect((await db.doc(`accountPurgeProgress/${uid}`).get()).data()).toMatchObject({
      partnerClaimsComplete: true,
      rsvpComplete: true,
    });
    vi.restoreAllMocks();
    await purge(uid);
    expect((await db.doc(`accountPurgeProgress/${uid}`).get()).exists).toBe(false);
    await expect(getAuth(app).getUser(uid)).rejects.toMatchObject({ code: 'auth/user-not-found' });
  });

  it('retains the owner after a late transaction failure so reference cleanup and quota release can resume', async () => {
    const uid = `${prefix}-transaction`,
      offerId = `${prefix}-transaction-offer`,
      reservationId = `${prefix}-transaction-reservation`;
    await db.doc(`offers/${offerId}`).set({ capacity: 1, remainingCount: 0 });
    await db.doc(`partnerOfferQuotas/${offerId}`).set({ reservedCount: 1, redeemedCount: 0 });
    await db.doc(`partnerReservations/${reservationId}`).set({ uid, offerId, status: 'reserved' });
    await db.doc(`partnerClaimLocks/${hash(`${uid}:${offerId}`)}`).set({ reservationId });
    await db.doc(`partnerRedemptionTokens/${reservationId}`).set({ reservationId });
    vi.spyOn(db, 'runTransaction').mockRejectedValueOnce(new Error('injected reservation failure'));
    await expect(erase(uid)).rejects.toThrow('injected reservation failure');
    expect((await db.doc(`partnerReservations/${reservationId}`).get()).exists).toBe(true);
    expect((await db.doc(`partnerRedemptionTokens/${reservationId}`).get()).exists).toBe(false);
    expect((await db.doc(`partnerOfferQuotas/${offerId}`).get()).data()!.reservedCount).toBe(1);
    vi.restoreAllMocks();
    await erase(uid);
    await erase(uid);
    expect((await db.doc(`partnerReservations/${reservationId}`).get()).exists).toBe(false);
    expect((await db.doc(`partnerOfferQuotas/${offerId}`).get()).data()!.reservedCount).toBe(0);
    expect((await db.doc(`offers/${offerId}`).get()).data()!.remainingCount).toBe(1);
  });

  it('leaves a large account pending after its reservation budget and resumes without double release', async () => {
    const uid = `${prefix}-large`;
    const batch = db.batch();
    for (let n = 0; n < 101; n++) {
      const offerId = `${prefix}-large-offer-${n}`,
        reservationId = `${prefix}-large-reservation-${n}`;
      batch.set(db.doc(`offers/${offerId}`), { capacity: 1, remainingCount: 0 });
      batch.set(db.doc(`partnerOfferQuotas/${offerId}`), { reservedCount: 1, redeemedCount: 0 });
      batch.set(db.doc(`partnerReservations/${reservationId}`), {
        uid,
        offerId,
        status: 'reserved',
      });
      batch.set(db.doc(`partnerClaimLocks/${hash(`${uid}:${offerId}`)}`), { reservationId });
    }
    await batch.commit();
    await expect(erase(uid)).rejects.toThrow('Partner reservation erasure is pending');
    expect((await db.collection('partnerReservations').where('uid', '==', uid).get()).size).toBe(1);
    expect((await db.doc(`accountPurgeProgress/${uid}`).get()).data()).toMatchObject({
      partnerErasureActive: true,
    });
    expect((await db.doc(`accountPurgeProgress/${uid}`).get()).data()).not.toHaveProperty(
      'partnerClaimsComplete',
    );
    await erase(uid);
    expect((await db.collection('partnerReservations').where('uid', '==', uid).get()).empty).toBe(
      true,
    );
    for (let n = 0; n < 101; n++)
      expect(
        (await db.doc(`partnerOfferQuotas/${prefix}-large-offer-${n}`).get()).data()!.reservedCount,
      ).toBe(0);
  });

  it('normal terminal cleanup keeps an account-erasure anchor until all bounded references are gone', async () => {
    const uid = `${prefix}-cleanup-race`,
      offerId = `${prefix}-cleanup-offer`,
      reservationId = `${prefix}-cleanup-reservation`;
    const batch = db.batch();
    batch.set(db.doc(`offers/${offerId}`), { capacity: 3, remainingCount: 2 });
    batch.set(db.doc(`partnerOfferQuotas/${offerId}`), { reservedCount: 0, redeemedCount: 1 });
    batch.set(db.doc(`partnerReservations/${reservationId}`), {
      uid,
      offerId,
      status: 'redeemed',
      closedAt: Timestamp.fromMillis(Date.now() - 40 * 86400000),
    });
    batch.set(db.doc(`partnerClaimLocks/${hash(`${uid}:${offerId}`)}`), { reservationId });
    for (let n = 0; n < 302; n++)
      batch.set(db.doc(`partnerPortalRequests/${prefix}-cleanup-request-${n}`), {
        reservationId,
        expiresAt: Timestamp.fromMillis(Date.now() - 86400000),
      });
    await batch.commit();
    const original = db.batch.bind(db);
    vi.spyOn(db, 'batch').mockImplementationOnce(() => {
      const batch = original();
      vi.spyOn(batch, 'commit').mockRejectedValueOnce(new Error('injected references failure'));
      return batch;
    });
    await expect(erase(uid)).rejects.toThrow('injected references failure');
    vi.restoreAllMocks();
    await expire();
    expect((await db.doc(`partnerReservations/${reservationId}`).get()).exists).toBe(true);
    expect(
      (
        await db
          .collection('partnerPortalRequests')
          .where('reservationId', '==', reservationId)
          .get()
      ).size,
    ).toBeGreaterThan(0);
    await erase(uid);
    expect((await db.doc(`partnerReservations/${reservationId}`).get()).exists).toBe(false);
    expect(
      (
        await db
          .collection('partnerPortalRequests')
          .where('reservationId', '==', reservationId)
          .get()
      ).empty,
    ).toBe(true);
    expect((await db.doc(`partnerOfferQuotas/${offerId}`).get()).data()!.redeemedCount).toBe(1);
  });

  it('denies stale inactive-account calls and grants while their profile still says not deleted', async () => {
    const customer = await account('fenced-customer'),
      manager = await account('fenced-manager'),
      admin = await account('fenced-admin', 'admin');
    const target = await account('fenced-target');
    const companyId = `${prefix}-fenced-company`,
      offerId = `${prefix}-fenced-offer`;
    await db
      .doc(`companies/${companyId}`)
      .set({ name: 'Fence business', status: 'active', description: 'Original presentation' });
    await db
      .doc(`partnerMembers/${hash(`${companyId}:${manager}`)}`)
      .set({ uid: manager, companyId, role: 'manager', enabled: true });
    await db.doc(`offers/${offerId}`).set({
      companyId,
      status: 'active',
      redemptionMode: 'reservation',
      version: 1,
      capacity: 3,
      reservationDays: 7,
      title: 'Fence offer',
    });
    await db.doc(`offers/${offerId}/details/member`).set({ terms: 'Original terms' });
    const reservation = await command(customer, 'reserve', {
      offerId,
      expectedVersion: 1,
      requestId: randomUUID(),
    });
    const token = await command(customer, 'token', { reservationId: reservation.reservationId });
    await getAuth(app).updateUser(customer, { disabled: true });
    await getAuth(app).revokeRefreshTokens(customer);
    await db
      .doc(`accountDeletionRequests/${customer}`)
      .set({ userId: customer, status: 'pending' });
    expect((await db.doc(`users/${customer}`).get()).data()!.deleted).toBe(false);
    for (const [action, fields] of [
      ['reserve', { offerId, expectedVersion: 1, requestId: randomUUID() }],
      ['token', { reservationId: reservation.reservationId }],
      ['submitApplication', application()],
      ['workspace', {}],
    ] as const)
      await expect(command(customer, action, fields)).rejects.toMatchObject({
        code: 'permission-denied',
      });
    await expect(command(manager, 'inspect', { token: token.token })).rejects.toMatchObject({
      code: 'permission-denied',
    });
    await expect(
      command(manager, 'redeem', { token: token.token, requestId: randomUUID() }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    await getAuth(app).updateUser(manager, { disabled: true });
    await db.doc(`accountDeletionRequests/${manager}`).set({ userId: manager, status: 'pending' });
    for (const [action, fields] of [
      ['updateProfile', { companyId, description: 'Forbidden replacement' }],
      ['inspect', { token: token.token }],
      ['redeem', { token: token.token, requestId: randomUUID() }],
    ] as const)
      await expect(command(manager, action, fields)).rejects.toMatchObject({
        code: 'permission-denied',
      });
    expect((await db.doc(`companies/${companyId}`).get()).data()!.description).toBe(
      'Original presentation',
    );
    // An active Auth snapshot must not override the retained deletion fence
    // when granting staff or publishing an applicant's requested permission.
    await db.doc(`accountDeletionRequests/${target}`).set({ userId: target, status: 'pending' });
    await expect(
      command(
        admin,
        'membership',
        { companyId, uid: target, role: 'manager', enabled: true },
        true,
      ),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    const applicationId = `${prefix}-fenced-application`;
    await db.doc(`partnerApplications/${applicationId}`).set({
      submittedByUserId: target,
      status: 'submitted',
      revision: 1,
      selfServiceRequested: true,
      companyName: 'Pending business',
      category: 'other',
      proposedDescription: 'Business presentation',
      proposedAddress: 'Business address',
    });
    await expect(
      command(
        admin,
        'reviewApplication',
        {
          applicationId,
          decision: 'publish',
          expectedRevision: 1,
          grantSelfService: true,
          latitude: 57.5,
          longitude: 12.1,
        },
        true,
      ),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    expect((await db.doc(`partnerMembers/${hash(`${companyId}:${target}`)}`).get()).exists).toBe(
      false,
    );
    expect(
      (await db.collection('companies').where('sourceApplicationId', '==', applicationId).get())
        .empty,
    ).toBe(true);
    expect(
      (await db.doc(`partnerReservations/${reservation.reservationId}`).get()).data()!.status,
    ).toBe('reserved');
  });

  it('fences immediate purges without a deletion request and rejects an old token after its progress marker is removed', async () => {
    const uid = await account('immediate');
    const offerId = `${prefix}-immediate-offer`;
    await db.doc(`partnerClaimLocks/${hash(`${uid}:${offerId}`)}`).set({ used: true, offerId });
    await expect(erase(uid, { pageSize: 1, maxPages: 1 })).rejects.toThrow('resume');
    expect((await db.doc(`accountDeletionRequests/${uid}`).get()).exists).toBe(false);
    expect((await db.doc(`users/${uid}`).get()).data()!.deleted).toBe(false);
    await expect(command(uid, 'submitApplication', application())).rejects.toMatchObject({
      code: 'permission-denied',
    });
    const policy = db.doc('carCommunityPolicy/access'),
      previous = await policy.get();
    try {
      await policy.set({ enforceInitialized: false }, { merge: true });
      await purge(uid);
      expect((await db.doc(`accountPurgeProgress/${uid}`).get()).exists).toBe(false);
      expect((await db.doc(`users/${uid}`).get()).exists).toBe(false);
      await expect(command(uid, 'submitApplication', application())).rejects.toMatchObject({
        code: 'permission-denied',
      });
      expect(
        (await db.collection('partnerApplications').where('submittedByUserId', '==', uid).get())
          .empty,
      ).toBe(true);
    } finally {
      if (previous.exists) await policy.set(previous.data()!);
      else await policy.delete();
    }
  });
});
