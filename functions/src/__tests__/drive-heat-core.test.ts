/**
 * Unit tests for the drive-heat pure core (partnerInsights/drive-heat-core.ts):
 * endpoint trimming, H3 binning, and the ≥10 unique-contributor aggregation.
 * No emulators, no Firebase, no Storage.
 */

import { describe, expect, it } from 'vitest';
import { greatCircleDistance, latLngToCell, UNITS } from 'h3-js';
import {
  aggregateDriveHeat,
  DriveHeatAccumulator,
  routeCells,
  trimRouteEndpoints,
  DRIVE_HEAT_H3_RESOLUTION,
  ENDPOINT_TRIM_METERS,
  MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD,
  type DriveContribution,
  type HeatPoint,
} from '../partnerInsights/drive-heat-core';

/**
 * A straight west→east line of GPS fixes starting at `start`, one every `stepM`
 * metres for `count` points. ~1 deg lon ≈ 60_000 m at 57°N, good enough for a
 * test fixture (trimming/binning care about relative distance, not geodesy).
 */
function line(startLat: number, startLon: number, count: number, stepM: number): HeatPoint[] {
  const metresPerDegLon = 111_320 * Math.cos((startLat * Math.PI) / 180);
  const dLon = stepM / metresPerDegLon;
  return Array.from({ length: count }, (_, i) => ({
    latitude: startLat,
    longitude: startLon + i * dLon,
  }));
}

describe('trimRouteEndpoints', () => {
  it('drops the first and last ~200 m by cumulative distance', () => {
    // 2 km line, 10 m spacing (201 points). After trimming 200 m each end, the
    // surviving span is ~1600 m and every kept point is >=200 m from both ends.
    const pts = line(57.48, 12.0, 201, 10);
    const total = greatCircleDistance(
      [pts[0]!.latitude, pts[0]!.longitude],
      [pts[pts.length - 1]!.latitude, pts[pts.length - 1]!.longitude],
      UNITS.m,
    );
    const kept = trimRouteEndpoints(pts, ENDPOINT_TRIM_METERS);
    expect(kept.length).toBeGreaterThan(0);
    // First surviving point is at least ~200 m in from the origin.
    const firstOffset = greatCircleDistance(
      [pts[0]!.latitude, pts[0]!.longitude],
      [kept[0]!.latitude, kept[0]!.longitude],
      UNITS.m,
    );
    const lastOffset = greatCircleDistance(
      [kept[kept.length - 1]!.latitude, kept[kept.length - 1]!.longitude],
      [pts[pts.length - 1]!.latitude, pts[pts.length - 1]!.longitude],
      UNITS.m,
    );
    expect(firstOffset).toBeGreaterThanOrEqual(ENDPOINT_TRIM_METERS - 10);
    expect(lastOffset).toBeGreaterThanOrEqual(ENDPOINT_TRIM_METERS - 10);
    expect(total).toBeGreaterThan(2 * ENDPOINT_TRIM_METERS);
  });

  it('discards a short drive that is entirely inside the trim zones', () => {
    // 300 m line < 2 x 200 m: the whole thing is endpoint, contributes nothing.
    const pts = line(57.48, 12.0, 31, 10);
    expect(trimRouteEndpoints(pts, ENDPOINT_TRIM_METERS)).toEqual([]);
  });

  it('returns empty for a degenerate route (<2 points)', () => {
    expect(trimRouteEndpoints([], ENDPOINT_TRIM_METERS)).toEqual([]);
    expect(trimRouteEndpoints([{ latitude: 57.48, longitude: 12 }], ENDPOINT_TRIM_METERS)).toEqual(
      [],
    );
  });
});

describe('routeCells', () => {
  it('bins points to res-10 H3 cells with no duplicates', () => {
    const pts = line(57.48, 12.0, 50, 10);
    const cells = routeCells(pts);
    expect(cells.length).toBeGreaterThan(0);
    expect(new Set(cells).size).toBe(cells.length); // de-duplicated
    // Every returned cell is a valid res-10 index.
    for (const c of cells) {
      expect(c).toMatch(/^[0-9a-f]{15}$/);
    }
  });

  it('gap-fills between sparse consecutive fixes so the track hugs the road', () => {
    // Two fixes ~300 m apart (well over one res-10 cell): the path between them
    // must be filled, so we get more than the two endpoint cells.
    const a: HeatPoint = { latitude: 57.48, longitude: 12.0 };
    const b: HeatPoint = { latitude: 57.48, longitude: 12.0 + 300 / (111_320 * Math.cos((57.48 * Math.PI) / 180)) };
    const cells = routeCells([a, b]);
    const endpointCells = new Set([
      latLngToCell(a.latitude, a.longitude, DRIVE_HEAT_H3_RESOLUTION),
      latLngToCell(b.latitude, b.longitude, DRIVE_HEAT_H3_RESOLUTION),
    ]);
    expect(cells.length).toBeGreaterThan(endpointCells.size);
  });
});

describe('aggregateDriveHeat — privacy floor', () => {
  const CELL = latLngToCell(57.48, 12.0, DRIVE_HEAT_H3_RESOLUTION);

  function contributionsFrom(userIds: string[]): DriveContribution[] {
    return userIds.map((userId) => ({ userId, cells: [CELL] }));
  }

  it('omits a cell with fewer than 10 distinct contributors', () => {
    const cells = aggregateDriveHeat(contributionsFrom(['u1', 'u2', 'u3']));
    expect(cells).toEqual([]);
  });

  it('emits a cell once it reaches 10 distinct contributors', () => {
    const userIds = Array.from({ length: 10 }, (_, i) => `u${i}`);
    const cells = aggregateDriveHeat(contributionsFrom(userIds));
    expect(cells).toHaveLength(1);
    expect(cells[0]!.h3Index).toBe(CELL);
    expect(cells[0]!.contributorCount).toBe(10);
    expect(cells[0]!.weight).toBe(10);
  });

  it('counts each user ONCE toward contributors but every drive toward weight', () => {
    // 10 distinct users, but u0 drove the cell 5 times (5 separate drives).
    const userIds = Array.from({ length: 10 }, (_, i) => `u${i}`);
    const drives = [
      ...contributionsFrom(userIds),
      ...contributionsFrom(['u0', 'u0', 'u0', 'u0']),
    ];
    const cells = aggregateDriveHeat(drives);
    expect(cells).toHaveLength(1);
    expect(cells[0]!.contributorCount).toBe(10); // u0 still counts once
    expect(cells[0]!.weight).toBe(14); // but every traversal adds to weight
  });

  it('never lowers the floor below the absolute minimum, even if asked', () => {
    const userIds = Array.from({ length: 5 }, (_, i) => `u${i}`);
    // Requesting a threshold of 1 must NOT expose a 5-contributor cell.
    const cells = aggregateDriveHeat(contributionsFrom(userIds), 1);
    expect(cells).toEqual([]);
    expect(MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD).toBe(10);
  });

  it('DriveHeatAccumulator streams to the same result as the array helper', () => {
    const CELL = latLngToCell(57.48, 12.0, DRIVE_HEAT_H3_RESOLUTION);
    const userIds = Array.from({ length: 12 }, (_, i) => `u${i}`);
    const drives: DriveContribution[] = [
      ...userIds.map((userId) => ({ userId, cells: [CELL] })),
      { userId: 'u0', cells: [CELL] }, // u0 drives it a second time
    ];
    const acc = new DriveHeatAccumulator();
    for (const d of drives) acc.add(d.userId, d.cells);
    expect(acc.finalize()).toEqual(aggregateDriveHeat(drives));
    // u0 counted once toward contributors, but both traversals toward weight.
    const [cell] = acc.finalize();
    expect(cell!.contributorCount).toBe(12);
    expect(cell!.weight).toBe(13);
  });

  it('sorts emitted cells by weight descending', () => {
    const busy = latLngToCell(57.48, 12.0, DRIVE_HEAT_H3_RESOLUTION);
    const quiet = latLngToCell(57.6, 12.3, DRIVE_HEAT_H3_RESOLUTION);
    const users = Array.from({ length: 12 }, (_, i) => `u${i}`);
    const drives: DriveContribution[] = [
      ...users.map((u) => ({ userId: u, cells: [busy, quiet] })),
      // Extra traversals of `busy` only.
      ...users.slice(0, 6).map((u) => ({ userId: u, cells: [busy] })),
    ];
    const cells = aggregateDriveHeat(drives);
    expect(cells.map((c) => c.h3Index)).toEqual([busy, quiet]);
    expect(cells[0]!.weight).toBeGreaterThan(cells[1]!.weight);
  });
});
