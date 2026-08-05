import { describe, it, expect, vi, beforeEach } from 'vitest';

// The crown-hunt barrel pulls in the Firestore/callable data layer, which would
// otherwise eagerly initialise the Firebase app. These tests exercise the PURE
// shape conversion/validation + the callable wrappers, so stub those seams —
// same approach as crown-spawn-cells.test.ts.
const { callAdmin } = vi.hoisted(() => ({ callAdmin: vi.fn() }));
vi.mock('../lib/callables', () => ({ callAdmin }));
vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('firebase/firestore', () => ({
  collection: (_db: unknown, path: string) => ({ path }),
  doc: (_db: unknown, path: string, id?: string) => ({ path, id }),
  getDoc: vi.fn(),
  getDocs: vi.fn(),
  query: (t: unknown, ...c: unknown[]) => ({ t, c }),
  where: (f: string, op: string, v: unknown) => ({ f, op, v }),
  orderBy: (f: string, d: string) => ({ f, d }),
  limit: (n: number) => ({ n }),
}));

import {
  validateAreaShape,
  ringToPolygonShape,
  ringToRectangleShape,
  circleToShape,
  shapeToGeoJson,
  describeShape,
  buildActivateAreaRequest,
  buildDeactivateAreaRequest,
  buildCreateAreaRequest,
  areaPoiCount,
  adminCreateSpawnArea,
  adminUpdateSpawnArea,
  adminListSpawnAreas,
  ApiError,
  type CrownSpawnAreaShape,
  type AdminCrownSpawnArea,
} from '@/features/crown-hunt';

const square = (): CrownSpawnAreaShape => ({
  type: 'polygon',
  vertices: [
    { lat: 57.0, lon: 12.0 },
    { lat: 57.0, lon: 12.1 },
    { lat: 57.1, lon: 12.1 },
    { lat: 57.1, lon: 12.0 },
    { lat: 57.0, lon: 12.0 },
  ],
});

describe('validateAreaShape — polygon', () => {
  it('accepts a closed ring of >=3 distinct vertices within 4..500 points', () => {
    expect(validateAreaShape(square())).toEqual({ ok: true });
  });

  it('rejects fewer than 4 points', () => {
    expect(
      validateAreaShape({
        type: 'polygon',
        vertices: [
          { lat: 57, lon: 12 },
          { lat: 57.1, lon: 12 },
          { lat: 57, lon: 12 },
        ],
      }),
    ).toEqual({ ok: false, code: 'polygon_too_few' });
  });

  it('rejects more than 500 points', () => {
    const vertices = Array.from({ length: 502 }, (_v, i) => ({ lat: 57 + i * 1e-6, lon: 12 }));
    vertices.push({ lat: vertices[0]!.lat, lon: vertices[0]!.lon });
    expect(validateAreaShape({ type: 'polygon', vertices }).code).toBe('polygon_too_many');
  });

  it('rejects an unclosed ring (first != last)', () => {
    expect(
      validateAreaShape({
        type: 'polygon',
        vertices: [
          { lat: 57.0, lon: 12.0 },
          { lat: 57.0, lon: 12.1 },
          { lat: 57.1, lon: 12.1 },
          { lat: 57.1, lon: 12.0 },
        ],
      }),
    ).toEqual({ ok: false, code: 'polygon_not_closed' });
  });

  it('rejects fewer than three DISTINCT vertices', () => {
    expect(
      validateAreaShape({
        type: 'polygon',
        vertices: [
          { lat: 57.0, lon: 12.0 },
          { lat: 57.0, lon: 12.1 },
          { lat: 57.0, lon: 12.0 },
          { lat: 57.0, lon: 12.0 },
        ],
      }),
    ).toEqual({ ok: false, code: 'polygon_too_few_distinct' });
  });

  it('rejects an out-of-range vertex', () => {
    expect(
      validateAreaShape({
        type: 'polygon',
        vertices: [
          { lat: 57.0, lon: 12.0 },
          { lat: 91.0, lon: 12.1 },
          { lat: 57.1, lon: 12.1 },
          { lat: 57.1, lon: 12.0 },
          { lat: 57.0, lon: 12.0 },
        ],
      }).code,
    ).toBe('vertex_out_of_range');
  });
});

describe('validateAreaShape — circle', () => {
  it('accepts a radius within 10..50000 m', () => {
    expect(validateAreaShape({ type: 'circle', center: { lat: 57, lon: 12 }, radiusMeters: 250 })).toEqual({
      ok: true,
    });
  });

  it('rejects a radius below 10 m', () => {
    expect(
      validateAreaShape({ type: 'circle', center: { lat: 57, lon: 12 }, radiusMeters: 5 }).code,
    ).toBe('circle_radius_range');
  });

  it('rejects a radius above 50000 m', () => {
    expect(
      validateAreaShape({ type: 'circle', center: { lat: 57, lon: 12 }, radiusMeters: 60000 }).code,
    ).toBe('circle_radius_range');
  });
});

describe('validateAreaShape — rectangle', () => {
  it('accepts north>south and east>west', () => {
    expect(
      validateAreaShape({ type: 'rectangle', bounds: { north: 57.1, south: 57.0, east: 12.1, west: 12.0 } }),
    ).toEqual({ ok: true });
  });

  it('rejects north <= south', () => {
    expect(
      validateAreaShape({ type: 'rectangle', bounds: { north: 57.0, south: 57.1, east: 12.1, west: 12.0 } })
        .code,
    ).toBe('rectangle_lat_order');
  });

  it('rejects east <= west (no antimeridian wrap)', () => {
    expect(
      validateAreaShape({ type: 'rectangle', bounds: { north: 57.1, south: 57.0, east: 12.0, west: 12.1 } })
        .code,
    ).toBe('rectangle_lng_order');
  });

  it('rejects an area whose bounding box exceeds the cell budget', () => {
    expect(
      validateAreaShape({ type: 'rectangle', bounds: { north: 80, south: -80, east: 170, west: -170 } }).code,
    ).toBe('area_too_large');
  });
});

describe('draw geometry -> contract shape', () => {
  it('converts a GeoJSON ring ([lon,lat]) to a closed polygon of {lat,lon}', () => {
    const shape = ringToPolygonShape([
      [12.0, 57.0],
      [12.1, 57.0],
      [12.1, 57.1],
      [12.0, 57.0],
    ]);
    expect(shape).toEqual({
      type: 'polygon',
      vertices: [
        { lat: 57.0, lon: 12.0 },
        { lat: 57.0, lon: 12.1 },
        { lat: 57.1, lon: 12.1 },
        { lat: 57.0, lon: 12.0 },
      ],
    });
  });

  it('closes an open ring by repeating the first vertex', () => {
    const shape = ringToPolygonShape([
      [12.0, 57.0],
      [12.1, 57.0],
      [12.1, 57.1],
      [12.0, 57.1],
    ]);
    if (shape.type !== 'polygon') throw new Error('expected polygon');
    const first = shape.vertices[0]!;
    const last = shape.vertices[shape.vertices.length - 1]!;
    expect([last.lat, last.lon]).toEqual([first.lat, first.lon]);
  });

  it('reduces a rectangle ring to axis-aligned bounds', () => {
    const shape = ringToRectangleShape([
      [12.0, 57.0],
      [12.1, 57.0],
      [12.1, 57.1],
      [12.0, 57.1],
      [12.0, 57.0],
    ]);
    expect(shape).toEqual({
      type: 'rectangle',
      bounds: { north: 57.1, south: 57.0, east: 12.1, west: 12.0 },
    });
  });

  it('builds a circle shape from a centre + radius', () => {
    expect(circleToShape({ lat: 57, lon: 12 }, 300)).toEqual({
      type: 'circle',
      center: { lat: 57, lon: 12 },
      radiusMeters: 300,
    });
  });

  it('round-trips every shape to a renderable GeoJSON polygon', () => {
    for (const shape of [
      square(),
      circleToShape({ lat: 57, lon: 12 }, 500),
      { type: 'rectangle', bounds: { north: 57.1, south: 57, east: 12.1, west: 12 } } as CrownSpawnAreaShape,
    ]) {
      const gj = shapeToGeoJson(shape);
      expect(gj.geometry.type).toBe('Polygon');
      expect(gj.geometry.coordinates[0]!.length).toBeGreaterThan(2);
    }
  });

  it('summarises shapes for the list', () => {
    expect(describeShape(square())).toEqual({ type: 'polygon', detail: '5' });
    expect(describeShape(circleToShape({ lat: 57, lon: 12 }, 250))).toEqual({
      type: 'circle',
      detail: '250',
    });
  });
});

describe('activation safety gate (pure)', () => {
  it('REFUSES to build an activation request without the confirmation', () => {
    expect(buildActivateAreaRequest('a1', false)).toBeNull();
  });

  it('builds an activation request carrying the literal safeAreaConfirmed:true', () => {
    expect(buildActivateAreaRequest('a1', true)).toEqual({
      areaId: 'a1',
      active: true,
      safeAreaConfirmed: true,
    });
  });

  it('deactivation never carries a confirmation', () => {
    expect(buildDeactivateAreaRequest('a1')).toEqual({ areaId: 'a1', active: false });
  });

  it('create request omits active unless activateNow AND confirmed', () => {
    const shape = square();
    expect(buildCreateAreaRequest(shape, 'Town', false, true).active).toBeUndefined();
    expect(buildCreateAreaRequest(shape, 'Town', true, false).active).toBeUndefined();
    expect(buildCreateAreaRequest(shape, 'Town', true, true)).toEqual({
      shape,
      name: 'Town',
      active: true,
      safeAreaConfirmed: true,
    });
  });
});

describe('areaPoiCount', () => {
  it('reads a numeric poiCount when present, else null', () => {
    expect(areaPoiCount({ poiCount: 7 } as unknown as AdminCrownSpawnArea)).toBe(7);
    expect(areaPoiCount({} as AdminCrownSpawnArea)).toBeNull();
  });
});

describe('callable wrappers', () => {
  beforeEach(() => callAdmin.mockReset());

  it('createSpawnArea validates client-side BEFORE calling the backend', async () => {
    await expect(
      adminCreateSpawnArea({
        shape: { type: 'circle', center: { lat: 57, lon: 12 }, radiusMeters: 1 },
      }),
    ).rejects.toBeInstanceOf(ApiError);
    expect(callAdmin).not.toHaveBeenCalled();
  });

  it('createSpawnArea calls crownHunt-createSpawnArea on a valid shape', async () => {
    callAdmin.mockResolvedValue({ areaId: 'a1', active: false, safeAreaConfirmed: false, removedCrowns: 0 });
    await adminCreateSpawnArea({ shape: square() });
    expect(callAdmin).toHaveBeenCalledWith('crownHunt-createSpawnArea', { shape: square() });
  });

  it('updateSpawnArea re-validates a supplied shape', async () => {
    await expect(
      adminUpdateSpawnArea({ areaId: 'a1', shape: { type: 'rectangle', bounds: { north: 1, south: 2, east: 3, west: 1 } } }),
    ).rejects.toBeInstanceOf(ApiError);
    expect(callAdmin).not.toHaveBeenCalled();
  });

  it('listSpawnAreas returns [] when the callable yields no areas', async () => {
    callAdmin.mockResolvedValue({});
    expect(await adminListSpawnAreas()).toEqual([]);
  });
});
