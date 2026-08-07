/**
 * Unit tests for DriveHeatMap.cellsToFeatures — the pure H3-cell → GeoJSON
 * polygon conversion. The boundary function is injected so the test needs no
 * Mapbox/WebGL and no real h3-js runtime.
 */

import { describe, expect, it } from 'vitest';
import { cellToBoundary } from 'h3-js';
import { latLngToCell } from 'h3-js';

import { cellsToFeatures } from '@/components/map/DriveHeatMap';
import type { DriveHeatCell } from '@/features/partner-drive-heat';

describe('cellsToFeatures', () => {
  it('converts each cell to a closed polygon feature carrying weight', () => {
    const h3Index = latLngToCell(57.48, 12.07, 10);
    const cells: DriveHeatCell[] = [{ h3Index, contributorCount: 12, weight: 34 }];
    const fc = cellsToFeatures(cells, cellToBoundary);
    expect(fc.type).toBe('FeatureCollection');
    expect(fc.features).toHaveLength(1);
    const feature = fc.features[0]!;
    expect(feature.geometry.type).toBe('Polygon');
    // A single ring, closed ([lng,lat] pairs, first === last).
    const ring = feature.geometry.coordinates[0]!;
    expect(ring.length).toBeGreaterThanOrEqual(4);
    expect(ring[0]).toEqual(ring[ring.length - 1]);
    expect(feature.properties).toEqual({ weight: 34, contributorCount: 12 });
  });

  it('drops a cell whose boundary is degenerate', () => {
    const cells: DriveHeatCell[] = [{ h3Index: 'bad', contributorCount: 12, weight: 1 }];
    // Stub boundary returns too few points → the cell is skipped, not thrown on.
    const fc = cellsToFeatures(cells, () => [[0, 0]]);
    expect(fc.features).toHaveLength(0);
  });

  it('is empty for no cells', () => {
    expect(cellsToFeatures([], cellToBoundary).features).toEqual([]);
  });
});
