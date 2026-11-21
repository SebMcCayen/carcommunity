import { Timestamp } from 'firebase-admin/firestore';
import { cellToBoundary, cellToLatLng, getResolution, isValidCell } from 'h3-js';
import { PARTNER_STATS_CONSENT_VERSION } from './consent';
import { DRIVE_HEAT_H3_RESOLUTION, MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD } from './drive-heat-core';
import {
  DRIVE_HEAT_MAX_AGE_MS,
  DRIVE_HEAT_MAX_CELLS,
  DRIVE_HEAT_WINDOW_DAYS,
} from './drive-heat-policy';

export interface DriveHeatCellOut {
  h3Index: string;
  contributorCount: number;
  weight: number;
}
export interface DriveHeatResult {
  cells: DriveHeatCellOut[];
  resolution: number;
  windowDays: number;
  generatedAt: string | null;
}
/** Same fail-closed projection for administrative and partner readers. */
export function projectDriveHeatSnapshot(raw: unknown, now: number): DriveHeatResult {
  const data = raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : {};
  const generated = data.generatedAt;
  const age = generated instanceof Timestamp ? now - generated.toMillis() : NaN;
  const valid =
    data.consentVersion === PARTNER_STATS_CONSENT_VERSION &&
    Number.isFinite(age) &&
    age >= 0 &&
    age <= DRIVE_HEAT_MAX_AGE_MS &&
    data.resolution === DRIVE_HEAT_H3_RESOLUTION &&
    data.windowDays === DRIVE_HEAT_WINDOW_DAYS &&
    Array.isArray(data.cells) &&
    data.cells.length <= DRIVE_HEAT_MAX_CELLS;
  const cells: DriveHeatCellOut[] = [];
  if (valid)
    for (const rawCell of data.cells as unknown[]) {
      if (!rawCell || typeof rawCell !== 'object') continue;
      const c = rawCell as Record<string, unknown>;
      if (
        typeof c.h3Index !== 'string' ||
        !/^[0-9a-f]{15,16}$/.test(c.h3Index) ||
        typeof c.contributorCount !== 'number' ||
        !Number.isSafeInteger(c.contributorCount) ||
        c.contributorCount < MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD ||
        typeof c.weight !== 'number' ||
        !Number.isSafeInteger(c.weight) ||
        c.weight < c.contributorCount
      )
        continue;
      cells.push({ h3Index: c.h3Index, contributorCount: c.contributorCount, weight: c.weight });
    }
  cells.sort(
    (a, b) =>
      b.weight - a.weight ||
      b.contributorCount - a.contributorCount ||
      a.h3Index.localeCompare(b.h3Index),
  );
  return {
    cells,
    resolution: DRIVE_HEAT_H3_RESOLUTION,
    windowDays: DRIVE_HEAT_WINDOW_DAYS,
    generatedAt: valid ? (generated as Timestamp).toDate().toISOString() : null,
  };
}

export const PARTNER_HEAT_MAX_CELLS = 1_000;
/** Global, already-thresholded snapshot only. No customer/route data or new cohort queries. */
export function projectPartnerDriveHeat(raw: unknown, now: number) {
  const snapshot = projectDriveHeatSnapshot(raw, now);
  const expiresInSeconds =
    snapshot.generatedAt === null
      ? 0
      : Math.max(
          0,
          Math.floor((Date.parse(snapshot.generatedAt) + DRIVE_HEAT_MAX_AGE_MS - now) / 1000),
        );
  const seen = new Set<string>();
  const eligible =
    expiresInSeconds === 0
      ? []
      : snapshot.cells.filter((c) => {
          if (
            !isValidCell(c.h3Index) ||
            getResolution(c.h3Index) !== DRIVE_HEAT_H3_RESOLUTION ||
            seen.has(c.h3Index)
          )
            return false;
          seen.add(c.h3Index);
          return true;
        });
  const min = eligible.length ? Math.min(...eligible.map((c) => c.weight)) : 0;
  const max = eligible.length ? eligible[0]!.weight : 0;
  const levels = max - min + 1,
    bands = Math.min(5, levels);
  const bandFor = (weight: number) => {
    if (max <= min) return 5;
    let index = 0;
    while (index + 1 < bands && weight >= min + Math.floor(((index + 1) * levels) / bands)) index++;
    return Math.round((index * 4) / (bands - 1)) + 1;
  };
  return {
    generatedAt: expiresInSeconds > 0 ? snapshot.generatedAt : null,
    windowDays: snapshot.windowDays,
    expiresInSeconds,
    truncated: eligible.length > PARTNER_HEAT_MAX_CELLS,
    cells: eligible.slice(0, PARTNER_HEAT_MAX_CELLS).map((c) => {
      const [latitude, longitude] = cellToLatLng(c.h3Index);
      return {
        cellId: c.h3Index,
        latitude,
        longitude,
        // The same public H3 boundary as the admin map, never a driven route.
        boundary: cellToBoundary(c.h3Index).map(([lat, lon]) => ({
          latitude: lat,
          longitude: lon,
        })),
        // Partners see relative activity rather than exact traversal/account counts.
        band: bandFor(c.weight),
      };
    }),
  };
}
