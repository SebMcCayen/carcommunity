/**
 * Unit tests for the Kronjakt marked-area geometry + admin-input core.
 * Pure — no emulator. Colocated sibling of crown-area-core.ts.
 */

import { describe, expect, it } from 'vitest';
import {
  MAX_AREA_CELLS,
  MAX_AREA_RADIUS_METERS,
  boundingBoxCellSpan,
  cellKeysForBoundingBox,
  cellKeysForShape,
  isPointInCircle,
  isPointInPolygon,
  isPointInRectangle,
  isPointInShape,
  parseCreateSpawnAreaInput,
  parseDeleteSpawnAreaInput,
  parseListSpawnAreasInput,
  parseUpdateSpawnAreaInput,
  pointInShapeAccept,
  shapeBoundingBox,
  type CrownSpawnAreaShape,
} from './crown-area-core';
import { CROWN_CELL_DEGREES, crownCellKey, parseCrownCellKey } from './crown-spawn-core';
import { haversineDistanceMeters } from './crown-hunt-geo';

// A small square around Alingsås (~57.9°N, 12.5°E), lat/lon in that order.
const SQUARE: CrownSpawnAreaShape = {
  type: 'polygon',
  vertices: [
    { lat: 57.9, lon: 12.5 },
    { lat: 57.9, lon: 12.6 },
    { lat: 58.0, lon: 12.6 },
    { lat: 58.0, lon: 12.5 },
    { lat: 57.9, lon: 12.5 }, // closed
  ],
};

describe('isPointInRectangle', () => {
  const bounds = { north: 58.0, south: 57.9, east: 12.6, west: 12.5 };

  it('accepts an interior point', () => {
    expect(isPointInRectangle(57.95, 12.55, bounds)).toBe(true);
  });

  it('rejects points outside on every side', () => {
    expect(isPointInRectangle(58.1, 12.55, bounds)).toBe(false); // north
    expect(isPointInRectangle(57.8, 12.55, bounds)).toBe(false); // south
    expect(isPointInRectangle(57.95, 12.7, bounds)).toBe(false); // east
    expect(isPointInRectangle(57.95, 12.4, bounds)).toBe(false); // west
  });

  it('is inclusive on all four edges and the corners', () => {
    expect(isPointInRectangle(58.0, 12.55, bounds)).toBe(true); // north edge
    expect(isPointInRectangle(57.9, 12.55, bounds)).toBe(true); // south edge
    expect(isPointInRectangle(57.95, 12.6, bounds)).toBe(true); // east edge
    expect(isPointInRectangle(57.95, 12.5, bounds)).toBe(true); // west edge
    expect(isPointInRectangle(58.0, 12.6, bounds)).toBe(true); // corner
  });
});

describe('isPointInCircle', () => {
  const center = { lat: 57.9, lon: 12.5 };

  it('accepts the centre and a clearly interior point', () => {
    expect(isPointInCircle(57.9, 12.5, center, 500)).toBe(true);
    expect(isPointInCircle(57.9008, 12.5, center, 500)).toBe(true); // ~89 m north
  });

  it('rejects a point beyond the radius', () => {
    // ~890 m north is outside a 500 m circle but inside a 1000 m one.
    expect(isPointInCircle(57.908, 12.5, center, 500)).toBe(false);
    expect(isPointInCircle(57.908, 12.5, center, 1000)).toBe(true);
  });

  it('treats the boundary as inclusive (measured by haversine)', () => {
    const d = haversineDistanceMeters(57.905, 12.5, center.lat, center.lon);
    expect(isPointInCircle(57.905, 12.5, center, d)).toBe(true); // exactly on the edge
    expect(isPointInCircle(57.905, 12.5, center, d - 0.001)).toBe(false);
  });

  it('rejects a non-positive or non-finite radius', () => {
    expect(isPointInCircle(57.9, 12.5, center, 0)).toBe(false);
    expect(isPointInCircle(57.9, 12.5, center, -10)).toBe(false);
    expect(isPointInCircle(57.9, 12.5, center, Number.NaN)).toBe(false);
  });
});

describe('isPointInPolygon', () => {
  const square = SQUARE.vertices;

  it('accepts an interior point and rejects an exterior one', () => {
    expect(isPointInPolygon(57.95, 12.55, square)).toBe(true);
    expect(isPointInPolygon(57.95, 12.8, square)).toBe(false);
    expect(isPointInPolygon(59.0, 12.55, square)).toBe(false);
  });

  it('works on an OPEN ring the same as the closed one', () => {
    const open = square.slice(0, -1); // drop the repeated closing vertex
    expect(isPointInPolygon(57.95, 12.55, open)).toBe(true);
    expect(isPointInPolygon(57.95, 12.8, open)).toBe(false);
  });

  it('handles a concave (L-shaped) polygon — the notch is OUTSIDE', () => {
    // An L: full bottom row, only the left half of the top row.
    const lShape = [
      { lat: 0, lon: 0 },
      { lat: 0, lon: 2 },
      { lat: 1, lon: 2 },
      { lat: 1, lon: 1 },
      { lat: 2, lon: 1 },
      { lat: 2, lon: 0 },
      { lat: 0, lon: 0 },
    ];
    expect(isPointInPolygon(0.5, 1.5, lShape)).toBe(true); // in the bottom bar
    expect(isPointInPolygon(1.5, 0.5, lShape)).toBe(true); // in the left column
    expect(isPointInPolygon(1.5, 1.5, lShape)).toBe(false); // in the cut-out notch
  });

  it('returns false for a degenerate ring (<3 vertices)', () => {
    expect(
      isPointInPolygon(0, 0, [
        { lat: 0, lon: 0 },
        { lat: 1, lon: 1 },
      ]),
    ).toBe(false);
  });
});

describe('isPointInShape + pointInShapeAccept', () => {
  const circle: CrownSpawnAreaShape = {
    type: 'circle',
    center: { lat: 57.9, lon: 12.5 },
    radiusMeters: 1000,
  };
  const rect: CrownSpawnAreaShape = {
    type: 'rectangle',
    bounds: { north: 58, south: 57.9, east: 12.6, west: 12.5 },
  };

  it('dispatches to the right shape test', () => {
    expect(isPointInShape(57.95, 12.55, SQUARE)).toBe(true);
    expect(isPointInShape(59.0, 12.55, SQUARE)).toBe(false);
    expect(isPointInShape(57.9, 12.5, circle)).toBe(true);
    expect(isPointInShape(57.95, 12.55, rect)).toBe(true);
    expect(isPointInShape(57.8, 12.55, rect)).toBe(false);
  });

  it('pointInShapeAccept adapts to the sampler {latitude, longitude} shape', () => {
    const accept = pointInShapeAccept(rect);
    expect(accept({ latitude: 57.95, longitude: 12.55 })).toBe(true);
    expect(accept({ latitude: 57.8, longitude: 12.55 })).toBe(false);
  });
});

describe('shapeBoundingBox', () => {
  it('is the identity for a rectangle', () => {
    const bounds = { north: 58, south: 57.9, east: 12.6, west: 12.5 };
    expect(shapeBoundingBox({ type: 'rectangle', bounds })).toEqual(bounds);
  });

  it('is the vertex extent for a polygon', () => {
    expect(shapeBoundingBox(SQUARE)).toEqual({ north: 58.0, south: 57.9, east: 12.6, west: 12.5 });
  });

  it('encloses a circle and its box actually contains the circle edge', () => {
    const shape: CrownSpawnAreaShape = {
      type: 'circle',
      center: { lat: 57.9, lon: 12.5 },
      radiusMeters: 1000,
    };
    const box = shapeBoundingBox(shape);
    expect(box.north).toBeGreaterThan(57.9);
    expect(box.south).toBeLessThan(57.9);
    expect(box.east).toBeGreaterThan(12.5);
    expect(box.west).toBeLessThan(12.5);
    // A point ~950 m north is inside the circle AND inside the box.
    expect(isPointInShape(57.9085, 12.5, shape)).toBe(true);
    expect(57.9085).toBeLessThanOrEqual(box.north);
  });
});

describe('cell enumeration', () => {
  it('enumerates every cell a small box spans, and each key parses', () => {
    const box = { north: 59.005, south: 58.995, east: 12.005, west: 11.995 };
    expect(boundingBoxCellSpan(box)).toBe(4);
    const { keys, truncated } = cellKeysForBoundingBox(box);
    expect(truncated).toBe(false);
    expect(keys.length).toBe(4);
    for (const key of keys) expect(parseCrownCellKey(key)).not.toBeNull();
  });

  it('covers the cell of any point inside the box', () => {
    const box = { north: 59.02, south: 58.98, east: 12.02, west: 11.98 };
    const { keys } = cellKeysForBoundingBox(box);
    const set = new Set(keys);
    for (const [lat, lon] of [
      [59.0, 12.0],
      [58.985, 11.985],
      [59.015, 12.015],
    ] as const) {
      expect(set.has(crownCellKey(lat, lon))).toBe(true);
    }
  });

  it('flags truncation when the cap bites', () => {
    const box = { north: 59.05, south: 58.95, east: 12.05, west: 11.95 };
    const { keys, truncated } = cellKeysForBoundingBox(box, 3);
    expect(keys.length).toBe(3);
    expect(truncated).toBe(true);
  });

  it('cellKeysForShape agrees with the shape bounding box', () => {
    const fromShape = cellKeysForShape(SQUARE);
    const fromBox = cellKeysForBoundingBox(shapeBoundingBox(SQUARE));
    expect(fromShape.keys).toEqual(fromBox.keys);
  });

  it('a single-cell-sized box yields one cell', () => {
    const half = CROWN_CELL_DEGREES / 4;
    const box = { north: 59.0 + half, south: 59.0 - half, east: 12.0 + half, west: 12.0 - half };
    // Could straddle a boundary; span is 1 only when it does not. Use a box well
    // inside one cell.
    const inside = { north: 59.004, south: 59.001, east: 12.004, west: 12.001 };
    void box;
    expect(boundingBoxCellSpan(inside)).toBe(1);
    expect(cellKeysForBoundingBox(inside).keys).toEqual([crownCellKey(59.002, 12.002)]);
  });
});

describe('parseCreateSpawnAreaInput', () => {
  const circle = { type: 'circle', center: { lat: 57.9, lon: 12.5 }, radiusMeters: 500 };

  it('accepts a valid inactive circle area', () => {
    const parsed = parseCreateSpawnAreaInput({ shape: circle, name: 'Torget' });
    expect(parsed.ok).toBe(true);
    if (parsed.ok) expect(parsed.input.active).toBeUndefined();
  });

  it('accepts activation when safeAreaConfirmed is the literal true', () => {
    const parsed = parseCreateSpawnAreaInput({
      shape: circle,
      active: true,
      safeAreaConfirmed: true,
    });
    expect(parsed.ok).toBe(true);
  });

  it('REJECTS activation without the safety confirmation', () => {
    const parsed = parseCreateSpawnAreaInput({ shape: circle, active: true });
    expect(parsed.ok).toBe(false);
    if (!parsed.ok) expect(parsed.message).toMatch(/safeAreaConfirmed/);
  });

  it('accepts all three shapes', () => {
    expect(parseCreateSpawnAreaInput({ shape: SQUARE }).ok).toBe(true);
    expect(
      parseCreateSpawnAreaInput({
        shape: { type: 'rectangle', bounds: { north: 58, south: 57.9, east: 12.6, west: 12.5 } },
      }).ok,
    ).toBe(true);
    expect(parseCreateSpawnAreaInput({ shape: circle }).ok).toBe(true);
  });

  it('rejects an unclosed polygon ring', () => {
    const open = { type: 'polygon', vertices: SQUARE.vertices.slice(0, -1) };
    const parsed = parseCreateSpawnAreaInput({ shape: open });
    expect(parsed.ok).toBe(false);
    if (!parsed.ok) expect(parsed.message).toMatch(/closed/);
  });

  it('rejects a polygon with too few vertices', () => {
    const tiny = {
      type: 'polygon',
      vertices: [
        { lat: 0, lon: 0 },
        { lat: 0, lon: 0 },
      ],
    };
    expect(parseCreateSpawnAreaInput({ shape: tiny }).ok).toBe(false);
  });

  it('rejects a closed ring with fewer than three DISTINCT vertices', () => {
    // Four vertices, closed, but only two distinct positions — no area.
    const degenerate = {
      type: 'polygon',
      vertices: [
        { lat: 0, lon: 0 },
        { lat: 0, lon: 1 },
        { lat: 0, lon: 0 },
        { lat: 0, lon: 0 },
      ],
    };
    const parsed = parseCreateSpawnAreaInput({ shape: degenerate });
    expect(parsed.ok).toBe(false);
    if (!parsed.ok) expect(parsed.message).toMatch(/distinct/);
  });

  it('rejects a rectangle with north <= south or east <= west', () => {
    expect(
      parseCreateSpawnAreaInput({
        shape: { type: 'rectangle', bounds: { north: 57.9, south: 58, east: 12.6, west: 12.5 } },
      }).ok,
    ).toBe(false);
    expect(
      parseCreateSpawnAreaInput({
        shape: { type: 'rectangle', bounds: { north: 58, south: 57.9, east: 12.5, west: 12.6 } },
      }).ok,
    ).toBe(false);
  });

  it('rejects a circle radius over the ceiling', () => {
    expect(
      parseCreateSpawnAreaInput({
        shape: {
          type: 'circle',
          center: { lat: 57.9, lon: 12.5 },
          radiusMeters: MAX_AREA_RADIUS_METERS + 1,
        },
      }).ok,
    ).toBe(false);
  });

  it('rejects an area whose bounding box exceeds the cell ceiling', () => {
    // A 10°×10° rectangle spans ~1e6 cells, far past MAX_AREA_CELLS.
    const huge = { type: 'rectangle', bounds: { north: 60, south: 50, east: 20, west: 10 } };
    const parsed = parseCreateSpawnAreaInput({ shape: huge });
    expect(parsed.ok).toBe(false);
    if (!parsed.ok) expect(parsed.message).toMatch(/too large/);
    expect(boundingBoxCellSpan(shapeBoundingBox(huge as CrownSpawnAreaShape))).toBeGreaterThan(
      MAX_AREA_CELLS,
    );
  });

  it('rejects unknown fields (strict)', () => {
    expect(parseCreateSpawnAreaInput({ shape: circle, wat: 1 }).ok).toBe(false);
  });
});

describe('parseUpdateSpawnAreaInput', () => {
  it('requires at least one mutable field', () => {
    const parsed = parseUpdateSpawnAreaInput({ areaId: 'abc123' });
    expect(parsed.ok).toBe(false);
  });

  it('accepts a name-only patch', () => {
    expect(parseUpdateSpawnAreaInput({ areaId: 'abc123', name: 'Nytt namn' }).ok).toBe(true);
  });

  it('requires safeAreaConfirmed to activate', () => {
    expect(parseUpdateSpawnAreaInput({ areaId: 'abc123', active: true }).ok).toBe(false);
    expect(
      parseUpdateSpawnAreaInput({ areaId: 'abc123', active: true, safeAreaConfirmed: true }).ok,
    ).toBe(true);
  });

  it('accepts deactivation freely (turning off is never harder than on)', () => {
    expect(parseUpdateSpawnAreaInput({ areaId: 'abc123', active: false }).ok).toBe(true);
  });

  it('rejects a malformed areaId', () => {
    expect(parseUpdateSpawnAreaInput({ areaId: 'bad id/slash', name: 'x' }).ok).toBe(false);
  });
});

describe('parseDeleteSpawnAreaInput / parseListSpawnAreasInput', () => {
  it('delete requires an areaId', () => {
    expect(parseDeleteSpawnAreaInput({}).ok).toBe(false);
    expect(parseDeleteSpawnAreaInput({ areaId: 'abc123' }).ok).toBe(true);
    expect(parseDeleteSpawnAreaInput({ areaId: 'abc123', reason: 'closed' }).ok).toBe(true);
  });

  it('list accepts empty, activeOnly, and a bounded limit', () => {
    expect(parseListSpawnAreasInput(undefined).ok).toBe(true);
    expect(parseListSpawnAreasInput({ activeOnly: true }).ok).toBe(true);
    expect(parseListSpawnAreasInput({ limit: 50 }).ok).toBe(true);
    expect(parseListSpawnAreasInput({ limit: 0 }).ok).toBe(false);
    expect(parseListSpawnAreasInput({ limit: 99999 }).ok).toBe(false);
  });
});
