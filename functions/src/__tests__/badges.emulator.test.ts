/**
 * Badges emulator integration tests (Phase 9f).
 *
 * Exercises the badge hooks and callable end-to-end:
 * - garage-addVehicle → garage_created award (once)
 * - a VERIFIED check-in (onAttendanceVerified) → attendance credit + first_event
 *   award; an RSVP + completion credits nothing
 * - badges-awardHelpfulMember (admin gate, reason, audit, idempotency)
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
import { FieldValue, getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { BADGE_CATALOG_ORDER } from '../badges/badge-core';
import { runBadgeBacklogSweep } from '../badges/scheduled';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'badges-emulator-tests');
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

const badgeDoc = (uid: string, key: string) =>
  adminDb.collection('users').doc(uid).collection('badges').doc(key);

let adminUser: TestUser;
let member: TestUser;

const futureStart = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'badges-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('badges-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  member = await createProvisionedUser('badges-member');
  await adminDb.collection('users').doc(member.uid).set({ activeMember: true }, { merge: true });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('garage_created badge', () => {
  it('is awarded on first vehicle and never duplicated', async () => {
    await signInAs(member);
    await call('garage-addVehicle', {
      make: 'Saab',
      model: '900 Turbo',
      modelYear: 1987,
      powertrain: 'petrol',
    });

    const first = await pollUntil(async () => {
      const snap = await badgeDoc(member.uid, 'garage_created').get();
      return snap.exists ? snap.data() : undefined;
    });
    expect(first!.source).toBe('automatic');
    expect(first!.name).toBe('Garageprofil skapad');
    const firstAwardedAt = first!.awardedAt;

    await call('garage-addVehicle', {
      make: 'Saab',
      model: '9000 Aero',
      modelYear: 1994,
      powertrain: 'petrol',
    });
    const after = (await badgeDoc(member.uid, 'garage_created').get()).data()!;
    expect(after.awardedAt).toEqual(firstAwardedAt);
  });
});

describe('event attendance badges', () => {
  /**
   * Writes the eventAttendance/{eventId}__{uid} document with `verified: true`
   * — exactly the false→true edge events.checkIn produces once its server-side
   * geofence + dwell evaluation passes. That edge fires
   * points-onAttendanceVerified, which is what credits the attendance badge.
   */
  async function verifyCheckIn(eventId: string, uid: string): Promise<void> {
    await adminDb
      .collection('eventAttendance')
      .doc(`${eventId}__${uid}`)
      .set(
        {
          eventId,
          userId: uid,
          verified: true,
          verifiedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
  }

  it('credits a VERIFIED check-in — not an RSVP — toward first_event', async () => {
    await signInAs(adminUser);
    const eventId = (
      (
        await call('events-create', {
          title: 'Badge test event',
          startsAt: futureStart,
          approximateArea: 'Test area',
        })
      ).data as { eventId: string }
    ).eventId;
    await call('events-publish', { eventId });

    // A going-RSVP plus completion must NOT credit the badge any more: the
    // attendance badge counts proof-of-presence, not a tapped "going".
    await adminDb
      .collection('events')
      .doc(eventId)
      .collection('rsvps')
      .doc(member.uid)
      .set({ status: 'going', updatedAt: new Date() });
    await call('events-complete', { eventId });
    // Give any (now-removed) completion-time credit a chance to land, then assert
    // it did not: no attendance counter, no first_event badge. The badgeProgress
    // DOCUMENT may exist for OTHER reasons (e.g. this member added a vehicle in
    // an earlier test → vehiclesInGarage), so the assertion is on the attendance
    // FIELD, not the document's existence.
    await new Promise((resolve) => setTimeout(resolve, 2_000));
    expect(
      (await adminDb.collection('badgeProgress').doc(member.uid).get()).data()
        ?.completedEventsAttended,
    ).toBeUndefined();
    expect((await badgeDoc(member.uid, 'first_event').get()).exists).toBe(false);

    // Now the member actually checks in and it verifies — THIS is what credits.
    await verifyCheckIn(eventId, member.uid);

    const progress = await pollUntil(async () => {
      const value = (await adminDb.collection('badgeProgress').doc(member.uid).get()).data()
        ?.completedEventsAttended;
      return typeof value === 'number' ? value : undefined;
    });
    expect(progress).toBe(1);

    const badge = await pollUntil(async () => {
      const snap = await badgeDoc(member.uid, 'first_event').get();
      return snap.exists ? snap.data()! : undefined;
    });
    expect(badge.badgeKey).toBe('first_event');
    expect(badge.source).toBe('automatic');

    // five_events requires five verified check-ins — not awarded after one.
    expect((await badgeDoc(member.uid, 'five_events').get()).exists).toBe(false);

    // Idempotent per (uid, event): re-writing the already-verified record does
    // not double-count. The onAttendanceVerified edge is false→true only, and
    // the attendanceCredits marker guards any trigger redelivery besides.
    await verifyCheckIn(eventId, member.uid);
    await new Promise((resolve) => setTimeout(resolve, 2_000));
    expect(
      (await adminDb.collection('badgeProgress').doc(member.uid).get()).data()
        ?.completedEventsAttended,
    ).toBe(1);
    // The one-shot marker exists for this event.
    expect(
      (
        await adminDb
          .collection('badgeProgress')
          .doc(member.uid)
          .collection('attendanceCredits')
          .doc(eventId)
          .get()
      ).exists,
    ).toBe(true);
  });
});

describe('badges-awardHelpfulMember', () => {
  it('rejects non-admin callers and missing reasons', async () => {
    await signInAs(member);
    expect(
      await callableErrorCode(
        call('badges-awardHelpfulMember', { targetUid: member.uid, reason: 'Self award' }),
      ),
    ).toBe('functions/permission-denied');

    await signInAs(adminUser);
    expect(
      await callableErrorCode(call('badges-awardHelpfulMember', { targetUid: member.uid })),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(
        call('badges-awardHelpfulMember', { targetUid: 'missing-user', reason: 'x' }),
      ),
    ).toBe('functions/not-found');
  });

  it('awards once with an audit record; repeats are idempotent without new audit entries', async () => {
    await signInAs(adminUser);
    const first = (
      await call('badges-awardHelpfulMember', {
        targetUid: member.uid,
        reason: 'Organiserade träffen',
      })
    ).data as { alreadyAwarded: boolean };
    expect(first.alreadyAwarded).toBe(false);

    const badge = (await badgeDoc(member.uid, 'helpful_member').get()).data()!;
    expect(badge.source).toBe('admin_manual');
    // The awarding admin's UID must never reach this publicly-readable
    // document; adminAuditEvents carries it instead.
    expect(badge).not.toHaveProperty('awardedByUserId');

    const second = (
      await call('badges-awardHelpfulMember', {
        targetUid: member.uid,
        reason: 'Igen',
      })
    ).data as { alreadyAwarded: boolean };
    expect(second.alreadyAwarded).toBe(true);

    const audits = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'badge.awardHelpfulMember')
      .where('targetId', '==', member.uid)
      .get();
    expect(audits.size).toBe(1);
    // size asserted === 1 above, so docs[0] is present.
    expect(audits.docs[0]!.data().reason).toBe('Organiserade träffen');
  });
});

describe('badges-adminSummary', () => {
  it('rejects non-admin callers', async () => {
    await signInAs(member);
    expect(await callableErrorCode(call('badges-adminSummary', {}))).toBe(
      'functions/permission-denied',
    );
  });

  it('returns aggregate per-key counts for admins (no user data)', async () => {
    await signInAs(adminUser);
    // Guarantee at least one helpful_member award exists (idempotent).
    await call('badges-awardHelpfulMember', { targetUid: member.uid, reason: 'Summary fixture' });

    const result = await call('badges-adminSummary', {});
    const summary = (
      result.data as {
        summary: Array<{ key: string; name: string; totalCount: number; recentCount: number }>;
      }
    ).summary;

    // Catalog order: the five historic milestones first, then the 22 ladder
    // rungs bottom-to-top (badge-core.ts::BADGE_CATALOG_ORDER). 22 not 24:
    // Trogen and Samlare each stop at Guld.
    expect(summary.map((s) => s.key)).toEqual([...BADGE_CATALOG_ORDER]);
    expect(summary.slice(0, 5).map((s) => s.key)).toEqual([
      'first_event',
      'five_events',
      'helpful_member',
      'early_member',
      'garage_created',
    ]);
    for (const item of summary) {
      expect(typeof item.name).toBe('string');
      expect(item.name.length).toBeGreaterThan(0);
      expect(item.totalCount).toBeGreaterThanOrEqual(item.recentCount);
      expect(item.recentCount).toBeGreaterThanOrEqual(0);
    }
    const helpful = summary.find((s) => s.key === 'helpful_member')!;
    expect(helpful.totalCount).toBeGreaterThanOrEqual(1);
    expect(helpful.recentCount).toBeGreaterThanOrEqual(1);
    // Aggregate only — never a per-user leaderboard.
    expect(result.data).not.toHaveProperty('users');
  });
});

/**
 * Tiered ladders — the trigger chain end to end.
 *
 * These exercise what the pure unit tests (badge-tiers.test.ts) cannot: that a
 * counter write really does reach the evaluator through
 * badges-onBadgeProgressWritten, that the Kronpoäng credit lands in the ledger
 * exactly once, and that a risk_review Kronjakt claim is excluded by the
 * badges-onCrownClaimWritten trigger rather than only by the pure guard.
 */
describe('tiered badge ladders', () => {
  const ledgerEntries = (uid: string) =>
    adminDb.collection('pointsLedger').doc(uid).collection('entries');

  it('awards every tier crossed in one jump, credits points once, and is idempotent', async () => {
    const climber = await createProvisionedUser('badges-tier-climb');
    await adminDb.collection('users').doc(climber.uid).set({ activeMember: true }, { merge: true });

    // A counter write is the ONLY input — no callable exists for tiers.
    await adminDb
      .collection('badgeProgress')
      .doc(climber.uid)
      .set({ crownsCollected: 300, updatedAt: new Date() }, { merge: true });

    // 300 crowns crosses Brons (10), Silver (50) AND Guld (250) at once.
    await pollUntil(async () => {
      const snap = await badgeDoc(climber.uid, 'kronjagare_guld').get();
      return snap.exists ? snap.data() : undefined;
    });
    for (const key of ['kronjagare_brons', 'kronjagare_silver', 'kronjagare_guld']) {
      const snap = await badgeDoc(climber.uid, key).get();
      expect(snap.exists).toBe(true);
      expect(snap.data()!.ladder).toBe('kronjagare');
      expect(snap.data()!.source).toBe('automatic');
    }
    // Platina (1000) is not reached.
    expect((await badgeDoc(climber.uid, 'kronjagare_platina').get()).exists).toBe(false);

    // 25 + 75 + 200 Kronpoäng, on deterministic idempotency keys.
    await pollUntil(async () => {
      const ledger = await adminDb.collection('pointsLedger').doc(climber.uid).get();
      return ledger.data()?.balance === 300 ? true : undefined;
    });
    for (const key of ['kronjagare_brons', 'kronjagare_silver', 'kronjagare_guld']) {
      const entry = await ledgerEntries(climber.uid).doc(`badge_award_${key}`).get();
      expect(entry.exists).toBe(true);
      expect(entry.data()!.source).toBe('badge');
    }
    const entriesAfterFirst = (await ledgerEntries(climber.uid).get()).size;
    expect(entriesAfterFirst).toBe(3);

    // Re-evaluation must be safe: touch the counter document again (new
    // updatedAt guarantees a real write, so the trigger definitely re-fires)
    // and confirm nothing is awarded or credited twice.
    const brons = (await badgeDoc(climber.uid, 'kronjagare_brons').get()).data()!.awardedAt;
    await adminDb
      .collection('badgeProgress')
      .doc(climber.uid)
      .set({ crownsCollected: 300, updatedAt: new Date() }, { merge: true });
    await new Promise((resolve) => setTimeout(resolve, 3_000));
    expect((await badgeDoc(climber.uid, 'kronjagare_brons').get()).data()!.awardedAt).toEqual(
      brons,
    );
    expect((await ledgerEntries(climber.uid).get()).size).toBe(entriesAfterFirst);
    expect((await adminDb.collection('pointsLedger').doc(climber.uid).get()).data()!.balance).toBe(
      300,
    );
  }, 120_000);

  it('never counts a risk_review Kronjakt claim toward Kronjägare', async () => {
    const hunter = await createProvisionedUser('badges-tier-risk');
    await adminDb.collection('users').doc(hunter.uid).set({ activeMember: true }, { merge: true });

    // One claim the anti-fraud path sent to review, plus exactly ten awarded.
    await adminDb.collection('crownHuntClaims').doc(`${hunter.uid}__risk`).set({
      userId: hunter.uid,
      pointId: 'p-risk',
      result: 'risk_review',
      claimedAt: new Date(),
    });
    for (let index = 0; index < 10; index += 1) {
      await adminDb.collection('crownHuntClaims').doc(`${hunter.uid}__ok${index}`).set({
        userId: hunter.uid,
        pointId: `p-${index}`,
        result: 'awarded',
        claimedAt: new Date(),
      });
    }

    await pollUntil(async () => {
      const snap = await badgeDoc(hunter.uid, 'kronjagare_brons').get();
      return snap.exists ? true : undefined;
    });
    // Exactly ten — the risk_review claim contributed nothing.
    await pollUntil(async () => {
      const progress = await adminDb.collection('badgeProgress').doc(hunter.uid).get();
      return progress.data()?.crownsCollected === 10 ? true : undefined;
    });
    expect(
      (await adminDb.collection('badgeProgress').doc(hunter.uid).get()).data()!.crownsCollected,
    ).toBe(10);
  }, 120_000);

  /**
   * The live-hunt regression (issue #793): auto-spawn crowns land in
   * `crownSpawnClaims`, a DIFFERENT collection from the hand-placed
   * `crownHuntClaims`. Before badges-onSpawnClaimWritten existed nothing counted
   * them, so a member collecting only auto-spawn crowns stayed locked at zero.
   * This pins that an awarded spawn claim now reaches Kronjägare, and that a
   * risk_review spawn claim is still excluded.
   */
  it('counts an auto-spawn Kronjakt crown toward Kronjägare (issue #793)', async () => {
    const hunter = await createProvisionedUser('badges-spawn-crown');
    await adminDb.collection('users').doc(hunter.uid).set({ activeMember: true }, { merge: true });

    // One spawn claim the anti-fraud path sent to review, plus exactly ten
    // awarded — the same shape claimSpawn writes (userId + result).
    await adminDb.collection('crownSpawnClaims').doc(`${hunter.uid}__spawn_risk`).set({
      userId: hunter.uid,
      spawnId: 's-risk',
      result: 'risk_review',
      createdAt: new Date(),
    });
    for (let index = 0; index < 10; index += 1) {
      await adminDb.collection('crownSpawnClaims').doc(`${hunter.uid}__spawn_ok${index}`).set({
        userId: hunter.uid,
        spawnId: `s-${index}`,
        result: 'awarded',
        createdAt: new Date(),
      });
    }

    await pollUntil(async () => {
      const snap = await badgeDoc(hunter.uid, 'kronjagare_brons').get();
      return snap.exists ? true : undefined;
    });
    // Exactly ten — the risk_review spawn claim contributed nothing.
    await pollUntil(async () => {
      const progress = await adminDb.collection('badgeProgress').doc(hunter.uid).get();
      return progress.data()?.crownsCollected === 10 ? true : undefined;
    });
    expect(
      (await adminDb.collection('badgeProgress').doc(hunter.uid).get()).data()!.crownsCollected,
    ).toBe(10);
  }, 120_000);

  /**
   * The self-heal for members who predate the #793 fix: the 6-hour sweep
   * reconciles `crownsCollected` UP to the authoritative all-time Kronjakt
   * leaderboard, which has always counted both hand-placed and auto-spawn
   * collections. It is a running-MAX raise, so once the counter already agrees
   * with the leaderboard the sweep is a no-op and cannot double-count against
   * the live increments.
   */
  it('reconciles crownsCollected from the all-time leaderboard without double-counting', async () => {
    const collector = await createProvisionedUser('badges-spawn-reconcile');
    await adminDb
      .collection('users')
      .doc(collector.uid)
      .set({ activeMember: true }, { merge: true });

    // A member who collected 12 crowns before the fix: the leaderboard recorded
    // them, but the badge counter never did. Their badgeProgress document
    // exists from other activity (a login streak) — which is what the sweep
    // pages over — but has no crownsCollected field yet. No Kronjägare badge.
    await adminDb
      .collection('crownHuntLeaderboardEntries')
      .doc(`alltime__${collector.uid}`)
      .set({ scope: 'alltime', uid: collector.uid, crownsCollected: 12, points: 120 });
    await adminDb
      .collection('badgeProgress')
      .doc(collector.uid)
      .set({ currentDayStreak: 1, bestDayStreak: 1, updatedAt: new Date() }, { merge: true });
    expect(
      (await adminDb.collection('badgeProgress').doc(collector.uid).get()).data()?.crownsCollected,
    ).toBeUndefined();

    // Run the sweep until it wraps, so this member is visited wherever the
    // shared cursor happened to be.
    await adminDb.collection('badgeSweepState').doc('backlog').delete();
    for (let pass = 0; pass < 10; pass += 1) {
      const { wrapped } = await runBadgeBacklogSweep();
      if (wrapped) break;
    }

    // Counter raised to 12 → Brons (10) awarded, Silver (50) not.
    await pollUntil(async () => {
      const snap = await badgeDoc(collector.uid, 'kronjagare_brons').get();
      return snap.exists ? true : undefined;
    });
    expect(
      (await adminDb.collection('badgeProgress').doc(collector.uid).get()).data()!.crownsCollected,
    ).toBe(12);
    expect((await badgeDoc(collector.uid, 'kronjagare_silver').get()).exists).toBe(false);

    // No double-count: the counter now equals the leaderboard, so another sweep
    // leaves it at 12 (running-max raise, not an add).
    await adminDb.collection('badgeSweepState').doc('backlog').delete();
    for (let pass = 0; pass < 10; pass += 1) {
      const { wrapped } = await runBadgeBacklogSweep();
      if (wrapped) break;
    }
    expect(
      (await adminDb.collection('badgeProgress').doc(collector.uid).get()).data()!.crownsCollected,
    ).toBe(12);
  }, 120_000);

  /**
   * The backlog sweep is the ONLY path by which a garage that predates the
   * ladders ever earns a Samlare tier: `vehiclesInGarage` is a snapshot counter
   * re-derived on a vehicle CREATE, so a member already sitting at the
   * five-vehicle cap can never fire that trigger again. Without the
   * reconciliation step in badges/scheduled.ts they would hold a full garage
   * and no Samlare badge, forever.
   */
  it('back-fills Samlare for a garage that predates the ladders', async () => {
    const collector = await createProvisionedUser('badges-tier-samlare');
    await adminDb
      .collection('users')
      .doc(collector.uid)
      .set({ activeMember: true }, { merge: true });

    for (let index = 0; index < 3; index += 1) {
      await adminDb
        .collection('vehicles')
        .doc(`${collector.uid}__v${index}`)
        .set({ userId: collector.uid, make: 'Volvo', model: `240-${index}` });
    }
    // Let onVehicleCreated settle, then rewind to the pre-ladders state: the
    // counter never existed and no Samlare badge was ever awarded.
    await pollUntil(async () => {
      const snap = await badgeDoc(collector.uid, 'samlare_silver').get();
      return snap.exists ? true : undefined;
    });
    await adminDb
      .collection('badgeProgress')
      .doc(collector.uid)
      .set({ vehiclesInGarage: FieldValue.delete() }, { merge: true });
    await badgeDoc(collector.uid, 'samlare_brons').delete();
    await badgeDoc(collector.uid, 'samlare_silver').delete();
    expect((await badgeDoc(collector.uid, 'samlare_brons').get()).exists).toBe(false);

    // The sweep pages through `badgeProgress` from a shared cursor, so reset it
    // and run until it has wrapped at least once — that guarantees this member
    // was visited regardless of where a previous test left the cursor.
    await adminDb.collection('badgeSweepState').doc('backlog').delete();
    for (let pass = 0; pass < 10; pass += 1) {
      const { wrapped } = await runBadgeBacklogSweep();
      if (wrapped) {
        break;
      }
    }

    // Re-derived to 3 → Brons (1) and Silver (3), but not Guld (5).
    await pollUntil(async () => {
      const snap = await badgeDoc(collector.uid, 'samlare_silver').get();
      return snap.exists ? true : undefined;
    });
    expect((await badgeDoc(collector.uid, 'samlare_brons').get()).exists).toBe(true);
    expect((await badgeDoc(collector.uid, 'samlare_guld').get()).exists).toBe(false);
    expect(
      (await adminDb.collection('badgeProgress').doc(collector.uid).get()).data()!
        .vehiclesInGarage,
    ).toBe(3);
  }, 120_000);
});

/**
 * badges-getMyProgress — the owner-only callable that hands a member a read-only
 * projection of their OWN seven ladder counters (issue #799). The document it
 * reads (badgeProgress/{uid}) is denied to every client by the rules, so this
 * callable is the only client-reachable path to those numbers, and only ever
 * for the caller's own uid.
 */
describe('badges-getMyProgress (owner counters)', () => {
  it("returns the caller's own counters, projected through readBadgeCounters", async () => {
    const owner = await createProvisionedUser('badges-getprogress');
    await adminDb.collection('users').doc(owner.uid).set({ activeMember: true }, { merge: true });

    // Seed the backend-only progress doc with the raw stored shape: the
    // completed-event counter is stored under the HISTORIC field name
    // `completedEventsAttended` and must surface as `verifiedEventsAttended`,
    // and a nonsense value must sanitise to 0 rather than leak through.
    await adminDb.collection('badgeProgress').doc(owner.uid).set({
      crownsCollected: 34,
      lifetimeDistanceMeters: 234_567.8,
      completedEventsAttended: 7,
      bestDayStreak: 12,
      convoysLed: 3,
      vehiclesInGarage: 4,
      seasonsWon: -5, // nonsense → 0
    });

    await signInAs(owner);
    const result = (await call('badges-getMyProgress', {})).data as Record<string, number>;

    expect(result).toEqual({
      crownsCollected: 34,
      // Fractional metres are floored by the defensive projection.
      lifetimeDistanceMeters: 234_567,
      verifiedEventsAttended: 7,
      bestDayStreak: 12,
      convoysLed: 3,
      vehiclesInGarage: 4,
      seasonsWon: 0,
    });
  }, 120_000);

  it('returns all-zero counters when the caller has no progress document', async () => {
    const fresh = await createProvisionedUser('badges-getprogress-empty');
    await adminDb.collection('users').doc(fresh.uid).set({ activeMember: true }, { merge: true });

    await signInAs(fresh);
    const result = (await call('badges-getMyProgress', {})).data as Record<string, number>;

    expect(result).toEqual({
      crownsCollected: 0,
      lifetimeDistanceMeters: 0,
      verifiedEventsAttended: 0,
      bestDayStreak: 0,
      convoysLed: 0,
      vehiclesInGarage: 0,
      seasonsWon: 0,
    });
  }, 120_000);

  it('rejects an unauthenticated caller', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('badges-getMyProgress', {}))).toBe(
      'functions/unauthenticated',
    );
  }, 120_000);
});
