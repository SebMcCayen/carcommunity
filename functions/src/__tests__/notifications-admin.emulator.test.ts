/**
 * Admin notification batch-send emulator integration tests.
 *
 * Exercises notifications-adminSend end-to-end: admin gating, audience
 * validation, confirmation guard for broad sends, per-recipient fan-out
 * (respecting opt-outs), idempotency, and the batch/audit records.
 *
 * Requires the Auth + Functions + Firestore emulators — run via:
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
import { MAX_SYNC_AUDIENCE_SIZE } from '../notifications/adminSend-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'notif-admin-emulator-tests');
const adminDb = getAdminFirestore(adminApp);
const adminAuth = getAdminAuth(adminApp);

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

/**
 * Seeds `count` docs matching an audience query filter (e.g. role: 'admin'
 * or activeMember: true), with only the first `eligibleCount` of them
 * eligible (the rest are `suspended: true`). Used to prove the audience-cap
 * check runs against the RAW matched count, not the post-eligibility-filter
 * count: an over-cap RAW match must be rejected even when very few of those
 * users are actually eligible to receive anything.
 */
async function seedAudienceUsers(
  prefix: string,
  count: number,
  matchFields: Record<string, unknown>,
  eligibleCount: number,
): Promise<string[]> {
  const uids: string[] = [];
  let batch = adminDb.batch();
  let opsInBatch = 0;
  for (let i = 0; i < count; i++) {
    const uid = `${prefix}-${i}`;
    uids.push(uid);
    batch.set(adminDb.collection('users').doc(uid), {
      ...matchFields,
      suspended: i >= eligibleCount,
    });
    opsInBatch += 1;
    if (opsInBatch === 500) {
      await batch.commit();
      batch = adminDb.batch();
      opsInBatch = 0;
    }
  }
  if (opsInBatch > 0) await batch.commit();
  return uids;
}

async function deleteUsers(uids: string[]): Promise<void> {
  let batch = adminDb.batch();
  let opsInBatch = 0;
  for (const uid of uids) {
    batch.delete(adminDb.collection('users').doc(uid));
    opsInBatch += 1;
    if (opsInBatch === 500) {
      await batch.commit();
      batch = adminDb.batch();
      opsInBatch = 0;
    }
  }
  if (opsInBatch > 0) await batch.commit();
}

let adminUser: TestUser;
let target: TestUser;
let optedOut: TestUser;

const baseSend = {
  category: 'admin_message',
  title: 'Servicefönster',
  previewText: 'Planerat underhåll ikväll.',
  body: 'Appen kan vara långsam mellan 22 och 23.',
  reason: 'Planerat underhåll',
};

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'notif-admin-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('notifadmin-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  target = await createProvisionedUser('notifadmin-target');
  optedOut = await createProvisionedUser('notifadmin-optout');
  // Opt out of the non-essential admin_message category (in-app).
  await adminDb
    .collection('userPrivate')
    .doc(optedOut.uid)
    .set({ notificationPreferences: { admin_message: { inApp: false } } }, { merge: true });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('notifications-adminSend gating + validation', () => {
  it('rejects non-admin callers', async () => {
    await signInAs(target);
    expect(
      await callableErrorCode(
        call('notifications-adminSend', {
          ...baseSend,
          audience: 'specific_user',
          targetUserId: target.uid,
          idempotencyKey: `nonadmin-${Date.now()}`,
        }),
      ),
    ).toBe('functions/permission-denied');
  });

  it('requires targetUserId / confirmation as appropriate', async () => {
    await signInAs(adminUser);
    expect(
      await callableErrorCode(
        call('notifications-adminSend', { ...baseSend, audience: 'specific_user', idempotencyKey: `k1-${Date.now()}` }),
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(
        call('notifications-adminSend', { ...baseSend, audience: 'all_users', idempotencyKey: `k2-${Date.now()}` }),
      ),
    ).toBe('functions/failed-precondition');
  });
});

describe('notifications-adminSend delivery', () => {
  it('delivers to a specific user and records the batch, idempotently', async () => {
    await signInAs(adminUser);
    const idempotencyKey = `specific-${Date.now()}`;
    const result = await call('notifications-adminSend', {
      ...baseSend,
      audience: 'specific_user',
      targetUserId: target.uid,
      idempotencyKey,
    });
    const data = result.data as { batchId: string; recipientCount: number; audience: string };
    expect(data.audience).toBe('specific_user');
    expect(data.recipientCount).toBe(1);

    const item = await pollUntil(async () => {
      const snap = await adminDb
        .collection('notifications')
        .doc(target.uid)
        .collection('items')
        .doc(data.batchId)
        .get();
      return snap.exists ? snap.data() : undefined;
    });
    expect(item.category).toBe('admin_message');
    expect(item.title).toBe('Servicefönster');
    expect(item.batchId).toBe(data.batchId);

    const batch = (await adminDb.collection('adminNotificationBatches').doc(data.batchId).get()).data()!;
    expect(batch.recipientCount).toBe(1);
    expect(batch.createdByUserId).toBe(adminUser.uid);

    // Replaying the same idempotency key is rejected.
    expect(
      await callableErrorCode(
        call('notifications-adminSend', {
          ...baseSend,
          audience: 'specific_user',
          targetUserId: target.uid,
          idempotencyKey,
        }),
      ),
    ).toBe('functions/already-exists');
  });

  it('skips a recipient who opted out of the (non-essential) category', async () => {
    await signInAs(adminUser);
    const result = await call('notifications-adminSend', {
      ...baseSend,
      audience: 'specific_user',
      targetUserId: optedOut.uid,
      idempotencyKey: `optout-${Date.now()}`,
    });
    const data = result.data as { batchId: string };

    // Targeted, but no item is written for the opted-out user.
    await new Promise((resolve) => setTimeout(resolve, 1000));
    const snap = await adminDb
      .collection('notifications')
      .doc(optedOut.uid)
      .collection('items')
      .doc(data.batchId)
      .get();
    expect(snap.exists).toBe(false);
  });
});

describe('notifications-adminSend audience size cap (bounded query, not post-filter)', () => {
  // Regression coverage for the admins / members / free_users branches of
  // resolveRecipients: the audience query itself is bounded with
  // .limit(MAX_SYNC_AUDIENCE_SIZE + 1) and rejected on the RAW matched size,
  // the same way the all_users branch always has been. Each seeded audience
  // here has far more matching users than the cap, but only a handful are
  // actually eligible (the rest are suspended) — proving the cap is enforced
  // against the raw query result, not the post-eligibility-filter count. A
  // regression back to "fetch everything, filter, then check the eligible
  // count" would let these through (only ~10 eligible recipients), instead
  // of rejecting.
  const overCap = MAX_SYNC_AUDIENCE_SIZE + 1;
  const eligibleSlice = 10;

  it('rejects an over-cap admins audience on the raw matched size', async () => {
    await signInAs(adminUser);
    const uids = await seedAudienceUsers('cap-admins', overCap, { role: 'admin' }, eligibleSlice);
    try {
      const code = await callableErrorCode(
        call('notifications-adminSend', {
          ...baseSend,
          audience: 'admins',
          idempotencyKey: `cap-admins-${Date.now()}`,
        }),
      );
      expect(code).toBe('functions/invalid-argument');
    } finally {
      await deleteUsers(uids);
    }
  }, 60_000);

  it('rejects an over-cap members audience on the raw matched size', async () => {
    await signInAs(adminUser);
    const uids = await seedAudienceUsers('cap-members', overCap, { activeMember: true }, eligibleSlice);
    try {
      const code = await callableErrorCode(
        call('notifications-adminSend', {
          ...baseSend,
          audience: 'members',
          idempotencyKey: `cap-members-${Date.now()}`,
        }),
      );
      expect(code).toBe('functions/invalid-argument');
    } finally {
      await deleteUsers(uids);
    }
  }, 60_000);

  it('rejects an over-cap free_users audience on the raw matched size', async () => {
    await signInAs(adminUser);
    const uids = await seedAudienceUsers('cap-free', overCap, { activeMember: false }, eligibleSlice);
    try {
      const code = await callableErrorCode(
        call('notifications-adminSend', {
          ...baseSend,
          audience: 'free_users',
          confirmed: true,
          idempotencyKey: `cap-free-${Date.now()}`,
        }),
      );
      expect(code).toBe('functions/invalid-argument');
    } finally {
      await deleteUsers(uids);
    }
  }, 60_000);
});
