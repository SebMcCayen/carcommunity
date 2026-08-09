/**
 * Pure half of the on-demand "Retry POIs" callable (reingestAreaPois.ts) — the
 * result→response mapping, with NO Firebase/Admin-SDK import so it unit-tests
 * without the emulator (the same core/IO split as osm-poi-core.ts).
 */

import type { AreaPoiIngestionResult } from './poiIngestion';

/**
 * A localisation-free failure detail for a failed Overpass run. The admin UI
 * shows its OWN localised copy; this string is what the structured response
 * carries for non-UI consumers / logs. `runAreaPoiIngestion` logs the specific
 * upstream error server-side (it is not returned in the result).
 */
export const REINGEST_OVERPASS_FAILURE_MESSAGE =
  'The OpenStreetMap (Overpass) lookup timed out or failed. The previously cached safe stops were kept. Please try again in a moment.';

/**
 * Clamp a count to a non-negative integer. A NON-FINITE input (`NaN`/±`Infinity`,
 * e.g. a console-corrupted stored `poiCount`) becomes 0 rather than passing
 * through — a non-finite number would JSON-serialize to `null` and break the
 * response's `number` contract.
 */
function clampCount(n: number): number {
  return Number.isFinite(n) ? Math.max(0, Math.trunc(n)) : 0;
}

/**
 * Result of an on-demand POI re-ingestion. Mirrors
 * `AdminReingestSpawnAreaPoisResponse` in @carcommunity/shared (the functions
 * package does not depend on shared; kept in sync by hand as spawnAreas.ts does).
 */
export interface ReingestSpawnAreaPoisResponse {
  areaId: string;
  /** True when Overpass answered and the cache was rebuilt; false when it failed (cache kept). */
  ok: boolean;
  /** Cached in-shape POI count AFTER the run (the kept previous count on failure). */
  poiCount: number;
  /** Raw POIs Overpass returned before the in-shape filter (0 on failure). */
  fetched: number;
  /** Stale cached POIs removed by this run (0 on failure). */
  removedStale: number;
  /** A human failure detail on `ok: false`; null on success. */
  message: string | null;
}

/**
 * Maps an {@link AreaPoiIngestionResult} to the callable's response. A FAILED run
 * (a swallowed Overpass timeout, `poiCount: -1`) becomes a STRUCTURED failure
 * (`ok: false` + a message) reporting the KEPT previous count, never a throw; a
 * successful run reports the fresh count.
 */
export function toReingestResponse(
  areaId: string,
  previousPoiCount: number,
  result: AreaPoiIngestionResult,
): ReingestSpawnAreaPoisResponse {
  if (result.failed) {
    return {
      areaId,
      ok: false,
      poiCount: clampCount(previousPoiCount),
      fetched: 0,
      removedStale: 0,
      message: REINGEST_OVERPASS_FAILURE_MESSAGE,
    };
  }
  return {
    areaId,
    ok: true,
    poiCount: result.poiCount,
    fetched: result.fetched,
    removedStale: result.removedStale,
    message: null,
  };
}
