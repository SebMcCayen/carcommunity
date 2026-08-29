/**
 * Phase 11 emulator integration tests — subscription entitlement +
 * group driving.
 *
 * Subscription: subscription-verify FAILS CLOSED while providers are
 * unconfigured; subscription-grantEntitlement drives the full chain
 * (subscriptions/{uid} record, users.activeMember, activeMember claim,
 * audit record) and revocation tears it back down.
 *
 * Group drive: join preconditions (published + RSVP going|maybe),
 * idempotent join/rejoin, status updates, idempotent leave.
 *
 * Requires the Functions emulator — run via:
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
import { getFirestore as getAdminFirestore, FieldValue } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  PURCHASE_VERIFICATION_LEASE_MS,
  claimPurchaseTokenHash,
  releasePurchaseVerification,
  reservePurchaseVerification,
} from '../subscription/purchase-token-ownership';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'phase11-emulator-tests');
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

let adminUser: TestUser;
let member: TestUser;
let eventId: string;

const futureStart = new Date(Date.now() + 7 * 24 * 3600_000).toISOString();

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'phase11-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('p11-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  member = await createProvisionedUser('p11-member');

  // A published event for the group drive tests.
  await signInAs(adminUser);
  eventId = (
    (
      await call('events-create', {
        title: 'Phase 11 gruppkörning',
        summary: 'Testcruise',
        description: 'Lång beskrivning',
        startsAt: futureStart,
        approximateArea: 'Stockholm',
        locationName: 'Parkeringen',
        address: 'Garagevägen 1',
        latitude: 59.3,
        longitude: 18.0,
      })
    ).data as { eventId: string }
  ).eventId;
  await call('events-publish', { eventId });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('subscription entitlement chain', () => {
  it('verify FAILS CLOSED while store providers are unconfigured', async () => {
    await signInAs(member);
    expect(
      await callableErrorCode(
        call('subscription-verify', { platform: 'apple', purchaseToken: 'receipt-abc' }),
      ),
    ).toBe('functions/failed-precondition');
  });

  it('purchase-token hash claim is same-UID idempotent and rejects cross-UID replay', async () => {
    const tokenHash = `${Date.now().toString(16).padStart(16, '0')}${'a'.repeat(48)}`;
    await claimPurchaseTokenHash(tokenHash, {
      uid: member.uid,
      productId: 'plus_monthly',
    });
    await claimPurchaseTokenHash(tokenHash, {
      uid: member.uid,
      productId: 'plus_monthly',
    });

    await expect(
      claimPurchaseTokenHash(tokenHash, {
        uid: adminUser.uid,
        productId: 'plus_monthly',
      }),
    ).rejects.toMatchObject({ reason: 'different_user' });

    const claim = (
      await adminDb.collection('subscriptionPurchaseTokens').doc(tokenHash).get()
    ).data();
    expect(claim).toMatchObject({
      tokenHash,
      uid: member.uid,
      productId: 'plus_monthly',
    });
  });

  it('atomically permits only one different first-purchase token per UID', async () => {
    const raceMember = await createProvisionedUser('p11-purchase-race');
    const tokenA = `${Date.now().toString(16).padStart(16, '0')}${'b'.repeat(48)}`;
    const tokenB = `${(Date.now() + 1).toString(16).padStart(16, '0')}${'c'.repeat(48)}`;
    const results = await Promise.allSettled([
      reservePurchaseVerification(
        tokenA,
        { uid: raceMember.uid, productId: 'plus_monthly' },
        new Date(),
      ),
      reservePurchaseVerification(
        tokenB,
        { uid: raceMember.uid, productId: 'supporter_monthly' },
        new Date(),
      ),
    ]);

    expect(results.filter((result) => result.status === 'fulfilled')).toHaveLength(1);
    const rejected = results.find((result) => result.status === 'rejected');
    expect(rejected).toMatchObject({ reason: { reason: 'different_active_token' } });

    const winnerIndex = results[0]?.status === 'fulfilled' ? 0 : 1;
    const winner = winnerIndex === 0 ? tokenA : tokenB;
    const winningResult = results[winnerIndex];
    expect(winningResult?.status).toBe('fulfilled');
    if (winningResult?.status !== 'fulfilled') throw new Error('Expected a reservation winner.');
    await releasePurchaseVerification(raceMember.uid, winner, winningResult.value);
    expect(
      (await adminDb.collection('subscriptionPurchaseVerifications').doc(raceMember.uid).get())
        .exists,
    ).toBe(false);
  });

  it('does not let an expired holder release a newer same-token reservation', async () => {
    const member = await createProvisionedUser('p11-purchase-lease-holder');
    const tokenHash = `${Date.now().toString(16).padStart(16, '0')}${'d'.repeat(48)}`;
    const now = new Date();
    const oldReservationId = await reservePurchaseVerification(
      tokenHash,
      { uid: member.uid, productId: 'plus_monthly' },
      now,
    );
    const newReservationId = await reservePurchaseVerification(
      tokenHash,
      { uid: member.uid, productId: 'plus_monthly' },
      new Date(now.getTime() + PURCHASE_VERIFICATION_LEASE_MS + 1),
    );

    await releasePurchaseVerification(member.uid, tokenHash, oldReservationId);
    const lease = await adminDb
      .collection('subscriptionPurchaseVerifications')
      .doc(member.uid)
      .get();
    expect(lease.data()?.reservationId).toBe(newReservationId);

    await releasePurchaseVerification(member.uid, tokenHash, newReservationId);
  });

  it('admin manual grant applies record + flag + claim; revoke tears down', async () => {
    await signInAs(adminUser);
    await call('subscription-grantEntitlement', {
      targetUid: member.uid,
      entitlement: 'member_monthly',
      reason: 'Manuell aktivering under test.',
    });

    const record = (await adminDb.collection('subscriptions').doc(member.uid).get()).data()!;
    expect(record).toMatchObject({
      platform: 'manual',
      status: 'active',
      entitlement: 'member_monthly',
      tier: 'plus',
      purchaseTokenHash: null,
    });
    expect(record.startsAt?.toDate()).toBeInstanceOf(Date);
    expect((await adminDb.collection('users').doc(member.uid).get()).data()!.activeMember).toBe(
      true,
    );
    expect((await adminAuth.getUser(member.uid)).customClaims?.activeMember).toBe(true);
    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'subscription.grantEntitlement')
      .where('targetId', '==', member.uid)
      .get();
    expect(audit.size).toBe(1);
    expect(audit.docs[0]?.data().details).toMatchObject({ tier: 'plus' });

    // The member gate is genuinely open now: a member-only callable works.
    await signInAs(member);
    const session = (await call('live-startSession', { duration: '1h' })).data as {
      status: string;
    };
    expect(session.status).toBe('active');
    await call('live-stopSession', {});
  });

  it('revocation clears the flag and claim', async () => {
    const casual = await createProvisionedUser('p11-casual');
    await signInAs(adminUser);
    await call('subscription-grantEntitlement', {
      targetUid: casual.uid,
      entitlement: 'member_monthly',
      reason: 'Grant.',
    });
    const granted = (await adminDb.collection('subscriptions').doc(casual.uid).get()).data()!;
    await call('subscription-grantEntitlement', {
      targetUid: casual.uid,
      entitlement: 'none',
      reason: 'Revoke.',
    });
    expect((await adminDb.collection('users').doc(casual.uid).get()).data()!.activeMember).toBe(
      false,
    );
    expect((await adminAuth.getUser(casual.uid)).customClaims?.activeMember).toBeUndefined();
    const revoked = (await adminDb.collection('subscriptions').doc(casual.uid).get()).data()!;
    expect(revoked.status).toBe('revoked');
    expect(revoked.tier).toBe('plus');
    expect(revoked.startsAt.toMillis()).toBe(granted.startsAt.toMillis());
  });

  it('grants Supporter as paid and preserves tier/start across legacy regrant and revoke', async () => {
    const supporter = await createProvisionedUser('p11-supporter');
    await signInAs(adminUser);
    await call('subscription-grantEntitlement', {
      targetUid: supporter.uid,
      entitlement: 'member_monthly',
      tier: 'supporter',
      reason: 'Supporter test grant.',
    });
    const first = (await adminDb.collection('subscriptions').doc(supporter.uid).get()).data()!;
    expect(first.tier).toBe('supporter');
    expect((await adminDb.collection('users').doc(supporter.uid).get()).data()!.activeMember).toBe(
      true,
    );
    expect((await adminAuth.getUser(supporter.uid)).customClaims?.activeMember).toBe(true);

    // An old caller omits tier. It must not silently downgrade an active Supporter.
    await call('subscription-grantEntitlement', {
      targetUid: supporter.uid,
      entitlement: 'member_monthly',
      reason: 'Legacy-compatible regrant.',
    });
    const regranted = (await adminDb.collection('subscriptions').doc(supporter.uid).get()).data()!;
    expect(regranted.tier).toBe('supporter');
    expect(regranted.startsAt.toMillis()).toBe(first.startsAt.toMillis());

    await call('subscription-grantEntitlement', {
      targetUid: supporter.uid,
      entitlement: 'none',
      reason: 'Supporter test revoke.',
    });
    const supporterRevoked = (
      await adminDb.collection('subscriptions').doc(supporter.uid).get()
    ).data()!;
    expect(supporterRevoked).toMatchObject({
      status: 'revoked',
      entitlement: 'none',
      tier: 'supporter',
    });
    expect(supporterRevoked.startsAt.toMillis()).toBe(first.startsAt.toMillis());

    // A repeated legacy revoke remains idempotent and must not normalize the
    // retained lifecycle tier to Community.
    await call('subscription-grantEntitlement', {
      targetUid: supporter.uid,
      entitlement: 'none',
      reason: 'Repeated Supporter test revoke.',
    });
    const revokedAgain = (
      await adminDb.collection('subscriptions').doc(supporter.uid).get()
    ).data()!;
    expect(revokedAgain).toMatchObject({
      status: 'revoked',
      entitlement: 'none',
      tier: 'supporter',
    });
    expect(revokedAgain.startsAt.toMillis()).toBe(first.startsAt.toMillis());
  });
});

describe('group drive roster', () => {
  it('requires RSVP going|maybe, then joins idempotently and rejoins after leave', async () => {
    await signInAs(member);
    // No RSVP yet → denied.
    expect(await callableErrorCode(call('groupDrive-join', { eventId }))).toBe(
      'functions/permission-denied',
    );

    // RSVP going (rules-validated direct write, 9b model).
    await adminDb
      .collection('events')
      .doc(eventId)
      .collection('rsvps')
      .doc(member.uid)
      .set({ status: 'going', updatedAt: FieldValue.serverTimestamp() });

    const first = (await call('groupDrive-join', { eventId })).data as { rejoined: boolean };
    expect(first.rejoined).toBe(false);
    const roster = adminDb
      .collection('events')
      .doc(eventId)
      .collection('groupDriveParticipants')
      .doc(member.uid);
    expect((await roster.get()).data()!.status).toBe('joined');

    // Idempotent second join.
    const again = (await call('groupDrive-join', { eventId })).data as { rejoined: boolean };
    expect(again.rejoined).toBe(false);

    // Status progression; 'left' is not an updatable status.
    await call('groupDrive-updateStatus', { eventId, status: 'on_the_way' });
    expect((await roster.get()).data()!.status).toBe('on_the_way');
    expect(
      await callableErrorCode(call('groupDrive-updateStatus', { eventId, status: 'left' })),
    ).toBe('functions/invalid-argument');

    // Leave (idempotent), then rejoin resets.
    await call('groupDrive-leave', { eventId });
    expect((await roster.get()).data()!.status).toBe('left');
    await call('groupDrive-leave', { eventId }); // quiet no-op
    const rejoin = (await call('groupDrive-join', { eventId })).data as { rejoined: boolean };
    expect(rejoin.rejoined).toBe(true);
    expect((await roster.get()).data()!.leftAt).toBeNull();
  });

  it('rejects non-members and unpublished events', async () => {
    const free = await createProvisionedUser('p11-free');
    await signInAs(free);
    expect(await callableErrorCode(call('groupDrive-join', { eventId }))).toBe(
      'functions/permission-denied',
    );

    await signInAs(adminUser);
    const draftId = (
      (
        await call('events-create', {
          title: 'Utkast',
          summary: 'x',
          description: 'x',
          startsAt: futureStart,
          approximateArea: 'X',
          locationName: 'X',
          address: 'X',
          latitude: 59,
          longitude: 18,
        })
      ).data as { eventId: string }
    ).eventId;
    await signInAs(member);
    expect(await callableErrorCode(call('groupDrive-join', { eventId: draftId }))).toBe(
      'functions/failed-precondition',
    );
    // Cannot update status without an active participation.
    expect(
      await callableErrorCode(
        call('groupDrive-updateStatus', { eventId: draftId, status: 'arrived' }),
      ),
    ).toBe('functions/failed-precondition');
  });
});
