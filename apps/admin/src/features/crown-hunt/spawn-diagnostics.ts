/**
 * Admin client for crownHunt.spawnDiagnostics — the read-only troubleshooting
 * view over the MARKED-AREA auto-spawn engine.
 *
 * The callable returns raw FACTS (serverTime, nextRunAt, areasAhead, per-cell
 * detail, blockers). The time-relative numbers an admin actually reads — the
 * ticking countdown to the next run, and the ESTIMATED time until THIS area is
 * next served — are derived here, from those facts, by the pure helpers below so
 * they unit-test without a network. They are deliberately estimates: the pass is
 * round-robin over active areas, shares a per-run cell budget across areas, and
 * skips cells already at target, so the true moment a given cell tops up can only
 * be bracketed, not pinned.
 */

import type {
  AdminCrownSpawnDiagnosticsRequest,
  AdminCrownSpawnDiagnosticsResponse,
  CrownSpawnDiagnosticCell,
} from '@carcommunity/shared/crown-hunt';

import { callAdmin } from '../../lib/callables';

export type {
  AdminCrownSpawnDiagnosticsRequest,
  AdminCrownSpawnDiagnosticsResponse,
  CrownSpawnDiagnosticCell,
};

/** Fetches marked-area auto-spawn diagnostics for one area (admin, read-only). */
export async function adminSpawnDiagnostics(
  areaId: string,
): Promise<AdminCrownSpawnDiagnosticsResponse> {
  const request: AdminCrownSpawnDiagnosticsRequest = { areaId };
  return callAdmin<AdminCrownSpawnDiagnosticsResponse>('crownHunt-spawnDiagnostics', request);
}

/**
 * Whole seconds remaining until `targetIso`, never negative. The panel calls this
 * every tick against a live `nowMs` to drive the countdown; when the target has
 * passed it returns 0 rather than a negative, so the display reads "any moment"
 * instead of counting up.
 */
export function countdownSeconds(targetIso: string, nowMs: number): number {
  const targetMs = new Date(targetIso).getTime();
  if (!Number.isFinite(targetMs)) return 0;
  return Math.max(0, Math.ceil((targetMs - nowMs) / 1000));
}

export interface AreaServiceEstimate {
  /** Whole scheduled runs until this area is reached in the round-robin queue. */
  runsUntilServed: number;
  /** Estimated wall-clock instant the area is next visited (ms since epoch). */
  serviceAtMs: number;
}

/**
 * Estimates when the area is next VISITED by a pass, from its queue position.
 *
 * The pass serves `maxAreasPerRun` active areas per run, least-recently-served
 * first, so an area with `areasAhead` areas in front of it waits
 * `floor(areasAhead / maxAreasPerRun)` whole runs beyond the next one. With the
 * handful of areas an admin typically draws this is 0 — served on the very next
 * run — which is the honest common answer. This is the "next VISIT", not the
 * moment a specific cell tops up: a big area's cells are still walked across runs
 * under the shared per-run cell budget.
 */
export function estimateAreaService(input: {
  areasAhead: number;
  maxAreasPerRun: number;
  nextRunAtMs: number;
  runIntervalMs: number;
}): AreaServiceEstimate {
  const perRun = Math.max(1, input.maxAreasPerRun);
  const runsUntilServed = Math.max(0, Math.floor(input.areasAhead / perRun));
  return {
    runsUntilServed,
    serviceAtMs: input.nextRunAtMs + runsUntilServed * input.runIntervalMs,
  };
}

/** The scanned cells that are real spawn candidates (below target, with a POI anchor). */
export function candidateCells(
  cells: readonly CrownSpawnDiagnosticCell[],
): CrownSpawnDiagnosticCell[] {
  return cells.filter((cell) => cell.eligible);
}
