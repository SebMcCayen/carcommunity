/**
 * subscription-expireLapsed emulator integration tests — the EXIT from the
 * paid tier (functions/src/subscription/scheduled.ts).
 *
 * Covers, against a real Auth + Firestore emulator:
 * - lapsed beyond the grace window → entitlement revoked in ALL THREE
 *   places (subscriptions record, users.activeMember, activeMember claim),
 *   recorded on userLifecycle/{uid}.subscriptionExpiry, member notified;
 * - lapsed but still INSIDE the grace window → completely untouched;
 * - not yet expired → untouched;
 * - already revoked → idempotent no-op, and a second sweep neither
 *   re-revokes nor duplicates the notification;
 * - grace_period past the window → revoked (the derived status set);
 * - entitlement with NO subscriptions document → untouched (the perpetual
 *   manual grant / operator access case);
 * - entitlement whose subscription carries no expiresAt → untouched;
 * - an ORPHANED record whose AUTH account is gone → closed record-only;
 * - a record whose users/{uid} document is gone but whose Auth account is
 *   LIVE → fully revoked, claim included (a missing user document is not
 *   proof of an erased account);
 * - an Auth error that is NOT user-not-found → counted as failed and left
 *   granting for the next run, never mistaken for an orphan.
 *
 * Every emulator test file shares ONE Firestore instance, so display names
 * carry the `subexp` suffix to stay unique to this file.
 *
 * Requires the Functions/Firestore/Auth emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { describe, expect, it } from 'vitest';
import { runSubscriptionExpirySweep } from '../subscription/scheduled';
import {
  SUBSCRIPTION_EXPIRY_GRACE_HOURS,
  subscriptionExpiredNotificationId,
} from '../subscription/expiry-core';
import type { SubscriptionStatus } from '../subscription/subscription-core';

const PROJECT_ID = 'demo-test';
const SUFFIX = 'subexp';
const HOUR_MS = 60 * 60 * 1000;

const adminApp =
  getAdminApps()[0] ??
  initializeAdminApp({ projectId: PROJECT_ID }, 'subscription-expiry-emulator-tests');
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);

const NOW = new Date();

function hoursAgo(hours: number): Date {
  return new Date(NOW.getTime() - hours * HOUR_MS);
}

/** Past the grace window — the sweep must act. */
const LAPSED = hoursAgo(SUBSCRIPTION_EXPIRY_GRACE_HOURS + 6);
/** Expired, but still inside the grace window — the sweep must NOT act. */
const RECENTLY_LAPSED = hoursAgo(2);

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

/**
 * Creates a real Auth user (the sweep reads and writes custom claims, so a
 * stub uid will not do) plus its provisioned users/{uid} document, then
 * grants entitlement in all three places the same way applyEntitlement
 * does — so each test starts from a genuinely entitled member.
 */
async function createEntitledMember(
  label: string,
  options: {
    expiresAt?: Date | null;
    status?: SubscriptionStatus;
    /** Omit the subscriptions document entirely. */
    withoutSubscription?: boolean;
    /** Grant the record but not users.activeMember / the claim. */
    entitled?: boolean;
  } = {},
): Promise<string> {
  const { status = 'active', entitled = true } = options;
  const unique = `${label}-${SUFFIX}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const user = await adminAuth.createUser({
    email: `${unique}@example.com`,
    password: 'password-123',
  });
  const uid = user.uid;

  // The onUserCreate provisioning trigger owns users/{uid}; wait for it
  // rather than racing it with our own create.
  await pollUntil(async () => {
    const snap = await adminDb.collection('users').doc(uid).get();
    return snap.exists ? true : undefined;
  });
  await adminDb
    .collection('users')
    .doc(uid)
    .set({ displayName: `Sub Expiry ${unique}`, activeMember: entitled }, { merge: true });
  if (entitled) {
    await adminAuth.setCustomUserClaims(uid, { activeMember: true });
  }

  if (!options.withoutSubscription) {
    await adminDb
      .collection('subscriptions')
      .doc(uid)
      .set({
        userId: uid,
        platform: 'manual',
        status,
        entitlement: status === 'expired' || status === 'revoked' ? 'none' : 'member_monthly',
        purchaseTokenHash: null,
        expiresAt:
          options.expiresAt === undefined || options.expiresAt === null
            ? null
            : Timestamp.fromDate(options.expiresAt),
        updatedAt: Timestamp.fromDate(NOW),
      });
  }
  return uid;
}

interface EntitlementState {
  status: unknown;
  entitlement: unknown;
  platform: unknown;
  expiresAt: unknown;
  activeMemberField: unknown;
  activeMemberClaim: unknown;
}

/** Reads all three representations of entitlement for one member. */
async function readEntitlement(uid: string): Promise<EntitlementState> {
  const [subSnap, userSnap, authUser] = await Promise.all([
    adminDb.collection('subscriptions').doc(uid).get(),
    adminDb.collection('users').doc(uid).get(),
    adminAuth.getUser(uid),
  ]);
  const sub = subSnap.data();
  return {
    status: sub?.status,
    entitlement: sub?.entitlement,
    platform: sub?.platform,
    expiresAt: sub?.expiresAt,
    activeMemberField: userSnap.data()?.activeMember,
    activeMemberClaim: authUser.customClaims?.activeMember,
  };
}

async function readLifecycleExpiry(uid: string): Promise<Record<string, unknown> | undefined> {
  const snap = await adminDb.collection('userLifecycle').doc(uid).get();
  return snap.data()?.subscriptionExpiry as Record<string, unknown> | undefined;
}

async function readExpiryNotification(uid: string, expiresAt: Date) {
  return adminDb
    .collection('notifications')
    .doc(uid)
    .collection('items')
    .doc(subscriptionExpiredNotificationId(expiresAt))
    .get();
}

// No client app to wire: the sweep runs in-process against the emulators via
// the Admin SDK, exactly like runAccountPurge / runInactivityCleanup.

describe('runSubscriptionExpirySweep — revocation', () => {
  it('revokes all three representations of entitlement past the grace window', async () => {
    const uid = await createEntitledMember('lapsed', { expiresAt: LAPSED });

    // Precondition: genuinely entitled everywhere.
    await expect(readEntitlement(uid)).resolves.toMatchObject({
      status: 'active',
      entitlement: 'member_monthly',
      activeMemberField: true,
      activeMemberClaim: true,
    });

    const result = await runSubscriptionExpirySweep(NOW);
    expect(result.expiredUids).toContain(uid);

    const after = await readEntitlement(uid);
    // 1. the record
    expect(after.status).toBe('expired');
    expect(after.entitlement).toBe('none');
    // 2. users/{uid}.activeMember — what every gated callable reads
    expect(after.activeMemberField).toBe(false);
    // 3. the custom claim — what firestore/storage/database rules read.
    //    computeUpdatedClaims REMOVES a false claim rather than storing it
    //    (rules compare with `!= true`), so absent is the revoked state.
    expect(after.activeMemberClaim).toBeUndefined();
  });

  it('preserves the record history rather than blanking it', async () => {
    // applyEntitlement rewrites subscriptions/{uid} wholesale, so the sweep
    // has to carry these through. expiresAt stays the lapse instant — it is
    // the historical fact of when the paid period ended.
    const uid = await createEntitledMember('history', { expiresAt: LAPSED });
    await adminDb
      .collection('subscriptions')
      .doc(uid)
      .set({ platform: 'google', purchaseTokenHash: 'b'.repeat(64) }, { merge: true });

    await runSubscriptionExpirySweep(NOW);

    const sub = (await adminDb.collection('subscriptions').doc(uid).get()).data()!;
    expect(sub.platform).toBe('google');
    expect(sub.purchaseTokenHash).toBe('b'.repeat(64));
    expect((sub.expiresAt as Timestamp).toMillis()).toBe(LAPSED.getTime());
    expect(sub.userId).toBe(uid);
  });

  it('records the revocation on userLifecycle/{uid}.subscriptionExpiry', async () => {
    const uid = await createEntitledMember('audit', { expiresAt: LAPSED });
    await runSubscriptionExpirySweep(NOW);

    const record = await readLifecycleExpiry(uid);
    expect(record).toMatchObject({
      previousStatus: 'active',
      platform: 'manual',
      graceHours: SUBSCRIPTION_EXPIRY_GRACE_HOURS,
      source: 'subscription-expireLapsed',
    });
    expect((record!.lapsedAt as Timestamp).toMillis()).toBe(LAPSED.getTime());
    expect(record!.expiredAt).toBeInstanceOf(Timestamp);
  });

  it('notifies the member in-app under subscription_status', async () => {
    const uid = await createEntitledMember('notify', { expiresAt: LAPSED });
    const result = await runSubscriptionExpirySweep(NOW);
    expect(result.notifiedCount).toBeGreaterThan(0);

    const snap = await readExpiryNotification(uid, LAPSED);
    expect(snap.exists).toBe(true);
    expect(snap.data()).toMatchObject({
      category: 'subscription_status',
      actionType: 'open_subscription',
      read: false,
    });
  });

  it('revokes a grace_period subscription past the window too', async () => {
    // grace_period also GRANTS access (isSubscriptionActiveStatus), so the
    // store's billing retry cannot become an indefinite free ride.
    const uid = await createEntitledMember('graceperiod', {
      expiresAt: LAPSED,
      status: 'grace_period',
    });
    const result = await runSubscriptionExpirySweep(NOW);
    expect(result.expiredUids).toContain(uid);

    await expect(readEntitlement(uid)).resolves.toMatchObject({
      status: 'expired',
      activeMemberField: false,
      activeMemberClaim: undefined,
    });
    expect(await readLifecycleExpiry(uid)).toMatchObject({ previousStatus: 'grace_period' });
  });
});

describe('runSubscriptionExpirySweep — what it must NOT touch', () => {
  it('leaves a subscription still inside the grace window fully intact', async () => {
    // The renewal-latency case. A false revocation here locks a paying
    // member out of what they paid for.
    const uid = await createEntitledMember('within-grace', { expiresAt: RECENTLY_LAPSED });
    const result = await runSubscriptionExpirySweep(NOW);
    expect(result.expiredUids).not.toContain(uid);

    await expect(readEntitlement(uid)).resolves.toMatchObject({
      status: 'active',
      entitlement: 'member_monthly',
      activeMemberField: true,
      activeMemberClaim: true,
    });
    expect(await readLifecycleExpiry(uid)).toBeUndefined();
    expect((await readExpiryNotification(uid, RECENTLY_LAPSED)).exists).toBe(false);
  });

  it('leaves a not-yet-expired subscription alone', async () => {
    const future = new Date(NOW.getTime() + 20 * 24 * HOUR_MS);
    const uid = await createEntitledMember('future', { expiresAt: future });
    const result = await runSubscriptionExpirySweep(NOW);
    expect(result.expiredUids).not.toContain(uid);

    await expect(readEntitlement(uid)).resolves.toMatchObject({
      status: 'active',
      activeMemberField: true,
      activeMemberClaim: true,
    });
  });

  /**
   * THE MANUAL-GRANT CASE. subscription.verify has no store adapter and
   * fails closed, so entitlement in production is granted by hand — and a
   * grant with no expiry date is PERPETUAL by design (this is also how the
   * operator's own admin/test access is held). The sweep is driven by an
   * explicit expired `expiresAt`, never by the absence of evidence, so a
   * perpetual grant survives every run. Ending one stays an explicit
   * subscription.grantEntitlement call.
   */
  it('never revokes a perpetual grant (subscription with no expiresAt)', async () => {
    const uid = await createEntitledMember('perpetual', { expiresAt: null });
    const result = await runSubscriptionExpirySweep(NOW);
    expect(result.expiredUids).not.toContain(uid);
    // Excluded by the QUERY, not merely by the in-code decision: Firestore's
    // inequality filters follow its total type ordering, in which null sorts
    // BEFORE every timestamp, so without the epoch lower bound every
    // perpetual grant would sort to the front of the ascending page and
    // starve real lapses out of it. A skip here would mean that regressed.
    expect(result.skippedCount).toBe(0);

    await expect(readEntitlement(uid)).resolves.toMatchObject({
      status: 'active',
      entitlement: 'member_monthly',
      expiresAt: null,
      activeMemberField: true,
      activeMemberClaim: true,
    });
    expect(await readLifecycleExpiry(uid)).toBeUndefined();
  });

  /**
   * Entitlement with NO subscriptions document at all. The sweep walks the
   * `subscriptions` collection, so this member is not even a candidate —
   * which is the safe behaviour: a sweep that revoked on a MISSING record
   * would turn any partial migration or read failure into a mass lockout of
   * paying members, and would strip access from anyone whose record was
   * written out of band.
   */
  it('never revokes entitlement that has no subscription document', async () => {
    const uid = await createEntitledMember('no-record', { withoutSubscription: true });
    const result = await runSubscriptionExpirySweep(NOW);
    expect(result.expiredUids).not.toContain(uid);

    const userSnap = await adminDb.collection('users').doc(uid).get();
    expect(userSnap.data()?.activeMember).toBe(true);
    expect((await adminAuth.getUser(uid)).customClaims?.activeMember).toBe(true);
    expect((await adminDb.collection('subscriptions').doc(uid).get()).exists).toBe(false);
    expect(await readLifecycleExpiry(uid)).toBeUndefined();
  });
});

describe('runSubscriptionExpirySweep — idempotency', () => {
  it('is a no-op for an already-revoked subscription', async () => {
    const uid = await createEntitledMember('already-revoked', {
      expiresAt: LAPSED,
      status: 'expired',
      entitled: false,
    });
    const result = await runSubscriptionExpirySweep(NOW);
    expect(result.expiredUids).not.toContain(uid);
    // Untouched — no lifecycle stamp, no notification for a lapse the sweep
    // never handled.
    expect(await readLifecycleExpiry(uid)).toBeUndefined();
    expect((await readExpiryNotification(uid, LAPSED)).exists).toBe(false);
  });

  it('does not double-apply or duplicate the notice on a re-run', async () => {
    const uid = await createEntitledMember('rerun', { expiresAt: LAPSED });

    const first = await runSubscriptionExpirySweep(NOW);
    expect(first.expiredUids).toContain(uid);
    const afterFirst = await readEntitlement(uid);
    const notificationAfterFirst = await readExpiryNotification(uid, LAPSED);
    expect(notificationAfterFirst.exists).toBe(true);
    const firstCreatedAt = (notificationAfterFirst.data()!.createdAt as Timestamp).toMillis();

    // The revoked document no longer matches the sweep's query (it filters
    // on GRANTING statuses), so the second run cannot see it at all.
    const second = await runSubscriptionExpirySweep(NOW);
    expect(second.expiredUids).not.toContain(uid);

    expect(await readEntitlement(uid)).toEqual(afterFirst);
    const notificationAfterSecond = await readExpiryNotification(uid, LAPSED);
    // Same document, same createdAt — the deterministic ID collapsed it.
    expect((notificationAfterSecond.data()!.createdAt as Timestamp).toMillis()).toBe(
      firstCreatedAt,
    );
    const items = await adminDb
      .collection('notifications')
      .doc(uid)
      .collection('items')
      .where('category', '==', 'subscription_status')
      .get();
    expect(items.size).toBe(1);
  });

  it('closes an orphaned record whose AUTH account is gone, record-only', async () => {
    // subscriptions/{uid} is purged by neither the deletion doc-tree sweep
    // nor the owned-document sweep, so an erased account can leave one
    // behind. Left granting it would match the query in every future run.
    const uid = await createEntitledMember('orphan', { expiresAt: LAPSED });
    // Delete the ACCOUNT, the way account-purgeDeleted finishes: doc trees
    // first, then the Auth user.
    await adminDb.collection('users').doc(uid).delete();
    await adminDb.collection('userLifecycle').doc(uid).delete();
    await adminAuth.deleteUser(uid);

    const result = await runSubscriptionExpirySweep(NOW);
    expect(result.orphanedCount).toBeGreaterThan(0);
    expect(result.expiredUids).not.toContain(uid);
    expect(result.failedCount).toBe(0);

    const sub = (await adminDb.collection('subscriptions').doc(uid).get()).data()!;
    expect(sub.status).toBe('expired');
    expect(sub.entitlement).toBe('none');
    expect(sub.tier).toBe('plus');
    // Merged, so the history survives.
    expect((sub.expiresAt as Timestamp).toMillis()).toBe(LAPSED.getTime());
    // No lifecycle document resurrected for an erased account.
    expect((await adminDb.collection('userLifecycle').doc(uid).get()).exists).toBe(false);

    // And it is gone from the next run's candidate set.
    const second = await runSubscriptionExpirySweep(NOW);
    expect(second.orphanedCount).toBe(0);
  });

  it('fully revokes a LIVE account even when its user document is missing', async () => {
    // The failure this pins: a missing users/{uid} is NOT proof the account
    // is gone. account-purgeDeleted deletes the doc trees BEFORE the Auth
    // user, so this state is reachable — and if the sweep read it as an
    // orphan it would close the record record-only, the record would stop
    // matching the query, and the still-live account would keep its
    // `activeMember` claim (which firestore/storage/database rules honour)
    // forever. The orphan signal must come from Auth.
    const uid = await createEntitledMember('livenodoc', { expiresAt: LAPSED });
    await adminDb.collection('users').doc(uid).delete();

    const result = await runSubscriptionExpirySweep(NOW);
    expect(result.expiredUids).toContain(uid);
    expect(result.orphanedCount).toBe(0);

    // The claim — the representation a record-only close would have left
    // standing — is cleared.
    const authUser = await adminAuth.getUser(uid);
    expect(authUser.customClaims?.activeMember).toBeFalsy();
    const sub = (await adminDb.collection('subscriptions').doc(uid).get()).data()!;
    expect(sub.status).toBe('expired');
    expect(sub.entitlement).toBe('none');
  });

  it('does NOT treat a non-user-not-found Auth error as an orphan', async () => {
    // A document ID over the Admin SDK's 128-character uid limit makes
    // adminAuth.getUser reject with `auth/invalid-uid` — a real Auth error
    // that is emphatically not "this user was deleted". It stands in for
    // any transient failure (quota, network, auth/internal-error): the
    // sweep must count it as FAILED and leave the record granting so a
    // later run retries, never silently downgrade it to a record-only
    // close that would strand the entitlement un-revoked.
    const poisonId = `subexp-invalid-uid-${Date.now()}-${'x'.repeat(140)}`;
    const ref = adminDb.collection('subscriptions').doc(poisonId);
    await ref.set({
      userId: poisonId,
      platform: 'manual',
      status: 'active',
      entitlement: 'member_monthly',
      purchaseTokenHash: null,
      expiresAt: Timestamp.fromDate(LAPSED),
      updatedAt: Timestamp.fromDate(NOW),
    });

    try {
      const result = await runSubscriptionExpirySweep(NOW);
      expect(result.failedCount).toBeGreaterThan(0);
      expect(result.expiredUids).not.toContain(poisonId);

      // Left granting — untouched, so it is still a candidate next run.
      const sub = (await ref.get()).data()!;
      expect(sub.status).toBe('active');
      expect(sub.entitlement).toBe('member_monthly');
      // And emphatically not closed as an orphan.
      expect(sub.status).not.toBe('expired');
      // No audit stamp: the orphan test runs before recordRevocation, so a
      // failure there writes nothing at all.
      expect((await adminDb.collection('userLifecycle').doc(poisonId).get()).exists).toBe(false);
    } finally {
      // Do not leave a permanently-failing record in the shared emulator
      // Firestore — every later run in every later file would re-fail it.
      await ref.delete();
    }
  });
});
