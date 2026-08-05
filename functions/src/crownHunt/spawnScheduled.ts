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
  crownCellKey,
  crownExpiresAt,
  neighbourCrownCells,
  pickCrownRarity,
  sampleCrownPosition,
  targetCrownCount,
  type CrownPosition,
} from './crown-spawn-core';
import {
  MAX_AREA_CELLS,
  cellKeysForBoundingBox,
  pointInShapeAccept,
  shapeBoundingBox,
  type CrownSpawnAreaShape,
} from './crown-area-core';
import { POI_JITTER_METERS, samplePoiPlacement, type NormalizedPoi } from './osm-poi-core';
import { withServerErrorReporting } from '../errors/serverErrors';

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
// Marked-area replenisher
// ---------------------------------------------------------------------------

/**
 * Areas examined per run, least-recently-served first. Admins draw a handful of
 * big areas, not thousands, so this is generous; the round-robin cursor
 * (`lastSpawnPassAt`) keeps a long list fair rather than starving its tail.
 */
const MAX_AREAS_PER_RUN = 10;

/**
 * Grid CELLS processed per run across all areas — the read budget. A big area
 * spans many cells, so this is the bound that actually caps a run's cost; an
 * area's own cell cursor (`nextCellOffset`) advances so its tail is served on
 * later runs rather than always the same head.
 */
const MAX_AREA_CELLS_PER_RUN = 60;

/** Crowns created per area run, across all areas — the hard write budget. */
const MAX_AREA_SPAWNS_PER_RUN = 100;

/**
 * Cached safe-stop POIs loaded per area for anchoring placement. Matches the
 * ingestion cap ({@link MAX_POIS_PER_AREA}) so the whole cache is available; the
 * per-cell density budget and 150 m separation bound how many are ever used.
 */
const MAX_AREA_POIS_LOADED = 5000;

export interface CrownAreaSpawnLimits {
  maxAreas: number;
  maxCells: number;
  maxSpawns: number;
}

export interface CrownAreaSpawnResult {
  /** Active marked areas examined this run. */
  areasScanned: number;
  /** Grid cells (across all areas) whose activity was read this run. */
  cellsScanned: number;
  /** Cells whose activity was below the floor (`A < 1`). */
  cellsBelowActivityFloor: number;
  /** Crowns created. */
  spawned: number;
  /**
   * Times the sampler ran out of attempts — a cell saturated by separation OR
   * one that only clips the drawn shape at a corner, so no in-shape point far
   * enough from its neighbours was found. Left short; the next run tries again.
   */
  separationOrShapeRejections: number;
  /** Areas whose crowns were NOT written because the area was deactivated mid-pass. */
  areasDeactivatedMidPass: number;
  /**
   * Areas skipped because their stored bounding box exceeds MAX_AREA_CELLS — only
   * reachable by a document that bypassed the CRUD size gate. Zero in normal
   * operation; a non-zero value means a malformed area wants cleaning up.
   */
  areasSkippedOversize: number;
  /**
   * Areas skipped because they have NO cached safe-stop POIs. SAFETY-FIRST: an
   * area with no parking/fuel/charging POIs spawns NOTHING (rather than falling
   * back to random placement), so this counts every active area whose POI cache
   * is empty — a freshly-marked area whose ingestion has not landed yet, or a
   * genuinely POI-less region an admin should re-draw.
   */
  areasWithoutPois: number;
  /** True when the run stopped on {@link MAX_AREA_SPAWNS_PER_RUN}. */
  capped: boolean;
  /** True when the feature flag was off and nothing ran. */
  skipped: boolean;
}

/**
 * Runs one MARKED-AREA replenish pass against `now`.
 *
 * The parallel of {@link runCrownSpawnPass} for admin-DRAWN areas
 * (`crownSpawnAreas`) rather than hand-approved single cells (`crownSpawnCells`).
 * It reuses every property of the single-cell engine unchanged — the per-cell
 * activity score `A`, `targetCrownCount`, the 3×3 neighbourhood separation, the
 * rarity table and TTL — but its PLACEMENT is SAFE-STOP-ANCHORED rather than
 * random: instead of drawing a uniform-random in-shape point, a crown is placed
 * AT a cached OpenStreetMap safe-stop POI (a parking lot, fuel station, or
 * charging station) that lies inside the area — optionally jittered ≤ ~5 m — via
 * {@link samplePoiPlacement}. The POIs are ingested per area into
 * `crownSpawnAreaPois/{areaId}/pois` (poiIngestion.ts) and are already filtered
 * to the drawn shape at ingestion, so every anchor is genuinely inside the area.
 *
 * SAFETY-FIRST: an area with NO cached POIs spawns NOTHING (logged), rather than
 * falling back to random placement — the entire point is that crowns appear only
 * at real safe stops. Everything else is preserved: the two engines share the
 * `crownSpawns` collection and its neighbourhood read, so 150 m separation holds
 * ACROSS both sources (and stops two crowns stacking on one POI), and a cell
 * covered by both a manual approval and an area cannot exceed its per-cell target.
 *
 * The gates are identical in spirit to the single-cell path: the `crownHuntSpawn`
 * flag (this returns `skipped` while off), the admin allow-list (here the
 * candidate set IS `crownSpawnAreas where active == true`, and `active` can only
 * be true while `safeAreaConfirmed` is — enforced by the CRUD callables and
 * re-checked defensively below), and the `A < 1` activity floor plus the
 * slow-sighting filter narrowing placement within an approved area.
 */
/**
 * Advances an area's round-robin cursor, tolerating a concurrent delete.
 *
 * `crownHunt.deleteSpawnArea` hard-deletes the area document, and it can land
 * while this scheduled pass is iterating — at which point `update()` throws
 * NOT_FOUND (gRPC status 5). That is a benign race: the delete has already
 * drained the area's crowns, and there is nothing left to serve. Swallow ONLY
 * that case so one concurrently-deleted area cannot fail (and retry) the entire
 * scheduler pass; every other error is real and rethrown.
 */
async function advanceAreaCursor(
  ref: FirebaseFirestore.DocumentReference,
  data: DocumentData,
): Promise<void> {
  try {
    await ref.update(data);
  } catch (error) {
    // Match the repo's parent-missing idiom (onRsvpWrite.ts / onMessageReportCreate.ts):
    // the Admin SDK surfaces NOT_FOUND as EITHER the numeric gRPC status 5 OR the
    // string 'not-found', so both count as the benign concurrent-delete case.
    const code = (error as { code?: number | string }).code;
    if (code === 5 || code === 'not-found') {
      logger.info('Crown area cursor skipped: area deleted mid-pass', { areaId: ref.id });
      return;
    }
    throw error;
  }
}

export async function runCrownAreaSpawnPass(
  now: Date,
  limits: CrownAreaSpawnLimits = {
    maxAreas: MAX_AREAS_PER_RUN,
    maxCells: MAX_AREA_CELLS_PER_RUN,
    maxSpawns: MAX_AREA_SPAWNS_PER_RUN,
  },
  rng: () => number = Math.random,
): Promise<CrownAreaSpawnResult> {
  const result: CrownAreaSpawnResult = {
    areasScanned: 0,
    cellsScanned: 0,
    cellsBelowActivityFloor: 0,
    spawned: 0,
    separationOrShapeRejections: 0,
    areasDeactivatedMidPass: 0,
    areasSkippedOversize: 0,
    areasWithoutPois: 0,
    capped: false,
    skipped: false,
  };

  if (!(await readFeatureFlag(CROWN_SPAWN_FLAG_KEY))) {
    result.skipped = true;
    return result;
  }

  const nowTs = Timestamp.fromDate(now);
  const activityCutoff = Timestamp.fromMillis(now.getTime() - ACTIVITY_WINDOW_MS);

  // ACTIVE AREAS ARE THE CANDIDATE SET, least-recently-served first — the same
  // round-robin shape as the cell allow-list. `lastSpawnPassAt` is seeded to the
  // epoch on create/activate (spawnAreas.ts) so a never-served area sorts to the
  // front and a freshly activated one is picked up next pass.
  const areas = await db
    .collection('crownSpawnAreas')
    .where('active', '==', true)
    .orderBy('lastSpawnPassAt', 'asc')
    .limit(Math.max(1, limits.maxAreas))
    .get();

  let cellBudget = Math.max(1, limits.maxCells);

  for (const areaDoc of areas.docs) {
    if (result.spawned >= limits.maxSpawns) {
      result.capped = true;
      break;
    }
    if (cellBudget <= 0) break;

    const areaData = areaDoc.data();
    // Defensive: the query filters on `active`, but re-confirm the safety flag
    // the CRUD path couples to it, so a hand-edited doc with active:true and
    // safeAreaConfirmed:false is inert here too.
    if (areaData.safeAreaConfirmed !== true) {
      await advanceAreaCursor(areaDoc.ref, { lastSpawnPassAt: nowTs });
      continue;
    }
    result.areasScanned += 1;

    const shape = areaData.shape as CrownSpawnAreaShape | undefined;
    const approvedByUserId = (areaData.approvedByUserId as string | undefined) ?? null;

    // Advance the area cursor for EVERY area we look at (attention, not output),
    // exactly like the cell pass, so a permanently quiet area cannot camp the
    // head of the queue.
    let nextCellOffset = (areaData.nextCellOffset as number | undefined) ?? 0;

    if (!shape) {
      await advanceAreaCursor(areaDoc.ref, { lastSpawnPassAt: nowTs });
      continue;
    }

    const enumeration = cellKeysForBoundingBox(shapeBoundingBox(shape), MAX_AREA_CELLS);
    // The CRUD callables reject any shape whose bounding box exceeds
    // MAX_AREA_CELLS, so a truncated enumeration here means the stored document
    // bypassed that gate (a console edit or a hand-written migration). Spawning
    // in the PREFIX subset the cap kept would be surprising partial coverage of
    // an area that should never have been storable — so skip it entirely this
    // pass rather than place crowns in an arbitrary corner of an oversize box.
    if (enumeration.truncated) {
      result.areasSkippedOversize += 1;
      logger.warn('Crown area spawn skipped: bounding box exceeds MAX_AREA_CELLS', {
        areaId: areaDoc.id,
      });
      await advanceAreaCursor(areaDoc.ref, { lastSpawnPassAt: nowTs });
      continue;
    }
    const cells = enumeration.keys;
    if (cells.length === 0) {
      await advanceAreaCursor(areaDoc.ref, { lastSpawnPassAt: nowTs });
      continue;
    }

    // SAFE-STOP ANCHORS. Load this area's cached OpenStreetMap POIs (parking /
    // fuel / charging), already filtered to the drawn shape at ingestion, and
    // group them by grid cell. SAFETY-FIRST: an area with no cached POIs spawns
    // NOTHING — the whole point is real safe stops, so we do not fall back to
    // random placement. (A freshly-marked area whose ingestion has not landed yet
    // lands here too, and simply gets its crowns on the next pass once its POIs
    // are cached.)
    const poiSnap = await db
      .collection('crownSpawnAreaPois')
      .doc(areaDoc.id)
      .collection('pois')
      .limit(MAX_AREA_POIS_LOADED)
      .get();
    if (poiSnap.empty) {
      result.areasWithoutPois += 1;
      logger.warn('Crown area spawn skipped: area has no safe POIs; skipping', {
        areaId: areaDoc.id,
      });
      await advanceAreaCursor(areaDoc.ref, { lastSpawnPassAt: nowTs });
      continue;
    }
    const poisByCell = new Map<string, NormalizedPoi[]>();
    for (const doc of poiSnap.docs) {
      const data = doc.data();
      const lat = data.lat;
      const lon = data.lon;
      const category = data.category;
      if (typeof lat !== 'number' || typeof lon !== 'number') continue;
      const key =
        typeof data.cellKey === 'string' && data.cellKey.length > 0
          ? data.cellKey
          : crownCellKey(lat, lon);
      const bucket = poisByCell.get(key);
      const poi: NormalizedPoi = {
        lat,
        lon,
        category: category === 'fuel' || category === 'charging' ? category : 'parking',
      };
      if (bucket) bucket.push(poi);
      else poisByCell.set(key, [poi]);
    }

    const accept = pointInShapeAccept(shape);
    const startOffset = ((nextCellOffset % cells.length) + cells.length) % cells.length;
    let processed = 0;

    for (
      let step = 0;
      step < cells.length && processed < cellBudget && result.spawned < limits.maxSpawns;
      step += 1
    ) {
      const cellKey = cells[(startOffset + step) % cells.length]!;
      processed += 1;
      result.cellsScanned += 1;

      const neighbours = neighbourCrownCells(cellKey);
      if (neighbours.length === 0) continue; // enumerated keys always parse; defensive.

      // No safe-stop POI in this cell → nothing to anchor to, so skip BEFORE the
      // activity/neighbourhood reads. Most cells of a big area's bounding box have
      // no POI, so this keeps the pass cheap.
      const cellPois = poisByCell.get(cellKey);
      if (!cellPois || cellPois.length === 0) continue;

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
        result.cellsBelowActivityFloor += 1;
        continue;
      }

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

      const pending: { ref: FirebaseFirestore.DocumentReference; data: DocumentData }[] = [];
      for (let i = 0; i < deficit; i += 1) {
        // POI-ANCHORED: place at a cached safe-stop POI inside this cell (already
        // in-shape at ingestion), jittered ≤ ~5 m, that clears 150 m from every
        // live crown. Null when every in-cell POI is too close to an existing
        // crown — leave the cell short, the next pass tries again. `accept`
        // re-checks the shape after jitter; `cellKey` keeps a jittered point in
        // its own cell (falling back to the exact POI point otherwise).
        const position = samplePoiPlacement(cellPois, occupied, rng, {
          cellKey,
          accept,
          jitterMeters: POI_JITTER_METERS,
        });
        if (!position) {
          result.separationOrShapeRejections += 1;
          break;
        }
        occupied.push(position);

        const rarity = pickCrownRarity(rng());
        pending.push({
          ref: db.collection('crownSpawns').doc(),
          data: {
            ...buildCrownSpawnFields({
              cellKey,
              position,
              rarity,
              approvedCellBy: approvedByUserId,
              areaId: areaDoc.id,
            }),
            createdAt: FieldValue.serverTimestamp(),
            expiresAt: Timestamp.fromDate(crownExpiresAt(rarity, now)),
            claimedAt: null,
          },
        });
      }

      if (pending.length > 0) {
        // Re-check the AREA's active+safe flags at write time, transactionally —
        // the same window the cell path closes: a deactivation landing mid-pass
        // drains the area's crowns, and this transaction either commits before
        // that drain (which then deletes these) or re-reads active:false and
        // writes nothing.
        const committed = await db.runTransaction(async (tx) => {
          const fresh = await tx.get(areaDoc.ref);
          if (fresh.get('active') !== true || fresh.get('safeAreaConfirmed') !== true) return 0;
          for (const crown of pending) tx.set(crown.ref, crown.data);
          return pending.length;
        });
        if (committed === 0) {
          result.areasDeactivatedMidPass += 1;
          logger.warn('Crown area spawn skipped: area deactivated mid-pass', {
            areaId: areaDoc.id,
          });
        }
        result.spawned += committed;
      }
    }

    cellBudget -= processed;
    nextCellOffset = (startOffset + processed) % cells.length;
    await advanceAreaCursor(areaDoc.ref, { lastSpawnPassAt: nowTs, nextCellOffset });
  }

  logger.info('Crown area spawn pass complete', { ...result });
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
  withServerErrorReporting('crownHunt.spawnCrowns', async () => {
    // Both candidate sources, in one serialized invocation. The single-cell pass
    // runs first, then the marked-area pass; because the area pass reads the live
    // neighbourhood fresh per cell, it sees the crowns the cell pass just placed,
    // so the 150 m separation rule holds across BOTH sources within the tick.
    // maxInstances:1 + concurrency:1 (SPAWN_SCHEDULE_OPTS) keep the two passes
    // from ever overlapping another invocation's.
    const now = new Date();
    await runCrownSpawnPass(now);
    await runCrownAreaSpawnPass(now);
  }),
);

/** TTL sweep, every 15 minutes (mirrors `incidents-cleanupExpired`). */
export const sweepSpawns = onSchedule(
  { ...SPAWN_SCHEDULE_OPTS, schedule: '*/15 * * * *' },
  withServerErrorReporting('crownHunt.sweepSpawns', async () => {
    await runCrownSpawnCleanup(new Date());
  }),
);
