/**
 * Unit tests for the pure result→response mapping of the on-demand "Retry POIs"
 * callable. A successful ingestion reports the fresh count; a FAILED run (a
 * swallowed Overpass timeout — exactly what `runAreaPoiIngestion` returns on a
 * 504) must become a STRUCTURED failure the UI can show, never a throw. No
 * Firebase, no emulator.
 */

import { describe, expect, it } from 'vitest';

import type { AreaPoiIngestionResult } from './poiIngestion';
import {
  REINGEST_OVERPASS_FAILURE_MESSAGE,
  toReingestResponse,
} from './reingest-area-pois-core';

const success: AreaPoiIngestionResult = {
  areaId: 'area-1',
  poiCount: 7,
  fetched: 12,
  removedStale: 2,
  failed: false,
};

// What runAreaPoiIngestion returns when Overpass times out / 504s: it swallows
// the error, keeps the cache, and reports poiCount: -1.
const overpassFailure: AreaPoiIngestionResult = {
  areaId: 'area-1',
  poiCount: -1,
  fetched: 0,
  removedStale: 0,
  failed: true,
};

describe('toReingestResponse', () => {
  it('reports the fresh cached count on a successful run', () => {
    expect(toReingestResponse('area-1', 3, success)).toEqual({
      areaId: 'area-1',
      ok: true,
      poiCount: 7,
      fetched: 12,
      removedStale: 2,
      message: null,
    });
  });

  it('returns a structured failure that keeps the previous count on an Overpass failure', () => {
    const res = toReingestResponse('area-1', 4, overpassFailure);
    expect(res.ok).toBe(false);
    // The KEPT count, never the -1 sentinel.
    expect(res.poiCount).toBe(4);
    expect(res.fetched).toBe(0);
    expect(res.removedStale).toBe(0);
    expect(res.message).toBe(REINGEST_OVERPASS_FAILURE_MESSAGE);
  });

  it('never surfaces a negative kept count', () => {
    // A console-corrupted/absent previous count must clamp to 0, not go negative.
    expect(toReingestResponse('area-1', -5, overpassFailure).poiCount).toBe(0);
    expect(toReingestResponse('area-1', 2.9, overpassFailure).poiCount).toBe(2);
  });
});
