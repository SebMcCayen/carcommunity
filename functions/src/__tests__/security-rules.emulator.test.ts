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
// Firestore: badges (Phase 9f)
// ---------------------------------------------------------------------------

describe('Firestore – badges (Phase 9f)', () => {
  const OWNER = 'badge-owner';
  const OTHER = 'badge-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users', OWNER, 'badges', 'garage_created'), {
        badgeKey: 'garage_created',
        name: 'Garageprofil skapad',
        source: 'automatic',
      });
      await setDoc(doc(ctx.firestore(), 'badgeProgress', OWNER), {
        completedEventsAttended: 3,
      });
    });
  });

  it('owner can read their own badges; others cannot — not even admin clients', async () => {
    const ownerCtx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      getDoc(doc(ownerCtx.firestore(), 'users', OWNER, 'badges', 'garage_created')),
    );
    const otherCtx = testEnv.authenticatedContext(OTHER);
    await assertFails(
      getDoc(doc(otherCtx.firestore(), 'users', OWNER, 'badges', 'garage_created')),
    );
    // Strictly owner-only: admin workflows go through the Admin SDK, and a
    // compromised admin client must not browse users' badges.
    const adminCtx = testEnv.authenticatedContext('badge-admin', { admin: true });
    await assertFails(
      getDoc(doc(adminCtx.firestore(), 'users', OWNER, 'badges', 'garage_created')),
    );
  });

  it('no client can write badges — not even the owner', async () => {
    const ctx = testEnv.authenticatedContext(OWNER, { activeMember: true });
    await assertFails(
      setDoc(doc(ctx.firestore(), 'users', OWNER, 'badges', 'helpful_member'), {
        badgeKey: 'helpful_member',
        source: 'admin_manual',
      }),
    );
    await assertFails(
      deleteDoc(doc(ctx.firestore(), 'users', OWNER, 'badges', 'garage_created')),
    );
  });

  it('badgeProgress counters are fully backend-only', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(getDoc(doc(ctx.firestore(), 'badgeProgress', OWNER)));
    await assertFails(
      setDoc(doc(ctx.firestore(), 'badgeProgress', OWNER), { completedEventsAttended: 999 }),
    );
  });
});

// ---------------------------------------------------------------------------
// Firestore: points ledger (Phase 9g)
// ---------------------------------------------------------------------------

describe('Firestore – points ledger (Phase 9g)', () => {
  const OWNER = 'points-rules-owner';
  const OTHER = 'points-rules-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'pointsLedger', OWNER), { balance: 120 });
      await setDoc(doc(ctx.firestore(), 'pointsLedger', OWNER, 'entries', 'e-1'), {
        transactionType: 'earn',
        source: 'crown_hunt',
        amount: 120,
        balanceAfter: 120,
      });
    });
  });

  it('owner can read their wallet and ledger entries; others cannot', async () => {
    const ownerFs = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(getDoc(doc(ownerFs, 'pointsLedger', OWNER)));
    await assertSucceeds(getDoc(doc(ownerFs, 'pointsLedger', OWNER, 'entries', 'e-1')));

    const otherFs = testEnv.authenticatedContext(OTHER).firestore();
    await assertFails(getDoc(doc(otherFs, 'pointsLedger', OWNER)));
    await assertFails(getDoc(doc(otherFs, 'pointsLedger', OWNER, 'entries', 'e-1')));
  });

  it('no client can write balances or entries — not even the owner', async () => {
    const ownerFs = testEnv.authenticatedContext(OWNER, { activeMember: true }).firestore();
    await assertFails(updateDoc(doc(ownerFs, 'pointsLedger', OWNER), { balance: 999999 }));
    await assertFails(
      setDoc(doc(ownerFs, 'pointsLedger', OWNER, 'entries', 'forged'), {
        transactionType: 'earn',
        source: 'system',
        amount: 999999,
        balanceAfter: 999999,
      }),
    );
    await assertFails(deleteDoc(doc(ownerFs, 'pointsLedger', OWNER, 'entries', 'e-1')));
  });
});

// ---------------------------------------------------------------------------
// Firestore: Kronjakt (Phase 9h)
// ---------------------------------------------------------------------------

describe('Firestore – Kronjakt (Phase 9h)', () => {
  const MEMBER = 'ch-rules-member';
  const OTHER = 'ch-rules-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const firestore = ctx.firestore();
      await setDoc(doc(firestore, 'crownHuntPoints', 'p-active'), {
        title: 'Aktiv krona',
        latitude: 59.33,
        longitude: 18.07,
        geofenceRadiusMeters: 50,
        rewardPoints: 25,
        status: 'active',
      });
      await setDoc(doc(firestore, 'crownHuntPoints', 'p-draft'), {
        title: 'Utkast',
        latitude: 59.34,
        longitude: 18.08,
        geofenceRadiusMeters: 50,
        rewardPoints: 25,
        status: 'draft',
      });
      await setDoc(doc(firestore, 'crownHuntClaims', 'claim-1'), {
        userId: MEMBER,
        pointId: 'p-active',
        result: 'awarded',
      });
      await setDoc(doc(firestore, 'crownHuntClaimRisk', 'claim-1'), {
        userId: MEMBER,
        riskScore: 10,
        riskReasons: ['poor_gps_accuracy'],
      });
    });
  });

  it('members read active points; drafts and non-members are denied', async () => {
    const memberFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertSucceeds(getDoc(doc(memberFs, 'crownHuntPoints', 'p-active')));
    await assertFails(getDoc(doc(memberFs, 'crownHuntPoints', 'p-draft')));
    const freeFs = testEnv.authenticatedContext(OTHER).firestore();
    await assertFails(getDoc(doc(freeFs, 'crownHuntPoints', 'p-active')));
  });

  it('claims are owner-only member reads; risk data is fully backend-only', async () => {
    const memberFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertSucceeds(getDoc(doc(memberFs, 'crownHuntClaims', 'claim-1')));
    // Risk thresholds/reasons never reach clients — not even the claim owner.
    await assertFails(getDoc(doc(memberFs, 'crownHuntClaimRisk', 'claim-1')));
    const otherFs = testEnv.authenticatedContext(OTHER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(otherFs, 'crownHuntClaims', 'claim-1')));
  });

  it('no client writes to points or claims', async () => {
    const memberFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertFails(
      updateDoc(doc(memberFs, 'crownHuntPoints', 'p-active'), { rewardPoints: 1000 }),
    );
    await assertFails(
      setDoc(doc(memberFs, 'crownHuntClaims', 'forged'), {
        userId: MEMBER,
        pointId: 'p-active',
        result: 'awarded',
      }),
    );
  });
});

// ---------------------------------------------------------------------------
// Firestore: partners (Phase 9i)
// ---------------------------------------------------------------------------

describe('Firestore – partners (Phase 9i)', () => {
  const MEMBER = 'partner-rules-member';
  const FREE = 'partner-rules-free';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const firestore = ctx.firestore();
      await setDoc(doc(firestore, 'companies', 'co-active'), {
        name: 'Verkstan AB',
        category: 'workshop',
        status: 'active',
      });
      await setDoc(doc(firestore, 'companies', 'co-draft'), {
        name: 'Utkast AB',
        category: 'parts',
        status: 'draft',
      });
      await setDoc(doc(firestore, 'offers', 'of-active'), {
        companyId: 'co-active',
        partnerCompanyName: 'Verkstan AB',
        title: '10% rabatt',
        teaserText: 'Medlemsrabatt',
        offerType: 'percentage_discount',
        status: 'active',
      });
      await setDoc(doc(firestore, 'offers', 'of-active', 'details', 'member'), {
        description: 'Full detalj',
        percentageDiscount: 10,
      });
      await setDoc(doc(firestore, 'offers', 'of-active', 'secret', 'code'), {
        discountCode: 'HEMLIG10',
      });
      await setDoc(doc(firestore, 'offers', 'of-draft'), {
        companyId: 'co-active',
        partnerCompanyName: 'Verkstan AB',
        title: 'Utkast',
        teaserText: 'Ej publik',
        offerType: 'other',
        status: 'draft',
      });
      await setDoc(doc(firestore, 'partnerApplications', 'app-1'), {
        companyName: 'Däckfirman',
        contactEmail: 'anna@dack.se',
        status: 'submitted',
        submittedByUserId: MEMBER,
      });
    });
  });

  it('authenticated users read active companies and offer teasers; drafts hidden', async () => {
    const freeFs = testEnv.authenticatedContext(FREE).firestore();
    await assertSucceeds(getDoc(doc(freeFs, 'companies', 'co-active')));
    await assertFails(getDoc(doc(freeFs, 'companies', 'co-draft')));
    await assertSucceeds(getDoc(doc(freeFs, 'offers', 'of-active')));
    await assertFails(getDoc(doc(freeFs, 'offers', 'of-draft')));
  });

  it('member detail is member-gated; the secret code is unreadable by everyone', async () => {
    const memberFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertSucceeds(getDoc(doc(memberFs, 'offers', 'of-active', 'details', 'member')));
    const freeFs = testEnv.authenticatedContext(FREE).firestore();
    await assertFails(getDoc(doc(freeFs, 'offers', 'of-active', 'details', 'member')));
    // The discount code tier is closed even to members and admin clients.
    await assertFails(getDoc(doc(memberFs, 'offers', 'of-active', 'secret', 'code')));
    const adminFs = testEnv.authenticatedContext('partner-admin', { admin: true }).firestore();
    await assertFails(getDoc(doc(adminFs, 'offers', 'of-active', 'secret', 'code')));
  });

  it('applications are never client-readable — not even by the submitter', async () => {
    const memberFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(memberFs, 'partnerApplications', 'app-1')));
  });

  it('saved offers: member bookmarks with validated shape; non-members denied', async () => {
    const memberFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertSucceeds(
      setDoc(doc(memberFs, 'users', MEMBER, 'savedOffers', 'of-active'), {
        offerId: 'of-active',
        savedAt: serverTimestamp(),
      }),
    );
    // Doc ID must equal offerId; junk fields rejected; free users denied.
    await assertFails(
      setDoc(doc(memberFs, 'users', MEMBER, 'savedOffers', 'of-active'), {
        offerId: 'other-offer',
        savedAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(memberFs, 'users', MEMBER, 'savedOffers', 'of-draft'), {
        offerId: 'of-draft',
        savedAt: serverTimestamp(),
        note: 'x',
      }),
    );
    const freeFs = testEnv.authenticatedContext(FREE).firestore();
    await assertFails(
      setDoc(doc(freeFs, 'users', FREE, 'savedOffers', 'of-active'), {
        offerId: 'of-active',
        savedAt: serverTimestamp(),
      }),
    );
    await assertSucceeds(
      deleteDoc(doc(memberFs, 'users', MEMBER, 'savedOffers', 'of-active')),
    );
  });

  it('no client writes to companies or offers', async () => {
    const adminFs = testEnv.authenticatedContext('partner-admin', { admin: true }).firestore();
    await assertFails(updateDoc(doc(adminFs, 'companies', 'co-active'), { name: 'Hacked' }));
    const memberFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertFails(updateDoc(doc(memberFs, 'offers', 'of-active'), { title: 'Hacked' }));
  });
});

// ---------------------------------------------------------------------------
// Firestore: partner insights (Phase 9j)
// ---------------------------------------------------------------------------

describe('Firestore – partner insights (Phase 9j)', () => {
  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'partnerInsightsEvents', 'pi-ev-1'), {
        companyId: 'co-1',
        interactionType: 'profile_view',
        userReferenceHash: 'a'.repeat(64),
      });
      await setDoc(doc(ctx.firestore(), 'partnerInsights', 'co-1_profile_view_day_2026-07-01'), {
        companyId: 'co-1',
        totalCount: 5,
        resultStatus: 'available',
      });
    });
  });

  it('raw events and aggregates are closed to ALL clients — member and admin alike', async () => {
    const memberFs = testEnv
      .authenticatedContext('pi-rules-member', { activeMember: true })
      .firestore();
    const adminFs = testEnv.authenticatedContext('pi-rules-admin', { admin: true }).firestore();
    for (const firestore of [memberFs, adminFs]) {
      await assertFails(getDoc(doc(firestore, 'partnerInsightsEvents', 'pi-ev-1')));
      await assertFails(
        getDoc(doc(firestore, 'partnerInsights', 'co-1_profile_view_day_2026-07-01')),
      );
      await assertFails(
        setDoc(doc(firestore, 'partnerInsightsEvents', 'forged'), {
          companyId: 'co-1',
          interactionType: 'anonymous_pass_by',
          userReferenceHash: 'b'.repeat(64),
        }),
      );
      await assertFails(
        updateDoc(doc(firestore, 'partnerInsights', 'co-1_profile_view_day_2026-07-01'), {
          totalCount: 999,
        }),
      );
    }
  });
});

// ---------------------------------------------------------------------------
// Firestore + Storage: digital billboards (Phase 9k)
// ---------------------------------------------------------------------------

describe('Firestore – billboards (Phase 9k)', () => {
  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'billboards', 'bb-active'), {
        partnerCompanyId: 'co-1',
        headline: 'Aktiv skylt',
        message: 'Meddelande',
        status: 'active',
      });
      await setDoc(doc(ctx.firestore(), 'billboards', 'bb-draft'), {
        partnerCompanyId: 'co-1',
        headline: 'Utkast',
        message: 'Meddelande',
        status: 'draft',
      });
    });
  });

  it('authenticated users read active billboards; drafts hidden; no client writes', async () => {
    const ctx = testEnv.authenticatedContext('bb-rules-user');
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'billboards', 'bb-active')));
    await assertFails(getDoc(doc(ctx.firestore(), 'billboards', 'bb-draft')));
    await assertFails(getDoc(doc(testEnv.unauthenticatedContext().firestore(), 'billboards', 'bb-active')));
    const adminCtx = testEnv.authenticatedContext('bb-rules-admin', { admin: true });
    await assertFails(
      updateDoc(doc(adminCtx.firestore(), 'billboards', 'bb-active'), { headline: 'Hacked' }),
    );
  });

  it('billboard images: authenticated read, admin-only write', async () => {
    const adminCtx = testEnv.authenticatedContext('bb-img-admin', { admin: true });
    const data = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);
    await assertSucceeds(
      uploadBytes(storageRef(adminCtx.storage(), 'billboardImages/bb-1/hero.png'), data, {
        contentType: 'image/png',
      }),
    );
    const userCtx = testEnv.authenticatedContext('bb-img-user');
    await assertSucceeds(
      getBytes(storageRef(userCtx.storage(), 'billboardImages/bb-1/hero.png')),
    );
    await assertFails(
      uploadBytes(storageRef(userCtx.storage(), 'billboardImages/bb-1/spoof.png'), data, {
        contentType: 'image/png',
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
// Firestore: notifications (Phase 9l)
// In-app inbox: owner-only read, backend-only writes (delivery via the
// backend writer, read-state via notifications.markRead / markAllRead).
// Push tokens: owner-only read of hash-only documents, callable-only writes.
// ---------------------------------------------------------------------------

describe('Firestore – notifications (Phase 9l)', () => {
  const OWNER = 'notif-owner';
  const OTHER = 'notif-other';
  const ITEM = `notifications/${OWNER}/items/n1`;
  const TOKEN = `userPrivate/${OWNER}/pushTokens/${'a'.repeat(64)}`;

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), ITEM), {
        category: 'system_notice',
        title: 'Nyheter i appen',
        previewText: 'Vi har uppdaterat kartan.',
        body: null,
        actionType: 'none',
        relatedEntityId: null,
        batchId: null,
        read: false,
        readAt: null,
        createdAt: serverTimestamp(),
      });
      await setDoc(doc(ctx.firestore(), TOKEN), {
        platform: 'android',
        appVersion: null,
        buildNumber: null,
        createdAt: serverTimestamp(),
        lastSeenAt: serverTimestamp(),
      });
    });
  });

  it('owner can read their own notifications', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(getDoc(doc(ctx.firestore(), ITEM)));
  });

  it('another user cannot read someone else’s notifications', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(getDoc(doc(ctx.firestore(), ITEM)));
  });

  it('unauthenticated users cannot read notifications', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getDoc(doc(ctx.firestore(), ITEM)));
  });

  it('the owner cannot author, edit, or delete notifications (backend-only writes)', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      setDoc(doc(ctx.firestore(), `notifications/${OWNER}/items/self-made`), {
        category: 'admin_message',
        title: 'Falskt meddelande',
        previewText: 'x',
        read: false,
      }),
    );
    // Even the read flag goes through notifications.markRead, never directly.
    await assertFails(updateDoc(doc(ctx.firestore(), ITEM), { read: true }));
    await assertFails(deleteDoc(doc(ctx.firestore(), ITEM)));
  });

  it('owner can read their own push token registrations, others cannot', async () => {
    const owner = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(getDoc(doc(owner.firestore(), TOKEN)));
    const other = testEnv.authenticatedContext(OTHER);
    await assertFails(getDoc(doc(other.firestore(), TOKEN)));
  });

  it('push token writes are callable-only', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      setDoc(doc(ctx.firestore(), `userPrivate/${OWNER}/pushTokens/${'b'.repeat(64)}`), {
        platform: 'android',
      }),
    );
    await assertFails(updateDoc(doc(ctx.firestore(), TOKEN), { platform: 'ios' }));
    await assertFails(deleteDoc(doc(ctx.firestore(), TOKEN)));
  });
});

// ---------------------------------------------------------------------------
// Firestore: config / feature flags (Phase 9m)
// config/featureFlags: authenticated read, admin.setFeatureFlag-only writes.
// Every other config document (e.g. config/partnerInsights) stays
// backend-only.
// ---------------------------------------------------------------------------

describe('Firestore – config / feature flags (Phase 9m)', () => {
  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      // Only contract-default values: the emulator database is shared with
      // the functions test suites, so seeding a flipped flag (or a raised
      // insights threshold) would leak into their behavior.
      await setDoc(doc(ctx.firestore(), 'config/featureFlags'), { chat: true }, { merge: true });
      await setDoc(doc(ctx.firestore(), 'config/partnerInsights'), { minThreshold: 10 });
    });
  });

  afterAll(async () => {
    // Remove the threshold config so the insights aggregation tests see
    // their expected floor-default state.
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await deleteDoc(doc(ctx.firestore(), 'config/partnerInsights'));
    });
  });

  it('any authenticated user can read the feature flags document', async () => {
    const ctx = testEnv.authenticatedContext('ff-reader');
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'config/featureFlags')));
  });

  it('unauthenticated clients cannot read feature flags', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getDoc(doc(ctx.firestore(), 'config/featureFlags')));
  });

  it('clients can never write feature flags (admin.setFeatureFlag only)', async () => {
    const ctx = testEnv.authenticatedContext('ff-writer');
    await assertFails(updateDoc(doc(ctx.firestore(), 'config/featureFlags'), { chat: false }));
    await assertFails(deleteDoc(doc(ctx.firestore(), 'config/featureFlags')));
  });

  it('other config documents stay backend-only (privacy threshold)', async () => {
    const ctx = testEnv.authenticatedContext('ff-reader');
    await assertFails(getDoc(doc(ctx.firestore(), 'config/partnerInsights')));
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'config/partnerInsights'), { minThreshold: 1 }),
    );
  });

// ---------------------------------------------------------------------------
// Firestore: diagnostics reports (Phase 9n)
// Admin-only read; callable-only writes (server-side sanitization).
// ---------------------------------------------------------------------------

describe('Firestore – diagnostics reports (Phase 9n)', () => {
  const REPORT = 'diagnosticsReports/report-1';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), REPORT), {
        userId: null,
        severity: 'error',
        platform: 'android',
        featureArea: 'auth',
        safeMessage: 'seed',
        fingerprint: 'f'.repeat(64),
        createdAt: serverTimestamp(),
      });
    });
  });

  it('admins can read reports', async () => {
    const ctx = testEnv.authenticatedContext('diag-admin', { admin: true });
    await assertSucceeds(getDoc(doc(ctx.firestore(), REPORT)));
  });

  it('regular users cannot read reports (not even their own)', async () => {
    const ctx = testEnv.authenticatedContext('diag-user');
    await assertFails(getDoc(doc(ctx.firestore(), REPORT)));
  });

  it('clients can never write reports directly (sanitization bypass)', async () => {
    const ctx = testEnv.authenticatedContext('diag-user');
    await assertFails(
      setDoc(doc(ctx.firestore(), 'diagnosticsReports/self-made'), {
        severity: 'error',
        safeMessage: 'raw',
        metadata: { idToken: 'leaked' },
      }),
    );
    const admin = testEnv.authenticatedContext('diag-admin', { admin: true });
    await assertFails(deleteDoc(doc(admin.firestore(), REPORT)));
  });

// ---------------------------------------------------------------------------
// Firestore: moderation reports field validation (Phase 9o)
// Reporter identity pinned, whitelisted shape, status starts pending.
// ---------------------------------------------------------------------------

describe('Firestore – moderation reports validation (Phase 9o)', () => {
  const REPORTER = 'mod-reporter';
  const validReport = {
    reportedBy: REPORTER,
    targetType: 'user',
    targetId: 'bad-actor',
    reason: 'harassment',
    details: 'Upprepade otrevliga meddelanden.',
    status: 'pending',
  };

  // A dedicated, pre-seeded report for the admin-review assertions below,
  // so that test does not depend on the create test having run first.
  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'moderationReports/review-target'), {
        ...validReport,
        createdAt: serverTimestamp(),
      });
    });
  });

  it('accepts a valid report from its own reporter', async () => {
    const ctx = testEnv.authenticatedContext(REPORTER);
    await assertSucceeds(
      setDoc(doc(ctx.firestore(), 'moderationReports/valid-1'), {
        ...validReport,
        createdAt: serverTimestamp(),
      }),
    );
  });

  it('rejects spoofed reporters, bad shapes, and non-pending status', async () => {
    const ctx = testEnv.authenticatedContext(REPORTER);
    await assertFails(
      setDoc(doc(ctx.firestore(), 'moderationReports/spoof'), {
        ...validReport,
        reportedBy: 'someone-else',
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(ctx.firestore(), 'moderationReports/extra'), {
        ...validReport,
        extraField: 'x',
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(ctx.firestore(), 'moderationReports/badtype'), {
        ...validReport,
        targetType: 'vehicle',
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(ctx.firestore(), 'moderationReports/resolved'), {
        ...validReport,
        status: 'reviewed',
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(ctx.firestore(), 'moderationReports/clientts'), {
        ...validReport,
        createdAt: new Date(),
      }),
    );
  });

  it('reporters cannot read, update, or delete their reports (admin-only review)', async () => {
    const ctx = testEnv.authenticatedContext(REPORTER);
    await assertFails(getDoc(doc(ctx.firestore(), 'moderationReports/review-target')));
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'moderationReports/review-target'), { status: 'reviewed' }),
    );
    await assertFails(deleteDoc(doc(ctx.firestore(), 'moderationReports/review-target')));
    const admin = testEnv.authenticatedContext('mod-admin', { admin: true });
    await assertSucceeds(getDoc(doc(admin.firestore(), 'moderationReports/review-target')));
    await assertSucceeds(
      updateDoc(doc(admin.firestore(), 'moderationReports/review-target'), { status: 'reviewed' }),
    );
    // Admin review updates are status-only: immutable fields stay immutable
    // and the status vocabulary is closed.
    await assertFails(
      updateDoc(doc(admin.firestore(), 'moderationReports/review-target'), {
        status: 'reviewed',
        reason: 'rewritten',
      }),
    );
    await assertFails(
      updateDoc(doc(admin.firestore(), 'moderationReports/review-target'), { status: 'archived' }),
    );
    // Even admins cannot delete review records.
    await assertFails(deleteDoc(doc(admin.firestore(), 'moderationReports/review-target')));
  });
});
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
// Firestore: events – teaser/detail split and RSVPs (Phase 9b)
// ---------------------------------------------------------------------------

describe('Firestore – events (Phase 9b)', () => {
  const PUBLISHED = 'event-published';
  const DRAFT = 'event-draft';
  const MEMBER = 'events-member';
  const NON_MEMBER = 'events-non-member';

  const memberCtx = () => testEnv.authenticatedContext(MEMBER, { activeMember: true });

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const firestore = ctx.firestore();
      for (const [id, status] of [
        [PUBLISHED, 'published'],
        [DRAFT, 'draft'],
      ] as const) {
        await setDoc(doc(firestore, 'events', id), {
          title: 'Test event',
          approximateArea: 'Stockholm area',
          status,
          rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
        });
        await setDoc(doc(firestore, 'events', id, 'details', 'private'), {
          locationName: 'Exact spot',
          latitude: 59.3,
          longitude: 18.0,
        });
      }
    });
  });

  it('any authenticated user can read a published event teaser', async () => {
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'events', PUBLISHED)));
  });

  it('non-admin users cannot read draft events', async () => {
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    await assertFails(getDoc(doc(ctx.firestore(), 'events', DRAFT)));
  });

  it('an admin can read draft events', async () => {
    const ctx = testEnv.authenticatedContext('events-admin', { admin: true });
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'events', DRAFT)));
  });

  it('unauthenticated users cannot read any event', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getDoc(doc(ctx.firestore(), 'events', PUBLISHED)));
  });

  it('non-members cannot read the member-gated detail document', async () => {
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    await assertFails(getDoc(doc(ctx.firestore(), 'events', PUBLISHED, 'details', 'private')));
  });

  it('active members can read the detail document of a published event', async () => {
    await assertSucceeds(
      getDoc(doc(memberCtx().firestore(), 'events', PUBLISHED, 'details', 'private')),
    );
  });

  it('members cannot read the detail document of a draft event', async () => {
    await assertFails(
      getDoc(doc(memberCtx().firestore(), 'events', DRAFT, 'details', 'private')),
    );
  });

  it('a suspended member loses detail access (suspension overrides entitlement)', async () => {
    const ctx = testEnv.authenticatedContext('events-suspended', {
      activeMember: true,
      suspended: true,
    });
    await assertFails(getDoc(doc(ctx.firestore(), 'events', PUBLISHED, 'details', 'private')));
  });

  it('no client can write event documents — not even admins (callables only)', async () => {
    const adminCtx = testEnv.authenticatedContext('events-admin', { admin: true });
    await assertFails(
      updateDoc(doc(adminCtx.firestore(), 'events', PUBLISHED), { title: 'Edited' }),
    );
    await assertFails(
      updateDoc(doc(memberCtx().firestore(), 'events', PUBLISHED), { status: 'cancelled' }),
    );
  });

  it('an active member can create and change their own RSVP', async () => {
    const rsvpRef = doc(memberCtx().firestore(), 'events', PUBLISHED, 'rsvps', MEMBER);
    await assertSucceeds(setDoc(rsvpRef, { status: 'going', updatedAt: serverTimestamp() }));
    await assertSucceeds(setDoc(rsvpRef, { status: 'not_going', updatedAt: serverTimestamp() }));
  });

  it('rejects RSVPs with an invalid status or extra fields', async () => {
    const firestore = memberCtx().firestore();
    await assertFails(
      setDoc(doc(firestore, 'events', PUBLISHED, 'rsvps', MEMBER), { status: 'attending' }),
    );
    await assertFails(
      setDoc(doc(firestore, 'events', PUBLISHED, 'rsvps', MEMBER), {
        status: 'going',
        plusOnes: 4,
      }),
    );
  });

  it('rejects RSVPs without a server-timestamp updatedAt', async () => {
    const firestore = memberCtx().firestore();
    await assertFails(
      setDoc(doc(firestore, 'events', PUBLISHED, 'rsvps', MEMBER), { status: 'going' }),
    );
    await assertFails(
      setDoc(doc(firestore, 'events', PUBLISHED, 'rsvps', MEMBER), {
        status: 'going',
        updatedAt: new Date('2020-01-01T00:00:00Z'),
      }),
    );
  });

  it('members cannot read other documents under details/ (only private)', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'events', PUBLISHED, 'details', 'internal-notes'), {
        note: 'staff only',
      });
    });
    await assertFails(
      getDoc(doc(memberCtx().firestore(), 'events', PUBLISHED, 'details', 'internal-notes')),
    );
  });

  it('members cannot RSVP to a draft event', async () => {
    await assertFails(
      setDoc(doc(memberCtx().firestore(), 'events', DRAFT, 'rsvps', MEMBER), {
        status: 'going',
      }),
    );
  });

  it('non-members cannot RSVP', async () => {
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    await assertFails(
      setDoc(doc(ctx.firestore(), 'events', PUBLISHED, 'rsvps', NON_MEMBER), {
        status: 'going',
      }),
    );
  });

  it("members cannot write another user's RSVP or read it", async () => {
    const firestore = memberCtx().firestore();
    await assertFails(
      setDoc(doc(firestore, 'events', PUBLISHED, 'rsvps', 'someone-else'), {
        status: 'going',
      }),
    );
    await assertFails(getDoc(doc(firestore, 'events', PUBLISHED, 'rsvps', 'someone-else')));
  });

  it('members cannot delete their RSVP (answers change instead)', async () => {
    await assertFails(
      deleteDoc(doc(memberCtx().firestore(), 'events', PUBLISHED, 'rsvps', MEMBER)),
    );
  });
});

// ---------------------------------------------------------------------------
// Firestore: event chat (Phase 9c)
// Messages readable only by chat-eligible members (active member + published
// event + going/maybe RSVP); all writes are callable-only. Reports are fully
// backend-only.
// ---------------------------------------------------------------------------

describe('Firestore – event chat (Phase 9c)', () => {
  const EVENT = 'chat-event';
  const DRAFT_EVENT = 'chat-draft-event';
  const GOING = 'chat-member-going';
  const MAYBE = 'chat-member-maybe';
  const NOT_GOING = 'chat-member-notgoing';
  const NO_RSVP = 'chat-member-norsvp';
  const FREE_GOING = 'chat-free-going';

  const memberCtx = (uid: string) => testEnv.authenticatedContext(uid, { activeMember: true });

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const firestore = ctx.firestore();
      for (const [id, status] of [
        [EVENT, 'published'],
        [DRAFT_EVENT, 'draft'],
      ] as const) {
        await setDoc(doc(firestore, 'events', id), {
          title: 'Chat event',
          approximateArea: 'Area',
          status,
        });
        await setDoc(doc(firestore, 'events', id, 'messages', 'msg-1'), {
          authorUserId: 'author-1',
          authorDisplayName: 'Author One',
          message: 'hello everyone',
          moderationState: 'visible',
        });
      }
      const rsvps: Array<[string, string]> = [
        [GOING, 'going'],
        [MAYBE, 'maybe'],
        [NOT_GOING, 'not_going'],
        [FREE_GOING, 'going'],
      ];
      for (const [uid, status] of rsvps) {
        await setDoc(doc(firestore, 'events', EVENT, 'rsvps', uid), { status });
        await setDoc(doc(firestore, 'events', DRAFT_EVENT, 'rsvps', uid), { status });
      }
      await setDoc(doc(firestore, 'events', EVENT, 'messageReports', 'report-1'), {
        messageId: 'msg-1',
        reporterUserId: 'someone',
        reason: 'spam',
        status: 'new',
      });
    });
  });

  it('members with a going or maybe RSVP can read chat messages', async () => {
    await assertSucceeds(
      getDoc(doc(memberCtx(GOING).firestore(), 'events', EVENT, 'messages', 'msg-1')),
    );
    await assertSucceeds(
      getDoc(doc(memberCtx(MAYBE).firestore(), 'events', EVENT, 'messages', 'msg-1')),
    );
  });

  it('members with not_going or no RSVP cannot read chat messages', async () => {
    await assertFails(
      getDoc(doc(memberCtx(NOT_GOING).firestore(), 'events', EVENT, 'messages', 'msg-1')),
    );
    await assertFails(
      getDoc(doc(memberCtx(NO_RSVP).firestore(), 'events', EVENT, 'messages', 'msg-1')),
    );
  });

  it('non-members cannot read chat even with a going RSVP', async () => {
    const ctx = testEnv.authenticatedContext(FREE_GOING);
    await assertFails(getDoc(doc(ctx.firestore(), 'events', EVENT, 'messages', 'msg-1')));
  });

  it('chat of a draft event is unreadable even for eligible members', async () => {
    await assertFails(
      getDoc(doc(memberCtx(GOING).firestore(), 'events', DRAFT_EVENT, 'messages', 'msg-1')),
    );
  });

  it('admins can read chat without an RSVP', async () => {
    const ctx = testEnv.authenticatedContext('chat-admin', { admin: true });
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'events', EVENT, 'messages', 'msg-1')));
  });

  it('no client can write chat messages — posting goes through the callable', async () => {
    const firestore = memberCtx(GOING).firestore();
    await assertFails(
      setDoc(doc(firestore, 'events', EVENT, 'messages', 'direct-msg'), {
        authorUserId: GOING,
        authorDisplayName: 'Going Member',
        message: 'direct write',
        moderationState: 'visible',
      }),
    );
    await assertFails(
      updateDoc(doc(firestore, 'events', EVENT, 'messages', 'msg-1'), { message: 'edited' }),
    );
    await assertFails(deleteDoc(doc(firestore, 'events', EVENT, 'messages', 'msg-1')));
  });

  it('message reports are fully backend-only (no reads, no writes)', async () => {
    const memberFs = memberCtx(GOING).firestore();
    await assertFails(getDoc(doc(memberFs, 'events', EVENT, 'messageReports', 'report-1')));
    await assertFails(
      setDoc(doc(memberFs, 'events', EVENT, 'messageReports', 'direct-report'), {
        messageId: 'msg-1',
        reporterUserId: GOING,
        reason: 'spam',
        status: 'new',
      }),
    );
    const adminFs = testEnv.authenticatedContext('chat-admin', { admin: true }).firestore();
    await assertFails(getDoc(doc(adminFs, 'events', EVENT, 'messageReports', 'report-1')));
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

  it('unauthenticated users cannot read vehicles', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getDoc(doc(ctx.firestore(), 'vehicles', 'v-read-test')));
  });

  it('no client writes at all — even the member owner (garage.* callables only)', async () => {
    // Phase 9e: direct writes would bypass the per-user cap, the strict
    // no-plate/no-VIN validation, and storage cleanup on delete.
    const ctx = testEnv.authenticatedContext(OWNER, { activeMember: true });
    await assertFails(
      setDoc(doc(ctx.firestore(), 'vehicles', 'v-new'), {
        userId: OWNER,
        make: 'Ford',
        model: 'Mustang',
      }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'vehicles', 'v-read-test'), {
        registrationPlate: 'ABC123',
      }),
    );
    await assertFails(deleteDoc(doc(ctx.firestore(), 'vehicles', 'v-delete-test')));
  });

  it("another user cannot delete someone else's vehicle", async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertFails(deleteDoc(doc(ctx.firestore(), 'vehicles', 'v-no-delete')));
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

  it('no client writes at all — even the owner (drives.save/delete callables only)', async () => {
    // A direct create/update could forge server-computed stats; deletes must
    // clean the Cloud Storage prefix, which only drives.delete does (9d).
    const ctx = testEnv.authenticatedContext(OWNER, { activeMember: true });
    await assertFails(
      setDoc(doc(ctx.firestore(), 'rides', 'r-direct'), {
        userId: OWNER,
        title: 'Direct',
        distanceMeters: 999999,
      }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'rides', 'r-private'), { distanceMeters: 999999 }),
    );
    await assertFails(deleteDoc(doc(ctx.firestore(), 'rides', 'r-private')));
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
// Firestore: client-error reporting (backend-only writes, admin-only reads)
// ---------------------------------------------------------------------------

describe('Firestore – client-error reports + issue links', () => {
  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'clientErrorReports', 'err-1'), {
        uid: 'reporter-uid',
        feature: 'messages.conversationList',
        message: 'Conversation inbox listener failed',
        fingerprint: 'fp-1',
      });
      await setDoc(doc(ctx.firestore(), 'clientErrorIssueLinks', 'fp-1'), {
        fingerprint: 'fp-1',
        status: 'created',
        count: 3,
      });
    });
  });

  it('admin can read client-error reports and issue links', async () => {
    const ctx = testEnv.authenticatedContext('admin-uid', { admin: true });
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'clientErrorReports', 'err-1')));
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'clientErrorIssueLinks', 'fp-1')));
  });

  it('the reporter (a regular user) cannot read their own client-error report', async () => {
    const ctx = testEnv.authenticatedContext('reporter-uid');
    await assertFails(getDoc(doc(ctx.firestore(), 'clientErrorReports', 'err-1')));
    await assertFails(getDoc(doc(ctx.firestore(), 'clientErrorIssueLinks', 'fp-1')));
  });

  it('no client (not even admin) can write client-error reports or issue links', async () => {
    const ctx = testEnv.authenticatedContext('admin-uid', { admin: true });
    await assertFails(
      setDoc(doc(ctx.firestore(), 'clientErrorReports', 'forged'), { uid: 'x', feature: 'f' }),
    );
    await assertFails(
      setDoc(doc(ctx.firestore(), 'clientErrorIssueLinks', 'forged'), { status: 'created' }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'clientErrorReports', 'err-1'), { message: 'tampered' }),
    );
    await assertFails(deleteDoc(doc(ctx.firestore(), 'clientErrorIssueLinks', 'fp-1')));
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
  const ACTIVE_MEMBER = 'active-member';

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
      // savedOffers is a member bookmark a client may delete directly, but
      // only while an active (non-suspended) member — the delete rule is
      // `isOwner(userId) && isActiveMember()`, and isActiveMember() folds in
      // isNotSuspended(). Seed one for the suspended user and one for a
      // non-suspended member to prove the suspension gate both ways below.
      await setDoc(
        doc(ctx.firestore(), 'users', SUSPENDED, 'savedOffers', 'suspended-offer'),
        { offerId: 'suspended-offer' },
      );
      await setDoc(
        doc(ctx.firestore(), 'users', ACTIVE_MEMBER, 'savedOffers', 'active-offer'),
        { offerId: 'active-offer' },
      );
      // Published event where the (now suspended) member had RSVP'd — chat
      // read eligibility must still be revoked by suspension.
      await setDoc(doc(ctx.firestore(), 'events', 'suspended-chat-event'), {
        title: 'Chat event',
        approximateArea: 'Area',
        status: 'published',
        rsvpCounts: { going: 1, maybe: 0, not_going: 0 },
      });
      await setDoc(
        doc(ctx.firestore(), 'events', 'suspended-chat-event', 'rsvps', SUSPENDED),
        { status: 'going' },
      );
      await setDoc(
        doc(ctx.firestore(), 'events', 'suspended-chat-event', 'messages', 'msg-1'),
        {
          authorUserId: 'someone',
          authorDisplayName: 'Someone',
          message: 'hello',
          moderationState: 'visible',
        },
      );
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

  it('suspension overrides the activeMember entitlement for event chat reads', async () => {
    const ctx = testEnv.authenticatedContext(SUSPENDED, { activeMember: true, suspended: true });
    await assertFails(
      getDoc(doc(ctx.firestore(), 'events', 'suspended-chat-event', 'messages', 'msg-1')),
    );
  });

  it('suspension blocks an otherwise-permitted client delete (saved offers)', async () => {
    // savedOffers is one of the few docs a client may delete directly, and only
    // while an active (non-suspended) member — the delete rule folds in
    // isNotSuspended() via isActiveMember(). Prove the gate both ways so the
    // assertion is meaningful — asserting a vehicle or friendRequests delete
    // would be a false signal, since neither has any client delete at all
    // (garage.* / friend.* callables own those mutations), so *every* client is
    // denied regardless of suspension.
    const activeCtx = testEnv.authenticatedContext(ACTIVE_MEMBER, { activeMember: true });
    await assertSucceeds(
      deleteDoc(
        doc(activeCtx.firestore(), 'users', ACTIVE_MEMBER, 'savedOffers', 'active-offer'),
      ),
    );

    const suspendedCtx = testEnv.authenticatedContext(SUSPENDED, {
      activeMember: true,
      suspended: true,
    });
    await assertFails(
      deleteDoc(
        doc(suspendedCtx.firestore(), 'users', SUSPENDED, 'savedOffers', 'suspended-offer'),
      ),
    );
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
        createdAt: serverTimestamp(),
      }),
    );
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'accountDeletionRequests', SUSPENDED)));
  });

  it('suspended user retains access to support paths (reports, settings)', async () => {
    const ctx = testEnv.authenticatedContext(SUSPENDED, { suspended: true });
    await assertSucceeds(
      setDoc(doc(ctx.firestore(), 'moderationReports', 'suspended-report'), {
        reportedBy: SUSPENDED,
        targetType: 'user',
        targetId: 'someone',
        reason: 'appeal',
        status: 'pending',
        createdAt: serverTimestamp(),
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
// Firestore: group drive roster + announcements (Phase 16 coverage audit)
// ---------------------------------------------------------------------------

describe('Firestore – group drive roster (Phase 11 rules)', () => {
  const EVENT = 'gd-event';
  const PARTICIPANT = 'gd-participant';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), `events/${EVENT}`), {
        title: 'Roster test',
        status: 'published',
      });
      await setDoc(
        doc(ctx.firestore(), `events/${EVENT}/groupDriveParticipants/${PARTICIPANT}`),
        {
          displayName: 'Deltagare',
          status: 'joined',
          joinedAt: serverTimestamp(),
          leftAt: null,
          updatedAt: serverTimestamp(),
        },
      );
    });
  });

  it('members read the roster of published events; owners always', async () => {
    const memberCtx = testEnv.authenticatedContext('gd-member', { activeMember: true });
    await assertSucceeds(
      getDoc(doc(memberCtx.firestore(), `events/${EVENT}/groupDriveParticipants/${PARTICIPANT}`)),
    );
    const ownerCtx = testEnv.authenticatedContext(PARTICIPANT);
    await assertSucceeds(
      getDoc(doc(ownerCtx.firestore(), `events/${EVENT}/groupDriveParticipants/${PARTICIPANT}`)),
    );
  });

  it('non-members cannot read; nobody writes directly', async () => {
    const freeCtx = testEnv.authenticatedContext('gd-free');
    await assertFails(
      getDoc(doc(freeCtx.firestore(), `events/${EVENT}/groupDriveParticipants/${PARTICIPANT}`)),
    );
    const memberCtx = testEnv.authenticatedContext('gd-member', { activeMember: true });
    await assertFails(
      setDoc(doc(memberCtx.firestore(), `events/${EVENT}/groupDriveParticipants/gd-member`), {
        displayName: 'Direkt',
        status: 'joined',
      }),
    );
  });
});

describe('Firestore – announcements (pre-migration scaffold coverage)', () => {
  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'announcements/a1'), {
        title: 'Info',
        body: 'Hej!',
      });
    });
  });

  it('authenticated users read; regular users cannot write', async () => {
    const userCtx = testEnv.authenticatedContext('ann-user');
    await assertSucceeds(getDoc(doc(userCtx.firestore(), 'announcements/a1')));
    await assertFails(
      setDoc(doc(userCtx.firestore(), 'announcements/a2'), { title: 'Spam' }),
    );
    const unauth = testEnv.unauthenticatedContext();
    await assertFails(getDoc(doc(unauth.firestore(), 'announcements/a1')));
  });
});

// ---------------------------------------------------------------------------
// Firestore: managedCredentials (admin token / credential renewal tracker)
// Admin-only on BOTH read and write — members must never see this collection.
// ---------------------------------------------------------------------------

describe('Firestore – managedCredentials (admin-only)', () => {
  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'managedCredentials/c1'), {
        name: 'Upload keystore',
        category: 'signing-keystore',
        expiresAt: null,
      });
    });
  });

  it('admins read and write; regular users and unauth are denied read and write', async () => {
    const adminFs = testEnv
      .authenticatedContext('cred-admin', { admin: true })
      .firestore();
    await assertSucceeds(getDoc(doc(adminFs, 'managedCredentials/c1')));
    await assertSucceeds(
      setDoc(doc(adminFs, 'managedCredentials/c2'), {
        name: 'Mapbox token',
        category: 'mapbox-token',
        expiresAt: null,
      }),
    );

    const userFs = testEnv.authenticatedContext('cred-user').firestore();
    await assertFails(getDoc(doc(userFs, 'managedCredentials/c1')));
    await assertFails(
      setDoc(doc(userFs, 'managedCredentials/c3'), { name: 'Sneaky' }),
    );

    const unauth = testEnv.unauthenticatedContext();
    await assertFails(getDoc(doc(unauth.firestore(), 'managedCredentials/c1')));
    await assertFails(
      setDoc(doc(unauth.firestore(), 'managedCredentials/c4'), { name: 'Sneaky unauth' }),
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
// Realtime Database: liveLocation (Phase 10)
// Backend-write-only; markers readable by entitled members; sessions
// owner-readable. (Retires the Phase 3 `liveLocations` plural scaffold.)
// ---------------------------------------------------------------------------

describe('Realtime Database – liveLocation (Phase 10)', () => {
  const SHARER = 'loc-sharer';
  const MEMBER = 'loc-member';
  const NON_MEMBER = 'loc-non-member';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await dbSet(dbRef(ctx.database(), `liveLocation/${SHARER}`), {
        session: {
          id: 's1',
          status: 'active',
          duration: '1h',
          startedAt: '2026-07-05T12:00:00.000Z',
          expiresAt: '2026-07-05T13:00:00.000Z',
          stoppedAt: null,
        },
        latest: {
          latitude: 59.33,
          longitude: 18.07,
          recordedAt: '2026-07-05T12:00:00.000Z',
          sessionId: 's1',
          displayName: 'Sharer',
        },
      });
    });
  });

  it('no client can write positions or sessions — not even the owner', async () => {
    const ctx = testEnv.authenticatedContext(SHARER, { activeMember: true });
    await assertFails(
      dbSet(dbRef(ctx.database(), `liveLocation/${SHARER}/latest`), {
        latitude: 1,
        longitude: 1,
        recordedAt: '2026-07-05T12:01:00.000Z',
      }),
    );
    await assertFails(
      dbSet(dbRef(ctx.database(), `liveLocation/${SHARER}/session/status`), 'stopped'),
    );
  });

  it('entitled members read markers; owners read their own session', async () => {
    const memberCtx = testEnv.authenticatedContext(MEMBER, { activeMember: true });
    await assertSucceeds(dbGet(dbRef(memberCtx.database(), `liveLocation/${SHARER}/latest`)));
    const ownerCtx = testEnv.authenticatedContext(SHARER);
    await assertSucceeds(dbGet(dbRef(ownerCtx.database(), `liveLocation/${SHARER}/session`)));
  });

  it('blocking hides the marker symmetrically (either party having blocked)', async () => {
    const BLOCKED_BY_SHARER = 'loc-blocked-by-sharer'; // SHARER blocked them
    const BLOCKER_OF_SHARER = 'loc-blocker-of-sharer'; // they blocked SHARER

    // Seed the RTDB mirror the way blocking-onBlockWrite maintains it.
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await dbSet(
        dbRef(ctx.database(), `liveLocationBlocks/${SHARER}/${BLOCKED_BY_SHARER}`),
        true,
      );
      await dbSet(
        dbRef(ctx.database(), `liveLocationBlocks/${BLOCKER_OF_SHARER}/${SHARER}`),
        true,
      );
    });

    // Target blocked viewer → denied.
    const blockedBySharer = testEnv.authenticatedContext(BLOCKED_BY_SHARER, {
      activeMember: true,
    });
    await assertFails(dbGet(dbRef(blockedBySharer.database(), `liveLocation/${SHARER}/latest`)));

    // Viewer blocked target → also denied (symmetric hide).
    const blockerOfSharer = testEnv.authenticatedContext(BLOCKER_OF_SHARER, {
      activeMember: true,
    });
    await assertFails(dbGet(dbRef(blockerOfSharer.database(), `liveLocation/${SHARER}/latest`)));

    // An unblocked entitled member is unaffected, and the owner still reads own.
    const other = testEnv.authenticatedContext('loc-unblocked-member', { activeMember: true });
    await assertSucceeds(dbGet(dbRef(other.database(), `liveLocation/${SHARER}/latest`)));
    const ownerCtx = testEnv.authenticatedContext(SHARER, { activeMember: true });
    await assertSucceeds(dbGet(dbRef(ownerCtx.database(), `liveLocation/${SHARER}/latest`)));
  });

  it('the liveLocationBlocks mirror is never client-readable or writable', async () => {
    const ctx = testEnv.authenticatedContext(MEMBER, { activeMember: true });
    await assertFails(dbGet(dbRef(ctx.database(), `liveLocationBlocks/${SHARER}/${MEMBER}`)));
    await assertFails(
      dbSet(dbRef(ctx.database(), `liveLocationBlocks/${MEMBER}/${SHARER}`), true),
    );
  });

  it('non-members, suspended members, and strangers cannot read', async () => {
    const nonMember = testEnv.authenticatedContext(NON_MEMBER);
    await assertFails(dbGet(dbRef(nonMember.database(), `liveLocation/${SHARER}/latest`)));
    const suspended = testEnv.authenticatedContext('loc-suspended', {
      activeMember: true,
      suspended: true,
    });
    await assertFails(dbGet(dbRef(suspended.database(), `liveLocation/${SHARER}/latest`)));
    const unauth = testEnv.unauthenticatedContext();
    await assertFails(dbGet(dbRef(unauth.database(), `liveLocation/${SHARER}/latest`)));
    // Sessions are private to their owner.
    const member = testEnv.authenticatedContext(MEMBER, { activeMember: true });
    await assertFails(dbGet(dbRef(member.database(), `liveLocation/${SHARER}/session`)));
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

  it('member owner can upload a ride route to their own path', async () => {
    const ctx = testEnv.authenticatedContext(OWNER, { activeMember: true });
    const data = new Uint8Array([0x1f, 0x8b, 0x08]); // gzip magic bytes
    const ref = storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`);
    await assertSucceeds(uploadBytes(ref, data, { contentType: 'application/octet-stream' }));
  });

  it('member owner can read their own ride route', async () => {
    const ctx = testEnv.authenticatedContext(OWNER, { activeMember: true });
    await assertSucceeds(
      getBytes(storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`)),
    );
  });

  it('non-member owner cannot read their route file (member-only route visuals)', async () => {
    // Legacy parity: routeOverview is withheld from non-members even for
    // their own drives; the Firestore metadata stays readable, the route
    // file does not (Phase 9d).
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      getBytes(storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`)),
    );
  });

  it('suspension overrides membership for route files', async () => {
    const ctx = testEnv.authenticatedContext(OWNER, { activeMember: true, suspended: true });
    await assertFails(
      getBytes(storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`)),
    );
  });

  it("another member cannot read someone else's ride route", async () => {
    const ctx = testEnv.authenticatedContext(OTHER, { activeMember: true });
    await assertFails(
      getBytes(storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`)),
    );
  });

  it("another member cannot upload to someone else's ride route path", async () => {
    const ctx = testEnv.authenticatedContext(OTHER, { activeMember: true });
    const data = new Uint8Array([0x00, 0x01, 0x02]);
    const ref = storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-spoof/route.bin`);
    await assertFails(uploadBytes(ref, data, { contentType: 'application/octet-stream' }));
  });

  it('member owner can upload a PNG map preview under the route prefix', async () => {
    const ctx = testEnv.authenticatedContext(OWNER, { activeMember: true });
    const data = new Uint8Array([0x89, 0x50, 0x4e, 0x47]); // PNG magic bytes
    const ref = storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/preview.png`);
    await assertSucceeds(uploadBytes(ref, data, { contentType: 'image/png' }));
  });

  it('only the canonical route.bin and preview.png filenames are writable', async () => {
    const ctx = testEnv.authenticatedContext(OWNER, { activeMember: true });
    const data = new Uint8Array([0x00, 0x01]);
    await assertFails(
      uploadBytes(storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/extra.bin`), data, {
        contentType: 'application/octet-stream',
      }),
    );
    // Wrong content type for the canonical name is rejected too.
    await assertFails(
      uploadBytes(storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`), data, {
        contentType: 'image/png',
      }),
    );
  });
});

// ---------------------------------------------------------------------------
// Cloud Storage: vehicle images
// ---------------------------------------------------------------------------

describe('Cloud Storage – vehicle images', () => {
  const OWNER = 'vehicle-img-owner';
  const OTHER = 'vehicle-img-other';

  it('member owner can upload a vehicle image under the vehicleId segment', async () => {
    const ctx = testEnv.authenticatedContext(OWNER, { activeMember: true });
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `vehicleImages/${OWNER}/v-1/car.jpg`);
    await assertSucceeds(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it('non-member owner cannot upload a vehicle image (garage is member-only)', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `vehicleImages/${OWNER}/v-1/car2.jpg`);
    await assertFails(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it('the old two-segment vehicle image path is closed', async () => {
    const ctx = testEnv.authenticatedContext(OWNER, { activeMember: true });
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `vehicleImages/${OWNER}/legacy-flat.jpg`);
    await assertFails(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it("another user cannot upload to someone else's vehicle image path", async () => {
    const ctx = testEnv.authenticatedContext(OTHER, { activeMember: true });
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `vehicleImages/${OWNER}/v-1/spoof.jpg`);
    await assertFails(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  it('authenticated user can read a vehicle image', async () => {
    const ctx = testEnv.authenticatedContext(OTHER);
    await assertSucceeds(
      getBytes(storageRef(ctx.storage(), `vehicleImages/${OWNER}/v-1/car.jpg`)),
    );
  });

  it('unauthenticated user cannot read a vehicle image', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getBytes(storageRef(ctx.storage(), `vehicleImages/${OWNER}/v-1/car.jpg`)));
  });
});

// ---------------------------------------------------------------------------
// Cloud Storage: ride preview images
// ---------------------------------------------------------------------------

describe('Cloud Storage – retired ridePreviewImages prefix (Phase 9d)', () => {
  const OWNER = 'ride-preview-owner';

  it('the old world-readable preview prefix is fully closed', async () => {
    // Ride previews reveal the private route shape; they now live under the
    // member-gated rideRoutes/{uid}/{rideId}/ prefix instead.
    const ownerCtx = testEnv.authenticatedContext(OWNER, { activeMember: true });
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    await assertFails(
      uploadBytes(
        storageRef(ownerCtx.storage(), `ridePreviewImages/${OWNER}/ride-abc/preview.jpg`),
        data,
        { contentType: 'image/jpeg' },
      ),
    );
    await assertFails(
      getBytes(storageRef(ownerCtx.storage(), `ridePreviewImages/${OWNER}/ride-abc/preview.jpg`)),
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
