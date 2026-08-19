/**
 * Kronjakt auto-spawn — the scheduled REPLENISHER and SWEEPER.
 *
 * `crownHunt-spawnCrowns` (every 10 min): tops each grid cell of an ADMIN-DRAWN
 * marked area up toward its per-cell target, respecting the minimum separation
 * between live crowns. The target is BASELINE + activity-derived (so an approved
 * area populates its safe stops even at zero recent activity), and every crown
 * is placed AT a cached safe-stop POI inside the area.
 *
 * NOTE: the former single-cell random-placement path (`runCrownSpawnPass` over
 * the `crownSpawnCells` allow-list) has been REMOVED. Only the marked-area
 * (Områden) path remains. The `setSpawnCellApproval` callable and the
 * `crownSpawnCells` collection are intentionally left in place, dormant, so
 * removing a live-deployed function does not create an orphan that aborts the
 * non-interactive deploy — a coordinated follow-up can delete them.
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
 *  2. the ADMIN ALLOW-LIST — the candidate set is always something an admin
 *     approved, never the set of cells that happen to have activity. The
 *     marked-area path (`runCrownAreaSpawnPass`) scans `crownSpawnAreas` where
 *     `active == true` (with `safeAreaConfirmed` re-checked defensively per
 *     area, spawnAreas.ts) and places crowns AT cached safe-stop POIs — targeting
 *     BASELINE + activity. An area nobody approved is invisible to this function,
 *     however the activity data looks;
 *  3. WITHIN an approved area, what narrows WHERE a crown lands: the
 *     slow-sighting activity filter plus POI ANCHORING. Every crown — baseline
 *     or activity-derived — is placed AT a cached safe-stop POI, so a cell with
 *     no POI spawns nothing however it is targeted.
 * Each gate is independently sufficient to produce zero spawns. That is
 * deliberate: the failure mode this engine has to be defended against is
 * inviting a member to stop somewhere dangerous, and no single condition should
 * be the only thing standing between the algorithm and that outcome.
 *
 * ## Bounding
 * Every loop in here is bounded twice: by a per-run CELL budget and by a
 * per-run SPAWN budget, plus a per-cell attempt budget inside the rejection
 * sampler. Approved areas are visited LEAST-RECENTLY-SERVED first, so when the
 * approved list outgrows one run's budget the remainder is served on the next
 * pass rather than starved — an area that misses a round is at most 10 minutes
 * below target.
 *
 * ## Staggering (so crowns don't all appear at once)
 * A single pass never fills a cell's WHOLE deficit: it creates at most
 * {@link MAX_NEW_CROWNS_PER_CELL_PER_PASS} new crowns per cell, so an empty cell
 * fills over successive 10-minute passes rather than writing every crown in one
 * batch that a client's listener then pops onto the map simultaneously. The cell
 * still converges to its target; it just gets there across a few ticks. The
 * finer, in-pass spread (several cells of one area filling on the same tick) is
 * the client marker animator's job — it reveals a batch of newly-arrived crowns
 * a few hundred ms apart. Overall spawn DENSITY is unchanged: only the timing of
 * the fill is spread out.
 *
 * ## Cost per run
 * One allow-list query, then per grid cell of an approved area: one bounded
 * `recentUsers` read and one 9-cell neighbourhood read, and only for cells that
 * are actually short of target, a bounded POI read plus a handful of document
 * creates. Cells already at target cost two reads and one cursor write.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldValue, Timestamp, type DocumentData } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import { CPU_SCHEDULED } from '../shared/instanceLimits';
import {
  ACTIVITY_WINDOW_MS,
  CROWN_BASELINE_TARGET_PER_CELL,
  CROWN_SPAWN_FLAG_KEY,
  activityScore,
  buildCrownSpawnFields,
  crownExpiresAt,
  neighbourCrownCells,
  pickCrownRarity,
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
 * Distinct-user documents read per cell when computing `A`.
 *
 * The score is logarithmic and capped at 5 crowns by A ≈ 27, so the difference
 * between 200 recent visitors and 2000 is nil. Reading the first 200 is enough
 * to saturate the curve while keeping the per-cell read cost flat.
 *
 * Exported so the read-only `crownHunt.spawnDiagnostics` callable can compute a
 * cell's activity score with the SAME read bound the live pass uses, rather than
 * a second copy that could drift from it.
 */
export const MAX_ACTIVITY_USERS_PER_CELL = 200;

/**
 * Live crowns loaded from a 3x3 neighbourhood for the separation check.
 * Exported for `crownHunt.spawnDiagnostics` (see {@link MAX_ACTIVITY_USERS_PER_CELL}).
 */
export const MAX_NEIGHBOURHOOD_CROWNS = 100;

/** Expired crowns deleted per sweep. */
const MAX_SWEEP_DELETIONS = 1000;

/** Documents per Firestore batched write. */
const WRITE_BATCH_SIZE = 400;

/** Quiet activity cells reaped per sweep (recursiveDelete, so kept small). */
const MAX_ACTIVITY_CELLS_REAPED = 100;

// ---------------------------------------------------------------------------
// Marked-area replenisher
// ---------------------------------------------------------------------------

/**
 * Areas examined per run, least-recently-served first. Admins draw a handful of
 * big areas, not thousands, so this is generous; the round-robin cursor
 * (`lastSpawnPassAt`) keeps a long list fair rather than starving its tail.
 */
export const MAX_AREAS_PER_RUN = 10;

/**
 * Grid CELLS processed per run across all areas — the read budget. A big area
 * spans many cells, so this is the bound that actually caps a run's cost; an
 * area's own cell cursor (`nextCellOffset`) advances so its tail is served on
 * later runs rather than always the same head.
 */
export const MAX_AREA_CELLS_PER_RUN = 60;

/** Crowns created per area run, across all areas — the hard write budget. */
export const MAX_AREA_SPAWNS_PER_RUN = 100;

/**
 * The most NEW crowns one pass may create in a SINGLE cell — the staggering
 * cap.
 *
 * Without it a cell that is empty (freshly approved, or just swept) fills its
 * ENTIRE per-cell deficit in one pass: up to {@link MAX_CROWNS_PER_CELL} crowns
 * written in the same batch, with the same `createdAt` instant. A client's
 * Firestore listener then receives them in one snapshot and every marker
 * pops onto the map at the same moment — the "they all appear at once" the
 * owner asked us to stop.
 *
 * Capping the per-cell deficit at a small number turns a cell's fill into a
 * SEQUENCE across successive 10-minute passes (2, then 2, then 1 for a cell
 * targeting the full 5) rather than a single burst — natural staggering with no
 * new schema, no client contract, and no slowdown of the overall sweep (a cell
 * still reaches its target; it just takes a few passes to get there). The
 * remaining in-pass / in-batch spread — several cells of one area filling on the
 * same tick — is handled on the client by the crown marker animator, which
 * reveals a batch of newly-arrived crowns a few hundred ms apart rather than
 * simultaneously.
 *
 * 2, not 1: one-per-pass would take a cell 5 passes (~50 min) to reach full
 * density after a sweep, which reads as an empty area for the better part of an
 * hour; 2 halves that to ~30 min while still guaranteeing a cell never dumps its
 * whole deficit in one instant. Exported so the emulator test can assert the
 * per-pass cap directly rather than hard-coding the number.
 */
export const MAX_NEW_CROWNS_PER_CELL_PER_PASS = 2;

/**
 * Cached safe-stop POIs read for ONE grid cell when it has a spawn deficit to
 * fill. A single ~1.1 km cell rarely holds more than a handful of parking/fuel/
 * charging stops, and the per-cell density budget is at most 5 crowns with a
 * 150 m separation, so a bounded page is ample; this caps the read so a
 * pathologically POI-dense cell cannot blow up the pass. POIs are loaded per cell
 * and only on a deficit, so an at-target area reads zero POI docs per tick.
 */
const MAX_POIS_PER_CELL_READ = 200;

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
  /**
   * Cells that produced a per-cell target of 0 and so spawned nothing. With a
   * non-zero {@link CROWN_BASELINE_TARGET_PER_CELL} an approved area's target is
   * always ≥ the baseline, so this is 0 while the baseline is enabled; it counts
   * below-floor cells only when the baseline constant is set to 0.
   */
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
 * The auto-spawn engine for admin-DRAWN areas (`crownSpawnAreas`) — the sole
 * spawn path since the hand-approved single-cell path was removed. Per grid
 * cell it uses the per-cell activity score `A`, `targetCrownCount`, the 3×3
 * neighbourhood separation, the rarity table and TTL. Its per-cell target adds a
 * BASELINE ({@link CROWN_BASELINE_TARGET_PER_CELL}) on top of the activity-derived
 * amount, so an approved area receives a few crowns even at `A = 0` (a low-usage
 * area with no recent traffic still populates), clamped to the per-cell cap.
 * Its PLACEMENT is SAFE-STOP-ANCHORED: a crown is placed
 * AT a cached OpenStreetMap safe-stop POI (a parking lot, fuel station, or
 * charging station) that lies inside the area — optionally jittered ≤ ~5 m — via
 * {@link samplePoiPlacement}. The POIs are ingested per area into
 * `crownSpawnAreaPois/{areaId}/pois` (poiIngestion.ts) and are already filtered
 * to the drawn shape at ingestion, so every anchor is genuinely inside the area.
 *
 * SAFETY-FIRST: an area with NO cached POIs spawns NOTHING (logged), rather than
 * falling back to random placement — the entire point is that crowns appear only
 * at real safe stops. 150 m separation is enforced via the shared `crownSpawns`
 * neighbourhood read (which also stops two crowns stacking on one POI).
 *
 * The gates: the `crownHuntSpawn` flag (this returns `skipped` while off), the
 * admin allow-list (the candidate set IS `crownSpawnAreas where active == true`,
 * and `active` can only be true while `safeAreaConfirmed` is — enforced by the
 * CRUD callables and re-checked defensively below), and — for the activity BONUS
 * only — the `A < 1` floor plus the slow-sighting filter. The baseline is
 * unconditional, so a cell below the activity floor is not skipped; it still
 * receives the baseline crowns, provided (as always) it has a cached safe-stop
 * POI to anchor them to.
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

/**
 * Loads the cached safe-stop POIs for ONE grid cell of an area, bounded. The
 * default reads `crownSpawnAreaPois/{areaId}/pois where cellKey == cellKey`,
 * capped at {@link MAX_POIS_PER_CELL_READ}. Injectable so a test can assert the
 * pass reads POIs ONLY for cells that actually have a spawn deficit (an idle tick
 * must do zero POI reads).
 */
export type AreaCellPoiLoader = (areaId: string, cellKey: string) => Promise<NormalizedPoi[]>;

const defaultCellPoiLoader: AreaCellPoiLoader = async (areaId, cellKey) => {
  const snap = await db
    .collection('crownSpawnAreaPois')
    .doc(areaId)
    .collection('pois')
    .where('cellKey', '==', cellKey)
    .limit(MAX_POIS_PER_CELL_READ)
    .get();
  const pois: NormalizedPoi[] = [];
  for (const doc of snap.docs) {
    const data = doc.data();
    const lat = data.lat;
    const lon = data.lon;
    if (typeof lat !== 'number' || typeof lon !== 'number') continue;
    const category = data.category;
    pois.push({
      lat,
      lon,
      category: category === 'fuel' || category === 'charging' ? category : 'parking',
    });
  }
  return pois;
};

export async function runCrownAreaSpawnPass(
  now: Date,
  limits: CrownAreaSpawnLimits = {
    maxAreas: MAX_AREAS_PER_RUN,
    maxCells: MAX_AREA_CELLS_PER_RUN,
    maxSpawns: MAX_AREA_SPAWNS_PER_RUN,
  },
  rng: () => number = Math.random,
  deps: { loadCellPois?: AreaCellPoiLoader } = {},
): Promise<CrownAreaSpawnResult> {
  const loadCellPois = deps.loadCellPois ?? defaultCellPoiLoader;
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

    // SAFE-STOP GATE (cheap, no POI reads). SAFETY-FIRST: an area with no cached
    // safe stops spawns NOTHING — the whole point is real stops, so we never fall
    // back to random placement. We read this off the area's stored `poiCount`
    // (stamped by ingestion, poiIngestion.ts) rather than loading POI docs, so a
    // POI-less area (or one whose ingestion has not landed yet) costs zero POI
    // reads. The actual POIs are loaded PER CELL, and only for a cell that has a
    // real spawn deficit (below), so an idle tick over an at-target area reads no
    // POI docs at all.
    const poiCount = typeof areaData.poiCount === 'number' ? areaData.poiCount : 0;
    if (poiCount <= 0) {
      result.areasWithoutPois += 1;
      logger.warn('Crown area spawn skipped: area has no safe POIs; skipping', {
        areaId: areaDoc.id,
      });
      await advanceAreaCursor(areaDoc.ref, { lastSpawnPassAt: nowTs });
      continue;
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

      // BASELINE + activity bonus. The baseline is added on THIS (POI-anchored)
      // path only, so an approved area at A = 0 still targets a few crowns — but
      // they are placed exclusively at cached safe-stop POIs below, never a random
      // point. With a non-zero baseline the target is never 0, so the below-floor
      // skip only fires when the baseline constant is set to 0.
      const target = targetCrownCount(activityScore(lastSeenValues, now.getTime()), {
        baseline: CROWN_BASELINE_TARGET_PER_CELL,
      });
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

      // STAGGERING: never create a cell's whole deficit in one pass. Capped at
      // MAX_NEW_CROWNS_PER_CELL_PER_PASS so an empty cell fills over successive
      // passes instead of dumping every crown into one snapshot the client then
      // pops onto the map simultaneously. Still bounded by the target, the
      // per-cell density cap (via `target`), and the run's remaining write
      // budget — this only ever LOWERS the count, never raises it, so the cell
      // still converges to its target, just across a few ticks.
      const deficit = Math.min(
        target - liveInCell,
        limits.maxSpawns - result.spawned,
        MAX_NEW_CROWNS_PER_CELL_PER_PASS,
      );
      if (deficit <= 0) continue;

      // ONLY NOW load this cell's cached safe-stop POIs — after we know there is a
      // real deficit to fill. A cell already at target (the common case on a
      // steady-state tick) reaches `continue` above and reads ZERO POI docs; the
      // read is bounded to MAX_POIS_PER_CELL_READ. If the cell has no POIs (its
      // area does, just not here) there is nothing to anchor to — skip it.
      const cellPois = await loadCellPois(areaDoc.id, cellKey);
      if (cellPois.length === 0) continue;

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
 * `runCrownAreaSpawnPass` reads a cell's live neighbourhood, then writes into it.
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
  // Half a vCPU — this scheduled pair is on the CPU_SCHEDULED tier in spirit
  // (one lightweight read-then-write pass per tick, not CPU-bound). concurrency
  // is already pinned to 1 above, so dropping below a full vCPU is allowed by
  // the gen2 "cpu < 1 requires concurrency 1" rule.
  cpu: CPU_SCHEDULED,
  concurrency: 1,
};

/**
 * Minutes between replenish passes. The single source of truth for BOTH the
 * `spawnCrowns` cron below and the `crownHunt.spawnDiagnostics` countdown, so the
 * "time to next spawn run" an admin sees can never drift from the actual
 * cadence. A step cron on the minute field fires on wall-clock minutes that are
 * multiples of N and aligned to the hour, and every timezone Sweden uses is a
 * whole-hour offset, so the next boundary is simply the next epoch multiple of
 * this interval.
 */
export const SPAWN_RUN_INTERVAL_MINUTES = 10;

/** {@link SPAWN_RUN_INTERVAL_MINUTES} in milliseconds. */
export const SPAWN_RUN_INTERVAL_MS = SPAWN_RUN_INTERVAL_MINUTES * 60 * 1000;

/** The replenish cron, derived from the interval so the two cannot disagree. */
const SPAWN_SCHEDULE_CRON = `*/${SPAWN_RUN_INTERVAL_MINUTES} * * * *`;

/**
 * Replenish pass, every 10 minutes.
 *
 * Faster than the 15-minute sweep so a cell that loses its crowns (collected or
 * expired) refills promptly, and slow enough that the whole engine costs a few
 * hundred reads an hour at the active footprint we expect.
 */
export const spawnCrowns = onSchedule(
  { ...SPAWN_SCHEDULE_OPTS, schedule: SPAWN_SCHEDULE_CRON },
  withServerErrorReporting('crownHunt.spawnCrowns', async () => {
    // The MARKED-AREA (Områden) pass is the sole candidate source. The former
    // single-cell random-placement pass (runCrownSpawnPass over crownSpawnCells)
    // was removed; only admin-drawn areas anchored to cached safe-stop POIs
    // spawn crowns now. maxInstances:1 + concurrency:1 (SPAWN_SCHEDULE_OPTS) keep
    // this pass from ever overlapping another invocation's.
    const now = new Date();
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
