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
import {
  collection,
  collectionGroup,
  deleteDoc,
  deleteField,
  doc,
  getDoc,
  getDocs,
  limit,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
} from 'firebase/firestore';
import { get as dbGet, ref as dbRef, set as dbSet } from 'firebase/database';
import { getBytes, ref as storageRef, uploadBytes } from 'firebase/storage';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const FIREBASE_DIR = resolve(__dirname, '../../../firebase');

// The Firestore instance a rules-unit-testing context hands back (compat type),
// so a helper can take one without pulling in the modular `Firestore` type.
type RulesFirestore = ReturnType<
  ReturnType<RulesTestEnvironment['unauthenticatedContext']>['firestore']
>;

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
      await setDoc(doc(ctx.firestore(), 'users', OWNER, 'badges', 'kronjagare_silver'), {
        badgeKey: 'kronjagare_silver',
        name: 'Kronjägare Silver',
        ladder: 'kronjagare',
        tier: 'silver',
        source: 'automatic',
      });
      await setDoc(doc(ctx.firestore(), 'badgeProgress', OWNER), {
        completedEventsAttended: 3,
      });
    });
  });

  // PUBLIC BADGES (2026-07). Earned badges are a showcase: any authenticated
  // user may read another member's wall, exactly like the users/{uid} profile
  // document and the /vehicles garage it is rendered beside.
  it('any authenticated member can read another member’s earned badges', async () => {
    const ownerCtx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      getDoc(doc(ownerCtx.firestore(), 'users', OWNER, 'badges', 'kronjagare_silver')),
    );

    const otherCtx = testEnv.authenticatedContext(OTHER);
    // Single award AND the whole wall — the member-profile screen lists the
    // subcollection, so the list read is the one that actually has to pass.
    await assertSucceeds(
      getDoc(doc(otherCtx.firestore(), 'users', OWNER, 'badges', 'kronjagare_silver')),
    );
    await assertSucceeds(getDocs(collection(otherCtx.firestore(), 'users', OWNER, 'badges')));

    // Not member-gated: the profile and garage beside it are not either, so a
    // non-subscriber sees a whole profile rather than a half-rendered one.
    const noClaimsCtx = testEnv.authenticatedContext('badge-viewer-no-claims');
    await assertSucceeds(getDocs(collection(noClaimsCtx.firestore(), 'users', OWNER, 'badges')));
  });

  it('signed-out visitors still cannot read badges', async () => {
    const anonCtx = testEnv.unauthenticatedContext();
    await assertFails(
      getDoc(doc(anonCtx.firestore(), 'users', OWNER, 'badges', 'kronjagare_silver')),
    );
    await assertFails(getDocs(collection(anonCtx.firestore(), 'users', OWNER, 'badges')));
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
      setDoc(doc(ctx.firestore(), 'users', OWNER, 'badges', 'kronjagare_platina'), {
        badgeKey: 'kronjagare_platina',
        ladder: 'kronjagare',
        tier: 'platina',
        source: 'automatic',
      }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER, 'badges', 'kronjagare_silver'), {
        tier: 'platina',
      }),
    );
    await assertFails(
      deleteDoc(doc(ctx.firestore(), 'users', OWNER, 'badges', 'garage_created')),
    );
    // A viewer who can now READ someone else's wall still cannot touch it.
    const otherCtx = testEnv.authenticatedContext(OTHER, { activeMember: true });
    await assertFails(
      setDoc(doc(otherCtx.firestore(), 'users', OWNER, 'badges', 'traffrav_guld'), {
        badgeKey: 'traffrav_guld',
        source: 'automatic',
      }),
    );
    await assertFails(
      deleteDoc(doc(otherCtx.firestore(), 'users', OWNER, 'badges', 'kronjagare_silver')),
    );
  });

  // THE PUBLIC READ IS PER-MEMBER, NOT A GLOBAL INDEX. Widening
  // users/{uid}/badges to isAuthenticated() lets a viewer open ONE member's
  // wall; it does not let anyone enumerate every member's awards, and several
  // doc claims (adminSummary.ts, contracts/functions/functions.json,
  // firestore.rules) depend on that. Asserted here rather than asserted in
  // prose: a collection group query is authorised ONLY by a rule written
  // against a recursive-wildcard path (match /{path=**}/badges/{badgeKey}); a
  // rule nested under /users/{userId} never applies to one. The single
  // recursive-wildcard rule in firestore.rules is the deny-all catch-all, so
  // adding a /{path=**}/badges/… grant later would break this test.
  it('no client can scan badges across members — collectionGroup is denied', async () => {
    // Negative control FIRST, so the denials below cannot pass for the wrong
    // reason: with rules off the very same query returns the seeded awards,
    // proving the collection group exists and the query is well-formed. What
    // follows is therefore a rules denial, not a broken query. (assertFails
    // itself only accepts permission-denied, so both halves are pinned.)
    await testEnv.withSecurityRulesDisabled(async (bypass) => {
      const all = await getDocs(collectionGroup(bypass.firestore(), 'badges'));
      expect(all.size).toBeGreaterThan(0);
    });

    const viewerCtx = testEnv.authenticatedContext(OTHER);
    await assertFails(getDocs(collectionGroup(viewerCtx.firestore(), 'badges')));

    // Not an entitlement loophole and not an admin loophole either: the Admin
    // SDK in badges.adminSummary bypasses rules, no client credential does.
    const memberCtx = testEnv.authenticatedContext('badge-scanner', { activeMember: true });
    await assertFails(getDocs(collectionGroup(memberCtx.firestore(), 'badges')));
    const adminCtx = testEnv.authenticatedContext('badge-scanner-admin', { admin: true });
    await assertFails(getDocs(collectionGroup(adminCtx.firestore(), 'badges')));

    // The per-member read the product actually needs still works, so this is a
    // real restriction on scanning and not a blanket denial.
    await assertSucceeds(getDocs(collection(viewerCtx.firestore(), 'users', OWNER, 'badges')));
  });

  it('badgeProgress counters are fully backend-only', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(getDoc(doc(ctx.firestore(), 'badgeProgress', OWNER)));
    await assertFails(
      setDoc(doc(ctx.firestore(), 'badgeProgress', OWNER), { completedEventsAttended: 999 }),
    );
  });

  // The trophies/telemetry split, asserted as one fact: making the wall public
  // must NOT have dragged the counters behind it into public view.
  it('publishes the trophies but not the telemetry behind them', async () => {
    const viewerCtx = testEnv.authenticatedContext('badge-telemetry-viewer', {
      activeMember: true,
    });
    // The wall: readable.
    await assertSucceeds(getDocs(collection(viewerCtx.firestore(), 'users', OWNER, 'badges')));
    // The counters it was earned against: denied to that same viewer…
    await assertFails(getDoc(doc(viewerCtx.firestore(), 'badgeProgress', OWNER)));
    // …and to the owner of the counters themselves.
    const ownerCtx = testEnv.authenticatedContext(OWNER);
    await assertFails(getDoc(doc(ownerCtx.firestore(), 'badgeProgress', OWNER)));
  });

  it('no client can forge a tiered-ladder counter or the sweep cursor', async () => {
    // The anti-abuse core of the tiered badges: every threshold is tested
    // against badgeProgress, so a client write here would mint any badge.
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(
      setDoc(doc(ctx.firestore(), 'badgeProgress', OWNER), {
        crownsCollected: 1000,
        lifetimeDistanceMeters: 10000000,
        bestDayStreak: 365,
        convoysLed: 50,
        vehiclesInGarage: 5,
      }),
    );
    const adminCtx = testEnv.authenticatedContext('badge-admin-2', { admin: true });
    await assertFails(getDoc(doc(adminCtx.firestore(), 'badgeProgress', OWNER)));
    await assertFails(getDoc(doc(ctx.firestore(), 'badgeSweepState', 'backlog')));
    await assertFails(setDoc(doc(ctx.firestore(), 'badgeSweepState', 'backlog'), { lastUid: 'x' }));
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

  it('owner reads wallet + entries; another member reads the PUBLIC balance doc but NOT the entries', async () => {
    const ownerFs = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(getDoc(doc(ownerFs, 'pointsLedger', OWNER)));
    await assertSucceeds(getDoc(doc(ownerFs, 'pointsLedger', OWNER, 'entries', 'e-1')));

    // The balance document is public profile info (2026-08): any authenticated
    // member may GET another member's balance BY ID for their profile — same
    // gate as the badge wall and garage rendered beside it.
    const otherFs = testEnv.authenticatedContext(OTHER).firestore();
    await assertSucceeds(getDoc(doc(otherFs, 'pointsLedger', OWNER)));
    // ...but only a single-doc get: LISTING/querying the pointsLedger collection
    // is DENIED, so the public balance can never be turned into a scrape of
    // everyone's balances. The rule grants `get`, not `read`.
    await assertFails(getDocs(collection(otherFs, 'pointsLedger')));
    // The append-only ledger ENTRIES stay owner-only — the profile shows the
    // balance, never the per-transaction activity statement behind it.
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
// Firestore: Kronjakt SHOP (Crown Hunt Shop) — perkInventory + config/perkCatalog
// ---------------------------------------------------------------------------

describe('Firestore – Kronjakt shop (perkInventory + config/perkCatalog)', () => {
  const OWNER = 'perk-rules-owner';
  const OTHER = 'perk-rules-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'perkInventory', OWNER), { shield: 2, boost: 1 });
      await setDoc(doc(ctx.firestore(), 'config', 'perkCatalog'), {
        version: 1,
        perks: [{ perkId: 'shield', kind: 'shield', name: 'Sköld', iconKey: 'x', costKp: 100 }],
      });
    });
  });

  it('owner reads their own inventory by id; nobody else can, and there is no list', async () => {
    const ownerFs = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(getDoc(doc(ownerFs, 'perkInventory', OWNER)));

    const otherFs = testEnv.authenticatedContext(OTHER).firestore();
    await assertFails(getDoc(doc(otherFs, 'perkInventory', OWNER)));
    // `get`, not `read`: a collection query is denied so inventories cannot be scraped.
    await assertFails(getDocs(collection(ownerFs, 'perkInventory')));
  });

  it('no client can write inventory — not even the owner', async () => {
    const ownerFs = testEnv.authenticatedContext(OWNER, { activeMember: true }).firestore();
    await assertFails(updateDoc(doc(ownerFs, 'perkInventory', OWNER), { shield: 999 }));
    await assertFails(setDoc(doc(ownerFs, 'perkInventory', OWNER), { boost: 999 }));
    await assertFails(deleteDoc(doc(ownerFs, 'perkInventory', OWNER)));
  });

  it('any authenticated non-suspended user reads the display catalog; suspended users and clients cannot write it', async () => {
    // isActiveMember() today = authenticated + not-suspended (the entitlement
    // term is disabled repo-wide), so a plain authenticated context reads it.
    const memberFs = testEnv.authenticatedContext('perk-catalog-reader').firestore();
    await assertSucceeds(getDoc(doc(memberFs, 'config', 'perkCatalog')));

    const suspendedFs = testEnv
      .authenticatedContext('perk-catalog-suspended', { suspended: true })
      .firestore();
    await assertFails(getDoc(doc(suspendedFs, 'config', 'perkCatalog')));

    await assertFails(
      setDoc(doc(memberFs, 'config', 'perkCatalog'), { version: 2, perks: [] }),
    );
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

  it('members read active points; drafts stay denied', async () => {
    const memberFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertSucceeds(getDoc(doc(memberFs, 'crownHuntPoints', 'p-active')));
    await assertFails(getDoc(doc(memberFs, 'crownHuntPoints', 'p-draft')));
  });

  it('non-members read active points while member gating is disabled', async () => {
    // Was: denied. isActiveMember() currently means signed-in + not suspended
    // (firebase/firestore.rules switch). Re-locking restores the denial.
    const freeFs = testEnv.authenticatedContext(OTHER).firestore();
    await assertSucceeds(getDoc(doc(freeFs, 'crownHuntPoints', 'p-active')));
    // The draft embargo is NOT part of the member gate and must still hold.
    await assertFails(getDoc(doc(freeFs, 'crownHuntPoints', 'p-draft')));
  });

  it('STILL denies a suspended user, entitled or not', async () => {
    const suspendedMember = testEnv
      .authenticatedContext('ch-susp-member', { activeMember: true, suspended: true })
      .firestore();
    await assertFails(getDoc(doc(suspendedMember, 'crownHuntPoints', 'p-active')));
    const suspendedFree = testEnv
      .authenticatedContext('ch-susp-free', { suspended: true })
      .firestore();
    await assertFails(getDoc(doc(suspendedFree, 'crownHuntPoints', 'p-active')));
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
// Firestore: crownSpawns – AUTO-SPAWNED crowns, member read (regression)
//
// The map layer reads live auto-spawned crowns with the EXACT query in
// FirebaseCrownSpawnRepository.listNearby:
//   where cellKey in [...] , where status == 'live', where expiresAt > DEVICE_now
// DEVICE_now is captured on the DEVICE *before* the request is issued, so it
// is always a timestamp in the PAST relative to the server's request.time
// (network latency + any clock skew guarantee DEVICE_now < request.time).
//
// A `list` rule is authorised against the QUERY's constraints, not the docs it
// returns (see the billboards test above). A rule term `resource.data.expiresAt
// > request.time` is therefore only satisfiable if the query's lower bound on
// expiresAt is provably >= request.time. The client's bound is DEVICE_now,
// which is < request.time, so the term can NOT be proven and the whole query
// is DENIED for non-admins — while admins slip through the `|| isAdmin()`
// bypass. Net effect in production: auto-spawned crowns are ADMIN-ONLY.
// ---------------------------------------------------------------------------

describe('Firestore – crownSpawns auto-spawn member read', () => {
  const CELL = '5933_1807';
  const LIVE = 'cs-spawn-live';
  const CLAIMED = 'cs-spawn-claimed';
  const EXPIRED = 'cs-spawn-expired';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const firestore = ctx.firestore();
      // Clearly live and unexpired: hours in the future.
      const expiresAt = Timestamp.fromMillis(Date.now() + 6 * 60 * 60 * 1000);
      await setDoc(doc(firestore, 'crownSpawns', LIVE), {
        cellKey: CELL,
        status: 'live',
        expiresAt,
        latitude: 59.33,
        longitude: 18.07,
        rewardPoints: 25,
      });
      // A crown that is NOT live (claimed) but still in-window — the status
      // gate, independent of the time clause, must keep hiding it.
      await setDoc(doc(firestore, 'crownSpawns', CLAIMED), {
        cellKey: CELL,
        status: 'claimed',
        expiresAt,
        latitude: 59.34,
        longitude: 18.08,
        rewardPoints: 25,
      });
      // Still status 'live' but EXPIRED — the sweep has not deleted it yet.
      // The `get` rule keeps a server-time expiry check precisely so a member
      // cannot fetch this stale crown by ID.
      await setDoc(doc(firestore, 'crownSpawns', EXPIRED), {
        cellKey: CELL,
        status: 'live',
        expiresAt: Timestamp.fromMillis(Date.now() - 60 * 60 * 1000),
        latitude: 59.35,
        longitude: 18.09,
        rewardPoints: 25,
      });
    });
  });

  // The exact client query. DEVICE_now is deliberately a couple of seconds in
  // the PAST to deterministically mirror "captured before the request".
  function clientLiveQuery(fs: RulesFirestore) {
    const deviceNow = Timestamp.fromMillis(Date.now() - 2000);
    return query(
      collection(fs, 'crownSpawns'),
      where('cellKey', 'in', [CELL]),
      where('status', '==', 'live'),
      where('expiresAt', '>', deviceNow),
    );
  }

  it('a non-admin member CAN read live auto-spawned crowns with the client query', async () => {
    // Negative control FIRST: with rules OFF the very same query is well-formed
    // and returns exactly the one live crown, so the assertion below is a rules
    // outcome and not a broken query.
    await testEnv.withSecurityRulesDisabled(async (bypass) => {
      const snap = await getDocs(clientLiveQuery(bypass.firestore()));
      expect(snap.size).toBe(1);
    });

    // The regression: a real signed-in member issuing the production query with
    // a device-captured (past) `now`. This is the read that was ADMIN-ONLY
    // before the fix because of the `expiresAt > request.time` rule term.
    const memberFs = testEnv.authenticatedContext('cs-member', { activeMember: true }).firestore();
    await assertSucceeds(getDocs(clientLiveQuery(memberFs)));
  });

  it('an admin can also read live auto-spawned crowns (bypass preserved)', async () => {
    const adminFs = testEnv.authenticatedContext('cs-admin', { admin: true }).firestore();
    await assertSucceeds(getDocs(clientLiveQuery(adminFs)));
  });

  it('status gating survives: a member cannot read a non-live spawn', async () => {
    // Same shape as the client query but asking for a claimed crown — the
    // status term (`resource.data.status == 'live'`) must still deny it, so
    // dropping the time clause did not open non-live crowns.
    const memberFs = testEnv.authenticatedContext('cs-member-2', { activeMember: true }).firestore();
    const deviceNow = Timestamp.fromMillis(Date.now() - 2000);
    await assertFails(
      getDocs(
        query(
          collection(memberFs, 'crownSpawns'),
          where('cellKey', 'in', [CELL]),
          where('status', '==', 'claimed'),
          where('expiresAt', '>', deviceNow),
        ),
      ),
    );
    // A direct get on the claimed crown is denied too (status gate on `get`).
    await assertFails(getDoc(doc(memberFs, 'crownSpawns', CLAIMED)));
  });

  it('get-side expiry survives: a member can get a live unexpired crown but NOT an expired one', async () => {
    // The `get` rule (unlike `list`) keeps `expiresAt > request.time`, because a
    // single-doc read is evaluated against the ACTUAL document so the server-time
    // check is verifiable. This stops a member fetching an expired-but-unswept
    // crown by ID, while the live one is still gettable.
    const memberFs = testEnv.authenticatedContext('cs-member-3', { activeMember: true }).firestore();
    await assertSucceeds(getDoc(doc(memberFs, 'crownSpawns', LIVE)));
    await assertFails(getDoc(doc(memberFs, 'crownSpawns', EXPIRED)));
    // Admin bypass still reaches the expired crown (the admin portal sees all).
    const adminFs = testEnv.authenticatedContext('cs-admin-2', { admin: true }).firestore();
    await assertSucceeds(getDoc(doc(adminFs, 'crownSpawns', EXPIRED)));
  });

  it('an unauthenticated client cannot read crownSpawns at all (get AND list)', async () => {
    const anonFs = testEnv.unauthenticatedContext().firestore();
    // Cover BOTH facets of the split read: a single-doc get…
    await assertFails(getDoc(doc(anonFs, 'crownSpawns', LIVE)));
    // …and a list, so a future rule change can't silently reopen `list` to
    // anonymous clients without this suite catching it.
    await assertFails(
      getDocs(query(collection(anonFs, 'crownSpawns'), where('status', '==', 'live'))),
    );
  });

  it('no client may write a crownSpawns document — not even an admin', async () => {
    const adminFs = testEnv.authenticatedContext('cs-admin-w', { admin: true }).firestore();
    await assertFails(
      setDoc(doc(adminFs, 'crownSpawns', 'forged'), {
        cellKey: CELL,
        status: 'live',
        expiresAt: Timestamp.fromMillis(Date.now() + 3_600_000),
        rewardPoints: 999,
      }),
    );
    await assertFails(updateDoc(doc(adminFs, 'crownSpawns', LIVE), { rewardPoints: 999 }));
  });
});

// ---------------------------------------------------------------------------
// Firestore: Kronjakt stats + leaderboard + seasons (read aggregates)
// ---------------------------------------------------------------------------

describe('Firestore – Kronjakt stats + leaderboard + seasons', () => {
  const MEMBER = 'chs-member';
  const OTHER = 'chs-other';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const firestore = ctx.firestore();
      await setDoc(doc(firestore, 'crownHuntLeaderboardEntries', `alltime__${MEMBER}`), {
        scope: 'alltime',
        uid: MEMBER,
        points: 120,
        crownsCollected: 6,
      });
      await setDoc(doc(firestore, 'crownHuntUserStats', MEMBER), {
        uid: MEMBER,
        seasonsWon: 2,
        collectionStreakCurrent: 3,
      });
      // A clearly-historical id so it never collides with a season the
      // rollover integration test computes from the real clock (both files
      // share one emulator Firestore).
      await setDoc(doc(firestore, 'crownHuntSeasons', '2000-01'), {
        seasonId: '2000-01',
        period: 'month',
        status: 'ended',
      });
      await setDoc(doc(firestore, 'crownHuntSpawnStats', 'alltime'), {
        scope: 'alltime',
        spawnedTotal: 10,
        collectedTotal: 4,
      });
      await setDoc(doc(firestore, 'crownHuntCellStats', '5933_1807'), {
        cellKey: '5933_1807',
        spawned: 3,
        collected: 1,
      });
    });
  });

  it('the leaderboard is public to any authenticated member', async () => {
    const memberFs = testEnv.authenticatedContext(OTHER, { activeMember: true }).firestore();
    await assertSucceeds(
      getDoc(doc(memberFs, 'crownHuntLeaderboardEntries', `alltime__${MEMBER}`)),
    );
  });

  it('seasons are member-readable so the app can show past champions', async () => {
    const memberFs = testEnv.authenticatedContext(OTHER, { activeMember: true }).firestore();
    await assertSucceeds(getDoc(doc(memberFs, 'crownHuntSeasons', '2000-01')));
  });

  it('a suspended member cannot read the leaderboard or seasons (suspension overrides)', async () => {
    const suspFs = testEnv
      .authenticatedContext('chs-susp', { activeMember: true, suspended: true })
      .firestore();
    await assertFails(getDoc(doc(suspFs, 'crownHuntLeaderboardEntries', `alltime__${MEMBER}`)));
    await assertFails(getDoc(doc(suspFs, 'crownHuntSeasons', '2000-01')));
  });

  it('personal stats are owner-only (and admin); another member is denied', async () => {
    const ownerFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertSucceeds(getDoc(doc(ownerFs, 'crownHuntUserStats', MEMBER)));
    const otherFs = testEnv.authenticatedContext(OTHER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(otherFs, 'crownHuntUserStats', MEMBER)));
    const adminFs = testEnv.authenticatedContext('chs-admin', { admin: true }).firestore();
    await assertSucceeds(getDoc(doc(adminFs, 'crownHuntUserStats', MEMBER)));
  });

  it('admin spawn/cell aggregates are admin-only (a member cannot read the heat-map)', async () => {
    const memberFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertFails(getDoc(doc(memberFs, 'crownHuntSpawnStats', 'alltime')));
    await assertFails(getDoc(doc(memberFs, 'crownHuntCellStats', '5933_1807')));
    const adminFs = testEnv.authenticatedContext('chs-admin', { admin: true }).firestore();
    await assertSucceeds(getDoc(doc(adminFs, 'crownHuntSpawnStats', 'alltime')));
    await assertSucceeds(getDoc(doc(adminFs, 'crownHuntCellStats', '5933_1807')));
  });

  it('no client may write any stat aggregate', async () => {
    const memberFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertFails(
      setDoc(doc(memberFs, 'crownHuntLeaderboardEntries', `alltime__${MEMBER}`), {
        scope: 'alltime',
        uid: MEMBER,
        points: 999999,
        crownsCollected: 999,
      }),
    );
    await assertFails(
      updateDoc(doc(memberFs, 'crownHuntUserStats', MEMBER), { seasonsWon: 99 }),
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

  it('offer detail is readable by members and (gating disabled) free users; the secret code is unreadable by everyone', async () => {
    const memberFs = testEnv.authenticatedContext(MEMBER, { activeMember: true }).firestore();
    await assertSucceeds(getDoc(doc(memberFs, 'offers', 'of-active', 'details', 'member')));
    // Was: denied. Re-locking the firestore.rules isActiveMember() switch
    // restores the member-only detail tier.
    const freeFs = testEnv.authenticatedContext(FREE).firestore();
    await assertSucceeds(getDoc(doc(freeFs, 'offers', 'of-active', 'details', 'member')));
    // The discount code tier is closed even to members and admin clients —
    // the unlock does NOT reach it (it is not member-gated, it is backend-only).
    await assertFails(getDoc(doc(memberFs, 'offers', 'of-active', 'secret', 'code')));
    const adminFs = testEnv.authenticatedContext('partner-admin', { admin: true }).firestore();
    await assertFails(getDoc(doc(adminFs, 'offers', 'of-active', 'secret', 'code')));
  });

  it('STILL denies offer detail to a suspended user', async () => {
    const suspendedFs = testEnv
      .authenticatedContext('offers-susp-detail', { activeMember: true, suspended: true })
      .firestore();
    await assertFails(getDoc(doc(suspendedFs, 'offers', 'of-active', 'details', 'member')));
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
    // Free users may now bookmark too (member gating disabled); shape rules
    // and the suspension guard are unaffected.
    const freeFs = testEnv.authenticatedContext(FREE).firestore();
    await assertSucceeds(
      setDoc(doc(freeFs, 'users', FREE, 'savedOffers', 'of-active'), {
        offerId: 'of-active',
        savedAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(freeFs, 'users', FREE, 'savedOffers', 'of-active'), {
        offerId: 'mismatched-id',
        savedAt: serverTimestamp(),
      }),
    );
    const suspendedFs = testEnv
      .authenticatedContext('offers-suspended', { activeMember: true, suspended: true })
      .firestore();
    await assertFails(
      setDoc(doc(suspendedFs, 'users', 'offers-suspended', 'savedOffers', 'of-active'), {
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
// Firestore: community growth metrics (admin-only, server-written)
// ---------------------------------------------------------------------------

describe('Firestore – metrics snapshots', () => {
  const DATE = '2026-07-31';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'metrics', DATE), {
        date: DATE,
        totalUsers: 42,
        brandDistribution: { volvo: 3, other: 1 },
      });
    });
  });

  it('an admin can read a metrics snapshot', async () => {
    const adminFs = testEnv.authenticatedContext('metrics-admin', { admin: true }).firestore();
    await assertSucceeds(getDoc(doc(adminFs, 'metrics', DATE)));
  });

  it('a non-admin member cannot read metrics', async () => {
    const memberFs = testEnv
      .authenticatedContext('metrics-member', { activeMember: true })
      .firestore();
    await assertFails(getDoc(doc(memberFs, 'metrics', DATE)));
  });

  it('an unauthenticated client cannot read metrics', async () => {
    const anonFs = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(anonFs, 'metrics', DATE)));
  });

  it('a suspended admin cannot read metrics (suspension overrides admin)', async () => {
    const suspendedFs = testEnv
      .authenticatedContext('metrics-suspended-admin', { admin: true, suspended: true })
      .firestore();
    await assertFails(getDoc(doc(suspendedFs, 'metrics', DATE)));
  });

  it('no client may write metrics — not even an admin', async () => {
    const adminFs = testEnv.authenticatedContext('metrics-admin-w', { admin: true }).firestore();
    await assertFails(setDoc(doc(adminFs, 'metrics', '2026-08-01'), { totalUsers: 1 }));
    await assertFails(updateDoc(doc(adminFs, 'metrics', DATE), { totalUsers: 999 }));
  });
});

// ---------------------------------------------------------------------------
// Firestore: finance recurring costs (admin-only, operator-entered)
// ---------------------------------------------------------------------------

describe('Firestore – finance recurring costs', () => {
  const COST_ID = 'fin-recurring-rules';

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'financeRecurringCosts', COST_ID), {
        label: 'Claude',
        description: 'Max plan',
        amount: 200,
        currency: 'USD',
        period: 'monthly',
        createdByUid: 'seed-admin',
      });
    });
  });

  it('an admin can read a recurring cost', async () => {
    const adminFs = testEnv.authenticatedContext('fin-rc-admin', { admin: true }).firestore();
    await assertSucceeds(getDoc(doc(adminFs, 'financeRecurringCosts', COST_ID)));
  });

  it('NO client may write a recurring cost — not even an admin (writes go via the audited callable)', async () => {
    const adminFs = testEnv.authenticatedContext('fin-rc-admin-w', { admin: true }).firestore();
    await assertFails(
      setDoc(doc(adminFs, 'financeRecurringCosts', 'fin-rc-admin-created'), {
        label: 'Domain',
        description: 'carcommunity.se',
        amount: 120,
        currency: 'SEK',
        period: 'yearly',
        createdByUid: 'fin-rc-admin-w',
      }),
    );
    await assertFails(updateDoc(doc(adminFs, 'financeRecurringCosts', COST_ID), { amount: 999 }));
    await assertFails(deleteDoc(doc(adminFs, 'financeRecurringCosts', COST_ID)));
  });

  it('a non-admin member cannot read or write recurring costs', async () => {
    const memberFs = testEnv
      .authenticatedContext('fin-rc-member', { activeMember: true })
      .firestore();
    await assertFails(getDoc(doc(memberFs, 'financeRecurringCosts', COST_ID)));
    await assertFails(
      setDoc(doc(memberFs, 'financeRecurringCosts', 'fin-rc-member-created'), { label: 'X', amount: 1 }),
    );
  });

  it('an unauthenticated client cannot read or write recurring costs', async () => {
    const anonFs = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(anonFs, 'financeRecurringCosts', COST_ID)));
    await assertFails(
      setDoc(doc(anonFs, 'financeRecurringCosts', 'fin-rc-anon-created'), { label: 'X', amount: 1 }),
    );
  });

  it('a suspended admin cannot read recurring costs (suspension overrides admin)', async () => {
    const suspendedFs = testEnv
      .authenticatedContext('fin-rc-suspended-admin', { admin: true, suspended: true })
      .firestore();
    await assertFails(getDoc(doc(suspendedFs, 'financeRecurringCosts', COST_ID)));
    await assertFails(
      updateDoc(doc(suspendedFs, 'financeRecurringCosts', COST_ID), { amount: 999 }),
    );
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
        mapVisible: true,
        createdAt: serverTimestamp(),
      });
      await setDoc(doc(ctx.firestore(), 'billboards', 'bb-draft'), {
        partnerCompanyId: 'co-1',
        headline: 'Utkast',
        message: 'Meddelande',
        status: 'draft',
        mapVisible: false,
        createdAt: serverTimestamp(),
      });
      // ACTIVE but outside its availability window — the server resolved that
      // into mapVisible=false (the lifecycle callables, or the scheduled
      // sweep). This is the fixture that proves the SCHEDULE is enforced by the
      // rule, not merely by client filtering.
      await setDoc(doc(ctx.firestore(), 'billboards', 'bb-out-of-window'), {
        partnerCompanyId: 'co-1',
        headline: 'Schemalagd skylt',
        message: 'Meddelande',
        status: 'active',
        mapVisible: false,
        createdAt: serverTimestamp(),
      });
      // A legacy document from before the field existed: absent, not false.
      // Must read as hidden until the sweep backfills it.
      await setDoc(doc(ctx.firestore(), 'billboards', 'bb-legacy'), {
        partnerCompanyId: 'co-1',
        headline: 'Gammal skylt',
        message: 'Meddelande',
        status: 'active',
        createdAt: serverTimestamp(),
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

  it('an active billboard OUTSIDE its window is unreadable, not merely undrawn', async () => {
    // "If it isn't activated it shouldn't be shown" — a client-side filter
    // would satisfy the map and nothing else. This asserts a member cannot
    // fetch the document at all, however they ask for it.
    const ctx = testEnv.authenticatedContext('bb-window-user');
    await assertFails(getDoc(doc(ctx.firestore(), 'billboards', 'bb-out-of-window')));
    await assertFails(getDoc(doc(ctx.firestore(), 'billboards', 'bb-legacy')));
    // Admins still see everything, for the admin portal.
    const adminCtx = testEnv.authenticatedContext('bb-window-admin', { admin: true });
    await assertSucceeds(getDoc(doc(adminCtx.firestore(), 'billboards', 'bb-out-of-window')));
  });

  it('the map layer query succeeds and returns only map-visible billboards', async () => {
    // This is the regression guard for the coupling between the read rule and
    // the map query. A `list` is evaluated against the QUERY's constraints, not
    // against the documents it returns, so the query must filter on every field
    // the rule reads — drop either `where` below and this fails with "Property
    // <x> is undefined on object", i.e. the whole billboard layer goes dark
    // rather than merely showing too much.
    //
    // It also asserts the collection can hold a draft, an out-of-window and a
    // legacy billboard without any of them reaching a member.
    const ctx = testEnv.authenticatedContext('bb-query-user');
    const snapshot = await assertSucceeds(
      getDocs(
        query(
          collection(ctx.firestore(), 'billboards'),
          where('status', '==', 'active'),
          where('mapVisible', '==', true),
          orderBy('createdAt', 'asc'),
          limit(150),
        ),
      ),
    );
    const ids = (snapshot as { docs: Array<{ id: string }> }).docs.map((d) => d.id);
    expect(ids).toContain('bb-active');
    expect(ids).not.toContain('bb-out-of-window');
    expect(ids).not.toContain('bb-legacy');
    expect(ids).not.toContain('bb-draft');
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
      updateDoc(doc(ctx.firestore(), 'userPrivate', OWNER), { licenceConfirmedAt: new Date() }),
    );
    // Legacy 18+ consent record: still unwritable by the owner, so nobody can
    // forge or erase what a pre-change member actually attested to.
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

  // Registrations hold the RAW FCM token (a hash-only registry cannot send),
  // so the collection is closed to clients entirely — the owner included. The
  // client gets its own token from the FCM SDK and never reads this back.
  it('nobody can read push token registrations, not even the owner', async () => {
    const owner = testEnv.authenticatedContext(OWNER);
    await assertFails(getDoc(doc(owner.firestore(), TOKEN)));
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
// Firestore: moderationReports is callable-only
// The Phase 9o client `create` path is REMOVED — chatchannels.reportMessage /
// dm.reportMessage / moderation.reportUser (and events.reportChatMessage for
// event chat) are the only writers. Admins read and may move `status` only.
// ---------------------------------------------------------------------------

describe('Firestore – moderationReports is callable-only', () => {
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

  it('denies a client create even in the previously-valid shape', async () => {
    // The exact document the Phase 9o rules used to accept. A client create is
    // now denied outright: it bypasses the report callables' eligibility check
    // on the surface being reported, their per-reporter rate limit, their
    // dedup, and the message snapshot.
    const ctx = testEnv.authenticatedContext(REPORTER);
    await assertFails(
      setDoc(doc(ctx.firestore(), 'moderationReports/valid-1'), {
        ...validReport,
        createdAt: serverTimestamp(),
      }),
    );
  });

  it('denies a client create aimed at an arbitrary target', async () => {
    const ctx = testEnv.authenticatedContext(REPORTER);
    await assertFails(
      setDoc(doc(ctx.firestore(), 'moderationReports/arbitrary'), {
        ...validReport,
        targetType: 'message',
        targetId: 'a-message-in-a-dm-they-are-not-party-to',
        createdAt: serverTimestamp(),
      }),
    );
  });

  it('denies an admin client create too (callables use the Admin SDK)', async () => {
    const admin = testEnv.authenticatedContext('mod-admin', { admin: true });
    await assertFails(
      setDoc(doc(admin.firestore(), 'moderationReports/admin-create'), {
        ...validReport,
        createdAt: serverTimestamp(),
      }),
    );
  });

  it('denies reads of the per-target report summary to everyone but admins', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'moderationUserSummaries/bad-actor'), {
        reportedUserId: 'bad-actor',
        reporterCount: 3,
        totalSubmissions: 5,
        lastReportedAt: serverTimestamp(),
      });
    });
    const reporter = testEnv.authenticatedContext(REPORTER);
    await assertFails(getDoc(doc(reporter.firestore(), 'moderationUserSummaries/bad-actor')));
    // Least of all the reported user themselves — the count is a moderation
    // signal, and surfacing it would be both a harassment vector and a way to
    // work out who reported whom.
    const target = testEnv.authenticatedContext('bad-actor');
    await assertFails(getDoc(doc(target.firestore(), 'moderationUserSummaries/bad-actor')));
    await assertFails(
      setDoc(doc(target.firestore(), 'moderationUserSummaries/bad-actor'), { reporterCount: 0 }),
    );
    const admin = testEnv.authenticatedContext('mod-admin', { admin: true });
    await assertSucceeds(getDoc(doc(admin.firestore(), 'moderationUserSummaries/bad-actor')));
    // Admin-READ-only: the aggregate is callable-maintained, not admin-editable.
    await assertFails(
      updateDoc(doc(admin.firestore(), 'moderationUserSummaries/bad-actor'), { reporterCount: 0 }),
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

  // ---- Social handles ----------------------------------------------------
  // THE POINT OF THESE TESTS: a profile edit is a DIRECT owner write — there is
  // no callable in front of it — so firebase/firestore.rules is the only thing
  // standing between a hostile client and a link on a public profile. The
  // Android SocialLinks parser is UX; these tests are the enforcement. Every
  // rejection below is one a client that simply skipped the parser could try.

  it('owner can set, keep and clear each social handle', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), {
        facebook: 'seb.mccayen',
        instagram: 'sebmccayen',
        youtube: 'SebMcCayen',
      }),
    );
    // Clearing REMOVES the field (FieldValue.delete()), which is the single
    // representation of "unset" the public profile keys off.
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), {
        facebook: deleteField(),
        instagram: deleteField(),
        youtube: deleteField(),
      }),
    );
  });

  it('owner cannot store an empty string instead of clearing', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { facebook: '' }));
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { instagram: '' }));
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { youtube: '' }));
  });

  it('owner cannot store a URL in a social handle — a foreign host included', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    for (const value of [
      'https://evil.com/x',
      'http://evil.com/sebmccayen',
      '//evil.com/x',
      'evil.com/x',
      'https://www.instagram.com/sebmccayen',
      'https://user:pass@instagram.com/sebmccayen',
      'https://instagram.com:8080/sebmccayen',
    ]) {
      await assertFails(
        updateDoc(doc(ctx.firestore(), 'users', OWNER), { instagram: value }),
      );
    }
  });

  it('owner cannot store a non-web scheme in a social handle', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    for (const value of [
      'javascript:alert(1)',
      'javascript://instagram.com/%0aalert(1)',
      'data:text/html,<script>alert(1)</script>',
      'intent://x#Intent;scheme=https;end',
      'file:///etc/passwd',
    ]) {
      await assertFails(
        updateDoc(doc(ctx.firestore(), 'users', OWNER), { instagram: value }),
      );
    }
  });

  it('owner cannot store control characters, whitespace or look-alikes', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    for (const value of [
      'seb mccayen',
      'seb\tmccayen',
      'seb\nmccayen',
      'seb\u0000mccayen',
      'seb\u007Fmccayen',
      'seb\u00A0mccayen',
      'seb\u200Bmccayen',
      'seb\u202Emccayen',
      's\u0435bmccayen',
      'seb@evil.com',
      '../../evil',
      '.sebmccayen',
    ]) {
      await assertFails(
        updateDoc(doc(ctx.firestore(), 'users', OWNER), { instagram: value }),
      );
    }
  });

  it('owner cannot store an uncanonicalised (upper-case) facebook or instagram handle', async () => {
    // Both platforms are case-insensitive and stored folded, so the rules admit
    // lower case only — an upper-case value means the client skipped
    // normalisation, and is rejected rather than silently repaired.
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { facebook: 'SebMcCayen' }));
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { instagram: 'SebMcCayen' }));
    // YouTube handles ARE displayed with case, so theirs is preserved.
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { youtube: 'SebMcCayen' }),
    );
  });

  it('owner cannot exceed the per-platform handle length', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { facebook: 'a'.repeat(50) }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { facebook: 'a'.repeat(51) }),
    );
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { instagram: 'a'.repeat(30) }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { instagram: 'a'.repeat(31) }),
    );
    await assertSucceeds(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { youtube: 'a'.repeat(30) }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { youtube: 'a'.repeat(31) }),
    );
    // Below YouTube's 3-character minimum.
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { youtube: 'ab' }));
  });

  it('owner cannot store a non-string social handle', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { instagram: 42 }));
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { instagram: ['sebmccayen'] }),
    );
    await assertFails(updateDoc(doc(ctx.firestore(), 'users', OWNER), { instagram: true }));
  });

  it('another member cannot write a social handle onto this profile', async () => {
    const ctx = testEnv.authenticatedContext('validation-stranger');
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'users', OWNER), { instagram: 'sebmccayen' }),
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
  const COMPLETED = 'event-completed';
  const CANCELLED = 'event-cancelled';
  const MEMBER = 'events-member';
  const NON_MEMBER = 'events-non-member';

  const memberCtx = () => testEnv.authenticatedContext(MEMBER, { activeMember: true });

  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const firestore = ctx.firestore();
      for (const [id, status] of [
        [PUBLISHED, 'published'],
        [DRAFT, 'draft'],
        [COMPLETED, 'completed'],
        [CANCELLED, 'cancelled'],
      ] as const) {
        await setDoc(doc(firestore, 'events', id), {
          title: 'Test event',
          approximateArea: 'Stockholm area',
          // Map location is PUBLIC teaser data now (2026-07): every signed-in
          // user sees event pins on the community map.
          locationName: 'Exact spot',
          latitude: 59.3,
          longitude: 18.0,
          status,
          rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
        });
        await setDoc(doc(firestore, 'events', id, 'details', 'private'), {
          description: 'Member-only long write-up',
          address: 'Garagevägen 1',
        });
      }
    });
  });

  it('any authenticated user can read a published event teaser', async () => {
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'events', PUBLISHED)));
  });

  it('a non-member reads the public map location (place name + coordinates) off the teaser', async () => {
    // Deliberate 2026-07 open-up: location moved onto the teaser so every
    // signed-in user can render the event as a map pin, with no member gate.
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    const snap = await getDoc(doc(ctx.firestore(), 'events', PUBLISHED));
    expect(snap.data()?.latitude).toBe(59.3);
    expect(snap.data()?.longitude).toBe(18.0);
    expect(snap.data()?.locationName).toBe('Exact spot');
  });

  it('any authenticated user can read a completed (ended) event teaser', async () => {
    // events-autoClose makes `completed` the normal end state of every event a
    // few hours after it finishes, so the teaser must stay readable — an ended
    // event that 403s would vanish from its own attendees and break any
    // archive/past list built on it.
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'events', COMPLETED)));
  });

  it('non-admin users cannot read cancelled events', async () => {
    // Only `published` and `completed` opened up — `cancelled` is still admin-only.
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    await assertFails(getDoc(doc(ctx.firestore(), 'events', CANCELLED)));
  });

  it('completing an event closes its member-gated detail', async () => {
    // The teaser (including the now-public map location) stays readable, but the
    // member-gated detail — the long description + precise street address — does
    // NOT: an ended event is closed, and details/private stays gated on
    // `published`.
    await assertFails(
      getDoc(doc(memberCtx().firestore(), 'events', COMPLETED, 'details', 'private')),
    );
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

  it('non-members CAN read the detail document while member gating is disabled', async () => {
    // Was: denied. Re-locking (firestore.rules isActiveMember) restores it.
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'events', PUBLISHED, 'details', 'private')));
  });

  it('STILL denies the detail document to suspended users', async () => {
    const ctx = testEnv.authenticatedContext('ev-suspended', {
      activeMember: true,
      suspended: true,
    });
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
    // The document must be otherwise VALID, or this passes on the shape check
    // instead of the draft embargo it is meant to pin.
    await assertFails(
      setDoc(doc(memberCtx().firestore(), 'events', DRAFT, 'rsvps', MEMBER), {
        status: 'going',
        updatedAt: serverTimestamp(),
      }),
    );
  });

  it('non-members CAN RSVP while member gating is disabled', async () => {
    const ctx = testEnv.authenticatedContext(NON_MEMBER);
    await assertSucceeds(
      setDoc(doc(ctx.firestore(), 'events', PUBLISHED, 'rsvps', NON_MEMBER), {
        status: 'going',
        updatedAt: serverTimestamp(),
      }),
    );
  });

  it('STILL blocks a suspended user from RSVPing', async () => {
    // Teeth: an otherwise-valid RSVP from an entitled but suspended user.
    const ctx = testEnv.authenticatedContext('rsvp-suspended', {
      activeMember: true,
      suspended: true,
    });
    await assertFails(
      setDoc(doc(ctx.firestore(), 'events', PUBLISHED, 'rsvps', 'rsvp-suspended'), {
        status: 'going',
        updatedAt: serverTimestamp(),
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

  it('non-members with a going RSVP CAN read chat while member gating is disabled', async () => {
    const ctx = testEnv.authenticatedContext(FREE_GOING);
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'events', EVENT, 'messages', 'msg-1')));
  });

  it('STILL denies chat to a suspended user with a going RSVP', async () => {
    const ctx = testEnv.authenticatedContext(FREE_GOING, { suspended: true });
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
    // Phase 9e: direct writes would bypass the per-user cap, the strict no-VIN
    // schema validation, the server-side registrationPlate normalisation, and
    // storage cleanup on delete. (The registrationPlate write below must still
    // fail: the field is deliberately PUBLIC to READ, but never client-writable.)
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
// Firestore: SERVER-error reporting + the global issue budget
//
// serverErrorReports holds the FULL error message/stack/context — the detail the
// auto-filed PUBLIC GitHub issue deliberately omits, because server error text
// embeds Firestore document paths (and therefore uids). It must be admin-read-only
// and completely client-unwritable: a forged report would file a public issue on
// our repo. githubIssueBudget is pure rate-limiter state and is backend-ONLY —
// not even an admin reads it, so no client can learn how much budget is left.
// ---------------------------------------------------------------------------

describe('Firestore – server-error reports, issue links + issue budget', () => {
  beforeAll(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'serverErrorReports', 'srv-1'), {
        source: 'account.purgeDeleted',
        errorName: 'FirebaseFirestoreError',
        errorCode: 'not-found',
        message: 'no entity to update: document users/some-uid/vehicles/v1',
        fingerprint: 'srv-fp-1',
      });
      await setDoc(doc(ctx.firestore(), 'serverErrorIssueLinks', 'srv-fp-1'), {
        fingerprint: 'srv-fp-1',
        source: 'account.purgeDeleted',
        status: 'created',
        count: 12,
      });
      await setDoc(doc(ctx.firestore(), 'githubIssueBudget', '2026073003'), {
        bucketId: '2026073003',
        count: 3,
      });
    });
  });

  it('admin can read server-error reports and issue links', async () => {
    const ctx = testEnv.authenticatedContext('admin-uid', { admin: true });
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'serverErrorReports', 'srv-1')));
    await assertSucceeds(getDoc(doc(ctx.firestore(), 'serverErrorIssueLinks', 'srv-fp-1')));
  });

  it('a regular user cannot read server-error reports or issue links', async () => {
    const ctx = testEnv.authenticatedContext('member-uid');
    await assertFails(getDoc(doc(ctx.firestore(), 'serverErrorReports', 'srv-1')));
    await assertFails(getDoc(doc(ctx.firestore(), 'serverErrorIssueLinks', 'srv-fp-1')));
  });

  it('an unauthenticated client cannot read server-error reports or issue links', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getDoc(doc(ctx.firestore(), 'serverErrorReports', 'srv-1')));
    await assertFails(getDoc(doc(ctx.firestore(), 'serverErrorIssueLinks', 'srv-fp-1')));
  });

  it('no client (not even admin) can write server-error reports or issue links', async () => {
    const ctx = testEnv.authenticatedContext('admin-uid', { admin: true });
    await assertFails(
      setDoc(doc(ctx.firestore(), 'serverErrorReports', 'forged'), {
        source: 'account.purgeDeleted',
        errorName: 'TypeError',
      }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'serverErrorReports', 'srv-1'), { message: 'tampered' }),
    );
    await assertFails(deleteDoc(doc(ctx.firestore(), 'serverErrorReports', 'srv-1')));
    // Pre-claiming a fingerprint from a client would silence a real failure.
    await assertFails(
      setDoc(doc(ctx.firestore(), 'serverErrorIssueLinks', 'forged'), { status: 'created' }),
    );
    await assertFails(
      updateDoc(doc(ctx.firestore(), 'serverErrorIssueLinks', 'srv-fp-1'), { count: 0 }),
    );
    await assertFails(deleteDoc(doc(ctx.firestore(), 'serverErrorIssueLinks', 'srv-fp-1')));
  });

  it('the global issue budget is fully backend-only — no client reads or writes, admin included', async () => {
    const contexts = [
      testEnv.authenticatedContext('admin-uid', { admin: true }),
      testEnv.authenticatedContext('member-uid'),
      testEnv.unauthenticatedContext(),
    ];
    for (const ctx of contexts) {
      await assertFails(getDoc(doc(ctx.firestore(), 'githubIssueBudget', '2026073003')));
      await assertFails(
        setDoc(doc(ctx.firestore(), 'githubIssueBudget', '2026073003'), { count: 0 }),
      );
      await assertFails(
        updateDoc(doc(ctx.firestore(), 'githubIssueBudget', '2026073003'), { count: 0 }),
      );
      await assertFails(deleteDoc(doc(ctx.firestore(), 'githubIssueBudget', '2026073003')));
    }
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

  it('suspended user retains access to support paths (settings)', async () => {
    const ctx = testEnv.authenticatedContext(SUSPENDED, { suspended: true });
    // Filing a report is no longer a direct write for ANYONE (the collection is
    // callable-only now), so the support path a suspended user keeps here is
    // the settings write; report eligibility is decided in the callables'
    // actor gates, not in these rules.
    await assertFails(
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

  it('non-members CAN read while member gating is disabled; nobody writes directly', async () => {
    const freeCtx = testEnv.authenticatedContext('gd-free');
    await assertSucceeds(
      getDoc(doc(freeCtx.firestore(), `events/${EVENT}/groupDriveParticipants/${PARTICIPANT}`)),
    );
    // Suspension still closes the read (teeth).
    const suspendedCtx = testEnv.authenticatedContext('gd-suspended', {
      activeMember: true,
      suspended: true,
    });
    await assertFails(
      getDoc(doc(suspendedCtx.firestore(), `events/${EVENT}/groupDriveParticipants/${PARTICIPANT}`)),
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
// Backend-write-only. The OWNER always reads their own marker (sharing is
// free); reading ANOTHER user's marker requires an entitled (activeMember,
// non-suspended, unblocked) viewer. Sessions are owner-readable.
// (Retires the Phase 3 `liveLocations` plural scaffold.)
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

  it('the owner reads their OWN marker without a subscription (sharing is free)', async () => {
    // No activeMember claim: viewing your own position is free.
    const ownerCtx = testEnv.authenticatedContext(SHARER);
    await assertSucceeds(dbGet(dbRef(ownerCtx.database(), `liveLocation/${SHARER}/latest`)));
    // A suspended owner can still read their own marker (privacy/own-data).
    const suspendedOwner = testEnv.authenticatedContext(SHARER, { suspended: true });
    await assertSucceeds(dbGet(dbRef(suspendedOwner.database(), `liveLocation/${SHARER}/latest`)));
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

  it('non-members CAN read while member gating is disabled', async () => {
    // Was: denied. The activeMember term was removed from the
    // liveLocation/$uid/latest read rule (firebase/database.rules.json switch).
    const nonMember = testEnv.authenticatedContext(NON_MEMBER);
    await assertSucceeds(dbGet(dbRef(nonMember.database(), `liveLocation/${SHARER}/latest`)));
  });

  it('STILL denies suspended users, strangers to a session, and the unauthenticated', async () => {
    // Teeth: the RTDB read rule bundles the suspension guard and BOTH
    // liveLocationBlocks direction checks into one expression alongside the
    // entitlement term. Removing the entitlement term must not take these out.
    const suspended = testEnv.authenticatedContext('loc-suspended', {
      activeMember: true,
      suspended: true,
    });
    await assertFails(dbGet(dbRef(suspended.database(), `liveLocation/${SHARER}/latest`)));
    const suspendedFree = testEnv.authenticatedContext('loc-suspended-free', { suspended: true });
    await assertFails(dbGet(dbRef(suspendedFree.database(), `liveLocation/${SHARER}/latest`)));
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

  it('non-member owner CAN read their route file while member gating is disabled', async () => {
    // Was: denied (legacy parity — routeOverview withheld from non-members even
    // for their own drives). Re-locking the storage.rules isActiveMember()
    // switch restores that denial.
    const ctx = testEnv.authenticatedContext(OWNER);
    await assertSucceeds(
      getBytes(storageRef(ctx.storage(), `rideRoutes/${OWNER}/ride-xyz/route.bin`)),
    );
  });

  it('STILL denies a suspended non-member owner their route file', async () => {
    // Teeth: storage.rules isActiveMember() bundles isNotSuspended(); the
    // unlock must not drop it (the PR #428 vehicleImages regression).
    const ctx = testEnv.authenticatedContext(OWNER, { suspended: true });
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

  // Managing your own garage is no longer member-gated, so a signed-in owner
  // with no activeMember entitlement may upload a photo for their own vehicle.
  it('non-member owner can upload a vehicle image (garage is no longer member-only)', async () => {
    const ctx = testEnv.authenticatedContext(OWNER);
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `vehicleImages/${OWNER}/v-1/car2.jpg`);
    await assertSucceeds(uploadBytes(ref, data, { contentType: 'image/jpeg' }));
  });

  // Suspension still overrides access: it used to be enforced via
  // isActiveMember(), and is now kept explicitly by isNotSuspended() so the
  // rule mirrors the backend requireActiveActor guard.
  it('suspended owner cannot upload a vehicle image', async () => {
    const ctx = testEnv.authenticatedContext(OWNER, { suspended: true });
    const data = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const ref = storageRef(ctx.storage(), `vehicleImages/${OWNER}/v-1/car3.jpg`);
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
