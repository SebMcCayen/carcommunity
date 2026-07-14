import { describe, expect, it } from 'vitest';

import {
  CELL_SIZE_DEGREES,
  DEFAULT_RADIUS_METERS,
  MAX_RADIUS_METERS,
  MIN_RADIUS_METERS,
  boundingBox,
  buildIncidentFields,
  chunk,
  clampRadiusMeters,
  expiryFor,
  geoCellKey,
  geoCellsForRadius,
  isWithinRadius,
  parseListNearbyInput,
  parseRemoveInput,
  parseReportInput,
  INCIDENT_TTL_MS,
} from '../incidents/incidents-core';
import {
  buildTrafikverketRequestBody,
  classifyIncidentType,
  importedIncidentDocId,
  parseTrafikverketResponse,
  parseWgs84Point,
} from '../incidents/trafikverket-core';

describe('incidents-core geo cells', () => {
  it('assigns the same cell to nearby points and different cells far apart', () => {
    const a = geoCellKey(57.4874, 12.0757); // Kungsbacka
    const b = geoCellKey(57.4901, 12.0801); // ~400 m away
    const c = geoCellKey(59.3293, 18.0686); // Stockholm
    expect(a).toBe(b);
    expect(a).not.toBe(c);
  });

  it('is deterministic and grid-aligned', () => {
    expect(geoCellKey(0.0, 0.0)).toBe('0_0');
    expect(geoCellKey(CELL_SIZE_DEGREES + 0.001, 0)).toBe('1_0');
    expect(geoCellKey(-0.001, 0)).toBe('-1_0');
  });

  it('covers the query point cell in geoCellsForRadius', () => {
    const cells = geoCellsForRadius(57.4874, 12.0757, 15_000);
    expect(cells).toContain(geoCellKey(57.4874, 12.0757));
  });

  it('produces a bounded cell count even at the max radius (northern Sweden)', () => {
    // Kiruna latitude — where longitude degrees are shortest, so cell count peaks.
    const cells = geoCellsForRadius(67.85, 20.23, MAX_RADIUS_METERS);
    expect(cells.length).toBeGreaterThan(0);
    expect(cells.length).toBeLessThan(200);
    expect(new Set(cells).size).toBe(cells.length); // no duplicates
  });

  it("includes a neighbour's cell when it falls inside the radius", () => {
    // A point ~10 km east should be reachable within a 15 km query.
    const centerLat = 57.4874;
    const centerLng = 12.0757;
    const eastLng = centerLng + 10_000 / (111_320 * Math.cos((centerLat * Math.PI) / 180));
    const cells = geoCellsForRadius(centerLat, centerLng, 15_000);
    expect(cells).toContain(geoCellKey(centerLat, eastLng));
  });
});

describe('incidents-core boundingBox', () => {
  it('widens longitude more than latitude at high latitude', () => {
    const box = boundingBox(60, 15, 10_000);
    const latSpan = box.maxLat - box.minLat;
    const lngSpan = box.maxLng - box.minLng;
    expect(lngSpan).toBeGreaterThan(latSpan);
  });
});

describe('incidents-core isWithinRadius', () => {
  it('accepts points inside and rejects points outside', () => {
    const lat = 57.4874;
    const lng = 12.0757;
    // ~1 km north.
    const nearLat = lat + 1_000 / 111_320;
    expect(isWithinRadius(lat, lng, nearLat, lng, 2_000)).toBe(true);
    expect(isWithinRadius(lat, lng, nearLat, lng, 500)).toBe(false);
  });
});

describe('incidents-core clampRadiusMeters', () => {
  it('defaults, clamps low, and clamps high', () => {
    expect(clampRadiusMeters(undefined)).toBe(DEFAULT_RADIUS_METERS);
    expect(clampRadiusMeters(1)).toBe(MIN_RADIUS_METERS);
    expect(clampRadiusMeters(9_999_999)).toBe(MAX_RADIUS_METERS);
    expect(clampRadiusMeters(5_000)).toBe(5_000);
  });
});

describe('incidents-core chunk', () => {
  it('splits into chunks of the given size', () => {
    expect(chunk([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]]);
    expect(chunk([], 3)).toEqual([]);
  });
});

describe('incidents-core expiryFor', () => {
  it('applies the per-type TTL', () => {
    const now = new Date('2026-07-12T00:00:00.000Z');
    expect(expiryFor('police', now).getTime()).toBe(now.getTime() + INCIDENT_TTL_MS.police);
    expect(expiryFor('roadwork', now).getTime()).toBe(now.getTime() + INCIDENT_TTL_MS.roadwork);
    // Police ages out fastest, roadwork slowest.
    expect(INCIDENT_TTL_MS.police).toBeLessThan(INCIDENT_TTL_MS.roadwork);
  });
});

describe('incidents-core buildIncidentFields', () => {
  it('computes the geoCell, status, and normalizes an empty note to null', () => {
    const fields = buildIncidentFields({
      type: 'hazard',
      latitude: 57.4874,
      longitude: 12.0757,
      source: 'user',
      reporterUid: 'uid-1',
      note: '   ',
    });
    expect(fields.status).toBe('active');
    expect(fields.geoCell).toBe(geoCellKey(57.4874, 12.0757));
    expect(fields.note).toBeNull();
    expect(fields.reporterUid).toBe('uid-1');
  });

  it('keeps a trimmed note', () => {
    const fields = buildIncidentFields({
      type: 'accident',
      latitude: 0,
      longitude: 0,
      source: 'user',
      reporterUid: 'u',
      note: '  krock i korsningen  ',
    });
    expect(fields.note).toBe('krock i korsningen');
  });
});

describe('incidents-core input parsing', () => {
  it('accepts a valid report and rejects a bad type / out-of-range coord', () => {
    expect(parseReportInput({ type: 'roadwork', latitude: 57, longitude: 12 }).ok).toBe(true);
    expect(parseReportInput({ type: 'nope', latitude: 57, longitude: 12 }).ok).toBe(false);
    expect(parseReportInput({ type: 'roadwork', latitude: 200, longitude: 12 }).ok).toBe(false);
    expect(parseReportInput({ type: 'roadwork', latitude: 57 }).ok).toBe(false);
  });

  it('rejects unknown keys (strict) and over-long notes', () => {
    expect(
      parseReportInput({ type: 'roadwork', latitude: 57, longitude: 12, extra: 1 }).ok,
    ).toBe(false);
    expect(
      parseReportInput({ type: 'roadwork', latitude: 57, longitude: 12, note: 'x'.repeat(201) }).ok,
    ).toBe(false);
  });

  it('parses listNearby with an optional radius and remove input', () => {
    expect(parseListNearbyInput({ latitude: 57, longitude: 12 }).ok).toBe(true);
    expect(parseListNearbyInput({ latitude: 57, longitude: 12, radiusMeters: 5000 }).ok).toBe(true);
    expect(parseListNearbyInput({ latitude: 57, longitude: 12, radiusMeters: -1 }).ok).toBe(false);
    expect(parseRemoveInput({ incidentId: 'abc' }).ok).toBe(true);
    expect(parseRemoveInput({ incidentId: '' }).ok).toBe(false);
  });
});

describe('trafikverket-core', () => {
  it('builds a request body with the login key and situation query', () => {
    const body = buildTrafikverketRequestBody('SECRET&KEY');
    expect(body).toContain('objecttype="Situation"');
    expect(body).toContain('namespace="road.trafficinfo"'); // required for Situation
    expect(body).toContain('schemaversion="1.6"'); // 1.5 errors + imports nothing
    expect(body).toContain('<FILTER></FILTER>'); // mandatory but empty
    expect(body).not.toContain('ManagedCause'); // old over-filter removed
    expect(body).toContain('authenticationkey="SECRET&amp;KEY"'); // XML-escaped
    expect(body).toContain('Deviation.MessageCodeValue'); // classification key
    expect(body).toContain('Deviation.Geometry.WGS84');
  });

  it('parses a WGS84 POINT string', () => {
    expect(parseWgs84Point('POINT (12.34 57.89)')).toEqual({ longitude: 12.34, latitude: 57.89 });
    expect(parseWgs84Point('POINT(18.0686 59.3293)')).toEqual({
      longitude: 18.0686,
      latitude: 59.3293,
    });
    expect(parseWgs84Point('garbage')).toBeNull();
    expect(parseWgs84Point(undefined)).toBeNull();
    expect(parseWgs84Point('POINT (999 999)')).toBeNull(); // out of range
  });

  it('classifies confirmed MessageCodeValue codes', () => {
    expect(classifyIncidentType('roadworks')).toBe('roadwork');
    expect(classifyIncidentType('resurfacingWork')).toBe('roadwork');
    expect(classifyIncidentType('blastingWork')).toBe('roadwork');
    expect(classifyIncidentType('accident')).toBe('accident');
    expect(classifyIncidentType('roadClosed')).toBe('road_closed');
    expect(classifyIncidentType('laneClosures')).toBe('road_closed');
    expect(classifyIncidentType('severeFrostDamagedRoadway')).toBe('hazard');
    expect(classifyIncidentType('roadSurfaceInPoorCondition')).toBe('hazard');
    expect(classifyIncidentType('followDiversionSigns')).toBe('hazard');
  });

  it('SKIPs ferries and standing regulatory restrictions (null → not imported)', () => {
    expect(classifyIncidentType('ferry')).toBeNull();
    expect(classifyIncidentType('speedRestrictionInOperation')).toBeNull();
    expect(classifyIncidentType('noOvertaking')).toBeNull();
    expect(classifyIncidentType('weightRestrictionInOperation')).toBeNull();
  });

  it('classification is case-insensitive and defaults unknown codes to hazard', () => {
    expect(classifyIncidentType('ROADWORKS')).toBe('roadwork');
    expect(classifyIncidentType('someBrandNewIncidentCode')).toBe('hazard');
  });

  it('falls back to the Swedish MessageType when MessageCodeValue is absent', () => {
    expect(classifyIncidentType(undefined, 'Olycka')).toBe('accident');
    expect(classifyIncidentType('', 'Vägarbete')).toBe('roadwork');
    expect(classifyIncidentType(undefined, 'Avstängd väg')).toBe('road_closed');
    expect(classifyIncidentType(undefined, 'Hinder')).toBe('hazard');
    expect(classifyIncidentType(undefined, undefined)).toBe('hazard'); // default
  });

  it('flattens a response into importable incidents, excluding ferries/restrictions, bad geometry and dupes', () => {
    const imported = parseTrafikverketResponse({
      RESPONSE: {
        RESULT: [
          {
            Situation: [
              {
                Id: 'S1',
                Deviation: [
                  {
                    Id: 'D1',
                    MessageCodeValue: 'roadworks',
                    MessageType: 'Vägarbete',
                    Message: 'Vägarbete på E6',
                    Geometry: { WGS84: 'POINT (12.0 57.5)' },
                  },
                  {
                    Id: 'D1', // duplicate id → skipped
                    MessageCodeValue: 'roadworks',
                    Geometry: { WGS84: 'POINT (12.0 57.5)' },
                  },
                  {
                    Id: 'D2',
                    MessageCodeValue: 'accident',
                    Geometry: { WGS84: 'no-point' }, // unparseable → skipped
                  },
                  {
                    Id: 'FERRY1',
                    MessageCodeValue: 'ferry', // excluded category → skipped
                    Geometry: { WGS84: 'POINT (11.9 57.6)' },
                  },
                  {
                    Id: 'SPEED1',
                    MessageCodeValue: 'speedRestrictionInOperation', // regulatory → skipped
                    Geometry: { WGS84: 'POINT (11.8 57.7)' },
                  },
                  {
                    Id: 'WEIGHT1',
                    MessageCodeValue: 'weightRestrictionInOperation', // regulatory → skipped
                    Geometry: { WGS84: 'POINT (11.7 57.8)' },
                  },
                  {
                    Id: 'D4',
                    MessageCodeValue: 'laneClosures',
                    Geometry: { WGS84: 'POINT (13.0 58.0)' },
                  },
                  {
                    Id: 'D3',
                    MessageCodeValue: 'accident',
                    Message: 'Olycka i korsning',
                    Geometry: { WGS84: 'POINT (18.07 59.33)' },
                  },
                ],
              },
            ],
          },
        ],
      },
    });
    // Ferries + speed/weight restrictions excluded; bad geometry + dupe dropped.
    expect(imported.map((i) => i.sourceId)).toEqual(['D1', 'D4', 'D3']);
    expect(imported.some((i) => i.sourceId === 'FERRY1')).toBe(false);
    expect(imported.some((i) => i.sourceId === 'SPEED1')).toBe(false);
    expect(imported.some((i) => i.sourceId === 'WEIGHT1')).toBe(false);
    expect(imported[0]).toMatchObject({
      sourceId: 'D1',
      type: 'roadwork',
      latitude: 57.5,
      longitude: 12.0,
      note: 'Vägarbete på E6',
    });
    expect(imported[1]).toMatchObject({ sourceId: 'D4', type: 'road_closed' });
    expect(imported[2]).toMatchObject({ sourceId: 'D3', type: 'accident' });
  });

  it('reports present-but-unrecognized MessageCodeValues via the onUnknownCode hook', () => {
    const unknown: string[] = [];
    const imported = parseTrafikverketResponse(
      {
        RESPONSE: {
          RESULT: [
            {
              Situation: [
                {
                  Id: 'S1',
                  Deviation: [
                    {
                      Id: 'U1',
                      MessageCodeValue: 'someBrandNewIncidentCode',
                      Geometry: { WGS84: 'POINT (12.0 57.5)' },
                    },
                  ],
                },
              ],
            },
          ],
        },
      },
      (code) => unknown.push(code),
    );
    expect(unknown).toEqual(['someBrandNewIncidentCode']);
    expect(imported[0]).toMatchObject({ sourceId: 'U1', type: 'hazard' }); // still imported
  });

  it('builds a Firestore-safe deterministic doc id', () => {
    expect(importedIncidentDocId('abc/def:123')).toBe('tv_abc_def_123');
    expect(importedIncidentDocId('D1')).toBe('tv_D1');
    // Same input → same id (idempotent upsert).
    expect(importedIncidentDocId('X')).toBe(importedIncidentDocId('X'));
  });
});
