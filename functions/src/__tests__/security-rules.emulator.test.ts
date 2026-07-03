/**
 * Security rules emulator tests.
 *
 * Requires the Firebase Emulator Suite to be running:
 *   pnpm emulators:start   (from functions/)
 *   — or —
 *   firebase emulators:start --project demo-test --config ../firebase/firebase.json
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
import { deleteDoc, doc, getDoc, serverTimestamp, setDoc, updateDoc } from 'firebase/firestore';
import { get as dbGet, ref as dbRef, set as dbSet } from 'firebase/database';
import { getBytes, ref as storageRef, uploadBytes } from 'firebase/storage';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, it } from 'vitest';

const FIREBASE_DIR = resolve(__dirname, '../../../firebase');

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
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { displayName: 'Updated Name' }),
    );
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

  it('owner cannot self-complete onboarding on their profile', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { onboardingCompletedAt: new Date() }),
    );
  });

  it('owner cannot create a profile with onboardingCompletedAt pre-set', async () => {
    const ctx = testEnv.authenticatedContext('new-user-onboarded');
    await assertFails(
      setDoc(doc(ctx.firestore(), 'users', 'new-user-onboarded'), {
        displayName: 'Speedrunner',
        onboardingCompletedAt: new Date(),
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

  it('owner can update their own contact details', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), { phone: '+46700000001' }),
    );
  });

  it('owner can update their own privacy settings', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), {
        anonymousPartnerStatsOptIn: true,
      }),
    );
  });

  it('owner cannot write consent timestamps (backend-managed audit records)', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), { ageConfirmedAt: new Date() }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), { termsAcceptedAt: new Date() }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), {
        privacyPolicyAcceptedAt: new Date(),
      }),
    );
  });

  it('owner cannot create their private doc directly (backend provisioning only)', async () => {
    // Provisioned by the backend alongside users/{uid}; a client-created doc
    // could lack createdAt/updatedAt forever (onUserCreate never clobbers an
    // existing userPrivate doc).
    const ctx = testEnv.authenticatedContext('private-clean-user');
    await assertFails(
      setDoc(doc(ctx.firestore(), 'userPrivate', 'private-clean-user'), {
        email: 'clean@example.com',
      }),
    );
  });

  it('owner cannot delete their private doc (backend deletion workflow only)', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(deleteDoc(doc(ctx.firestore(), 'userPrivate', OWNER)));
  });
});

// ---------------------------------------------------------------------------
// Firestore: user profile field validation (Phase 9a)
// Owner writes are whitelist-based with per-field validation; shapes follow
// contracts/schemas/user-profile.schema.json.
// ---------------------------------------------------------------------------

describe('Firestore – user profile field validation (Phase 9a)', () => {
  const OWNER = 'validation-owner';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users', OWNER), {
        displayName: 'Validation Owner',
        role: 'user',
        activeMember: false,
        suspended: false,
      });
    });
  });

  it('owner cannot set a display name longer than 120 characters', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { displayName: 'x'.repeat(121) }),
    );
  });

  it('owner cannot set an empty display name', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { displayName: '' }));
  });

  it('owner cannot set a non-string display name', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { displayName: 42 }));
  });

  it('owner can set a display name at the 120-character limit', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { displayName: 'x'.repeat(120) }),
    );
  });

  it('owner cannot write a field outside the whitelist', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { favoriteCar: 'Koenigsegg' }),
    );
  });

  it('owner can set an avatar path under their own storage prefix', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), {
        avatarPath: `profileImages/${OWNER}/avatar-1.jpg`,
      }),
    );
  });

  it("owner cannot point their avatar at another user's storage prefix", async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), {
        avatarPath: 'profileImages/someone-else/avatar-1.jpg',
      }),
    );
  });

  it('owner cannot set an avatar path outside profileImages', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), {
        avatarPath: 'https://evil.example.com/avatar.jpg',
      }),
    );
  });

  it('owner can set a bio up to 500 characters but not beyond', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { bio: 'b'.repeat(500) }),
    );
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { bio: 'b'.repeat(501) }));
  });

  it('owner can write updatedAt only as a server timestamp', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), {
        displayName: 'Timestamped',
        updatedAt: serverTimestamp(),
      }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), {
        displayName: 'Backdated',
        updatedAt: new Date('2020-01-01T00:00:00Z'),
      }),
    );
  });

  it('owner cannot create their profile directly (backend provisioning only)', async () => {
    // A client-created partial users/{uid} doc would make the idempotent
    // onUserCreate trigger a permanent no-op and skip backend-managed
    // defaults — creates are backend-only (trigger + completeOnboarding).
    const uid = 'validation-create-user';
    const ctx = testEnv.authenticatedContext(uid);
    await assertFails(
      setDoc(doc(ctx.firestore(), 'users', uid), {
        displayName: 'Fresh User',
        bio: 'Hello',
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      }),
    );
  });
});

// ---------------------------------------------------------------------------
// Firestore: userPrivate field validation (Phase 9a)
// ---------------------------------------------------------------------------

describe('Firestore – userPrivate field validation (Phase 9a)', () => {
  const OWNER = 'private-validation-owner';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'userPrivate', OWNER), {
        email: 'validation@example.com',
        anonymousPartnerStatsOptIn: false,
      });
    });
  });

  it('owner cannot set a non-boolean partner-stats opt-in', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), {
        anonymousPartnerStatsOptIn: 'yes',
      }),
    );
  });

  it('owner can update notification preferences as a map', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), {
        notificationPreferences: { push: true, email: false },
      }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), {
        notificationPreferences: 'all',
      }),
    );
  });

  it('owner cannot write a field outside the userPrivate whitelist', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), { secretBackendField: 1 }),
    );
  });

  it('owner cannot set an overlong email or phone', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), {
        email: `${'a'.repeat(320)}@example.com`,
      }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), { phone: '0'.repeat(33) }),
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

  it('audit events are immutable even for admins (no client update/delete)', async () => {
    const ctx = testEnv.authenticatedContext('admin-uid', { admin: true });
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'adminAuditEvents', 'audit-1'), { action: 'tampered' }),
    );
    await assertFails(deleteDoc(doc(ctx.firestore(), 'adminAuditEvents', 'audit-1')));
  });

  it('admin cannot forge new audit events from a client', async () => {
    const ctx = testEnv.authenticatedContext('admin-uid', { admin: true });
    await assertFails(
      setDoc(doc(ctx.firestore(), 'adminAuditEvents', 'forged-event'), {
        action: 'user.suspend',
        adminId: 'someone-else',
      }),
    );
  });

  it('suspended admin cannot read audit events (suspension overrides admin)', async () => {
    const ctx = testEnv.authenticatedContext('suspended-admin', { admin: true, suspended: true });
    await assertFails(getDoc(doc(ctx.firestore(), 'adminAuditEvents', 'audit-1')));
  });
});

// ---------------------------------------------------------------------------
// Firestore: moderationActions (backend-only writes, admin-only reads)
// ---------------------------------------------------------------------------

describe('Firestore – moderation actions', () => {
  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'moderationActions', 'action-1'), {
        targetUserId: 'some-user',
        actorUserId: 'admin-uid',
        actionType: 'permanent_suspension',
        reason: 'ToS violation',
      });
    });
  });

  it('admin can read moderation actions', async () => {
    const ctx = testEnv.authenticatedContext('admin-uid', { admin: true });
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'moderationActions', 'action-1')));
  });

  it('regular user cannot read moderation actions (not even about themselves)', async () => {
    const ctx = testEnv.authenticatedContext('some-user');
    await assertFails(getDoc(doc(ctx.firestore(), 'moderationActions', 'action-1')));
  });

  it('no client can write moderation actions — not even admins', async () => {
    const userCtx = testEnv.authenticatedContext('some-user');
    await assertFails(
      setDoc(doc(userCtx.firestore(), 'moderationActions', 'fake-action'), {
        targetUserId: 'enemy',
        actionType: 'permanent_suspension',
      }),
    );
    const adminCtx = testEnv.authenticatedContext('admin-uid', { admin: true });
    await assertFails(
      updateDoc(doc(adminCtx.firestore(), 'moderationActions', 'action-1'), {
        reason: 'tampered',
      }),
    );
    await assertFails(deleteDoc(doc(adminCtx.firestore(), 'moderationActions', 'action-1')));
  });
});

// ---------------------------------------------------------------------------
// Firestore: suspension enforcement (suspended custom claim)
// ---------------------------------------------------------------------------

describe('Firestore – suspension enforcement', () => {
  const SUSPENDED = 'suspended-user';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users', SUSPENDED), {
        displayName: 'Suspended User',
        role: 'user',
        activeMember: true,
        suspended: true,
        deleted: false,
      });
      await setDoc(doc(ctx.firestore(), 'userPrivate', SUSPENDED), {
        email: 'suspended@example.com',
      });
      await setDoc(doc(ctx.firestore(), 'friends', 'suspended-friendship'), {
        userId: SUSPENDED,
        friendId: 'some-friend',
      });
      await setDoc(doc(ctx.firestore(), 'friendRequests', 'suspended-request'), {
        senderId: SUSPENDED,
        receiverId: 'some-friend',
      });
      await setDoc(doc(ctx.firestore(), 'communityMessages', 'suspended-own-msg'), {
        userId: SUSPENDED,
        text: 'posted before suspension',
      });
      await setDoc(doc(ctx.firestore(), 'vehicles', 'suspended-owned-vehicle'), {
        userId: SUSPENDED,
        make: 'Volvo',
        model: '240',
      });
    });
  });

  it('suspended user cannot update their own profile', async () => {
    const ctx = testEnv.authenticatedContext(SUSPENDED, { suspended: true });
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', SUSPENDED), { displayName: 'Rebrand' }),
    );
  });

  it('suspended user cannot create content (vehicles, hazards)', async () => {
    const ctx = testEnv.authenticatedContext(SUSPENDED, { suspended: true });
    await assertFails(
      setDoc(doc(ctx.firestore(), 'vehicles', 'suspended-vehicle'), {
        userId: SUSPENDED,
        make: 'Koenigsegg',
        model: 'Jesko',
      }),
    );
    await assertFails(
      setDoc(doc(ctx.firestore(), 'hazards', 'suspended-hazard'), {
        reportedBy: SUSPENDED,
        kind: 'pothole',
      }),
    );
  });

  it('suspension overrides the activeMember entitlement for chat posts', async () => {
    const ctx = testEnv.authenticatedContext(SUSPENDED, { activeMember: true, suspended: true });
    await assertFails(
      setDoc(doc(ctx.firestore(), 'communityMessages', 'suspended-msg'), {
        userId: SUSPENDED,
        text: 'hello',
      }),
    );
  });

  it('suspension blocks deletes too (friends, friend requests, own messages, vehicles)', async () => {
    const ctx = testEnv.authenticatedContext(SUSPENDED, { activeMember: true, suspended: true });
    await assertFails(deleteDoc(doc(ctx.firestore(), 'friends', 'suspended-friendship')));
    await assertFails(deleteDoc(doc(ctx.firestore(), 'friendRequests', 'suspended-request')));
    await assertFails(deleteDoc(doc(ctx.firestore(), 'communityMessages', 'suspended-own-msg')));
    await assertFails(deleteDoc(doc(ctx.firestore(), 'vehicles', 'suspended-owned-vehicle')));
  });

  it('suspended user retains read access to their own data', async () => {
    const ctx = testEnv.authenticatedContext(SUSPENDED, { suspended: true });
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'users', SUSPENDED)));
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'userPrivate', SUSPENDED)));
  });

  it('suspended user retains access to the account deletion path', async () => {
    const ctx = testEnv.authenticatedContext(SUSPENDED, { suspended: true });
    await assertSucceeds(
      setDoc(doc(ctx.firestore(), 'accountDeletionRequests', SUSPENDED), {
        userId: SUSPENDED,
        reason: 'Please delete my account',
        status: 'pending',
      }),
    );
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'accountDeletionRequests', SUSPENDED)));
  });

  it('suspended user retains access to support paths (reports, settings)', async () => {
    const ctx = testEnv.authenticatedContext(SUSPENDED, { suspended: true });
    await assertSucceeds(
      setDoc(doc(ctx.firestore(), 'moderationReports', 'suspended-report'), {
        reportedBy: SUSPENDED,
        subject: 'appeal',
      }),
    );
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'userPrivate', SUSPENDED), {
        notificationPreferences: { push: false },
      }),
    );
  });

  it('a client can never set the suspended flag itself', async () => {
    const ctx = testEnv.authenticatedContext(SUSPENDED, { suspended: true });
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', SUSPENDED), { suspended: false }));
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

  it('suspended member cannot read live locations (suspension overrides entitlement)', async () => {
    const ctx = testEnv.authenticatedContext('loc-suspended-member', {
      activeMember: true,
      suspended: true,
    });
    await assertFails(dbGet(dbRef(ctx.database(), `liveLocations/${SHARER}`)));
  });

  it('suspended user cannot share their own live location', async () => {
    const ctx = testEnv.authenticatedContext('loc-suspended-sharer', { suspended: true });
    await assertFails(
      dbSet(dbRef(ctx.database(), 'liveLocations/loc-suspended-sharer'), {
        lat: 57.5,
        lng: 12.0,
        timestamp: Date.now(),
      }),
    );
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
    await assertSucceeds(
      getBytes(storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`)),
    );
  });

  it("another user cannot read someone else's ride route", async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(
      getBytes(storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`)),
    );
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
