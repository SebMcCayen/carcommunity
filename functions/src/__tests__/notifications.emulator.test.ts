/**
 * Notifications emulator integration tests (Phase 9l).
 *
 * Exercises the in-app inbox end-to-end: backend-only delivery with the
 * legacy eligibility invariants (deleted → nothing, suspended → essential
 * account notices only, per-category opt-outs that can never disable the
 * essential categories), idempotent producer IDs, the markRead /
 * markAllRead callables, hash-only push token registration, and the
 * scheduled retention sweep (unread 30 days, read 7 days).
 *
 * writeInAppNotification and runNotificationsCleanup are imported directly
 * (same pattern as the partner insights runners) — the Admin SDK connects
 * to the emulators through the env vars below.
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
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { writeInAppNotification } from '../notifications/deliver';
import { runNotificationsCleanup } from '../notifications/scheduled';
import {
  MAX_PUSH_TOKENS_PER_USER,
  hashPushToken,
} from '../notifications/notifications-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ??
  initializeAdminApp({ projectId: PROJECT_ID }, 'notifications-emulator-tests');
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

const itemsOf = (uid: string) =>
  adminDb.collection('notifications').doc(uid).collection('items');

const baseNotification = {
  category: 'system_notice' as const,
  title: 'Nyheter i appen',
  previewText: 'Vi har uppdaterat kartan.',
  body: 'Kartan har fått nya lager och snabbare laddning.',
};

let user: TestUser;
let reader: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'notifications-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  user = await createProvisionedUser('notif-user');
  reader = await createProvisionedUser('notif-reader');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('writeInAppNotification eligibility and shape', () => {
  it('writes unread documents and keeps producer IDs idempotent', async () => {
    const first = await writeInAppNotification(user.uid, baseNotification, 'producer-key-1');
    expect(first).toMatchObject({ delivered: true, notificationId: 'producer-key-1' });

    const stored = (await itemsOf(user.uid).doc('producer-key-1').get()).data()!;
    expect(stored).toMatchObject({
      category: 'system_notice',
      title: baseNotification.title,
      read: false,
      readAt: null,
      actionType: 'none',
      relatedEntityId: null,
    });
    expect(stored.createdAt).toBeInstanceOf(Timestamp);

    // Replayed producer: no duplicate, no overwrite.
    const replay = await writeInAppNotification(
      user.uid,
      { ...baseNotification, title: 'Ersatt titel' },
      'producer-key-1',
    );
    expect(replay.delivered).toBe(false);
    expect(replay.skippedReason).toBe('duplicate');
    expect((await itemsOf(user.uid).doc('producer-key-1').get()).data()!.title).toBe(
      baseNotification.title,
    );
  });

  it('suspended users receive only essential account notices', async () => {
    const suspended = await createProvisionedUser('notif-suspended');
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });

    const normal = await writeInAppNotification(suspended.uid, baseNotification);
    expect(normal).toMatchObject({ delivered: false, skippedReason: 'suspended' });

    const essential = await writeInAppNotification(suspended.uid, {
      category: 'account_suspension',
      title: 'Kontot är avstängt',
      previewText: 'Ditt konto har stängts av.',
    });
    expect(essential.delivered).toBe(true);
    const snap = await itemsOf(suspended.uid).get();
    expect(snap.size).toBe(1);
    expect(snap.docs[0].data().category).toBe('account_suspension');
  });

  it('honors opt-outs but never for essential categories; deleted users get nothing', async () => {
    const optedOut = await createProvisionedUser('notif-optout');
    await adminDb
      .collection('userPrivate')
      .doc(optedOut.uid)
      .set(
        { notificationPreferences: { system_notice: { inApp: false }, account_warning: { inApp: false } } },
        { merge: true },
      );

    expect(await writeInAppNotification(optedOut.uid, baseNotification)).toMatchObject({
      delivered: false,
      skippedReason: 'opted_out',
    });
    expect(
      (
        await writeInAppNotification(optedOut.uid, {
          category: 'account_warning',
          title: 'Varning',
          previewText: 'Ditt konto har fått en varning.',
        })
      ).delivered,
    ).toBe(true);

    const deleted = await createProvisionedUser('notif-deleted');
    await adminDb.collection('users').doc(deleted.uid).set({ deleted: true }, { merge: true });
    expect(await writeInAppNotification(deleted.uid, baseNotification)).toMatchObject({
      delivered: false,
      skippedReason: 'deleted',
    });
  });
});

describe('notifications-markRead / markAllRead', () => {
  it('marks own notifications read, idempotently, and never another inbox', async () => {
    const { notificationId } = await writeInAppNotification(reader.uid, baseNotification);

    await signInAs(reader);
    const first = (await call('notifications-markRead', { notificationId })).data as {
      marked: boolean;
    };
    expect(first.marked).toBe(true);
    const stored = (await itemsOf(reader.uid).doc(notificationId!).get()).data()!;
    expect(stored.read).toBe(true);
    expect(stored.readAt).toBeInstanceOf(Timestamp);

    const replay = (await call('notifications-markRead', { notificationId })).data as {
      marked: boolean;
    };
    expect(replay.marked).toBe(false);

    // Another user's notification ID is structurally not-found.
    await signInAs(user);
    expect(
      await callableErrorCode(call('notifications-markRead', { notificationId })),
    ).toBe('functions/not-found');
  });

  it('marks everything unread in one call and reports the count', async () => {
    const bulk = await createProvisionedUser('notif-bulk');
    for (let i = 0; i < 3; i += 1) {
      await writeInAppNotification(bulk.uid, baseNotification, `bulk-${i}`);
    }

    await signInAs(bulk);
    const first = (await call('notifications-markAllRead', {})).data as { markedCount: number };
    expect(first.markedCount).toBe(3);
    const unread = await itemsOf(bulk.uid).where('read', '==', false).get();
    expect(unread.size).toBe(0);

    const replay = (await call('notifications-markAllRead', {})).data as { markedCount: number };
    expect(replay.markedCount).toBe(0);
  });
});

describe('notifications push token registration', () => {
  const tokensOf = (uid: string) =>
    adminDb.collection('userPrivate').doc(uid).collection('pushTokens');

  it('keys the document by the token hash, stores the token, and registers idempotently', async () => {
    const rawToken = 'fcm-raw-token-abc-123';
    await signInAs(user);
    const result = (
      await call('notifications-registerPushToken', {
        token: rawToken,
        platform: 'android',
        appVersion: '1.0.0',
      })
    ).data as { tokenId: string; platform: string };

    expect(result.tokenId).toBe(hashPushToken(rawToken));
    // The RESPONSE still exposes only the hash — the raw token never round-trips
    // back to a client.
    expect(JSON.stringify(result)).not.toContain(rawToken);

    const stored = (await tokensOf(user.uid).doc(result.tokenId).get()).data()!;
    // The document DOES hold the raw token: FCM addresses a device by it, so a
    // hash-only row is unsendable. It is protected by rules (no client access
    // at all — see security-rules.emulator.test.ts) rather than by omission.
    expect(stored.token).toBe(rawToken);
    expect(stored.platform).toBe('android');
    expect(stored.createdAt).toBeInstanceOf(Timestamp);

    // Re-register: same document, lastSeenAt maintained, no duplicates.
    const again = (
      await call('notifications-registerPushToken', { token: rawToken, platform: 'android' })
    ).data as { tokenId: string };
    expect(again.tokenId).toBe(result.tokenId);
    expect((await tokensOf(user.uid).get()).size).toBe(1);

    // Unregister is idempotent.
    const removed = (
      await call('notifications-unregisterPushToken', { tokenId: result.tokenId })
    ).data as { removed: boolean };
    expect(removed.removed).toBe(true);
    const replay = (
      await call('notifications-unregisterPushToken', { tokenId: result.tokenId })
    ).data as { removed: boolean };
    expect(replay.removed).toBe(false);
  });

  it('caps the registry at MAX_PUSH_TOKENS_PER_USER, evicting least-recently-seen', async () => {
    // The abuse shape Copilot flagged: a client registering many distinct
    // tokens under its own uid. Each call is a real, legitimate-looking
    // registration — only the COUNT is the problem — so the cap has to hold at
    // the callable, which is what this exercises end to end.
    const spammer = await createProvisionedUser('notif-cap');
    await signInAs(spammer);

    const ids: string[] = [];
    for (let i = 0; i < MAX_PUSH_TOKENS_PER_USER + 5; i++) {
      const res = (
        await call('notifications-registerPushToken', {
          token: `fcm-cap-token-${i}`,
          platform: 'android',
        })
      ).data as { tokenId: string };
      ids.push(res.tokenId);
    }

    const remaining = await tokensOf(spammer.uid).get();
    expect(remaining.size).toBe(MAX_PUSH_TOKENS_PER_USER);

    // The survivors are the most recently registered, and the earliest
    // registrations were the ones evicted.
    const survivingIds = new Set(remaining.docs.map((d) => d.id));
    const expectedSurvivors = ids.slice(-MAX_PUSH_TOKENS_PER_USER);
    expect([...survivingIds].sort()).toEqual([...expectedSurvivors].sort());
    expect(survivingIds.has(ids[0])).toBe(false);
  });

  it('honors the pushNotifications flag for registration', async () => {
    await signInAs(user);
    await adminDb
      .collection('config')
      .doc('featureFlags')
      .set({ pushNotifications: false }, { merge: true });
    try {
      expect(
        await callableErrorCode(
          call('notifications-registerPushToken', { token: 'fcm-x', platform: 'android' }),
        ),
      ).toBe('functions/failed-precondition');
    } finally {
      await adminDb
        .collection('config')
        .doc('featureFlags')
        .set({ pushNotifications: true }, { merge: true });
    }
  });

  it('lets suspended users unregister but not register', async () => {
    const device = await createProvisionedUser('notif-device');
    await signInAs(device);
    const registered = (
      await call('notifications-registerPushToken', { token: 'fcm-device-1', platform: 'ios' })
    ).data as { tokenId: string };

    await adminDb.collection('users').doc(device.uid).set({ suspended: true }, { merge: true });

    expect(
      await callableErrorCode(
        call('notifications-registerPushToken', { token: 'fcm-device-2', platform: 'ios' }),
      ),
    ).toBe('functions/permission-denied');

    const removed = (
      await call('notifications-unregisterPushToken', { tokenId: registered.tokenId })
    ).data as { removed: boolean };
    expect(removed.removed).toBe(true);
  });
});

describe('notifications retention cleanup', () => {
  it('deletes read items past 7 days and unread items past 30 days', async () => {
    const hoarder = await createProvisionedUser('notif-hoarder');
    const dayMs = 24 * 60 * 60 * 1000;
    const now = new Date();
    const at = (daysAgo: number) => Timestamp.fromDate(new Date(now.getTime() - daysAgo * dayMs));

    const seed = (id: string, data: Record<string, unknown>) =>
      itemsOf(hoarder.uid)
        .doc(id)
        .set({
          ...baseNotification,
          body: null,
          actionType: 'none',
          relatedEntityId: null,
          batchId: null,
          ...data,
        });
    await seed('read-old', { read: true, readAt: at(8), createdAt: at(9) });
    await seed('read-recent', { read: true, readAt: at(2), createdAt: at(9) });
    await seed('unread-old', { read: false, readAt: null, createdAt: at(31) });
    await seed('unread-recent', { read: false, readAt: null, createdAt: at(29) });

    const result = await runNotificationsCleanup(now);
    expect(result.deletedReadCount).toBeGreaterThanOrEqual(1);
    expect(result.deletedUnreadCount).toBeGreaterThanOrEqual(1);

    const remaining = await itemsOf(hoarder.uid).get();
    const ids = remaining.docs.map((d) => d.id).sort();
    expect(ids).toEqual(['read-recent', 'unread-recent']);
  });
});
