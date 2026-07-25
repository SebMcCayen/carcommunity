/**
 * Kronjakt auto-spawn — the scheduled REPLENISHER and SWEEPER.
 *
 * `crownHunt-spawnCrowns` (every 10 min): tops each ADMIN-APPROVED grid cell up
 * toward its activity-derived target, respecting the minimum separation between
 * live crowns.
 *
 * `crownHunt-sweepSpawns` (every 15 min): deletes crowns whose `expiresAt` has
 * passed (including ones marked claimed, whose expiry is set to the claim
 * instant) and reaps cell-activity aggregates that have gone quiet for the
 * whole 7-day window.
 *
 * Both runners are exported so emulator tests can drive them deterministically
 * against an injected `now`, mirroring `incidents/scheduled.ts`.
 *
 * ## Three gates, in this order
 *  1. the `crownHuntSpawn` feature flag (contract default OFF — nothing here
 *     runs in production until it is deliberately switched on);
 *  2. the ADMIN ALLOW-LIST: the candidate set is `crownSpawnCells` where
 *     `approved == true`, never the set of cells that happen to have activity.
 *     A busy cell nobody approved is invisible to this function (spawnCells.ts);
 *  3. the activity floor and the slow-sighting filter, which narrow placement
 *     WITHIN an approved area.
 * Each gate is independently sufficient to produce zero spawns. That is
 * deliberate: the failure mode this engine has to be defended against is
 * inviting a member to stop somewhere dangerous, and no single condition should
 * be the only thing standing between the algorithm and that outcome.
 *
 * ## Bounding
 * Every loop in here is bounded twice: by a per-run CELL budget and by a
 * per-run SPAWN budget, plus a per-cell attempt budget inside the rejection
 * sampler. Approved cells are visited LEAST-RECENTLY-SERVED first, so when the
 * approved list outgrows one run's budget the remainder is served on the next
 * pass rather than starved — a cell that misses a round is at most 10 minutes
 * below target.
 *
 * ## Cost per run
 * One allow-list query, then per approved cell: one bounded `recentUsers` read
 * and one 9-cell neighbourhood read, and only for cells that are actually short
 * of target, a handful of document creates. Cells already at target cost two
 * reads and one cursor write.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldValue, Timestamp, type DocumentData } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import {
  ACTIVITY_WINDOW_MS,
  CROWN_SPAWN_FLAG_KEY,
  activityScore,
  buildCrownSpawnFields,
  crownExpiresAt,
  neighbourCrownCells,
  pickCrownRarity,
  sampleCrownPosition,
  targetCrownCount,
  type CrownPosition,
} from './crown-spawn-core';

// ---------------------------------------------------------------------------
// Bounds
// ---------------------------------------------------------------------------

/**
 * Approved cells examined per run, least-recently-served first.
 *
 * A cell only becomes a candidate by being on the admin allow-list, so this is
 * a bound on HUMAN-APPROVED areas, not on map area or on how busy the app gets.
 * 50 per 10-minute run serves 300 approved cells an hour; the round-robin
 * ordering means an allow-list larger than that degrades into a slower refresh
 * cycle rather than a permanently ignored tail.
 */
const MAX_CELLS_PER_RUN = 50;

/** Crowns created per run, across all cells — the hard write budget. */
const MAX_SPAWNS_PER_RUN = 100;

/**
 * Distinct-user documents read per cell when computing `A`.
 *
 * The score is logarithmic and capped at 5 crowns by A ≈ 27, so the difference
 * between 200 recent visitors and 2000 is nil. Reading the first 200 is enough
 * to saturate the curve while keeping the per-cell read cost flat.
 */
const MAX_ACTIVITY_USERS_PER_CELL = 200;

/** Live crowns loaded from a 3x3 neighbourhood for the separation check. */
const MAX_NEIGHBOURHOOD_CROWNS = 100;

/** Expired crowns deleted per sweep. */
const MAX_SWEEP_DELETIONS = 1000;

/** Documents per Firestore batched write. */
const WRITE_BATCH_SIZE = 400;

/** Quiet activity cells reaped per sweep (recursiveDelete, so kept small). */
const MAX_ACTIVITY_CELLS_REAPED = 100;

export interface CrownSpawnLimits {
  maxCells: number;
  maxSpawns: number;
}

export interface CrownSpawnResult {
  /** Approved cells examined this run. */
  cellsScanned: number;
  /** Approved cells whose activity was too low to spawn anything (`A < 1`). */
  cellsBelowActivityFloor: number;
  /** Crowns created. */
  spawned: number;
  /**
   * Times the rejection sampler ran out of attempts. Reported rather than kept
   * internal because it is the only way to see the separation rule biting: a
   * run that spawns fewer than the deficit is otherwise indistinguishable from
   * a run with no deficit.
   */
  separationRejections: number;
  /**
   * Cells whose crowns were NOT written because the cell was revoked after this
   * pass read the allow-list. Zero in normal operation; a non-zero value is the
   * mid-pass revocation guard doing its job, and is worth seeing in the logs.
   */
  cellsRevokedMidPass: number;
  /**
   * Approved cells skipped because their document ID is not a parseable cell
   * key. Zero in normal operation; a non-zero value means a malformed document
   * is sitting in `crownSpawnCells` and wants cleaning up.
   */
  cellsSkippedInvalidKey: number;
  /** True when the run stopped on {@link MAX_SPAWNS_PER_RUN}. */
  capped: boolean;
  /** True when the feature flag was off and nothing ran. */
  skipped: boolean;
}

// ---------------------------------------------------------------------------
// Replenisher
// ---------------------------------------------------------------------------

/**
 * Runs one replenish pass against `now`.
 *
 * `limits` and `rng` exist so tests can drive the bounds at a seedable scale;
 * the scheduled entry point passes neither, so production always runs on the
 * constants above with a clock-seeded generator.
 */
export async function runCrownSpawnPass(
  now: Date,
  limits: CrownSpawnLimits = { maxCells: MAX_CELLS_PER_RUN, maxSpawns: MAX_SPAWNS_PER_RUN },
  rng: () => number = Math.random,
): Promise<CrownSpawnResult> {
  const result: CrownSpawnResult = {
    cellsScanned: 0,
    cellsBelowActivityFloor: 0,
    spawned: 0,
    separationRejections: 0,
    cellsRevokedMidPass: 0,
    cellsSkippedInvalidKey: 0,
    capped: false,
    skipped: false,
  };

  if (!(await readFeatureFlag(CROWN_SPAWN_FLAG_KEY))) {
    result.skipped = true;
    return result;
  }

  const nowTs = Timestamp.fromDate(now);
  const activityCutoff = Timestamp.fromMillis(now.getTime() - ACTIVITY_WINDOW_MS);

  // THE ALLOW-LIST IS THE CANDIDATE SET. Not "active cells, filtered by
  // approval" — starting from the approved list makes it structurally
  // impossible for an unapproved cell to be considered at all, however the
  // activity data looks. Least-recently-served first, so a long allow-list is
  // served round-robin instead of the tail starving. Cells that have never been
  // served carry the epoch sentinel (SPAWN_CELL_NEVER_SERVED_AT_MS) and so sort
  // ahead of every served cell — a freshly approved area is picked up on the
  // next pass rather than after a full cycle.
  const cells = await db
    .collection('crownSpawnCells')
    .where('approved', '==', true)
    .orderBy('lastSpawnPassAt', 'asc')
    .limit(Math.max(1, limits.maxCells))
    .get();

  for (const cellDoc of cells.docs) {
    if (result.spawned >= limits.maxSpawns) {
      result.capped = true;
      break;
    }
    const cellKey = cellDoc.id;
    const approvedCellBy = (cellDoc.data().approvedByUserId as string | undefined) ?? null;
    result.cellsScanned += 1;

    // Advance the round-robin cursor for EVERY cell we look at, including ones
    // that end up spawning nothing: the cursor tracks attention, not output. If
    // it only moved on a successful spawn, a permanently quiet approved cell
    // would sit at the head of the queue forever and consume a slot every run.
    await cellDoc.ref.update({ lastSpawnPassAt: Timestamp.fromDate(now) });

    // A cell key is a DOCUMENT ID, so it is whatever was written there. The
    // collection is backend-only and `setSpawnCellApproval` validates the key,
    // but a console edit or a hand-written migration can still leave one that
    // does not parse — and `neighbourCrownCells` returns [] for those. Firestore
    // REJECTS an `in` filter with an empty array, so without this guard a single
    // malformed document would throw and take the WHOLE pass down with it,
    // every other approved cell included. Skip it loudly instead; the cursor is
    // already advanced, so a bad cell cannot block the round-robin either.
    const neighbours = neighbourCrownCells(cellKey);
    if (neighbours.length === 0) {
      result.cellsSkippedInvalidKey += 1;
      logger.warn('Crown spawn skipped: cell key does not parse', { cellKey });
      continue;
    }

    // A(cell): one decayed weight per DISTINCT user, distinctness guaranteed by
    // the document ID (a cell-scoped hash), never by counting rows.
    const recentUsers = await db
      .collection('crownCellActivity')
      .doc(cellKey)
      .collection('recentUsers')
      .where('lastSeenAt', '>=', activityCutoff)
      .orderBy('lastSeenAt', 'desc')
      .limit(MAX_ACTIVITY_USERS_PER_CELL)
      .select('lastSeenAt')
      .get();

    const lastSeenValues = recentUsers.docs
      .map((doc) => (doc.data().lastSeenAt as Timestamp | undefined)?.toMillis())
      .filter((value): value is number => typeof value === 'number');

    const target = targetCrownCount(activityScore(lastSeenValues, now.getTime()));
    if (target === 0) {
      // A < 1 — nobody has been here recently enough. Never spawn.
      result.cellsBelowActivityFloor += 1;
      continue;
    }

    // Live crowns across the 3x3 neighbourhood: the count INSIDE this cell sets
    // the deficit, while every position in the neighbourhood constrains the
    // separation check (a crown 20 m over the boundary is still a clump).
    const neighbourhood = await db
      .collection('crownSpawns')
      .where('cellKey', 'in', neighbours)
      .where('status', '==', 'live')
      .where('expiresAt', '>', nowTs)
      .limit(MAX_NEIGHBOURHOOD_CROWNS)
      .get();

    const occupied: CrownPosition[] = [];
    let liveInCell = 0;
    for (const doc of neighbourhood.docs) {
      const data = doc.data();
      if (typeof data.latitude === 'number' && typeof data.longitude === 'number') {
        occupied.push({ latitude: data.latitude, longitude: data.longitude });
      }
      if (data.cellKey === cellKey) liveInCell += 1;
    }

    const deficit = Math.min(target - liveInCell, limits.maxSpawns - result.spawned);
    if (deficit <= 0) continue;

    // Sampled OUTSIDE the transaction below, and against pre-generated document
    // refs, so that a transaction retry re-commits exactly these crowns instead
    // of resampling new positions and new IDs on each attempt.
    const pending: { ref: FirebaseFirestore.DocumentReference; data: DocumentData }[] = [];
    for (let i = 0; i < deficit; i += 1) {
      const position = sampleCrownPosition(cellKey, occupied, rng);
      if (!position) {
        // Geometrically saturated for now. Leave the cell short rather than
        // loosening the separation rule; the next run tries again.
        result.separationRejections += 1;
        break;
      }
      // Newly placed crowns immediately constrain the next sample in this loop,
      // so a single run cannot create its own clump.
      occupied.push(position);

      const rarity = pickCrownRarity(rng());
      pending.push({
        ref: db.collection('crownSpawns').doc(),
        data: {
          ...buildCrownSpawnFields({ cellKey, position, rarity, approvedCellBy }),
          createdAt: FieldValue.serverTimestamp(),
          expiresAt: Timestamp.fromDate(crownExpiresAt(rarity, now)),
          claimedAt: null,
        },
      });
    }

    if (pending.length > 0) {
      // THE APPROVAL IS RE-CHECKED AT WRITE TIME, TRANSACTIONALLY.
      //
      // The approved-cell list was read once at the top of this pass, and a
      // pass may run for minutes. `setSpawnCellApproval` revokes by flipping
      // `approved` and then deleting the cell's live crowns — so a revocation
      // landing mid-pass would drain a cell this loop is still working on, and
      // the plain batch that used to be here would then commit fresh crowns
      // into an area an admin had just declared unsafe. Nothing downstream
      // removes those: the sweeper only takes expired crowns, so they would
      // stand for their full TTL, up to 48 h for a legendary.
      //
      // Reading the cell document inside the transaction closes the window
      // rather than narrowing it. Firestore aborts and retries a transaction
      // whose reads were written by someone else before it committed, so the
      // two possible orderings are both safe: commit-before-revocation means
      // the revocation's drain deletes these crowns, and revocation-first means
      // the retry re-reads `approved: false` and writes nothing at all.
      const committed = await db.runTransaction(async (tx) => {
        const fresh = await tx.get(cellDoc.ref);
        if (fresh.get('approved') !== true) return 0;
        for (const crown of pending) tx.set(crown.ref, crown.data);
        return pending.length;
      });

      if (committed === 0) {
        result.cellsRevokedMidPass += 1;
        logger.warn('Crown spawn skipped: cell revoked mid-pass', { cellKey });
      }
      result.spawned += committed;
    }
  }

  logger.info('Crown spawn pass complete', { ...result });
  return result;
}

// ---------------------------------------------------------------------------
// Sweeper
// ---------------------------------------------------------------------------

export interface CrownSweepResult {
  /** Expired (or claimed-and-expired) crowns deleted. */
  spawnsDeleted: number;
  /** Cell-activity aggregates reaped after a full quiet window. */
  activityCellsDeleted: number;
  /** True when the run stopped on the deletion cap with work still remaining. */
  capped: boolean;
}

export interface CrownSweepLimits {
  maxSpawnDeletions: number;
  maxActivityCells: number;
}

/**
 * Runs one TTL sweep against `now`.
 *
 * NOT feature-flag gated, deliberately: turning the engine off must still let
 * already-placed crowns age off the map. A sweep that only ran while the
 * feature was on would leave the last generation of crowns live forever the
 * moment someone flipped the flag.
 */
export async function runCrownSpawnCleanup(
  now: Date,
  limits: CrownSweepLimits = {
    maxSpawnDeletions: MAX_SWEEP_DELETIONS,
    maxActivityCells: MAX_ACTIVITY_CELLS_REAPED,
  },
): Promise<CrownSweepResult> {
  const cutoff = Timestamp.fromDate(now);
  let spawnsDeleted = 0;
  let capped = false;

  // Oldest-first so a backlog drains strictly monotonically: every candidate is
  // due by construction, so a capped run can never starve an older crown.
  for (;;) {
    const remaining = limits.maxSpawnDeletions - spawnsDeleted;
    if (remaining <= 0) {
      const more = await db
        .collection('crownSpawns')
        .where('expiresAt', '<=', cutoff)
        .orderBy('expiresAt', 'asc')
        .limit(1)
        .get();
      capped = !more.empty;
      break;
    }

    const pageLimit = Math.min(WRITE_BATCH_SIZE, remaining);
    const expired = await db
      .collection('crownSpawns')
      .where('expiresAt', '<=', cutoff)
      .orderBy('expiresAt', 'asc')
      .limit(pageLimit)
      .get();
    if (expired.empty) break;

    // Plain batched delete, not recursiveDelete: a crown has no sub-collections
    // (claims live in their own top-level collection so a member's history
    // survives the crown disappearing).
    const batch = db.batch();
    for (const doc of expired.docs) batch.delete(doc.ref);
    await batch.commit();
    spawnsDeleted += expired.size;

    if (expired.size < pageLimit) break;
  }

  // Cells nobody has visited for the whole activity window contribute a score
  // of exactly 0 forever, so the parent AND its recentUsers sub-collection are
  // dead weight. recursiveDelete here (unlike the crowns above) precisely
  // BECAUSE of that sub-collection — a plain delete would orphan it.
  const quietCutoff = Timestamp.fromMillis(now.getTime() - ACTIVITY_WINDOW_MS);
  const quietCells = await db
    .collection('crownCellActivity')
    .where('lastActivityAt', '<=', quietCutoff)
    .orderBy('lastActivityAt', 'asc')
    .limit(Math.max(1, limits.maxActivityCells))
    .get();
  for (const doc of quietCells.docs) {
    await db.recursiveDelete(doc.ref);
  }

  const result: CrownSweepResult = {
    spawnsDeleted,
    activityCellsDeleted: quietCells.size,
    capped,
  };
  logger.info('Crown spawn sweep complete', { ...result });
  return result;
}

// ---------------------------------------------------------------------------
// Scheduled entry points
// ---------------------------------------------------------------------------

/**
 * Serialization for both schedulers.
 *
 * `runCrownSpawnPass` reads a cell's live neighbourhood, then writes into it.
 * Two passes running at the same time would both read the pre-write state, so
 * each would place crowns the other could not see — and the >= 150 m separation
 * rule, which is a stated property of the feature rather than a nice-to-have,
 * would be violated without either run doing anything wrong. Read-then-write
 * against a shared collection is only safe here because exactly one pass runs
 * at a time.
 *
 * `timeoutSeconds` (300) is already shorter than either interval, so a slow run
 * cannot bleed into the next tick. These two settings make that ENFORCED rather
 * than merely true today:
 *
 *  - `maxInstances: 1` — at most one container, so a retry or an early tick
 *    cannot be answered by a second instance running in parallel.
 *  - `concurrency: 1` — pinned explicitly, not inherited. It happens to be the
 *    default at 256MiB (Cloud Run defaults concurrency to 1 below 1 CPU and to
 *    80 at or above it), which means a later memory bump would otherwise let 80
 *    passes share one instance and silently undo the guarantee above.
 *
 * The sweeper carries the same pair. Duplicate deletes are harmless in
 * themselves, but overlapping sweeps double the Firestore load and make the
 * `capped` bound meaningless, and an unexplained asymmetry between two
 * schedulers in one file is worse than a redundant constant.
 */
const SPAWN_SCHEDULE_OPTS = {
  region: 'europe-west1',
  timeZone: 'Europe/Stockholm',
  memory: '256MiB' as const,
  timeoutSeconds: 300,
  maxInstances: 1,
  concurrency: 1,
};

/**
 * Replenish pass, every 10 minutes.
 *
 * Faster than the 15-minute sweep so a cell that loses its crowns (collected or
 * expired) refills promptly, and slow enough that the whole engine costs a few
 * hundred reads an hour at the active footprint we expect.
 */
export const spawnCrowns = onSchedule(
  { ...SPAWN_SCHEDULE_OPTS, schedule: '*/10 * * * *' },
  async () => {
    await runCrownSpawnPass(new Date());
  },
);

/** TTL sweep, every 15 minutes (mirrors `incidents-cleanupExpired`). */
export const sweepSpawns = onSchedule(
  { ...SPAWN_SCHEDULE_OPTS, schedule: '*/15 * * * *' },
  async () => {
    await runCrownSpawnCleanup(new Date());
  },
);
