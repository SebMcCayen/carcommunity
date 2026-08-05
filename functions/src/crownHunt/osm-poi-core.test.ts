/**
 * Unit tests for the Kronjakt OpenStreetMap safe-stop placement core.
 * Pure — no emulator, no network. Colocated sibling of osm-poi-core.ts.
 */

import { describe, expect, it } from 'vitest';
import {
  OSM_ATTRIBUTION,
  OSM_USER_AGENT,
  OVERPASS_ENDPOINT_DEFAULT,
  POI_CATEGORIES,
  POI_JITTER_METERS,
  buildOverpassQuery,
  buildOverpassRequestInit,
  classifyPoiCategory,
  crownPoiDocId,
  filterPoisInShape,
  jitterPosition,
  parseOverpassResponse,
  samplePoiPlacement,
  shouldIngestOnAreaWrite,
  type NormalizedPoi,
  type OverpassResponse,
} from './osm-poi-core';
import {
  MIN_CROWN_SEPARATION_METERS,
  createSeededRng,
  crownCellKey,
  isFarEnoughFromAll,
  type CrownPosition,
} from './crown-spawn-core';
import { isPointInShape, shapeBoundingBox, type CrownSpawnAreaShape } from './crown-area-core';
import { haversineDistanceMeters } from './crown-hunt-geo';

// A circle centred near Alingsås (~57.9°N, 12.5°E), 300 m radius.
const CENTER = { lat: 57.9, lon: 12.5 };
const CIRCLE: CrownSpawnAreaShape = { type: 'circle', center: CENTER, radiusMeters: 300 };

describe('constants', () => {
  it('exposes the ODbL attribution and the public endpoint default', () => {
    expect(OSM_ATTRIBUTION).toBe('© OpenStreetMap contributors');
    expect(OVERPASS_ENDPOINT_DEFAULT).toBe('https://overpass-api.de/api/interpreter');
    expect(POI_CATEGORIES).toEqual(['parking', 'fuel', 'charging']);
  });
});

describe('classifyPoiCategory', () => {
  it('maps the amenity tags to categories', () => {
    expect(classifyPoiCategory({ amenity: 'parking' })).toBe('parking');
    expect(classifyPoiCategory({ amenity: 'fuel' })).toBe('fuel');
    expect(classifyPoiCategory({ amenity: 'charging_station' })).toBe('charging');
  });

  it('treats man_made=charge_point as a charger', () => {
    expect(classifyPoiCategory({ man_made: 'charge_point' })).toBe('charging');
  });

  it('returns null for irrelevant or absent tags', () => {
    expect(classifyPoiCategory({ amenity: 'restaurant' })).toBeNull();
    expect(classifyPoiCategory({ highway: 'motorway' })).toBeNull();
    expect(classifyPoiCategory({})).toBeNull();
    expect(classifyPoiCategory(undefined)).toBeNull();
    expect(classifyPoiCategory(null)).toBeNull();
  });
});

describe('buildOverpassQuery', () => {
  it('emits a bbox query in (south,west,north,east) order for all three categories', () => {
    const box = shapeBoundingBox(CIRCLE);
    const q = buildOverpassQuery(box, 25);
    const bbox = `${box.south},${box.west},${box.north},${box.east}`;
    expect(q).toContain('[out:json][timeout:25];');
    expect(q).toContain(`node["amenity"="parking"](${bbox});`);
    expect(q).toContain(`way["amenity"="parking"](${bbox});`);
    expect(q).toContain(`node["amenity"="fuel"](${bbox});`);
    expect(q).toContain(`node["amenity"="charging_station"](${bbox});`);
    expect(q).toContain(`node["man_made"="charge_point"](${bbox});`);
    expect(q).toContain('out center;');
  });
});

describe('buildOverpassRequestInit', () => {
  it('POSTs form-encoded data= with an Accept and the required User-Agent', () => {
    const query = '[out:json][timeout:25];node["amenity"="fuel"](55,13,56,14);out center;';
    const init = buildOverpassRequestInit(query);
    expect(init.method).toBe('POST');
    // The documented Overpass POST form: application/x-www-form-urlencoded body
    // `data=<url-encoded query>`.
    expect(init.headers['Content-Type']).toBe('application/x-www-form-urlencoded');
    expect(init.body).toBe(`data=${encodeURIComponent(query)}`);
    // A descriptive User-Agent is REQUIRED — overpass-api.de 406s requests
    // without one (the original defect). Accept asks for JSON.
    expect(init.headers['User-Agent']).toBe(OSM_USER_AGENT);
    expect(init.headers['User-Agent']).toMatch(/carcommunity/);
    expect(init.headers.Accept).toBe('application/json');
  });
});

describe('shouldIngestOnAreaWrite', () => {
  const CIRCLE_A: CrownSpawnAreaShape = { type: 'circle', center: CENTER, radiusMeters: 300 };
  const CIRCLE_B: CrownSpawnAreaShape = { type: 'circle', center: CENTER, radiusMeters: 500 };
  const active = (shape: CrownSpawnAreaShape, extra: Record<string, unknown> = {}) => ({
    active: true,
    safeAreaConfirmed: true,
    shape,
    ...extra,
  });

  it('ingests when an area is CREATED active (no before doc)', () => {
    expect(shouldIngestOnAreaWrite(undefined, active(CIRCLE_A))).toBe(true);
  });

  it('ingests when an inactive area is ACTIVATED', () => {
    const before = { active: false, safeAreaConfirmed: true, shape: CIRCLE_A };
    expect(shouldIngestOnAreaWrite(before, active(CIRCLE_A))).toBe(true);
  });

  it('ingests when the SHAPE changes while active', () => {
    expect(shouldIngestOnAreaWrite(active(CIRCLE_A), active(CIRCLE_B))).toBe(true);
  });

  it('does NOT ingest on a round-robin cursor advance (active/safe/shape unchanged)', () => {
    const before = active(CIRCLE_A, { lastSpawnPassAt: 1, nextCellOffset: 0 });
    const after = active(CIRCLE_A, { lastSpawnPassAt: 2, nextCellOffset: 5 });
    expect(shouldIngestOnAreaWrite(before, after)).toBe(false);
  });

  it('does NOT ingest on its OWN poiCount / poisRefreshedAt write-back (no re-entrant loop)', () => {
    const before = active(CIRCLE_A);
    const after = active(CIRCLE_A, { poiCount: 12, poisRefreshedAt: 'ts' });
    expect(shouldIngestOnAreaWrite(before, after)).toBe(false);
    // And a SECOND identical bookkeeping write is still inert — the loop cannot start.
    expect(shouldIngestOnAreaWrite(after, after)).toBe(false);
  });

  it('does NOT re-ingest just because it was never ingested (would re-fire on every write)', () => {
    // active, safe, no poisRefreshedAt yet, but nothing relevant changed.
    const before = active(CIRCLE_A);
    const after = active(CIRCLE_A, { name: 'renamed' });
    expect(shouldIngestOnAreaWrite(before, after)).toBe(false);
  });

  it('does NOT ingest for an inactive or unconfirmed area', () => {
    expect(
      shouldIngestOnAreaWrite(undefined, {
        active: false,
        safeAreaConfirmed: true,
        shape: CIRCLE_A,
      }),
    ).toBe(false);
    expect(
      shouldIngestOnAreaWrite(undefined, {
        active: true,
        safeAreaConfirmed: false,
        shape: CIRCLE_A,
      }),
    ).toBe(false);
    expect(shouldIngestOnAreaWrite(undefined, { active: true, safeAreaConfirmed: true })).toBe(
      false,
    );
    expect(shouldIngestOnAreaWrite(undefined, undefined)).toBe(false);
  });
});

describe('parseOverpassResponse', () => {
  it('normalises nodes (own coords) and ways (center) with categories', () => {
    const response: OverpassResponse = {
      elements: [
        { type: 'node', id: 1, lat: 57.9, lon: 12.5, tags: { amenity: 'parking' } },
        {
          type: 'way',
          id: 2,
          center: { lat: 57.901, lon: 12.501 },
          tags: { amenity: 'fuel' },
        },
        { type: 'node', id: 3, lat: 57.902, lon: 12.502, tags: { man_made: 'charge_point' } },
      ],
    };
    const pois = parseOverpassResponse(response);
    expect(pois).toHaveLength(3);
    expect(pois[0]).toEqual({ lat: 57.9, lon: 12.5, category: 'parking' });
    expect(pois[1]).toEqual({ lat: 57.901, lon: 12.501, category: 'fuel' });
    expect(pois[2]).toEqual({ lat: 57.902, lon: 12.502, category: 'charging' });
  });

  it('drops elements without a classifiable tag, without coords, or out of range', () => {
    const response: OverpassResponse = {
      elements: [
        { type: 'node', id: 1, lat: 57.9, lon: 12.5, tags: { amenity: 'cafe' } },
        { type: 'way', id: 2, tags: { amenity: 'parking' } }, // no center
        { type: 'node', id: 3, lat: 200, lon: 12.5, tags: { amenity: 'fuel' } }, // bad lat
        { type: 'node', id: 4, lat: 57.9, lon: 12.5, tags: undefined }, // no tags
      ],
    };
    expect(parseOverpassResponse(response)).toHaveLength(0);
  });

  it('de-dupes on (lat, lon, category)', () => {
    const response: OverpassResponse = {
      elements: [
        { type: 'node', id: 1, lat: 57.9, lon: 12.5, tags: { amenity: 'parking' } },
        { type: 'node', id: 2, lat: 57.9, lon: 12.5, tags: { amenity: 'parking' } },
      ],
    };
    expect(parseOverpassResponse(response)).toHaveLength(1);
  });

  it('tolerates an empty / missing elements array', () => {
    expect(parseOverpassResponse({})).toEqual([]);
    expect(parseOverpassResponse({ elements: [] })).toEqual([]);
  });
});

describe('filterPoisInShape', () => {
  it('keeps only POIs inside the drawn shape, not merely its bbox', () => {
    const box = shapeBoundingBox(CIRCLE);
    // A corner of the bounding box is outside the inscribed circle.
    const corner: NormalizedPoi = { lat: box.north, lon: box.east, category: 'parking' };
    const inside: NormalizedPoi = { lat: CENTER.lat, lon: CENTER.lon, category: 'fuel' };
    expect(isPointInShape(corner.lat, corner.lon, CIRCLE)).toBe(false);
    const kept = filterPoisInShape([corner, inside], CIRCLE);
    expect(kept).toEqual([inside]);
  });
});

describe('crownPoiDocId', () => {
  it('is deterministic and coordinate-injective, hex, per area', () => {
    const a = crownPoiDocId('area1', 57.9, 12.5);
    const b = crownPoiDocId('area1', 57.9, 12.5);
    const c = crownPoiDocId('area1', 57.9, 12.500001);
    const d = crownPoiDocId('area2', 57.9, 12.5);
    expect(a).toBe(b);
    expect(a).not.toBe(c);
    expect(a).not.toBe(d);
    expect(a).toMatch(/^[0-9a-f]{64}$/);
  });
});

describe('jitterPosition', () => {
  it('stays within the jitter radius', () => {
    const rng = createSeededRng(42);
    const base: CrownPosition = { latitude: CENTER.lat, longitude: CENTER.lon };
    for (let i = 0; i < 200; i += 1) {
      const p = jitterPosition(base, rng, POI_JITTER_METERS);
      const d = haversineDistanceMeters(base.latitude, base.longitude, p.latitude, p.longitude);
      expect(d).toBeLessThanOrEqual(POI_JITTER_METERS + 1e-6);
    }
  });

  it('returns the base unchanged when jitter is 0', () => {
    const base: CrownPosition = { latitude: 1, longitude: 2 };
    expect(jitterPosition(base, createSeededRng(1), 0)).toEqual(base);
  });
});

describe('samplePoiPlacement', () => {
  // Three POIs inside the 300 m circle and pairwise >150 m apart (~200-310 m):
  // one at the centre, one ~200 m north, one ~237 m east.
  const POIS: NormalizedPoi[] = [
    { lat: 57.9, lon: 12.5, category: 'parking' },
    { lat: 57.9018, lon: 12.5, category: 'fuel' },
    { lat: 57.9, lon: 12.504, category: 'charging' },
  ];

  it('snaps a placement to (or within jitter of) a POI, inside the shape', () => {
    const rng = createSeededRng(7);
    const pos = samplePoiPlacement(POIS, [], rng, {
      accept: (p) => isPointInShape(p.latitude, p.longitude, CIRCLE),
      jitterMeters: POI_JITTER_METERS,
    })!;
    expect(pos).not.toBeNull();
    expect(isPointInShape(pos.latitude, pos.longitude, CIRCLE)).toBe(true);
    // Within jitter of SOME POI.
    const nearest = Math.min(
      ...POIS.map((poi) => haversineDistanceMeters(poi.lat, poi.lon, pos.latitude, pos.longitude)),
    );
    expect(nearest).toBeLessThanOrEqual(POI_JITTER_METERS + 1e-6);
  });

  it('respects the 150 m separation from occupied crowns', () => {
    const rng = createSeededRng(11);
    const occupied: CrownPosition[] = [];
    // Place all it can; each returned position must clear separation from the rest.
    for (let i = 0; i < POIS.length; i += 1) {
      const pos = samplePoiPlacement(POIS, occupied, rng, {
        minSeparationMeters: MIN_CROWN_SEPARATION_METERS,
      });
      if (!pos) break;
      expect(isFarEnoughFromAll(pos, occupied, MIN_CROWN_SEPARATION_METERS)).toBe(true);
      occupied.push(pos);
    }
    // The three POIs are >150 m apart, so all three are placeable.
    expect(occupied).toHaveLength(3);
  });

  it('does not stack a second crown on the same POI', () => {
    const single: NormalizedPoi[] = [{ lat: 57.9, lon: 12.5, category: 'parking' }];
    const rng = createSeededRng(3);
    const first = samplePoiPlacement(single, [], rng, {})!;
    expect(first).not.toBeNull();
    const second = samplePoiPlacement(single, [first], rng, {});
    // The only POI is <150 m from the first crown → nothing to place.
    expect(second).toBeNull();
  });

  it('returns null for an empty POI list', () => {
    expect(samplePoiPlacement([], [], createSeededRng(1), {})).toBeNull();
  });

  it('keeps a jittered candidate inside its cell (falls back to the POI point)', () => {
    // A POI exactly on a cell boundary: jitter could cross into the neighbour
    // cell, but the sampler must keep the returned point in the POI's own cell.
    const poi: NormalizedPoi = { lat: 57.9, lon: 12.5, category: 'parking' };
    const cellKey = crownCellKey(poi.lat, poi.lon);
    const rng = createSeededRng(99);
    for (let i = 0; i < 50; i += 1) {
      const pos = samplePoiPlacement([poi], [], rng, { cellKey, jitterMeters: POI_JITTER_METERS });
      expect(pos).not.toBeNull();
      expect(crownCellKey(pos!.latitude, pos!.longitude)).toBe(cellKey);
    }
  });
});
