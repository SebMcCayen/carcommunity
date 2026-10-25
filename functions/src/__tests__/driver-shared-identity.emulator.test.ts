/**
 * Shared-project contract for the Driver app.
 *
 * Driver reuses CarCommunity Auth/users/vehicles while every Driver-owned write
 * remains callable-only and namespaced. These tests pin that boundary so a
 * future rule edit cannot accidentally grant Driver a parallel profile write
 * path or let Driver moderation affect the canonical account.
 */
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { doc, getDoc, serverTimestamp, setDoc } from 'firebase/firestore';
import { deleteObject, getBytes, ref, uploadBytes } from 'firebase/storage';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const FIREBASE_DIR = resolve(__dirname, '../../../firebase');
const ALICE = 'driver-rules-alice';
const BOB = 'driver-rules-bob';
const DRIVER_ADMIN = 'driver-rules-admin';
const KCC_ADMIN = 'driver-rules-kcc-admin';
const DRIVER_SUSPENDED = 'driver-rules-suspended';
const NO_DRIVER = 'driver-rules-no-profile';

let testEnv: RulesTestEnvironment;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'demo-test',
    firestore: {
      rules: readFileSync(resolve(FIREBASE_DIR, 'firestore.rules'), 'utf8'),
      host: 'localhost',
      port: 8080,
    },
    storage: {
      rules: readFileSync(resolve(FIREBASE_DIR, 'storage.rules'), 'utf8'),
      host: 'localhost',
      port: 9199,
    },
  });

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (const uid of [ALICE, BOB, DRIVER_ADMIN, KCC_ADMIN, DRIVER_SUSPENDED, NO_DRIVER]) {
      await setDoc(doc(db, 'users', uid), {
        displayName: uid,
        onboardingCompletedAt: serverTimestamp(),
        suspended: false,
        deleted: false,
      });
    }
    for (const uid of [ALICE, BOB, DRIVER_ADMIN, KCC_ADMIN, DRIVER_SUSPENDED]) {
      await setDoc(doc(db, 'driverUsers', uid), {
        displayName: uid,
        onboardingComplete: true,
        status: uid === DRIVER_SUSPENDED ? 'suspended' : 'active',
      });
    }
    await setDoc(doc(db, 'driverUsers', ALICE, 'bookmarks', 'thread-1'), {
      threadId: 'thread-1',
    });
    await setDoc(doc(db, 'driverNotifications', ALICE, 'items', 'notice-1'), {
      title: 'Hej',
    });
    await setDoc(doc(db, 'driverProfiles', ALICE), {
      displayName: 'Alice',
      status: 'active',
    });
    await setDoc(doc(db, 'driverProfiles', DRIVER_SUSPENDED), {
      displayName: 'Suspended',
      status: 'suspended',
    });
    await setDoc(doc(db, 'driverThreads', 'thread-1'), {
      authorUid: ALICE,
      status: 'active',
      text: 'Hej Driver',
    });
    await setDoc(doc(db, 'driverThreads', 'hidden-thread'), {
      authorUid: ALICE,
      status: 'hidden',
      text: 'Moderated',
    });
    await setDoc(doc(db, 'driverAuditLogs', 'audit-1'), { action: 'test' });
    await setDoc(doc(db, 'vehicles', 'driver-car-1'), {
      userId: ALICE,
      make: 'Volvo',
      model: 'EX30',
      modelYear: 2026,
    });
  });
});

afterAll(async () => {
  await testEnv?.cleanup();
});

describe('Driver shared Firebase identity rules', () => {
  it('keeps public Driver projections readable while hiding moderated content', async () => {
    const anonymous = testEnv.unauthenticatedContext().firestore();
    await assertSucceeds(getDoc(doc(anonymous, 'driverProfiles', ALICE)));
    await assertSucceeds(getDoc(doc(anonymous, 'driverThreads', 'thread-1')));
    await assertFails(getDoc(doc(anonymous, 'driverThreads', 'hidden-thread')));
    await assertFails(getDoc(doc(anonymous, 'driverProfiles', DRIVER_SUSPENDED)));
  });

  it('isolates Driver private state and denies every direct Driver write', async () => {
    const alice = testEnv.authenticatedContext(ALICE).firestore();
    const bob = testEnv.authenticatedContext(BOB).firestore();
    await assertSucceeds(getDoc(doc(alice, 'driverUsers', ALICE)));
    await assertSucceeds(getDoc(doc(alice, 'driverUsers', ALICE, 'bookmarks', 'thread-1')));
    await assertSucceeds(getDoc(doc(alice, 'driverNotifications', ALICE, 'items', 'notice-1')));
    await assertFails(getDoc(doc(bob, 'driverUsers', ALICE)));
    await assertFails(getDoc(doc(bob, 'driverNotifications', ALICE, 'items', 'notice-1')));
    await assertFails(
      setDoc(doc(alice, 'driverThreads', 'forged'), {
        authorUid: ALICE,
        status: 'active',
        text: 'forged',
      }),
    );
    await assertFails(
      setDoc(doc(alice, 'vehicles', 'driver-car-1'), { model: 'XC60' }, { merge: true }),
    );
  });

  it('allows the owner to bootstrap Driver state before a Driver profile exists', async () => {
    const noDriver = testEnv.authenticatedContext(NO_DRIVER).firestore();
    const missingProfile = await assertSucceeds(getDoc(doc(noDriver, 'driverUsers', NO_DRIVER)));
    expect(missingProfile.exists()).toBe(false);
    await assertFails(
      getDoc(doc(noDriver, 'driverUsers', NO_DRIVER, 'bookmarks', 'thread-1')),
    );
  });

  it('keeps CarCommunity admin and Driver admin authority separate', async () => {
    const driverAdmin = testEnv
      .authenticatedContext(DRIVER_ADMIN, {
        driverRole: 'admin',
      })
      .firestore();
    const kccAdmin = testEnv
      .authenticatedContext(KCC_ADMIN, {
        admin: true,
      })
      .firestore();
    await assertSucceeds(getDoc(doc(driverAdmin, 'driverAuditLogs', 'audit-1')));
    await assertFails(getDoc(doc(kccAdmin, 'driverAuditLogs', 'audit-1')));
    await assertFails(setDoc(doc(driverAdmin, 'driverAuditLogs', 'forged'), { action: 'forged' }));
  });

  it('exposes only the root status record when the Driver profile is suspended', async () => {
    const suspended = testEnv.authenticatedContext(DRIVER_SUSPENDED).firestore();
    await assertSucceeds(getDoc(doc(suspended, 'driverUsers', DRIVER_SUSPENDED)));
    await assertFails(
      getDoc(doc(suspended, 'driverUsers', DRIVER_SUSPENDED, 'bookmarks', 'thread-1')),
    );
  });

  it('continues to expose the canonical profile and garage only under their CarCommunity rules', async () => {
    const alice = testEnv.authenticatedContext(ALICE).firestore();
    const anonymous = testEnv.unauthenticatedContext().firestore();
    await assertSucceeds(getDoc(doc(alice, 'users', ALICE)));
    await assertSucceeds(getDoc(doc(alice, 'vehicles', 'driver-car-1')));
    await assertFails(getDoc(doc(anonymous, 'vehicles', 'driver-car-1')));
  });
});

describe('Driver media rules in the shared bucket', () => {
  it('requires an active Driver profile for bounded JPEG/WebP uploads', async () => {
    const alice = testEnv.authenticatedContext(ALICE).storage();
    const bob = testEnv.authenticatedContext(BOB).storage();
    const noDriver = testEnv.authenticatedContext(NO_DRIVER).storage();
    const anonymous = testEnv.unauthenticatedContext().storage();
    const image = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
    const path = `driverThreadMedia/${ALICE}/rules-photo.jpg`;

    await assertFails(uploadBytes(ref(bob, path), image, { contentType: 'image/jpeg' }));
    await assertFails(
      uploadBytes(ref(noDriver, `driverThreadMedia/${NO_DRIVER}/photo.jpg`), image, {
        contentType: 'image/jpeg',
      }),
    );
    await assertFails(
      uploadBytes(ref(alice, `driverThreadMedia/${ALICE}/photo.png`), image, {
        contentType: 'image/png',
      }),
    );
    await assertSucceeds(uploadBytes(ref(alice, path), image, { contentType: 'image/jpeg' }));
    await assertSucceeds(getBytes(ref(anonymous, path)));
    await assertSucceeds(deleteObject(ref(alice, path)));
  });
});
