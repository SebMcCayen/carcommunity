/**
 * crownHunt.spawnDiagnostics — admin-only, READ-ONLY troubleshooting view over
 * the MARKED-AREA auto-spawn engine (spawnScheduled.ts, `runCrownAreaSpawnPass`).
 *
 * An admin who has drawn an auto-spawn area needs to see, for that area:
 *  1. WHEN the next replenish run is (a live countdown, client-side, off the
 *     server clock + the next 10-minute cron boundary), and an ESTIMATE of when
 *     THIS area is next visited given its round-robin queue position;
 *  2. WHERE crowns would land — the area's grid cells that are currently below
 *     target and have a cached safe-stop POI to anchor to, with each cell's
 *     centroid/bounds, live-vs-target count, and a sample of its POI anchors;
 *  3. WHY nothing is spawning — the area-level blockers the pass checks (flag
 *     off, inactive, unconfirmed, oversize box, no cached POIs, stale POIs, all
 *     cells below the activity floor / already at target).
 *
 * It reuses the ENGINE's own constants and per-cell decision shape (the activity
 * score `A`, `targetCrownCount`, the 3×3 neighbourhood live count, the round-robin
 * cursor, the per-run budgets) rather than a second copy, so the view cannot
 * drift from what the scheduler actually does. It is purely observational: no
 * writes, no audit record (nothing changed), `requireAdminActor` + App Check.
 *
 * The spawn-cell / area collections are backend-only in firestore.rules, which is
 * exactly why this has to be a callable — the admin web cannot read spawn state
 * directly. Everything time-relative is returned as raw facts (serverTime,
 * nextRunAt, areasAhead) and turned into a ticking countdown / ETA on the client,
 * and every such number is framed there as an ESTIMATE.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { Timestamp, type DocumentData } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';
import { readFeatureFlag } from '../shared/featureFlags';
import {
  ACTIVITY_WINDOW_MS,
  CROWN_BASELINE_TARGET_PER_CELL,
  CROWN_SPAWN_FLAG_KEY,
  activityScore,
  crownCellBounds,
  neighbourCrownCells,
  targetCrownCount,
} from './crown-spawn-core';
import {
  MAX_AREA_CELLS,
  cellKeysForBoundingBox,
  shapeBoundingBox,
  type CrownSpawnAreaShape,
} from './crown-area-core';
import {
  MAX_ACTIVITY_USERS_PER_CELL,
  MAX_AREAS_PER_RUN,
  MAX_AREA_CELLS_PER_RUN,
  MAX_NEIGHBOURHOOD_CROWNS,
  SPAWN_RUN_INTERVAL_MS,
} from './spawnScheduled';
import {
  classifyDiagnosticCell,
  nextScheduledSpawnRun,
  type DiagnosticCellReason,
  type DiagnosticPoiAnchor,
} from './spawn-diagnostics-core';
import type { PoiCategory } from './osm-poi-core';

// Local contract types (the functions package does not depend on
// @carcommunity/shared; these MIRROR AdminCrownSpawnDiagnostics* there and the
// crown-hunt.schema.json $defs, the same way spawnAreas.ts mirrors its summary).

/** Area-level reasons the marked-area spawner is placing nothing right now. */
export type SpawnDiagnosticBlocker =
  | 'spawn_flag_off'
  | 'area_inactive'
  | 'area_not_confirmed'
  | 'area_oversize'
  | 'no_area_pois'
  | 'pois_stale'
  | 'all_cells_below_activity_floor'
  | 'all_cells_at_target';

interface SpawnDiagnosticCell {
  cellKey: string;
  center: { lat: number; lon: number };
  bounds: { minLat: number; maxLat: number; minLon: number; maxLon: number };
  activityScore: number;
  target: number;
  liveCount: number;
  deficit: number;
  poiCount: number;
  poiCountCapped: boolean;
  poiAnchors: DiagnosticPoiAnchor[];
  reason: DiagnosticCellReason;
  eligible: boolean;
}

export interface SpawnDiagnosticsResponse {
  areaId: string;
  name: string | null;
  shape: CrownSpawnAreaShape;
  flagEnabled: boolean;
  active: boolean;
  safeAreaConfirmed: boolean;
  areaPoiCount: number;
  poisRefreshedAt: string | null;
  serverTime: string;
  nextRunAt: string;
  runIntervalSeconds: number;
  activeAreaCount: number;
  areasAhead: number;
  maxAreasPerRun: number;
  maxAreaCellsPerRun: number;
  lastSpawnPassAt: string | null;
  totalCells: number;
  cellsTruncated: boolean;
  nextCellOffset: number;
  cellsScanned: number;
  candidateCellCount: number;
  cells: SpawnDiagnosticCell[];
  blockers: SpawnDiagnosticBlocker[];
}

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/**
 * Cells examined per diagnostics call, from the area's round-robin cursor — the
 * SAME window the next run's cell budget would walk, so "cells this call scanned"
 * mirrors "cells the next visit would process for this area". Bounds the read
 * cost of a diagnostics call to at most this many cell-neighbourhood reads.
 */
const DIAGNOSTICS_MAX_CELLS_SCANNED = MAX_AREA_CELLS_PER_RUN;

/**
 * Cells scanned concurrently per batch. Each cell issues three reads, so this
 * caps the per-call read fan-out at ~3× this value at any instant instead of
 * bursting the whole scan window (~60 cells) at once.
 */
const DIAGNOSTICS_SCAN_CONCURRENCY = 8;

/** Cached POIs read per cell for the count/anchors — bounded like the spawn pass. */
const MAX_DIAGNOSTIC_POIS_PER_CELL = 50;

/** POI anchors surfaced per cell for the map/list (a sample, not the full set). */
const MAX_POI_ANCHORS_PER_CELL = 12;

/** Active areas read to compute this area's queue position (admins draw few). */
const MAX_ACTIVE_AREAS_SCANNED = 500;

/**
 * POIs are re-ingested weekly (poiIngestion.ts `refreshAreaPois`). A cache older
 * than this — a week plus a day of grace — is flagged as possibly stale so an
 * admin can tell "no POIs found here" from "the cache never refreshed".
 */
const POI_STALE_AFTER_MS = 8 * 24 * 60 * 60 * 1000;

const AREA_ID_RE = /^[A-Za-z0-9_-]+$/;

function toIsoOrNull(value: unknown): string | null {
  return value instanceof Timestamp ? value.toDate().toISOString() : null;
}

function toMillisOrNull(value: unknown): number | null {
  return value instanceof Timestamp ? value.toMillis() : null;
}

function normalizePoiCategory(value: unknown): PoiCategory {
  return value === 'fuel' || value === 'charging' ? value : 'parking';
}

export const spawnDiagnostics = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SpawnDiagnosticsResponse> => {
    await requireAdminActor(request);

    const raw = (request.data ?? {}) as { areaId?: unknown };
    const areaId = typeof raw.areaId === 'string' ? raw.areaId.trim() : '';
    if (!areaId || areaId.length > 64 || !AREA_ID_RE.test(areaId)) {
      throw new HttpsError('invalid-argument', 'A valid areaId is required.');
    }

    const areaSnap = await db.collection('crownSpawnAreas').doc(areaId).get();
    if (!areaSnap.exists) {
      throw new HttpsError('not-found', 'Marked area not found.');
    }
    const areaData = areaSnap.data() as DocumentData;

    const now = new Date();
    const nowMs = now.getTime();
    const nowTs = Timestamp.fromDate(now);
    const activityCutoff = Timestamp.fromMillis(nowMs - ACTIVITY_WINDOW_MS);

    const flagEnabled = await readFeatureFlag(CROWN_SPAWN_FLAG_KEY);
    const active = areaData.active === true;
    const safeAreaConfirmed = areaData.safeAreaConfirmed === true;
    // A shape-less area cannot be diagnosed and would return an out-of-contract
    // payload (the response schema requires `shape`). The live pass defensively
    // skips such an area; here we fail fast with a clear error, since a corrupted
    // area an admin opened diagnostics on is worth surfacing rather than hiding.
    const shape = areaData.shape as CrownSpawnAreaShape | undefined;
    if (!shape || typeof shape !== 'object' || typeof shape.type !== 'string') {
      throw new HttpsError('failed-precondition', 'This area has no drawn shape to diagnose.');
    }
    // Clamp the two count fields the schema requires to be non-negative: a
    // console-edited/corrupted document could hold a negative, and the scan below
    // normalizes the offset for enumeration regardless, so clamping only the
    // reported values keeps the response in-contract without changing behaviour.
    const areaPoiCount =
      typeof areaData.poiCount === 'number' ? Math.max(0, Math.trunc(areaData.poiCount)) : 0;
    const poisRefreshedMs = toMillisOrNull(areaData.poisRefreshedAt);
    const rawNextCellOffset =
      typeof areaData.nextCellOffset === 'number' ? areaData.nextCellOffset : 0;
    const nextCellOffset = Math.max(0, Math.trunc(rawNextCellOffset));
    const thisPassMs = toMillisOrNull(areaData.lastSpawnPassAt) ?? 0;
    // The epoch sentinel means "never served" (spawnAreas.ts seeds it), so it is
    // null for display rather than 1970.
    const lastSpawnPassAt = thisPassMs > 0 ? new Date(thisPassMs).toISOString() : null;

    // -----------------------------------------------------------------------
    // Round-robin queue position among ACTIVE areas (least-recently-served
    // first), the same ordering `runCrownAreaSpawnPass` reads: Firestore breaks
    // an equal `lastSpawnPassAt` by ascending document id (__name__), so ties —
    // common for epoch-seeded new areas — must break the SAME way here or the
    // queue estimate diverges from the real serving order. Counting the ordered
    // result's docs BEFORE this area (by (lastSpawnPassAt, id)) does exactly that.
    // -----------------------------------------------------------------------
    const activeAreasSnap = await db
      .collection('crownSpawnAreas')
      .where('active', '==', true)
      .orderBy('lastSpawnPassAt', 'asc')
      .limit(MAX_ACTIVE_AREAS_SCANNED)
      .get();
    const activeAreaCount = activeAreasSnap.size;
    let areasAhead = 0;
    for (const doc of activeAreasSnap.docs) {
      if (doc.id === areaId) continue;
      const ms = toMillisOrNull(doc.get('lastSpawnPassAt')) ?? 0;
      if (ms < thisPassMs || (ms === thisPassMs && doc.id < areaId)) areasAhead += 1;
    }

    // -----------------------------------------------------------------------
    // Cell enumeration + scan window (from the cursor), mirroring the pass.
    // -----------------------------------------------------------------------
    const enumeration = cellKeysForBoundingBox(shapeBoundingBox(shape), MAX_AREA_CELLS);
    const allKeys = enumeration.keys;
    const totalCells = allKeys.length;
    const cellsTruncated = enumeration.truncated;

    const scanCount = Math.min(DIAGNOSTICS_MAX_CELLS_SCANNED, totalCells);
    const startOffset =
      totalCells > 0 ? ((nextCellOffset % totalCells) + totalCells) % totalCells : 0;
    const scannedKeys: string[] = [];
    for (let i = 0; i < scanCount; i += 1) {
      scannedKeys.push(allKeys[(startOffset + i) % totalCells]!);
    }

    const scanCell = async (cellKey: string): Promise<SpawnDiagnosticCell> => {
      const bounds = crownCellBounds(cellKey);

      // A(cell): one decayed weight per distinct recent user, same read the
      // pass makes (same cutoff, same cap, distinctness by document id).
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
        .map((doc) => toMillisOrNull(doc.data().lastSeenAt))
        .filter((value): value is number => value !== null);
      const activity = activityScore(lastSeenValues, nowMs);
      // MIRROR the marked-area pass exactly (runCrownAreaSpawnPass): its target is
      // BASELINE + activity-derived, so the diagnostics must add the same baseline
      // or an admin would see "below activity floor / would not spawn" for a cell
      // the pass actually populates from the baseline.
      const target = targetCrownCount(activity, { baseline: CROWN_BASELINE_TARGET_PER_CELL });

      // Live crowns IN this cell, counted from the 3×3 neighbourhood read the
      // pass uses for the separation check.
      const neighbours = neighbourCrownCells(cellKey);
      let liveInCell = 0;
      if (neighbours.length > 0) {
        const neighbourhood = await db
          .collection('crownSpawns')
          .where('cellKey', 'in', neighbours)
          .where('status', '==', 'live')
          .where('expiresAt', '>', nowTs)
          .limit(MAX_NEIGHBOURHOOD_CROWNS)
          .get();
        for (const doc of neighbourhood.docs) {
          if (doc.get('cellKey') === cellKey) liveInCell += 1;
        }
      }

      // Cached safe-stop POIs inside this cell — what a crown would anchor to.
      const poiSnap = await db
        .collection('crownSpawnAreaPois')
        .doc(areaId)
        .collection('pois')
        .where('cellKey', '==', cellKey)
        .limit(MAX_DIAGNOSTIC_POIS_PER_CELL)
        .get();
      const poiCount = poiSnap.size;
      const poiCountCapped = poiSnap.size >= MAX_DIAGNOSTIC_POIS_PER_CELL;
      const poiAnchors: DiagnosticPoiAnchor[] = poiSnap.docs
        .slice(0, MAX_POI_ANCHORS_PER_CELL)
        .map((doc) => {
          const data = doc.data();
          return {
            lat: data.lat as number,
            lon: data.lon as number,
            category: normalizePoiCategory(data.category),
          };
        })
        .filter((anchor) => typeof anchor.lat === 'number' && typeof anchor.lon === 'number');

      const classification = classifyDiagnosticCell({ target, liveCount: liveInCell, poiCount });
      const center = bounds
        ? { lat: (bounds.minLat + bounds.maxLat) / 2, lon: (bounds.minLon + bounds.maxLon) / 2 }
        : { lat: 0, lon: 0 };

      return {
        cellKey,
        center,
        bounds: bounds ?? { minLat: 0, maxLat: 0, minLon: 0, maxLon: 0 },
        activityScore: activity,
        target,
        liveCount: liveInCell,
        deficit: classification.deficit,
        poiCount,
        poiCountCapped,
        poiAnchors,
        reason: classification.reason,
        eligible: classification.eligible,
      };
    };

    // Bound concurrency: scan the cells in small sequential batches rather than
    // firing every cell's reads at once. Each cell issues three reads, so a
    // fully-parallel scan of the ~60-cell window could burst ~180 concurrent
    // reads per call; batching keeps one admin call's fan-out modest (and the
    // per-cell reads within a batch still overlap) without going fully serial.
    const cells: SpawnDiagnosticCell[] = [];
    for (let i = 0; i < scannedKeys.length; i += DIAGNOSTICS_SCAN_CONCURRENCY) {
      const batch = scannedKeys.slice(i, i + DIAGNOSTICS_SCAN_CONCURRENCY);
      cells.push(...(await Promise.all(batch.map(scanCell))));
    }

    const candidateCellCount = cells.filter((cell) => cell.eligible).length;

    // -----------------------------------------------------------------------
    // Area-level blockers. Independent — any subset can hold. The "all cells"
    // hints are over the SCANNED window (the same window the next visit walks),
    // and only meaningful once at least one cell was scanned.
    // -----------------------------------------------------------------------
    const blockers: SpawnDiagnosticBlocker[] = [];
    if (!flagEnabled) blockers.push('spawn_flag_off');
    if (!active) blockers.push('area_inactive');
    if (!safeAreaConfirmed) blockers.push('area_not_confirmed');
    if (cellsTruncated) blockers.push('area_oversize');
    if (areaPoiCount <= 0) blockers.push('no_area_pois');
    if (poisRefreshedMs !== null && nowMs - poisRefreshedMs > POI_STALE_AFTER_MS) {
      blockers.push('pois_stale');
    }
    if (cells.length > 0 && cells.every((cell) => cell.reason === 'below_activity_floor')) {
      blockers.push('all_cells_below_activity_floor');
    } else if (
      candidateCellCount === 0 &&
      cells.some((cell) => cell.reason === 'at_target') &&
      !cells.some((cell) => cell.reason === 'would_spawn')
    ) {
      blockers.push('all_cells_at_target');
    }

    return {
      areaId,
      name: (areaData.name as string | null) ?? null,
      shape,
      flagEnabled,
      active,
      safeAreaConfirmed,
      areaPoiCount,
      poisRefreshedAt: toIsoOrNull(areaData.poisRefreshedAt),
      serverTime: now.toISOString(),
      nextRunAt: nextScheduledSpawnRun(now, SPAWN_RUN_INTERVAL_MS).toISOString(),
      runIntervalSeconds: Math.round(SPAWN_RUN_INTERVAL_MS / 1000),
      activeAreaCount,
      areasAhead,
      maxAreasPerRun: MAX_AREAS_PER_RUN,
      maxAreaCellsPerRun: MAX_AREA_CELLS_PER_RUN,
      lastSpawnPassAt,
      totalCells,
      cellsTruncated,
      nextCellOffset,
      cellsScanned: cells.length,
      candidateCellCount,
      cells,
      blockers,
    };
  },
);
