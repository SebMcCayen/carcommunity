/**
 * Social LEADERBOARD generator — the scheduled Admin-SDK precompute.
 *
 * Builds the ONE client-readable document `leaderboards/alltime` from the
 * server's authoritative aggregates, so the social screen renders every
 * category from a single cheap read (functions/src/leaderboard/leaderboard-core.ts
 * owns the pure assembly). Runs hourly; the board is a derived snapshot, so a
 * slightly stale run only delays a rank change, never loses data.
 *
 * SOURCES, one per category:
 *  - crownPoints  crownHuntLeaderboardEntries where scope == 'alltime', field
 *    `points` — a SINGLE unordered scoped get (`.where('scope','==','alltime')`)
 *    read into memory and ranked here, no ordered index or paging needed. The
 *    collection is one small document per member who has ever collected, so the
 *    whole all-time scope fits one query (the same approach as
 *    seasonRollover.readScopeEntries).
 *  - distance / events / convoys / streak — the four badgeProgress counters
 *    (lifetimeDistanceMeters, completedEventsAttended, convoysLed, bestDayStreak).
 *    `badgeProgress` is BACKEND-ONLY (denied to every client in firestore.rules),
 *    so it is read here directly with the Admin SDK, PAGED by document id — the
 *    same cursor pattern as the badge sweep (badges/scheduled.ts) — but walked to
 *    exhaustion within ONE invocation because a global top-N needs a full pass.
 *    Memory stays bounded: after each page the per-category candidate lists are
 *    truncated to LEADERBOARD_CANDIDATE_RETENTION.
 *
 * FIELD-NAME CAVEAT: the "events" counter is stored as `completedEventsAttended`
 * (the historic name the Träffräv ladder reads), NOT a field named `events`.
 * The four badgeProgress counters are running maxima / monotonic sums, which is
 * exactly what these all-time "best / most ever" categories want.
 *
 * OPT-OUT + IDENTITY: only the retained top candidates (across all categories)
 * have their `users/{uid}` profile and `userPrivate/{uid}.leaderboardOptOut`
 * read, in two batched getAll calls. A member who opted out, or whose `users`
 * doc is gone (deleted member), is removed by the pure builder before the board
 * is written — so an opted-out member is never published, in this doc or the
 * later public JSON.
 *
 * `runLeaderboardGeneration()` is exported for the emulator test; the scheduled
 * wrapper is `generateLeaderboards`.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldPath, FieldValue } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { CPU_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';
import {
  CROWN_LEADERBOARD_COLLECTION,
  ALL_TIME_SCOPE,
} from '../crownHunt/crown-hunt-stats-core';
import {
  LEADERBOARD_ALL_TIME_SCOPE,
  LEADERBOARD_CANDIDATE_RETENTION,
  LEADERBOARD_CATEGORIES,
  buildLeaderboardCategory,
  candidateUidsToResolve,
  readCandidateValue,
  topCandidates,
  type LeaderboardCandidate,
  type LeaderboardCategoryKey,
  type LeaderboardIdentity,
  type LeaderboardRow,
} from './leaderboard-core';

/** The client-readable board collection. One document per scope. */
export const LEADERBOARD_COLLECTION = 'leaderboards';

/** Members read per badgeProgress page during the full scan. */
export const LEADERBOARD_SCAN_PAGE_SIZE = 500;

/** The badgeProgress field each badge-backed category ranks on. */
const BADGE_CATEGORY_FIELD: Readonly<Record<Exclude<LeaderboardCategoryKey, 'crownPoints'>, string>> =
  {
    distance: 'lifetimeDistanceMeters',
    events: 'completedEventsAttended',
    convoys: 'convoysLed',
    streak: 'bestDayStreak',
  };

type CategoryCandidates = Record<LeaderboardCategoryKey, LeaderboardCandidate[]>;

function emptyCandidates(): CategoryCandidates {
  return {
    crownPoints: [],
    distance: [],
    events: [],
    convoys: [],
    streak: [],
  };
}

/** Truncates every category's candidate list to the retention bound, in place. */
function trimCandidates(candidates: CategoryCandidates): void {
  for (const key of LEADERBOARD_CATEGORIES) {
    candidates[key] = topCandidates(candidates[key], LEADERBOARD_CANDIDATE_RETENTION);
  }
}

/**
 * All-time crown-points candidates, from the maintained per-scope leaderboard
 * counters. Projects only `uid` + `points` to keep the payload small.
 */
async function readCrownPointCandidates(): Promise<LeaderboardCandidate[]> {
  const snap = await db
    .collection(CROWN_LEADERBOARD_COLLECTION)
    .where('scope', '==', ALL_TIME_SCOPE)
    .select('uid', 'points')
    .get();
  const candidates: LeaderboardCandidate[] = [];
  for (const doc of snap.docs) {
    const data = doc.data();
    const uid = typeof data.uid === 'string' && data.uid.length > 0 ? data.uid : null;
    if (!uid) {
      continue;
    }
    candidates.push({ uid, value: readCandidateValue(data.points) });
  }
  return candidates;
}

/**
 * The four badge-backed categories, from a full paged scan of `badgeProgress`
 * ordered by document id (needs no composite index and cannot skip a document
 * that lacks a field). Walked to exhaustion in one run; the per-category lists
 * are trimmed after every page so peak memory is bounded by the page size plus
 * a handful of retained candidates per category, not the member count.
 */
async function scanBadgeCandidates(): Promise<CategoryCandidates> {
  const candidates = emptyCandidates();
  let cursor: string | null = null;
  for (;;) {
    let query = db
      .collection('badgeProgress')
      .orderBy(FieldPath.documentId())
      .limit(LEADERBOARD_SCAN_PAGE_SIZE);
    if (cursor) {
      query = query.startAfter(cursor);
    }
    const page = await query.get();
    if (page.empty) {
      break;
    }
    for (const doc of page.docs) {
      const data = doc.data();
      for (const key of ['distance', 'events', 'convoys', 'streak'] as const) {
        const value = readCandidateValue(data[BADGE_CATEGORY_FIELD[key]]);
        if (value > 0) {
          candidates[key].push({ uid: doc.id, value });
        }
      }
    }
    trimCandidates(candidates);
    if (page.size < LEADERBOARD_SCAN_PAGE_SIZE) {
      break;
    }
    cursor = page.docs[page.docs.length - 1]?.id ?? null;
    if (!cursor) {
      break;
    }
  }
  return candidates;
}

/**
 * Resolves the public identity of the given uids from `users/{uid}` in batched
 * getAll reads (500 per batch, the Firestore getAll ceiling). A uid with no
 * `users` document — or one missing the contract-required `displayName` — is
 * simply absent from the returned map, so the pure builder drops that member off
 * the board rather than publishing an invented label.
 */
async function resolveIdentities(uids: readonly string[]): Promise<Map<string, LeaderboardIdentity>> {
  const identities = new Map<string, LeaderboardIdentity>();
  for (let i = 0; i < uids.length; i += 500) {
    const batch = uids.slice(i, i + 500);
    const refs = batch.map((uid) => db.collection('users').doc(uid));
    const snaps = await db.getAll(...refs);
    for (const snap of snaps) {
      if (!snap.exists) {
        continue;
      }
      const data = snap.data() ?? {};
      // `displayName` is contract-REQUIRED on users/{uid}
      // (contracts/schemas/user-profile.schema.json). A doc missing it is a
      // contract violation, not a member to paper over with a fake label — skip
      // it exactly like a missing doc so the member simply drops off rather than
      // publishing an invented, non-localizable name.
      if (typeof data.displayName !== 'string' || data.displayName.length === 0) {
        continue;
      }
      const avatarPath =
        typeof data.avatarPath === 'string' && data.avatarPath.length > 0 ? data.avatarPath : null;
      identities.set(snap.id, { displayName: data.displayName, avatarPath });
    }
  }
  return identities;
}

/**
 * The set of uids (of the given candidate uids) that have opted OUT of the
 * leaderboard, from `userPrivate/{uid}.leaderboardOptOut === true`. Absent /
 * false / missing document all mean opted IN — the flag is optional and defaults
 * to participating. Batched getAll, same as identity resolution.
 */
async function resolveOptOuts(uids: readonly string[]): Promise<Set<string>> {
  const optedOut = new Set<string>();
  for (let i = 0; i < uids.length; i += 500) {
    const batch = uids.slice(i, i + 500);
    const refs = batch.map((uid) => db.collection('userPrivate').doc(uid));
    const snaps = await db.getAll(...refs);
    for (const snap of snaps) {
      if (snap.exists && snap.data()?.leaderboardOptOut === true) {
        optedOut.add(snap.id);
      }
    }
  }
  return optedOut;
}

/**
 * Builds and writes `leaderboards/alltime`. Returns the assembled category rows
 * (for the emulator test); the scheduled wrapper ignores the return.
 */
export async function runLeaderboardGeneration(): Promise<
  Record<LeaderboardCategoryKey, LeaderboardRow[]>
> {
  const [crownPoints, badgeCandidates] = await Promise.all([
    readCrownPointCandidates(),
    scanBadgeCandidates(),
  ]);
  // Trim crownPoints to the SAME retention bound the badge categories were
  // trimmed to during their scan, so every category's candidate list matches
  // the uid set `candidateUidsToResolve` resolves identities/opt-outs for.
  // Otherwise `buildLeaderboardCategory` would iterate crownPoints candidates
  // ranked beyond the resolved set, find no identity for them, and wrongly treat
  // a live member as deleted — dropping valid rows (and over-sorting a list that
  // can be arbitrarily long).
  const perCategory: CategoryCandidates = {
    ...badgeCandidates,
    crownPoints: topCandidates(crownPoints, LEADERBOARD_CANDIDATE_RETENTION),
  };

  const uids = candidateUidsToResolve(perCategory);
  const [identities, optedOut] = await Promise.all([
    resolveIdentities(uids),
    resolveOptOuts(uids),
  ]);

  const categories = {} as Record<LeaderboardCategoryKey, LeaderboardRow[]>;
  for (const key of LEADERBOARD_CATEGORIES) {
    categories[key] = buildLeaderboardCategory(perCategory[key], identities, optedOut);
  }

  await db
    .collection(LEADERBOARD_COLLECTION)
    .doc(LEADERBOARD_ALL_TIME_SCOPE)
    .set({
      scope: LEADERBOARD_ALL_TIME_SCOPE,
      categories,
      generatedAt: FieldValue.serverTimestamp(),
    });

  logger.info('Social leaderboard generated', {
    scope: LEADERBOARD_ALL_TIME_SCOPE,
    counts: Object.fromEntries(
      LEADERBOARD_CATEGORIES.map((key) => [key, categories[key].length]),
    ),
    optedOut: optedOut.size,
  });
  return categories;
}

/**
 * Scheduled hourly at Europe/Stockholm. Hourly is cheap — one small query, one
 * bounded paged scan and a single document write — and keeps the board fresh
 * without a trigger on every counter change.
 *
 * A strict SINGLETON (`maxInstances: 1`): the whole function does one thing —
 * OVERWRITE `leaderboards/alltime` with the freshest snapshot — so two runs must
 * never overlap. If a slow run were allowed to finish AFTER a newer one, it
 * would clobber the newer board with a staler snapshot; serializing runs makes
 * that regression impossible. `concurrency: 1` is already required by the sub-1
 * CPU tier (see instanceLimits.ts); this pins the instance ceiling to match,
 * the same way the Kronjakt spawn/sweep pair sets `maxInstances: 1` locally.
 */
export const generateLeaderboards = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: 1,
    cpu: CPU_SCHEDULED,
    concurrency: 1,
    schedule: '0 * * * *',
    timeZone: 'Europe/Stockholm',
    memory: '512MiB',
    timeoutSeconds: 540,
  },
  withServerErrorReporting('leaderboard.generateLeaderboards', async () => {
    await runLeaderboardGeneration();
  }),
);
