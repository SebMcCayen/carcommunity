/** Optional regression against the unchanged Driver backend. No emulator is
 * started here; requires an explicitly granted disposable local slot. */
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { describe, expect, test } from 'vitest';
const loopback = (value: string | undefined) => /^(127\.0\.0\.1|localhost):\d+$/.test(value ?? '');
const enabled =
  process.env.KCC_TEST_NO_CLOUD === '1' &&
  process.env.GCLOUD_PROJECT === 'demo-chat-flood' &&
  !!process.env.CHAT_FLOOD_DRIVER_ROOT &&
  loopback(process.env.FIRESTORE_EMULATOR_HOST) &&
  loopback(process.env.FIREBASE_AUTH_EMULATOR_HOST) &&
  loopback(process.env.FIREBASE_STORAGE_EMULATOR_HOST);
(enabled ? describe : describe.skip)('unchanged Driver-only deletion', () => {
  test('retains the shared guard and canonical identity after its queued data purge', async () => {
    if (!enabled) throw Error('Explicit loopback demo Firestore/Auth/Storage slot required');
    const root = process.env.CHAT_FLOOD_DRIVER_ROOT!;
    const require = createRequire(resolve(root, 'functions/package.json'));
    const { getApps, initializeApp } =
      require('firebase-admin/app') as typeof import('firebase-admin/app');
    const { getFirestore, Timestamp } =
      require('firebase-admin/firestore') as typeof import('firebase-admin/firestore');
    const { getAuth } = require('firebase-admin/auth') as typeof import('firebase-admin/auth');
    const app =
      getApps().find((item) => item.name === '[DEFAULT]') ??
      initializeApp({
        projectId: 'demo-chat-flood',
        storageBucket: 'demo-chat-flood.appspot.com',
      });
    const db = getFirestore(app),
      auth = getAuth(app),
      uid = `guard_${crypto.randomUUID()}`;
    await auth.createUser({ uid });
    await db
      .doc(`users/${uid}`)
      .set({ displayName: 'Local fixture', onboardingCompletedAt: Timestamp.now() });
    await db.doc(`driverUsers/${uid}`).set({ status: 'active', onboardingComplete: true });
    const guardRef = db.doc(`chatSendGuards/${uid}`);
    const now = Date.now();
    await guardRef.set({
      policyVersion: 1,
      creditMs: 0,
      lastRefillMillis: now,
      violationLevel: 1,
      lastViolationMillis: now,
      blockedUntilMillis: now + 5000,
    });
    const before = await guardRef.get();
    const { driver } = await import(
      /* @vite-ignore */ pathToFileURL(resolve(root, 'functions/src/index.ts')).href
    );
    await driver.deleteDriverProfile.run({ auth: { uid, token: {} }, data: {} });
    await db
      .doc(`driverAccountDeletionRequests/${uid}`)
      .update({ executeAfter: Timestamp.fromMillis(0) });
    await driver.processAccountDeletions.run({});
    expect((await db.doc(`driverUsers/${uid}`).get()).get('status')).toBe('deleted');
    expect((await guardRef.get()).data()).toEqual(before.data());
    expect((await guardRef.get()).updateTime?.isEqual(before.updateTime!)).toBe(true);
    expect((await db.doc(`users/${uid}`).get()).exists).toBe(true);
    expect((await auth.getUser(uid)).disabled).toBe(false);
  }, 60000);
});
