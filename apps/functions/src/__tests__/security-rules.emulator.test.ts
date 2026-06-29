/**
 * Security rules emulator tests.
 *
 * Requires the Firebase Emulator Suite to be running:
 *   pnpm emulators:start   (from apps/functions)
 *   — or —
 *   firebase emulators:start --config ../../firebase/firebase.json
 *
 * Run with:
 *   pnpm test:emulator
 *   — or via the emulators:test script which starts emulators automatically.
 */

import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, setDoc, updateDoc } from 'firebase/firestore';
import { get as dbGet, ref as dbRef, set as dbSet } from 'firebase/database';
import { getBytes, ref as storageRef, uploadBytes } from 'firebase/storage';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, it } from 'vitest';

const FIREBASE_DIR = resolve(__dirname, '../../../../firebase');

let testEnv: RulesTestEnvironment;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'demo-test',
    firestore: {
      rules: readFileSync(resolve(FIREBASE_DIR, 'firestore.rules'), 'utf8'),
      host: 'localhost',
      port: 8080,
    },
    database: {
      rules: readFileSync(resolve(FIREBASE_DIR, 'database.rules.json'), 'utf8'),
      host: 'localhost',
      port: 9000,
    },
    storage: {
      rules: readFileSync(resolve(FIREBASE_DIR, 'storage.rules'), 'utf8'),
      host: 'localhost',
      port: 9199,
    },
  });
});

afterAll(async () => {
  await testEnv?.cleanup();
});

// ---------------------------------------------------------------------------
// Firestore: unauthenticated access
// ---------------------------------------------------------------------------

describe('Firestore security rules – unauthenticated access', () => {
  it('denies read on /users/{id}', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getDoc(doc(ctx.firestore(), 'users', 'some-user')));
  });

  it('denies write on /users/{id}', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(setDoc(doc(ctx.firestore(), 'users', 'some-user'), { name: 'test' }));
  });

  it('denies read on an arbitrary top-level collection', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getDoc(doc(ctx.firestore(), 'events', 'some-event')));
  });
});

// ---------------------------------------------------------------------------
// Firestore: user profile
// ---------------------------------------------------------------------------

describe('Firestore – user profile', () => {
  const OWNER = 'profile-owner';
  const OTHER = 'profile-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users', OWNER), {
        displayName: 'Owner User',
        role: 'user',
        activeMember: false,
        suspended: false,
      });
    });
  });

  it('owner can read their own public profile', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'users', OWNER)));
  });

  it('another authenticated user can read a public profile', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'users', OWNER)));
  });

  it('owner can update their own display name', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(updateDoc(doc(ctx.firestore(), 'users', OWNER), { displayName: 'Updated Name' }));
  });

  it('owner cannot change their own role', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { role: 'admin' }));
  });

  it('owner cannot grant themselves activeMember entitlement', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { activeMember: true }));
  });

  it('owner cannot set the admin claim flag on their profile', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { admin: true }));
  });

  it('owner cannot create a profile with a protected field pre-set', async () => {
    const ctx = testEnv.authenticatedContext('new-user-with-role');
    await assertFails(
      setDoc(doc(ctx.firestore(), 'users', 'new-user-with-role'), {
        displayName: 'Hacker',
        role: 'admin',
      }),
    );
  });
});

// ---------------------------------------------------------------------------
// Firestore: private user data
// ---------------------------------------------------------------------------

describe('Firestore – userPrivate', () => {
  const OWNER = 'private-owner';
  const OTHER = 'private-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'userPrivate', OWNER), {
        email: 'owner@example.com',
        phone: '+46700000000',
      });
    });
  });

  it('owner can read their own private data', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'userPrivate', OWNER)));
  });

  it('another user cannot read private data', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(getDoc(doc(ctx.firestore(), 'userPrivate', OWNER)));
  });

  it('another user cannot write private data', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(
      setDoc(doc(ctx.firestore(), 'userPrivate', OWNER), { email: 'evil@example.com' }),
    );
  });
});

// ---------------------------------------------------------------------------
// Firestore: vehicle ownership
// ---------------------------------------------------------------------------

describe('Firestore – vehicle ownership', () => {
  const OWNER = 'vehicle-owner';
  const OTHER = 'vehicle-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'vehicles', 'v-read-test'), {
        userId: OWNER,
        make: 'Volvo',
        model: 'V70',
      });
      await setDoc(doc(ctx.firestore(), 'vehicles', 'v-delete-test'), {
        userId: OWNER,
        make: 'Saab',
        model: '9-3',
      });
      await setDoc(doc(ctx.firestore(), 'vehicles', 'v-no-delete'), {
        userId: OWNER,
        make: 'BMW',
        model: '3 Series',
      });
    });
  });

  it('any authenticated user can read a vehicle', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'vehicles', 'v-read-test')));
  });

  it('owner can delete their own vehicle', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(deleteDoc(doc(ctx.firestore(), 'vehicles', 'v-delete-test')));
  });

  it("another user cannot delete someone else's vehicle", async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(deleteDoc(doc(ctx.firestore(), 'vehicles', 'v-no-delete')));
  });

  it('owner can create a vehicle with their own userId', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      setDoc(doc(ctx.firestore(), 'vehicles', 'v-new'), {
        userId: OWNER,
        make: 'Ford',
        model: 'Mustang',
      }),
    );
  });

  it('user cannot create a vehicle for another user', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(
      setDoc(doc(ctx.firestore(), 'vehicles', 'v-spoof'), {
        userId: OWNER,
        make: 'Fake',
        model: 'Spoof',
      }),
    );
  });
});

// ---------------------------------------------------------------------------
// Firestore: ride ownership
// ---------------------------------------------------------------------------

describe('Firestore – ride ownership', () => {
  const OWNER = 'ride-owner';
  const OTHER = 'ride-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'rides', 'r-private'), {
        userId: OWNER,
        title: 'Sunday Drive',
        routePath: 'rideRoutes/ride-owner/r-private/route.bin',
      });
    });
  });

  it('owner can read their own ride', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'rides', 'r-private')));
  });

  it('another user cannot read a ride they do not own', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(getDoc(doc(ctx.firestore(), 'rides', 'r-private')));
  });

  it('another user cannot delete a ride they do not own', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(deleteDoc(doc(ctx.firestore(), 'rides', 'r-private')));
  });

  it('user cannot create a ride for another user', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(
      setDoc(doc(ctx.firestore(), 'rides', 'r-spoof'), {
        userId: OWNER,
        title: 'Spoofed',
        routePath: 'rideRoutes/ride-owner/r-spoof/route.bin',
      }),
    );
  });
});

// ---------------------------------------------------------------------------
// Firestore: subscriptions (backend-only writes)
// ---------------------------------------------------------------------------

describe('Firestore – subscriptions', () => {
  const OWNER = 'sub-owner';
  const OTHER = 'sub-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'subscriptions', OWNER), {
        entitlement: 'member_monthly',
        status: 'active',
      });
    });
  });

  it('owner can read their own subscription', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'subscriptions', OWNER)));
  });

  it("another user cannot read someone else's subscription", async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(getDoc(doc(ctx.firestore(), 'subscriptions', OWNER)));
  });

  it('client cannot write their own subscription', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      setDoc(doc(ctx.firestore(), 'subscriptions', OWNER), {
        entitlement: 'member_monthly',
        status: 'active',
      }),
    );
  });
});

// ---------------------------------------------------------------------------
// Firestore: admin-only collections
// ---------------------------------------------------------------------------

describe('Firestore – admin audit events', () => {
  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'adminAuditEvents', 'audit-1'), {
        action: 'user.suspend',
        adminId: 'admin-uid',
        targetUserId: 'some-user',
      });
    });
  });

  it('admin can read audit events', async () => {
    const ctx = testEnv.authenticatedContext('admin-uid', { admin: true });
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'adminAuditEvents', 'audit-1')));
  });

  it('regular user cannot read audit events', async () => {
    const ctx = testEnv.authenticatedContext('regular-user');
    await assertFails(getDoc(doc(ctx.firestore(), 'adminAuditEvents', 'audit-1')));
  });

  it('regular user cannot write audit events', async () => {
    const ctx = testEnv.authenticatedContext('regular-user');
    await assertFails(
      setDoc(doc(ctx.firestore(), 'adminAuditEvents', 'fake-event'), {
        action: 'fake.action',
      }),
    );
  });
});

// ---------------------------------------------------------------------------
// Realtime Database security rules – unauthenticated access
// ---------------------------------------------------------------------------

describe('Realtime Database security rules – unauthenticated access', () => {
  it('denies read at root path', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(dbGet(dbRef(ctx.database(), '/')));
  });

  it('denies write at /users/{id}', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(dbSet(dbRef(ctx.database(), 'users/some-user'), { name: 'test' }));
  });
});

// ---------------------------------------------------------------------------
// Realtime Database: liveLocations
// ---------------------------------------------------------------------------

describe('Realtime Database – liveLocations', () => {
  const SHARER = 'loc-sharer';
  const MEMBER = 'loc-member';
  const NON_MEMBER = 'loc-non-member';

  it('owner can write their own live location', async () => {
    const ctx = testEnv.authenticatedContext(SHARER);
    await assertSucceeds(
      dbSet(dbRef(ctx.database(), `liveLocations/${SHARER}`), {
        lat: 57.5,
        lng: 12.0,
        timestamp: Date.now(),
      }),
    );
  });

  it("owner cannot write to another user's live location", async () => {
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    await assertFails(
      dbSet(dbRef(ctx.database(), `liveLocations/${SHARER}`), {
        lat: 57.5,
        lng: 12.0,
        timestamp: Date.now(),
      }),
    );
  });

  it('active member can read live locations', async () => {
    const ctx = testEnv.authenticatedContext(MEMBER, { activeMember: true });
    await assertSucceeds(dbGet(dbRef(ctx.database(), `liveLocations/${SHARER}`)));
  });

  it('non-member cannot read live locations', async () => {
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    await assertFails(dbGet(dbRef(ctx.database(), `liveLocations/${SHARER}`)));
  });

  it('unauthenticated user cannot read live locations', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(dbGet(dbRef(ctx.database(), `liveLocations/${SHARER}`)));
  });
});

// ---------------------------------------------------------------------------
// Realtime Database: presence
// ---------------------------------------------------------------------------

describe('Realtime Database – presence', () => {
  const USER_A = 'presence-a';
  const USER_B = 'presence-b';

  it('user can write their own presence', async () => {
    const ctx = testEnv.authenticatedContext(USER_A);
    await assertSucceeds(
      dbSet(dbRef(ctx.database(), `presence/${USER_A}`), {
        online: true,
        lastSeen: Date.now(),
      }),
    );
  });

  it("user cannot write to another user's presence", async () => {
    const ctx = testEnv.authenticatedContext(USER_A);
    await assertFails(
      dbSet(dbRef(ctx.database(), `presence/${USER_B}`), {
        online: true,
        lastSeen: Date.now(),
      }),
    );
  });

  it('authenticated user can read any presence record', async () => {
    const ctx = testEnv.authenticatedContext(USER_B);
    await assertSucceeds(dbGet(dbRef(ctx.database(), `presence/${USER_A}`)));
  });

  it('unauthenticated user cannot read presence', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(dbGet(dbRef(ctx.database(), `presence/${USER_A}`)));
  });
});

// ---------------------------------------------------------------------------
// Cloud Storage security rules – unauthenticated access
// ---------------------------------------------------------------------------

describe('Cloud Storage security rules – unauthenticated access', () => {
  it('denies unauthenticated upload', async () => {
    const ctx = testEnv.unauthenticatedContext();
    const data = new Uint8Array([1, 2, 3]);
    await assertFails(uploadBytes(storageRef(ctx.storage(), 'uploads/test.bin'), data));
  });

  it('denies unauthenticated download', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getBytes(storageRef(ctx.storage(), 'uploads/test.bin')));
  });
});

// ---------------------------------------------------------------------------
// Cloud Storage: ownership validation
// ---------------------------------------------------------------------------

describe('Cloud Storage – ownership validation', () => {
  const OWNER = 'storage-owner';
  const OTHER = 'storage-other';

  it('owner can upload a profile image to their own path', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `profileImages/${OWNER}/avatar.jpg`);
    await assertSucceeds(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it("user cannot upload to another user's profile image path", async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `profileImages/${OWNER}/avatar.jpg`);
    await assertFails(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it('unauthenticated user cannot read a profile image', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getBytes(storageRef(ctx.storage(), `profileImages/${OWNER}/avatar.jpg`)));
  });

  it('owner can upload a ride route to their own path', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    const data = new Uint8Array([0x1f, 0x8b, 0x08]); // gzip magic bytes
    const ref = storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`);
    await assertSucceeds(uploadBytes(ref, data, { contentType: 'application/octet-stream' }));
  });

  it('owner can read their own ride route', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(getBytes(storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`)));
  });

  it("another user cannot read someone else's ride route", async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(getBytes(storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`)));
  });

  it("another user cannot upload to someone else's ride route path", async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    const data = new Uint8Array([0x00, 0x01, 0x02]);
    const ref = storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-spoof/route.bin`);
    await assertFails(uploadBytes(ref, data, { contentType: 'application/octet-stream' }));
  });
});

// ---------------------------------------------------------------------------
// Cloud Storage: vehicle images
// ---------------------------------------------------------------------------

describe('Cloud Storage – vehicle images', () => {
  const OWNER = 'vehicle-img-owner';
  const OTHER = 'vehicle-img-other';

  it('owner can upload a vehicle image to their own path', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `vehicleImages/${OWNER}/car.jpg`);
    await assertSucceeds(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it("another user cannot upload to someone else's vehicle image path", async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `vehicleImages/${OWNER}/car.jpg`);
    await assertFails(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it('authenticated user can read a vehicle image', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertSucceeds(getBytes(storageRef(ctx.storage(), `vehicleImages/${OWNER}/car.jpg`)));
  });

  it('unauthenticated user cannot read a vehicle image', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getBytes(storageRef(ctx.storage(), `vehicleImages/${OWNER}/car.jpg`)));
  });
});

// ---------------------------------------------------------------------------
// Cloud Storage: ride preview images
// ---------------------------------------------------------------------------

describe('Cloud Storage – ride preview images', () => {
  const OWNER = 'ride-preview-owner';
  const OTHER = 'ride-preview-other';

  it('owner can upload a ride preview image to their own path', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `ridePreviewImages/${OWNER}/ride-abc/preview.jpg`);
    await assertSucceeds(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it("another user cannot upload to someone else's ride preview path", async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `ridePreviewImages/${OWNER}/ride-abc/preview.jpg`);
    await assertFails(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it('authenticated user can read a ride preview image', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertSucceeds(
      getBytes(storageRef(ctx.storage(), `ridePreviewImages/${OWNER}/ride-abc/preview.jpg`)),
    );
  });

  it('unauthenticated user cannot read a ride preview image', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(
      getBytes(storageRef(ctx.storage(), `ridePreviewImages/${OWNER}/ride-abc/preview.jpg`)),
    );
  });
});

// ---------------------------------------------------------------------------
// Cloud Storage: company images (admin-managed)
// ---------------------------------------------------------------------------

describe('Cloud Storage – company images', () => {
  const ADMIN_UID = 'storage-admin';
  const USER_UID = 'storage-regular';

  it('admin can upload a company image', async () => {
    const ctx = testEnv.authenticatedContext(ADMIN_UID, { admin: true });
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `companyImages/company-1/logo.jpg`);
    await assertSucceeds(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it('non-admin cannot upload a company image', async () => {
    const ctx = testEnv.authenticatedContext(USER_UID);
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `companyImages/company-1/logo.jpg`);
    await assertFails(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it('authenticated user can read a company image', async () => {
    const ctx = testEnv.authenticatedContext(USER_UID);
    await assertSucceeds(getBytes(storageRef(ctx.storage(), `companyImages/company-1/logo.jpg`)));
  });

  it('unauthenticated user cannot read a company image', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getBytes(storageRef(ctx.storage(), `companyImages/company-1/logo.jpg`)));
  });
});

// ---------------------------------------------------------------------------
// Cloud Storage: offer images (admin-managed)
// ---------------------------------------------------------------------------

describe('Cloud Storage – offer images', () => {
  const ADMIN_UID = 'offer-img-admin';
  const USER_UID = 'offer-img-user';

  it('admin can upload an offer image', async () => {
    const ctx = testEnv.authenticatedContext(ADMIN_UID, { admin: true });
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `offerImages/company-2/offer.jpg`);
    await assertSucceeds(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it('non-admin cannot upload an offer image', async () => {
    const ctx = testEnv.authenticatedContext(USER_UID);
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `offerImages/company-2/offer.jpg`);
    await assertFails(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it('authenticated user can read an offer image', async () => {
    const ctx = testEnv.authenticatedContext(USER_UID);
    await assertSucceeds(getBytes(storageRef(ctx.storage(), `offerImages/company-2/offer.jpg`)));
  });

  it('unauthenticated user cannot read an offer image', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getBytes(storageRef(ctx.storage(), `offerImages/company-2/offer.jpg`)));
  });
});
