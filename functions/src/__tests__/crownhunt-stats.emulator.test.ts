/**
 * Kronjakt STATS + LEADERBOARD + SEASONS — emulator integration tests.
 *
 * Needs the full Emulator Suite (auth + functions + firestore) and therefore a
 * JDK, so it is excluded from the default `vitest run` and runs behind
 * vitest.emulator.config.ts (pnpm test:emulator under emulators:exec).
 *
 * What it proves end to end (the pure maths is in crown-hunt-stats-core.test.ts):
 *  - a Kronjakt Kronpoäng ledger write drives the leaderboard trigger, folding
 *    points + crowns into BOTH the all-time and the current-season boards, and
 *    a redelivered/duplicate ledger entry never double-counts;
 *  - several members collecting produces the correct ranking;
 *  - the daily-collection streak advances across Stockholm days;
 *  - a crownSpawns spawn→claim drives the rarity breakdown, the per-cell
 *    heat-map and "rarest crown found";
 *  - the scheduled rollover finalizes a season (standings + top-3 winners),
 *    awards the rank-specific podium badges, and increments the LIFETIME
 *    championship counter — once per season, idempotently — and winning a
 *    second season raises the count to 2 and its Säsongsmästare badge.
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { initializeApp, type FirebaseApp } from 'firebase/app';
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  type Auth,
} from 'firebase/auth';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { Timestamp, getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  ALL_TIME_SCOPE,
  leaderboardEntryDocId,
  rankLeaderboard,
  seasonBounds,
  seasonIdForInstant,
  type LeaderboardCounter,
} from '../crownHunt/crown-hunt-stats-core';
import { finalizeSeason, runSeasonRollover } from '../crownHunt/seasonRollover';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'crownhunt-stats-emulator');
const adminDb = getAdminFirestore(adminApp);

let app: FirebaseApp;
let auth: Auth;

// Unique suffix so this file's members and cells never collide with another
// file sharing the one emulator Firestore.
const TAG = `chs${Date.now()}`;

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

/** Creates a real auth user (fires onUserCreate → users/{uid}) and returns its uid. */
async function provisionUser(prefix: string): Promise<string> {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const credential = await createUserWithEmailAndPassword(auth, email, 'password-123');
  const uid = credential.user.uid;
  await pollUntil(async () => {
    const snap = await adminDb.collection('users').doc(uid).get();
    return snap.exists ? true : undefined;
  });
  return uid;
}

/** Writes a Kronjakt crown award to the ledger (the leaderboard trigger's source). */
async function writeCrownLedgerEntry(
  uid: string,
  entryId: string,
  amount: number,
  collectedAt: Date,
): Promise<void> {
  await adminDb
    .collection('pointsLedger')
    .doc(uid)
    .collection('entries')
    .doc(entryId)
    .set({
      amount,
      transactionType: 'earn',
      source: 'crown_hunt',
      description: 'Kronjakt: test',
      createdAt: Timestamp.fromDate(collectedAt),
    });
}

async function leaderboardEntry(
  scope: string,
  uid: string,
): Promise<FirebaseFirestore.DocumentData | undefined> {
  const snap = await adminDb
    .collection('crownHuntLeaderboardEntries')
    .doc(leaderboardEntryDocId(scope, uid))
    .get();
  return snap.exists ? snap.data() : undefined;
}

async function userStats(uid: string): Promise<FirebaseFirestore.DocumentData | undefined> {
  const snap = await adminDb.collection('crownHuntUserStats').doc(uid).get();
  return snap.exists ? snap.data() : undefined;
}

async function hasBadge(uid: string, badgeKey: string): Promise<boolean> {
  const snap = await adminDb
    .collection('users')
    .doc(uid)
    .collection('badges')
    .doc(badgeKey)
    .get();
  return snap.exists;
}

beforeAll(async () => {
  app = initializeApp({ apiKey: 'demo', projectId: PROJECT_ID }, `crownhunt-stats-${Date.now()}`);
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
});

afterAll(async () => {
  await auth.signOut().catch(() => undefined);
});

describe('leaderboard aggregation (ledger trigger)', () => {
  it('folds points + crowns into the all-time and season boards and ranks members', async () => {
    const now = new Date();
    const season = seasonIdForInstant(now);
    const u1 = await provisionUser(`${TAG}-a`);
    const u2 = await provisionUser(`${TAG}-b`);
    const u3 = await provisionUser(`${TAG}-c`);

    // u1: two crowns (100 + 25 = 125), u2: one 250, u3: one 50.
    await writeCrownLedgerEntry(u1, `${TAG}-e1`, 100, now);
    await writeCrownLedgerEntry(u1, `${TAG}-e2`, 25, now);
    await writeCrownLedgerEntry(u2, `${TAG}-e3`, 250, now);
    await writeCrownLedgerEntry(u3, `${TAG}-e4`, 50, now);

    // All-time board settles.
    const a1 = await pollUntil(async () => {
      const e = await leaderboardEntry(ALL_TIME_SCOPE, u1);
      return e && e.points === 125 ? e : undefined;
    });
    expect(a1.crownsCollected).toBe(2);
    const a2 = await pollUntil(() => leaderboardEntry(ALL_TIME_SCOPE, u2));
    const a3 = await pollUntil(() => leaderboardEntry(ALL_TIME_SCOPE, u3));
    expect(a2.points).toBe(250);
    expect(a3.points).toBe(50);

    // The season board mirrors it for the same collections.
    const s1 = await pollUntil(async () => {
      const e = await leaderboardEntry(season, u1);
      return e && e.points === 125 ? e : undefined;
    });
    expect(s1.scope).toBe(season);

    // Ranking over just this file's three members is deterministic.
    const counters: LeaderboardCounter[] = [
      { uid: u1, points: a1.points, crownsCollected: a1.crownsCollected },
      { uid: u2, points: a2.points, crownsCollected: a2.crownsCollected },
      { uid: u3, points: a3.points, crownsCollected: a3.crownsCollected },
    ];
    expect(rankLeaderboard(counters).map((r) => [r.uid, r.rank])).toEqual([
      [u2, 1],
      [u1, 2],
      [u3, 3],
    ]);
  });

  it('never double-counts a redelivered ledger entry (fold marker)', async () => {
    const now = new Date();
    const uid = await provisionUser(`${TAG}-dup`);
    const entryId = `${TAG}-dup-e`;
    await writeCrownLedgerEntry(uid, entryId, 40, now);
    await pollUntil(async () => {
      const e = await leaderboardEntry(ALL_TIME_SCOPE, uid);
      return e && e.points === 40 ? e : undefined;
    });
    // Re-write the SAME entry id (a redelivery / overwrite). The fold marker
    // must keep the counter at 40, not 80.
    await writeCrownLedgerEntry(uid, entryId, 40, now);
    await new Promise((r) => setTimeout(r, 2_000));
    const e = await leaderboardEntry(ALL_TIME_SCOPE, uid);
    expect(e?.points).toBe(40);
    expect(e?.crownsCollected).toBe(1);
  });

  it('advances the daily-collection streak across Stockholm days', async () => {
    const uid = await provisionUser(`${TAG}-streak`);
    const day1 = new Date('2026-08-10T10:00:00Z');
    const day2 = new Date('2026-08-11T10:00:00Z');
    await writeCrownLedgerEntry(uid, `${TAG}-st1`, 10, day1);
    await pollUntil(async () => {
      const s = await userStats(uid);
      return s && s.collectionStreakCurrent === 1 ? s : undefined;
    });
    await writeCrownLedgerEntry(uid, `${TAG}-st2`, 10, day2);
    const s = await pollUntil(async () => {
      const st = await userStats(uid);
      return st && st.collectionStreakCurrent === 2 ? st : undefined;
    });
    expect(s.collectionStreakBest).toBe(2);
  });
});

describe('userStats is contract-complete regardless of which trigger fires first', () => {
  const RARITIES = ['common', 'uncommon', 'rare', 'legendary'] as const;

  it('the ledger-only (hand-placed) path still writes byRarity + seasonsWon', async () => {
    const uid = await provisionUser(`${TAG}-ledgeronly`);
    // A hand-placed collection: a crown_hunt ledger entry with NO crownSpawns
    // document, so the crownSpawns trigger never fires for this member.
    await writeCrownLedgerEntry(uid, `${TAG}-lo1`, 25, new Date());
    const s = await pollUntil(async () => {
      const st = await userStats(uid);
      return st && st.seasonsWon !== undefined && st.byRarity !== undefined ? st : undefined;
    });
    expect(s.seasonsWon).toBe(0);
    // byRarity present as a full four-key map, all zero (no spawned crowns).
    for (const r of RARITIES) {
      expect(s.byRarity?.[r]).toBe(0);
    }
  });

  it('the collect-first path writes seasonsWon even before any ledger entry', async () => {
    // A collection whose crownSpawns trigger writes the stats doc BEFORE (or
    // without) the ledger trigger. seasonsWon must still be present.
    const uid = await provisionUser(`${TAG}-collectfirst`);
    const spawnId = `${TAG}-cf-spawn`;
    const now = new Date();
    await adminDb
      .collection('crownSpawns')
      .doc(spawnId)
      .set({
        status: 'live',
        rarity: 'legendary',
        cellKey: `${TAG}cfcell`,
        rewardPoints: 500,
        latitude: 59.3,
        longitude: 18.0,
        createdAt: Timestamp.fromDate(now),
        expiresAt: Timestamp.fromDate(new Date(now.getTime() + 3_600_000)),
      });
    await adminDb
      .collection('crownSpawns')
      .doc(spawnId)
      .set(
        { status: 'claimed', claimedByUid: uid, claimedAt: Timestamp.fromDate(now) },
        { merge: true },
      );
    const s = await pollUntil(async () => {
      const st = await userStats(uid);
      return st && st.byRarity?.legendary === 1 ? st : undefined;
    });
    expect(s.seasonsWon).toBe(0);
    // The other rarity buckets are present and zero (full map).
    for (const r of ['common', 'uncommon', 'rare'] as const) {
      expect(s.byRarity?.[r]).toBe(0);
    }
  });
});

describe('spawn stats (crownSpawns trigger)', () => {
  it('records rarity, the per-cell heat-map and rarest crown on spawn and collect', async () => {
    const now = new Date();
    const season = seasonIdForInstant(now);
    const uid = await provisionUser(`${TAG}-spawn`);
    const cellKey = `${TAG}cell`;
    const spawnId = `${TAG}-spawn-1`;

    await adminDb
      .collection('crownSpawns')
      .doc(spawnId)
      .set({
        status: 'live',
        rarity: 'rare',
        cellKey,
        rewardPoints: 100,
        latitude: 59.33,
        longitude: 18.07,
        createdAt: Timestamp.fromDate(now),
        expiresAt: Timestamp.fromDate(new Date(now.getTime() + 3_600_000)),
      });

    // Spawn edge → cell + admin spawn stats.
    const cellAfterSpawn = await pollUntil(async () => {
      const snap = await adminDb.collection('crownHuntCellStats').doc(cellKey).get();
      return snap.exists && snap.data()?.spawned === 1 ? snap.data() : undefined;
    });
    expect(cellAfterSpawn.spawnedByRarity?.rare).toBe(1);
    const spawnStats = await pollUntil(async () => {
      const snap = await adminDb.collection('crownHuntSpawnStats').doc(season).get();
      return snap.exists && (snap.data()?.spawnedByRarity?.rare ?? 0) >= 1 ? snap.data() : undefined;
    });
    expect(spawnStats.spawnedTotal).toBeGreaterThanOrEqual(1);

    // Collect edge → claimed transition.
    await adminDb
      .collection('crownSpawns')
      .doc(spawnId)
      .set(
        {
          status: 'claimed',
          claimedByUid: uid,
          claimedAt: Timestamp.fromDate(now),
          expiresAt: Timestamp.fromDate(now),
        },
        { merge: true },
      );

    const cellAfterCollect = await pollUntil(async () => {
      const snap = await adminDb.collection('crownHuntCellStats').doc(cellKey).get();
      return snap.exists && snap.data()?.collected === 1 ? snap.data() : undefined;
    });
    expect(cellAfterCollect.collectedByRarity?.rare).toBe(1);

    const stats = await pollUntil(async () => {
      const s = await userStats(uid);
      return s && s.byRarity?.rare === 1 ? s : undefined;
    });
    expect(stats.rarestRarity).toBe('rare');
  });
});

describe('season rollover', () => {
  it('finalizes a season: standings, podium badges, and the lifetime champion counter', async () => {
    // A fixed historical season id — finalizeSeason works on any id, and a
    // hard-coded one cannot collide with a season another file computes from the
    // shared emulator clock.
    const pastSeason = '2001-01';
    const champ = await provisionUser(`${TAG}-champ`);
    const second = await provisionUser(`${TAG}-2nd`);
    const third = await provisionUser(`${TAG}-3rd`);

    // Seed the past season's board directly (points already accumulated).
    const seed = async (uid: string, points: number, crowns: number): Promise<void> => {
      await adminDb
        .collection('crownHuntLeaderboardEntries')
        .doc(leaderboardEntryDocId(pastSeason, uid))
        .set({ scope: pastSeason, uid, points, crownsCollected: crowns });
    };
    await seed(champ, 500, 20);
    await seed(second, 300, 12);
    await seed(third, 100, 5);

    await finalizeSeason(pastSeason);

    const season = (await adminDb.collection('crownHuntSeasons').doc(pastSeason).get()).data();
    expect(season?.status).toBe('ended');
    expect(season?.participantCount).toBe(3);
    expect(season?.winners?.map((w: { uid: string; rank: number }) => [w.uid, w.rank])).toEqual([
      [champ, 1],
      [second, 2],
      [third, 3],
    ]);

    // Rank-specific podium badges.
    expect(await hasBadge(champ, 'sasong_guld')).toBe(true);
    expect(await hasBadge(second, 'sasong_silver')).toBe(true);
    expect(await hasBadge(third, 'sasong_brons')).toBe(true);

    // Lifetime championship counter + its scaling badge (threshold 1 = brons).
    const champStats = await pollUntil(async () => {
      const s = await userStats(champ);
      return s && s.seasonsWon === 1 ? s : undefined;
    });
    expect(champStats.seasonsWon).toBe(1);
    expect(await hasBadge(champ, 'sasongsmastare_brons')).toBe(true);

    // Idempotent: re-finalizing the same season changes nothing.
    await finalizeSeason(pastSeason);
    const again = await userStats(champ);
    expect(again?.seasonsWon).toBe(1);
  });

  it('scales the championship count and badge across multiple seasons won', async () => {
    const earlierSeason = '2001-02';
    const champ = await provisionUser(`${TAG}-multichamp`);

    // Win an earlier season first.
    await adminDb
      .collection('crownHuntLeaderboardEntries')
      .doc(leaderboardEntryDocId(earlierSeason, champ))
      .set({ scope: earlierSeason, uid: champ, points: 400, crownsCollected: 15 });
    await finalizeSeason(earlierSeason);
    await pollUntil(async () => {
      const s = await userStats(champ);
      return s && s.seasonsWon === 1 ? s : undefined;
    });

    // Win a second, distinct season.
    const laterSeason = '2001-03';
    await adminDb
      .collection('crownHuntLeaderboardEntries')
      .doc(leaderboardEntryDocId(laterSeason, champ))
      .set({ scope: laterSeason, uid: champ, points: 600, crownsCollected: 22 });
    await finalizeSeason(laterSeason);

    const stats = await pollUntil(async () => {
      const s = await userStats(champ);
      return s && s.seasonsWon === 2 ? s : undefined;
    });
    expect(stats.seasonsWon).toBe(2);
    // badgeProgress mirrors the authoritative counter that drives the ladder.
    const progress = (await adminDb.collection('badgeProgress').doc(champ).get()).data();
    expect(progress?.seasonsWon).toBe(2);
    expect(await hasBadge(champ, 'sasongsmastare_brons')).toBe(true);
  });

  it('runSeasonRollover opens the current season as active', async () => {
    const now = new Date();
    const current = seasonIdForInstant(now);
    await runSeasonRollover(now);
    const season = (await adminDb.collection('crownHuntSeasons').doc(current).get()).data();
    expect(season?.status).toBe('active');
    expect(season?.period).toBe('month');
    const { startAt } = seasonBounds(current);
    expect((season?.startAt as Timestamp).toMillis()).toBe(startAt.getTime());
  });
});
