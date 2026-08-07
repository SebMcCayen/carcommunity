/**
 * Pure helpers behind `crownHunt.spawnDiagnostics` — the admin-only, read-only
 * troubleshooting view over the MARKED-AREA auto-spawn engine (spawnScheduled.ts,
 * `runCrownAreaSpawnPass`).
 *
 * Kept db-free so they unit-test without the emulator and, more importantly, so
 * the diagnostics can reuse the ENGINE's own decision shape rather than a second
 * copy that could drift: {@link classifyDiagnosticCell} answers the same three
 * questions the pass asks of a cell (is it below the activity floor? is it at
 * target? does it have a safe-stop POI to anchor to?), in the same order.
 *
 * The types here MIRROR the client contract in packages/shared/src/crown-hunt.ts
 * (and the JSON schema) but are LOCAL, because the functions package
 * deliberately does not depend on `@carcommunity/shared` — the same convention
 * spawnAreas.ts follows for its area summary types.
 */

import type { PoiCategory } from './osm-poi-core';

/**
 * The next scheduled replenish-run boundary STRICTLY after `now`.
 *
 * A step cron on the minute field fires on epoch-aligned multiples of the
 * interval (see SPAWN_RUN_INTERVAL_MINUTES), so the next run is the next such
 * multiple. When `now` sits exactly on a boundary the run for it is firing
 * already, so the "next" one is a full interval away — the countdown never
 * sticks at zero.
 */
export function nextScheduledSpawnRun(now: Date, intervalMs: number): Date {
  const nowMs = now.getTime();
  const boundary = (Math.floor(nowMs / intervalMs) + 1) * intervalMs;
  return new Date(boundary);
}

/** Why a scanned cell would (or would not) receive a crown on the next visit. */
export type DiagnosticCellReason =
  /** Below target AND has a safe-stop POI to anchor to — a real spawn candidate. */
  | 'would_spawn'
  /** `A < 1`: nobody has been here recently enough, so the target is 0. */
  | 'below_activity_floor'
  /** Target reached: live crowns already meet the activity-derived count. */
  | 'at_target'
  /** Below target but the cell has no cached OSM safe stop, so nothing to anchor. */
  | 'no_pois_in_cell';

export interface DiagnosticCellClassification {
  /** `target - liveCount`, floored at 0 for display (a negative deficit is "at target"). */
  deficit: number;
  reason: DiagnosticCellReason;
  /**
   * True when the cell itself would spawn on the next visit ASSUMING the
   * area-level gates pass (flag on, active, safe, in-budget). Area-level blockers
   * are reported separately so an admin can see "these cells WOULD get crowns
   * once you activate" even while the area is off.
   */
  eligible: boolean;
}

/**
 * Classifies one cell exactly as the area pass would: activity floor first
 * (target 0 → never), then the POI anchor gate (no safe stop → nothing to place
 * on), then the deficit (already at target → nothing to add).
 */
export function classifyDiagnosticCell(input: {
  target: number;
  liveCount: number;
  poiCount: number;
}): DiagnosticCellClassification {
  const rawDeficit = input.target - input.liveCount;
  const deficit = Math.max(0, rawDeficit);
  if (input.target <= 0) return { deficit: 0, reason: 'below_activity_floor', eligible: false };
  if (input.poiCount <= 0) return { deficit, reason: 'no_pois_in_cell', eligible: false };
  if (rawDeficit <= 0) return { deficit: 0, reason: 'at_target', eligible: false };
  return { deficit, reason: 'would_spawn', eligible: true };
}

/** A safe-stop POI anchor surfaced for the map/list — a coordinate + its kind. */
export interface DiagnosticPoiAnchor {
  lat: number;
  lon: number;
  category: PoiCategory;
}
