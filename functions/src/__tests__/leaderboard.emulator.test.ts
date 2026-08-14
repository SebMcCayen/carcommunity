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
  type LeaderboardRow,
} from '../leaderboard/leaderboard-core';
import { LEADERBOARD_COLLECTION, runLeaderboardGeneration } from '../leaderboard/generator';
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
  });
});
