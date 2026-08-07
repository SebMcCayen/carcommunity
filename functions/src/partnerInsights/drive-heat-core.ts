/**
 * Partner DRIVE HEATMAP — the privacy-critical binning + aggregation logic.
 *
 * Turns consented users' completed drives into an ANONYMISED H3 hex-grid heat
 * layer that shows partners WHERE the community drives, so they can buy in-app
 * digital billboards in the busiest areas. The whole point is aggregation: a
 * partner must never see an individual drive, route, endpoint, identity or
 * timestamp — only "this hex had at least N distinct drivers".
 *
 * ## Privacy invariants (enforced here; mirrored from partner-insights)
 * - **≥10 unique contributors per hex.** A hex is emitted ONLY if at least
 *   {@link MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD} DISTINCT users drove through it.
 *   Below-threshold hexes are OMITTED entirely — never zeroed-and-shown — so a
 *   sparse area can never leak that one or two people drive there.
 * - **Endpoint trimming.** The first and last {@link ENDPOINT_TRIM_METERS} of
 *   every drive are dropped BEFORE binning, so a drive's origin/destination
 *   (home/work) never reaches the aggregate. A drive shorter than twice the trim
 *   is discarded entirely — it is essentially all endpoint.
 * - **One user, one vote per hex.** A user who drove a hex a hundred times still
 *   counts as a single contributor toward its ≥10 floor; only the separate
 *   traversal weight reflects volume, and it carries no identity.
 * - The emitted cells hold ONLY `{ h3Index, contributorCount, weight }` — no
 *   user ids, no coordinates beyond the (public) H3 index, no timestamps.
 *
 * ## H3 resolution — res 10 (~76 m edge, ~150 m across)
 * Fine enough that the heat visibly HUGS individual arterials and streets rather
 * than smearing across a neighbourhood, yet coarse enough that a road's drivers
 * concentrate into the same cells and actually CLEAR the ≥10 floor. Res 11
 * (~29 m) hugs tighter but fragments the same drivers across ~7× more cells, so
 * almost nothing would clear the floor at MVP drive volume; res 9 (~174 m) stops
 * hugging roads. Res 10 is the defensible MVP middle — see the constant.
 *
 * Pure module — no Firebase Admin SDK / Cloud Storage / h3 I/O beyond the pure
 * h3-js math. Unit-tested in drive-heat-core.test.ts.
 */

import { greatCircleDistance, gridDistance, gridPathCells, latLngToCell, UNITS } from 'h3-js';
import { MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD } from './insights-core';

export { MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD };

/** A single GPS fix used for binning (subset of the decoded route point). */
export interface HeatPoint {
  latitude: number;
  longitude: number;
}

/**
 * H3 resolution for the heat grid. Res 10 ≈ 76 m hexagon edge (~150 m across) —
 * the MVP balance between hugging roads and clearing the ≥10 contributor floor
 * (see module header). A named constant so the whole pipeline (aggregation +
 * the admin map's boundary rendering) stays on ONE resolution.
 */
export const DRIVE_HEAT_H3_RESOLUTION = 10;

/**
 * Metres trimmed from each END of a drive before binning. ~200 m removes the
 * block or two around a trip's origin and destination — the home/work reveal —
 * while keeping the through-route intact. Trimming by cumulative DISTANCE (not a
 * fixed point count) makes the guarantee independent of GPS sample rate.
 */
export const ENDPOINT_TRIM_METERS = 200;

/**
 * Cap on how far apart two consecutive fixes may be for the segment between them
 * to be gap-filled with intermediate H3 cells. 1 Hz GPS at driving speed is
 * ~10–30 m apart (well inside one res-10 cell), so filling only matters for
 * downsampled tracks; beyond this distance a straight grid line would invent a
 * road across a GPS gap, so we instead bin only the two endpoints.
 */
const MAX_GAP_FILL_CELLS = 20;

/**
 * Trims the first and last {@link ENDPOINT_TRIM_METERS} of a route by cumulative
 * great-circle distance and returns the surviving middle points.
 *
 * Returns an empty array when the whole track is inside the two trim zones
 * (a drive of ≤ 2 × trim is essentially all endpoint and must contribute
 * nothing). Points are assumed ordered; a single point or fewer returns empty.
 */
export function trimRouteEndpoints(
  points: readonly HeatPoint[],
  trimMeters: number = ENDPOINT_TRIM_METERS,
): HeatPoint[] {
  if (points.length < 2) return [];

  // Cumulative distance from the start at each point.
  const cumulative: number[] = new Array(points.length);
  cumulative[0] = 0;
  for (let i = 1; i < points.length; i += 1) {
    const prev = points[i - 1]!;
    const cur = points[i]!;
    const step = greatCircleDistance(
      [prev.latitude, prev.longitude],
      [cur.latitude, cur.longitude],
      UNITS.m,
    );
    cumulative[i] = cumulative[i - 1]! + step;
  }
  const total = cumulative[cumulative.length - 1]!;

  // Nothing survives if the route is shorter than both trim zones combined.
  if (total <= 2 * trimMeters) return [];

  const kept: HeatPoint[] = [];
  for (let i = 0; i < points.length; i += 1) {
    const fromStart = cumulative[i]!;
    const fromEnd = total - fromStart;
    if (fromStart >= trimMeters && fromEnd >= trimMeters) {
      kept.push(points[i]!);
    }
  }
  return kept;
}

/**
 * The SET of distinct H3 cells a single (already-trimmed) drive passes through,
 * at {@link DRIVE_HEAT_H3_RESOLUTION}. Consecutive fixes are connected with
 * `gridPathCells` so the track hugs the road even when the sampling is sparse,
 * but only when the two fixes are within {@link MAX_GAP_FILL_CELLS} of each
 * other — a larger jump is a GPS gap and is NOT bridged (bridging would draw a
 * false straight road). Returns cell indexes with no duplicates.
 */
export function routeCells(
  points: readonly HeatPoint[],
  resolution: number = DRIVE_HEAT_H3_RESOLUTION,
): string[] {
  const cells = new Set<string>();
  let prevCell: string | null = null;
  for (const point of points) {
    const cell = latLngToCell(point.latitude, point.longitude, resolution);
    if (prevCell !== null && prevCell !== cell) {
      let filled = false;
      try {
        if (gridDistance(prevCell, cell) <= MAX_GAP_FILL_CELLS) {
          for (const c of gridPathCells(prevCell, cell)) cells.add(c);
          filled = true;
        }
      } catch {
        // gridPathCells/gridDistance can throw across pentagon distortion or on
        // an over-long path; fall back to just the endpoints below.
      }
      if (!filled) cells.add(cell);
    } else {
      cells.add(cell);
    }
    prevCell = cell;
  }
  return [...cells];
}

/** One drive's contribution: the user and the distinct cells they drove. */
export interface DriveContribution {
  userId: string;
  cells: readonly string[];
}

/** An emitted anonymised heat cell — the ONLY shape that leaves the backend. */
export interface DriveHeatCell {
  h3Index: string;
  /** Distinct users who drove this cell (≥ threshold, else the cell is omitted). */
  contributorCount: number;
  /** Total drive-traversals of this cell (density signal). Carries no identity. */
  weight: number;
}

/**
 * Streaming accumulator for drive-heat contributions.
 *
 * Folds one drive at a time into a per-cell tally so a caller can page through a
 * large window of drives without ever holding them all in memory: peak memory is
 * bounded by the number of DISTINCT cells and their contributor sets (the size
 * of the aggregate itself), NOT by the number of drives. {@link add} takes a
 * user's already-de-duplicated cells for a single drive; {@link finalize}
 * applies the ≥ {@link MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD} floor and returns the
 * emitted cells.
 */
export class DriveHeatAccumulator {
  private readonly byCell = new Map<string, { contributors: Set<string>; traversals: number }>();

  /**
   * Folds one drive's cells in: each counts once as a traversal and the user is
   * added once to each cell's contributor set (a user driving a cell twice still
   * counts once toward its contributor floor).
   */
  add(userId: string, cells: readonly string[]): void {
    for (const cell of cells) {
      const entry = this.byCell.get(cell) ?? { contributors: new Set<string>(), traversals: 0 };
      entry.contributors.add(userId);
      entry.traversals += 1;
      this.byCell.set(cell, entry);
    }
  }

  /**
   * The anonymised heat cells at or above the floor, sorted by weight descending
   * for a stable, review-able document. `threshold` can only RAISE the floor,
   * never lower it below {@link MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD}.
   */
  finalize(threshold: number = MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD): DriveHeatCell[] {
    const floor = Math.max(MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD, Math.floor(threshold) || 0);
    const cells: DriveHeatCell[] = [];
    for (const [h3Index, entry] of this.byCell) {
      const contributorCount = entry.contributors.size;
      if (contributorCount < floor) continue; // below floor → omitted entirely
      cells.push({ h3Index, contributorCount, weight: entry.traversals });
    }
    cells.sort((a, b) => b.weight - a.weight || b.contributorCount - a.contributorCount);
    return cells;
  }
}

/**
 * Aggregates an in-memory array of per-drive contributions into anonymised heat
 * cells. A thin convenience over {@link DriveHeatAccumulator} for callers (and
 * tests) that already have every contribution in hand; the scheduled job uses
 * the accumulator directly so it never materialises all drives at once.
 *
 * Each user counts ONCE toward a cell's contributor count no matter how many of
 * their drives touched it; `weight` is the total number of drive-traversals of
 * the cell (a density signal, not tied to any user). Cells below the threshold
 * are OMITTED.
 */
export function aggregateDriveHeat(
  contributions: readonly DriveContribution[],
  threshold: number = MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD,
): DriveHeatCell[] {
  const accumulator = new DriveHeatAccumulator();
  for (const drive of contributions) {
    accumulator.add(drive.userId, drive.cells);
  }
  return accumulator.finalize(threshold);
}
