/**
 * Social leaderboard generator — emulator integration test.
 *
 * Needs the Firestore emulator (no auth/functions triggers — the generator is a
 * plain Admin-SDK read/assemble/write), and runs behind vitest.emulator.config.ts
 * (pnpm test:emulator under emulators:exec). The pure ranking/assembly is proven
 * in leaderboard-core.test.ts; this proves the wiring end to end:
 *
 *  - crownPoints is read from the all-time crownHuntLeaderboardEntries counters
 *    and the four badge categories from a paged badgeProgress scan;
 *  - names + avatars are resolved from users/{uid};
 *  - an opted-out member (userPrivate/{uid}.leaderboardOptOut === true) is
 *    NEVER published, and a member with no users/{uid} doc (deleted) drops off;
 *  - the assembled leaderboards/alltime document has the contracted shape.
 */

process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { describe, expect, it } from 'vitest';
import {
  LEADERBOARD_ALL_TIME_SCOPE,
  LEADERBOARD_MONTHLY_CATEGORIES,
  MEMBER_MONTHLY_STATS_COLLECTION,
  memberMonthlyStatsDocId,
  type LeaderboardRow,
} from '../leaderboard/leaderboard-core';
import {
  LEADERBOARD_COLLECTION,
  runLeaderboardGeneration,
  runMonthlyLeaderboardGeneration,
} from '../leaderboard/generator';
import { ALL_TIME_SCOPE, leaderboardEntryDocId } from '../crownHunt/crown-hunt-stats-core';

const PROJECT_ID = 'demo-test';
const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'leaderboard-emulator');
const adminDb = getAdminFirestore(adminApp);

// Unique suffix so this file's members never collide with another file sharing
// the one emulator Firestore. Huge values so the seeded members dominate the
// top-10 regardless of whatever other suites have written.
const TAG = `lb${Date.now()}`;
const uid = (name: string): string => `${TAG}-${name}`;
const BIG = 1_000_000_000_000;

async function seedCrownPoints(u: string, points: number): Promise<void> {
  await adminDb
    .collection('crownHuntLeaderboardEntries')
    .doc(leaderboardEntryDocId(ALL_TIME_SCOPE, u))
    .set({ scope: ALL_TIME_SCOPE, uid: u, points, crownsCollected: 1 });
}

async function seedBadgeProgress(u: string, base: number): Promise<void> {
  await adminDb.collection('badgeProgress').doc(u).set({
    lifetimeDistanceMeters: base,
    completedEventsAttended: base,
    convoysLed: base,
    bestDayStreak: base,
    wavesSent: base,
  });
}

async function seedUser(u: string, displayName: string, avatarPath?: string): Promise<void> {
  await adminDb
    .collection('users')
    .doc(u)
    .set({ displayName, ...(avatarPath ? { avatarPath } : {}) });
}

/** Only this file's seeded rows, in published order. */
function mine(rows: LeaderboardRow[]): LeaderboardRow[] {
  return rows.filter((r) => r.uid.startsWith(`${TAG}-`));
}

// A far-future season id so the monthly seeds never collide with real data or
// another suite; a valid `YYYY-MM`, and a DIFFERENT month for the isolation check.
const MONTH = '2099-01';
const OTHER_MONTH = '2099-02';

async function seedCrownPointsForScope(scope: string, u: string, points: number): Promise<void> {
  await adminDb
    .collection('crownHuntLeaderboardEntries')
    .doc(leaderboardEntryDocId(scope, u))
    .set({ scope, uid: u, points, crownsCollected: 1 });
}

async function seedMonthlyBucket(
  scope: string,
  u: string,
  fields: {
    distanceMeters?: number;
    eventsAttended?: number;
    convoysLed?: number;
    waves?: number;
  },
): Promise<void> {
  await adminDb
    .collection(MEMBER_MONTHLY_STATS_COLLECTION)
    .doc(memberMonthlyStatsDocId(scope, u))
    .set({ scope, uid: u, ...fields });
}

describe('leaderboard generator', () => {
  it('assembles leaderboards/alltime, resolving names and honouring opt-out + deletion', async () => {
    const champ = uid('champ');
    const optout = uid('optout');
    const ghost = uid('ghost');
    const normal = uid('normal');

    // champ dominates; optout ranks even higher but has opted out; ghost ranks
    // high but has no users doc (deleted); normal is a distant fourth.
    await seedCrownPoints(champ, BIG + 30);
    await seedCrownPoints(optout, BIG + 40);
    await seedCrownPoints(ghost, BIG + 20);
    await seedCrownPoints(normal, BIG + 10);

    await seedBadgeProgress(champ, BIG + 30);
    await seedBadgeProgress(optout, BIG + 40);
    await seedBadgeProgress(ghost, BIG + 20);
    await seedBadgeProgress(normal, BIG + 10);

    await seedUser(champ, 'Champ', 'profileImages/champ/a.jpg');
    await seedUser(optout, 'OptOut');
    await seedUser(normal, 'Normal');
    // ghost: deliberately NO users doc.

    await adminDb.collection('userPrivate').doc(optout).set({ leaderboardOptOut: true });

    const categories = await runLeaderboardGeneration();

    // Return value and persisted document agree.
    const doc = (
      await adminDb.collection(LEADERBOARD_COLLECTION).doc(LEADERBOARD_ALL_TIME_SCOPE).get()
    ).data();
    expect(doc?.scope).toBe(LEADERBOARD_ALL_TIME_SCOPE);
    expect(doc?.generatedAt).toBeDefined();

    for (const source of [categories.crownPoints, doc?.categories?.crownPoints as LeaderboardRow[]]) {
      const rows = mine(source);
      // optout and ghost are gone; champ precedes normal.
      expect(rows.map((r) => r.uid)).toEqual([champ, normal]);
      expect(rows.map((r) => r.uid)).not.toContain(optout);
      expect(rows.map((r) => r.uid)).not.toContain(ghost);
      const championRow = rows.find((r) => r.uid === champ);
      expect(championRow?.displayName).toBe('Champ');
      expect(championRow?.avatarPath).toBe('profileImages/champ/a.jpg');
      expect(championRow?.value).toBe(BIG + 30);
    }

    // A badge-backed category behaves identically (distance = lifetimeDistanceMeters).
    const distance = mine(categories.distance);
    expect(distance.map((r) => r.uid)).toEqual([champ, normal]);
    expect(distance.find((r) => r.uid === normal)?.displayName).toBe('Normal');
    expect(distance.find((r) => r.uid === normal)?.avatarPath).toBeNull();

    // waves (all-time, from badgeProgress.wavesSent) is a real category too.
    const waves = mine(categories.waves);
    expect(waves.map((r) => r.uid)).toEqual([champ, normal]);
    expect(waves.find((r) => r.uid === champ)?.value).toBe(BIG + 30);
  });
});

describe('monthly leaderboard generator', () => {
  it('assembles leaderboards/{YYYY-MM} from month buckets, omits streak, honours opt-out + deletion', async () => {
    const champ = uid('mchamp');
    const optout = uid('moptout');
    const ghost = uid('mghost');
    const normal = uid('mnormal');

    // Crown-points from the MONTH-scoped crown counters (scope = 2099-01).
    await seedCrownPointsForScope(MONTH, champ, BIG + 30);
    await seedCrownPointsForScope(MONTH, optout, BIG + 40); // higher, but opted out
    await seedCrownPointsForScope(MONTH, ghost, BIG + 20); // higher, but no users doc
    await seedCrownPointsForScope(MONTH, normal, BIG + 10);

    // distance/events/convoys/waves from that month's memberMonthlyStats buckets.
    await seedMonthlyBucket(MONTH, champ, {
      distanceMeters: BIG + 30,
      eventsAttended: BIG + 30,
      convoysLed: BIG + 30,
      waves: BIG + 30,
    });
    await seedMonthlyBucket(MONTH, optout, {
      distanceMeters: BIG + 40,
      eventsAttended: BIG + 40,
      convoysLed: BIG + 40,
      waves: BIG + 40,
    });
    await seedMonthlyBucket(MONTH, ghost, {
      distanceMeters: BIG + 20,
      eventsAttended: BIG + 20,
      convoysLed: BIG + 20,
      waves: BIG + 20,
    });
    await seedMonthlyBucket(MONTH, normal, {
      distanceMeters: BIG + 10,
      eventsAttended: BIG + 10,
      convoysLed: BIG + 10,
      waves: BIG + 10,
    });

    // A DIFFERENT month's bucket for champ that must NOT leak into 2099-01: a
    // colossal value that would top every category if the scan were not
    // month-scoped (proves the month is derived correctly at read time).
    await seedMonthlyBucket(OTHER_MONTH, champ, {
      distanceMeters: BIG * 9,
      eventsAttended: BIG * 9,
      convoysLed: BIG * 9,
      waves: BIG * 9,
    });

    await seedUser(champ, 'MChamp', 'profileImages/mchamp/a.jpg');
    await seedUser(optout, 'MOptOut');
    await seedUser(normal, 'MNormal');
    // ghost: deliberately NO users doc.
    await adminDb.collection('userPrivate').doc(optout).set({ leaderboardOptOut: true });

    const categories = await runMonthlyLeaderboardGeneration(MONTH);

    const doc = (
      await adminDb.collection(LEADERBOARD_COLLECTION).doc(MONTH).get()
    ).data();
    expect(doc?.scope).toBe(MONTH);
    expect(doc?.generatedAt).toBeDefined();

    // The published category SET is exactly the monthly set — streak omitted.
    expect(Object.keys(doc?.categories ?? {}).sort()).toEqual(
      [...LEADERBOARD_MONTHLY_CATEGORIES].sort(),
    );
    expect(doc?.categories?.streak).toBeUndefined();
    expect(categories).not.toHaveProperty('streak');

    // Every monthly category: opt-out and deleted drop off; champ precedes
    // normal; the OTHER month's giant bucket never leaks in (champ's value is
    // this month's, not BIG*9).
    for (const key of LEADERBOARD_MONTHLY_CATEGORIES) {
      const rows = mine(
        (doc?.categories?.[key] as LeaderboardRow[]) ?? [],
      );
      expect(rows.map((r) => r.uid)).toEqual([champ, normal]);
      expect(rows.map((r) => r.uid)).not.toContain(optout);
      expect(rows.map((r) => r.uid)).not.toContain(ghost);
      expect(rows.find((r) => r.uid === champ)?.value).toBe(BIG + 30);
    }

    // Identity resolution works on the monthly board too.
    const crown = mine(categories.crownPoints);
    expect(crown.find((r) => r.uid === champ)?.displayName).toBe('MChamp');
    expect(crown.find((r) => r.uid === champ)?.avatarPath).toBe('profileImages/mchamp/a.jpg');
  });

  it('rejects a non-season scope so it can never overwrite the all-time doc', async () => {
    await expect(runMonthlyLeaderboardGeneration(ALL_TIME_SCOPE)).rejects.toThrow();
    await expect(runMonthlyLeaderboardGeneration('2099-13')).rejects.toThrow();
  });
});
