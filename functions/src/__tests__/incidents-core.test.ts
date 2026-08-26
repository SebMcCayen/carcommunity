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
  parseConfirmInput,
  extendedExpiryFor,
  isValidConfirmationCount,
  readConfirmationCount,
  INCIDENT_TTL_MS,
  INCIDENT_TYPES,
  LIFETIME_CAP_MULTIPLIER,
  INCIDENT_LIST_RATE_LIMIT_MAX,
  INCIDENT_LIST_RATE_LIMIT_WINDOW_MS,
  incidentListRateLimitDocId,
  incidentListRateLimitExpiry,
  incidentListRateLimitWindowIndex,
  isUnderIncidentListRateLimit,
  CLEAR_VOTES_TO_REMOVE,
  INCIDENT_CLEAR_GEOFENCE_RADIUS_METERS,
  INCIDENT_CLEAR_RATE_LIMIT_COLLECTION,
  INCIDENT_CLEAR_RATE_LIMIT_MAX,
  INCIDENT_LIST_RATE_LIMIT_COLLECTION,
  evaluateClearVote,
  incidentClearRateLimitDocId,
  isIncidentLive,
  isUnderIncidentClearRateLimit,
  parseReportClearedInput,
} from '../incidents/incidents-core';
import { haversineDistanceMeters, isWithinGeofence } from '../crownHunt/crown-hunt-geo';
import { MAX_REPORTED_ACCURACY_METERS } from '../crownHunt/crownhunt-core';

/**
 * The exact proximity decision `incidents.reportCleared` makes, expressed
 * against the SHARED crownHunt helpers rather than a local re-implementation —
 * a forked copy here would happily pass while the callable stayed broken.
 */
function withinClearFence(
  incidentLat: number,
  incidentLng: number,
  fixLat: number,
  fixLng: number,
  accuracyMeters: number | null,
): boolean {
  return isWithinGeofence(
    haversineDistanceMeters(incidentLat, incidentLng, fixLat, fixLng),
    INCIDENT_CLEAR_GEOFENCE_RADIUS_METERS,
    accuracyMeters,
  );
}
import {
  buildTrafikverketRequestBody,
  classifyIncidentType,
  importedIncidentDocId,
  inspectTrafikverketResponse,
  parseTrafikverketResponse,
  parseTrafikverketTime,
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
    expect(parseReportInput({ type: 'roadwork', latitude: 57, longitude: 12, extra: 1 }).ok).toBe(
      false,
    );
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
    // The original-post time the app shows as "x min ago" (fix: not the sync time).
    expect(body).toContain('Deviation.CreationTime');
    // #678: `Deviation.PublicationTime` is an INVALID field reference (PublicationTime
    // is a Situation-level field, not a Deviation field) and 400'd the whole query,
    // taking the entire sync down. It must NOT be requested under Deviation.
    expect(body).not.toContain('Deviation.PublicationTime');
    expect(body).not.toContain('PublicationTime');
  });

  it('parses an offset ISO time to the correct instant (offset NOT reinterpreted as local)', () => {
    // 14:23:00+02:00 is 12:23:00Z. The epoch must match that UTC instant exactly,
    // regardless of the runtime's own zone — the ISO-offset-as-local trap.
    expect(parseTrafikverketTime('2026-07-30T14:23:00+02:00')).toBe(
      Date.UTC(2026, 6, 30, 12, 23, 0),
    );
    // Winter offset + fractional seconds + Z all parse to the right instant.
    expect(parseTrafikverketTime('2026-01-15T08:00:00.000+01:00')).toBe(
      Date.UTC(2026, 0, 15, 7, 0, 0),
    );
    expect(parseTrafikverketTime('2026-07-30T12:23:00Z')).toBe(Date.UTC(2026, 6, 30, 12, 23, 0));
    expect(parseTrafikverketTime('2026-07-30T12:23:00+0200')).toBe(
      Date.UTC(2026, 6, 30, 10, 23, 0),
    );
  });

  it('rejects a zone-less or unparseable time (so the client hides the age)', () => {
    // No zone designator → would be read as LOCAL wall-clock → rejected as unusable.
    expect(parseTrafikverketTime('2026-07-30T14:23:00')).toBeNull();
    expect(parseTrafikverketTime('2026-07-30')).toBeNull();
    expect(parseTrafikverketTime('not-a-date')).toBeNull();
    expect(parseTrafikverketTime('')).toBeNull();
    expect(parseTrafikverketTime(undefined)).toBeNull();
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

  it('derives the upstream original post time from Deviation.CreationTime alone (#678)', () => {
    const imported = parseTrafikverketResponse({
      RESPONSE: {
        RESULT: [
          {
            Situation: [
              {
                Id: 'S1',
                Deviation: [
                  {
                    Id: 'HAS-CREATION',
                    MessageCodeValue: 'roadworks',
                    Geometry: { WGS84: 'POINT (12.0 57.5)' },
                    CreationTime: '2026-07-30T14:23:00+02:00',
                  },
                  {
                    // No CreationTime → no usable original time. (We no longer
                    // request PublicationTime — Deviation.PublicationTime is an
                    // invalid field reference that 400'd the whole query, #678 —
                    // so a deviation without CreationTime simply has no age.)
                    Id: 'NO-CREATION',
                    MessageCodeValue: 'roadworks',
                    Geometry: { WGS84: 'POINT (12.1 57.5)' },
                  },
                  {
                    Id: 'NO-TIME',
                    MessageCodeValue: 'roadworks',
                    Geometry: { WGS84: 'POINT (12.2 57.5)' },
                  },
                ],
              },
            ],
          },
        ],
      },
    });
    const byId = new Map(imported.map((i) => [i.sourceId, i.postedAtMs]));
    // postedAt derives from CreationTime.
    expect(byId.get('HAS-CREATION')).toBe(Date.UTC(2026, 6, 30, 12, 23, 0));
    // No CreationTime → null → client hides the age line.
    expect(byId.get('NO-CREATION')).toBeNull();
    expect(byId.get('NO-TIME')).toBeNull();
  });

  it('builds a Firestore-safe deterministic doc id', () => {
    expect(importedIncidentDocId('abc/def:123')).toBe('tv_abc_def_123');
    expect(importedIncidentDocId('D1')).toBe('tv_D1');
    // Same input → same id (idempotent upsert).
    expect(importedIncidentDocId('X')).toBe(importedIncidentDocId('X'));
  });

  it('marks only structurally complete, below-limit responses safe to reconcile', () => {
    const complete = inspectTrafikverketResponse({
      RESPONSE: {
        RESULT: [
          {
            Situation: [
              {
                Id: 'S1',
                Deviation: [
                  { Id: 'D1' },
                  // A temporarily unrenderable deviation still counts as
                  // PRESENT for reconciliation via the Situation fallback id.
                  {},
                ],
              },
            ],
          },
        ],
      },
    });
    expect(complete.structurallyValid).toBe(true);
    expect(complete.situationsReceived).toBe(1);
    expect(complete.deviationsReceived).toBe(2);
    expect(complete.upstreamIncidentDocIds).toEqual(new Set(['tv_D1', 'tv_S1']));
    expect(complete.reconciliationSkipReason).toBeNull();

    const capped = inspectTrafikverketResponse(
      {
        RESPONSE: {
          RESULT: [
            {
              Situation: [
                { Id: 'S1', Deviation: [{ Id: 'D1' }] },
                { Id: 'S2', Deviation: [{ Id: 'D2' }] },
              ],
            },
          ],
        },
      },
      2,
    );
    expect(capped.reconciliationSkipReason).toBe('query-limit-reached');

    expect(inspectTrafikverketResponse({ RESPONSE: { RESULT: [] } }).reconciliationSkipReason).toBe(
      'invalid-response-shape',
    );
    expect(
      inspectTrafikverketResponse({ RESPONSE: { RESULT: [{ Situation: [] }] } })
        .reconciliationSkipReason,
    ).toBe('empty-response');
    expect(
      inspectTrafikverketResponse({ RESPONSE: { RESULT: [{ Situation: [{ Id: 'S1' }] }] } })
        .reconciliationSkipReason,
    ).toBe('invalid-response-shape');
  });
});

describe('incidents-core confirmationCount validation', () => {
  it('accepts only non-negative integers', () => {
    expect(isValidConfirmationCount(0)).toBe(true);
    expect(isValidConfirmationCount(7)).toBe(true);
    // The cases `typeof x === 'number'` waves through. Firestore stores
    // doubles, so all of these are storable, and none is JSON-representable
    // (the callable framework serialises them to null).
    expect(isValidConfirmationCount(Number.NaN)).toBe(false);
    expect(isValidConfirmationCount(Number.POSITIVE_INFINITY)).toBe(false);
    expect(isValidConfirmationCount(Number.NEGATIVE_INFINITY)).toBe(false);
    expect(isValidConfirmationCount(-1)).toBe(false);
    expect(isValidConfirmationCount(1.5)).toBe(false);
    // Non-numbers, including the absent case.
    expect(isValidConfirmationCount(undefined)).toBe(false);
    expect(isValidConfirmationCount(null)).toBe(false);
    expect(isValidConfirmationCount('3')).toBe(false);
  });

  it('normalises every invalid value to 0 on the read path', () => {
    expect(readConfirmationCount(4)).toBe(4);
    expect(readConfirmationCount(0)).toBe(0);
    for (const bad of [undefined, null, Number.NaN, Number.POSITIVE_INFINITY, -2, 0.5, '3', {}]) {
      const result = readConfirmationCount(bad);
      expect(result).toBe(0);
      // Whatever went in, what comes out must be JSON-safe — that is the whole
      // point of the normalisation.
      expect(JSON.parse(JSON.stringify({ n: result })).n).toBe(0);
    }
  });
});

describe('incidents-core confirmation expiry extension', () => {
  const T0 = new Date('2026-07-19T10:00:00.000Z');
  const at = (ms: number) => new Date(T0.getTime() + ms);
  const HOUR = 60 * 60 * 1000;

  it('parses a confirm input and rejects a malformed one', () => {
    expect(parseConfirmInput({ incidentId: 'abc123' })).toEqual({
      ok: true,
      input: { incidentId: 'abc123' },
    });
    // Path separators / traversal segments must never reach db.doc().
    expect(parseConfirmInput({ incidentId: 'a/b' }).ok).toBe(false);
    expect(parseConfirmInput({ incidentId: '..' }).ok).toBe(false);
    expect(parseConfirmInput({}).ok).toBe(false);
    // Strict schema: no extra keys (e.g. a client trying to smuggle an expiry).
    expect(parseConfirmInput({ incidentId: 'a', expiresAt: 'x' }).ok).toBe(false);
  });

  it('resets expiry to a full fresh TTL from the confirmation instant', () => {
    // A police report (1h TTL) created at T0, confirmed 45 min later: the
    // marker should live a full hour from the confirmation, not from creation.
    const result = extendedExpiryFor({
      type: 'police',
      createdAt: T0,
      currentExpiresAt: at(HOUR),
      now: at(0.75 * HOUR),
    });
    expect(result.expiresAt.getTime()).toBe(at(1.75 * HOUR).getTime());
    expect(result.extended).toBe(true);
  });

  it('never moves the expiry backwards', () => {
    // An incident already pushed out by earlier confirmations (expiry T0+30h)
    // confirmed again at T0+11h: now + TTL is T0+23h, EARLIER than the expiry
    // the doc already carries, so the existing later value must win — a
    // confirmation must never SHORTEN an incident's life.
    const result = extendedExpiryFor({
      type: 'roadwork', // 12h TTL → cap at T0+36h, so the cap does not bind here
      createdAt: T0,
      currentExpiresAt: at(30 * HOUR),
      now: at(11 * HOUR),
    });
    expect(result.expiresAt.getTime()).toBe(at(30 * HOUR).getTime());
    expect(result.extended).toBe(false);
  });

  it('caps total lifetime — an incident cannot be confirmed into immortality', () => {
    // Simulate a stream of confirmations, one every 10 minutes for 10 days.
    // No matter how many land, expiresAt must never exceed the hard ceiling.
    const type = 'roadwork';
    const ttl = INCIDENT_TTL_MS[type];
    const ceiling = T0.getTime() + LIFETIME_CAP_MULTIPLIER * ttl;
    let expiresAt = new Date(T0.getTime() + ttl);
    for (let t = 10 * 60 * 1000; t < 10 * 24 * HOUR; t += 10 * 60 * 1000) {
      const now = at(t);
      // Stop once the incident would actually be dead — the callable refuses to
      // confirm an expired incident, so confirmations cannot resume after that.
      if (now.getTime() >= expiresAt.getTime()) break;
      expiresAt = extendedExpiryFor({
        type,
        createdAt: T0,
        currentExpiresAt: expiresAt,
        now,
      }).expiresAt;
      expect(expiresAt.getTime()).toBeLessThanOrEqual(ceiling);
    }
    // The cap is genuinely reached (the test is not vacuously passing).
    expect(expiresAt.getTime()).toBe(ceiling);
  });

  it('reports extended:false once the cap is hit, while the confirmation still counts', () => {
    const type = 'hazard';
    const ttl = INCIDENT_TTL_MS[type];
    const atCap = new Date(T0.getTime() + LIFETIME_CAP_MULTIPLIER * ttl);
    const result = extendedExpiryFor({
      type,
      createdAt: T0,
      currentExpiresAt: atCap,
      now: new Date(T0.getTime() + 2.5 * ttl),
    });
    expect(result.expiresAt.getTime()).toBe(atCap.getTime());
    expect(result.extended).toBe(false);
  });

  it('anchors the cap to createdAt, not to the confirmation time', () => {
    // The ceiling is a property of the report, so a late confirmation cannot
    // buy more absolute lifetime than an early one.
    const type = 'accident';
    const ttl = INCIDENT_TTL_MS[type];
    const early = extendedExpiryFor({
      type,
      createdAt: T0,
      currentExpiresAt: at(ttl),
      now: at(0.5 * ttl),
    });
    const late = extendedExpiryFor({
      type,
      createdAt: T0,
      currentExpiresAt: at(ttl),
      now: at(0.9 * ttl),
    });
    const ceiling = T0.getTime() + LIFETIME_CAP_MULTIPLIER * ttl;
    expect(early.expiresAt.getTime()).toBeLessThanOrEqual(ceiling);
    expect(late.expiresAt.getTime()).toBeLessThanOrEqual(ceiling);
    expect(late.expiresAt.getTime()).toBeGreaterThan(early.expiresAt.getTime());
  });

  it('passes an already-over-cap expiry through unchanged rather than clamping it down', () => {
    // An expiry beyond the ceiling can only arrive from OUTSIDE this module (a
    // console edit, a stale restore, an older bug) — no writer produces one.
    // The documented precedence is never-backwards over the ceiling, so the
    // value is returned untouched and the confirmation buys nothing.
    const type = 'police';
    const ttl = INCIDENT_TTL_MS[type];
    const wayOut = new Date(T0.getTime() + 500 * ttl);
    const result = extendedExpiryFor({
      type,
      createdAt: T0,
      currentExpiresAt: wayOut,
      now: at(0.5 * ttl),
    });
    // Unchanged — the confirmation did NOT move it, in either direction.
    expect(result.expiresAt.getTime()).toBe(wayOut.getTime());
    expect(result.extended).toBe(false);
    // And the invariant the cap actually asserts still holds: repeated
    // confirmations never push it further out.
    let expiresAt = result.expiresAt;
    for (let i = 0; i < 50; i += 1) {
      expiresAt = extendedExpiryFor({
        type,
        createdAt: T0,
        currentExpiresAt: expiresAt,
        now: at((i + 1) * ttl),
      }).expiresAt;
      expect(expiresAt.getTime()).toBe(wayOut.getTime());
    }
  });

  it('caps every incident type', () => {
    for (const type of INCIDENT_TYPES) {
      const ttl = INCIDENT_TTL_MS[type];
      const result = extendedExpiryFor({
        type,
        createdAt: T0,
        currentExpiresAt: at(ttl),
        // Far-future confirmation attempt: the cap, not now+ttl, must bind.
        now: at(100 * HOUR),
      });
      expect(result.expiresAt.getTime()).toBeLessThanOrEqual(
        T0.getTime() + LIFETIME_CAP_MULTIPLIER * ttl,
      );
    }
  });
});

describe('incidents-core listNearby rate limit', () => {
  const uid = 'user-abc';
  // A fixed instant 30 s into an epoch minute (window boundary is not on it).
  const WINDOW_MS = INCIDENT_LIST_RATE_LIMIT_WINDOW_MS;
  const windowStart = 1_700_000_040_000; // exact multiple of 60_000
  const midWindow = windowStart + 30_000;

  it('admits below the cap and throttles at/above it (pre-call count)', () => {
    expect(isUnderIncidentListRateLimit(0)).toBe(true);
    expect(isUnderIncidentListRateLimit(INCIDENT_LIST_RATE_LIMIT_MAX - 1)).toBe(true);
    // The Nth call reads a count of MAX and is rejected → exactly MAX admitted.
    expect(isUnderIncidentListRateLimit(INCIDENT_LIST_RATE_LIMIT_MAX)).toBe(false);
    expect(isUnderIncidentListRateLimit(INCIDENT_LIST_RATE_LIMIT_MAX + 1_000)).toBe(false);
  });

  it('respects a custom cap argument', () => {
    expect(isUnderIncidentListRateLimit(4, 5)).toBe(true);
    expect(isUnderIncidentListRateLimit(5, 5)).toBe(false);
  });

  it('fails OPEN on a corrupt (non-finite) counter so a bad doc never locks a user out', () => {
    expect(isUnderIncidentListRateLimit(Number.NaN)).toBe(true);
    expect(isUnderIncidentListRateLimit(Number.POSITIVE_INFINITY)).toBe(true);
  });

  it('derives a deterministic per-(uid, minute) doc id', () => {
    const index = incidentListRateLimitWindowIndex(midWindow);
    expect(incidentListRateLimitWindowIndex(windowStart)).toBe(index);
    expect(incidentListRateLimitWindowIndex(windowStart + WINDOW_MS - 1)).toBe(index);
    // Same uid + same window → same id; next window → different id.
    expect(incidentListRateLimitDocId(uid, midWindow)).toBe(`${uid}_${index}`);
    expect(incidentListRateLimitDocId(uid, windowStart)).toBe(
      incidentListRateLimitDocId(uid, windowStart + WINDOW_MS - 1),
    );
    expect(incidentListRateLimitDocId(uid, windowStart + WINDOW_MS)).not.toBe(
      incidentListRateLimitDocId(uid, midWindow),
    );
    // Different uids never collide within a window.
    expect(incidentListRateLimitDocId('other', midWindow)).not.toBe(
      incidentListRateLimitDocId(uid, midWindow),
    );
  });

  it('sets an expireAt after the window end (for the TTL sweep)', () => {
    const expiry = incidentListRateLimitExpiry(midWindow).getTime();
    // Strictly after this window ends, so the counter outlives its own window.
    expect(expiry).toBeGreaterThan(windowStart + WINDOW_MS);
  });
});

// ---------------------------------------------------------------------------
// Clear votes ("it's gone") — the net-score / threshold maths
// ---------------------------------------------------------------------------
//
// These are the safety-critical numbers of the whole feature: they decide when a
// marker fades and, more importantly, when a real hazard LEAVES everyone's map.
// Every branch is pinned by value, not by re-deriving the formula.

describe('isIncidentLive — the shared vote-liveness rule', () => {
  const ACTIVE = 'active';
  const T = 1_000_000;

  it('is live only while the deadline is still ahead of the given clock', () => {
    expect(isIncidentLive(ACTIVE, T, T - 1)).toBe(true);
    // Exactly at the deadline is GONE, matching the readers' rule
    // (`expiresAt > request.time`) and listNearby's bound.
    expect(isIncidentLive(ACTIVE, T, T)).toBe(false);
    expect(isIncidentLive(ACTIVE, T, T + 1)).toBe(false);
  });

  it('is never live for a non-active status, whatever the deadline says', () => {
    for (const status of ['removed', 'pending', '', null, undefined, 0]) {
      expect(isIncidentLive(status, T, T - 60_000)).toBe(false);
    }
  });

  it('is never live on a non-finite deadline or clock', () => {
    expect(isIncidentLive(ACTIVE, Number.NaN, T)).toBe(false);
    expect(isIncidentLive(ACTIVE, Number.POSITIVE_INFINITY, T)).toBe(false);
    expect(isIncidentLive(ACTIVE, T, Number.NaN)).toBe(false);
  });

  // ---------------------------------------------------------------------------
  // The regression this function exists for.
  //
  // incidents.reportCleared used to compare `expiresAt` against a clock captured
  // BEFORE db.runTransaction. Firestore re-runs a transaction body on
  // contention, so every retry re-used that same stale instant: an incident
  // another writer expired in between — including the writer whose clear vote
  // crossed the removal threshold and stamped `expiresAt` — was still judged
  // live on the retry, and a vote was counted onto an incident that was already
  // gone. The fix reads the clock inside the body; these pin that the verdict
  // actually MOVES with the clock, which is what makes reading it per attempt
  // meaningful.
  // ---------------------------------------------------------------------------
  it('re-evaluates per attempt: ONE snapshot, two attempt clocks, two verdicts', () => {
    // A single unchanged incident snapshot, as a retry would re-read it.
    const snapshot = { status: ACTIVE, expiresAtMs: T };

    // Attempt 1 runs before the deadline; attempt 2 after (a concurrent writer
    // expired it, we backed off, and retried).
    const attemptClocks = [T - 5_000, T + 5_000];
    const verdicts = attemptClocks.map((nowMs) =>
      isIncidentLive(snapshot.status, snapshot.expiresAtMs, nowMs),
    );

    expect(verdicts).toEqual([true, false]);
  });

  it('a stale clock re-used across attempts would keep saying "live" — the bug', () => {
    // Same snapshot, but both attempts pass the SAME pre-transaction instant.
    // This is the old shape, and it demonstrates why the clock must be a
    // per-attempt reading rather than a captured constant.
    const staleNow = T - 5_000;
    const verdicts = [staleNow, staleNow].map((nowMs) => isIncidentLive(ACTIVE, T, nowMs));

    expect(verdicts).toEqual([true, true]);
    // ...even though real time has moved past the deadline by then.
    expect(isIncidentLive(ACTIVE, T, T + 5_000)).toBe(false);
  });
});

describe('evaluateClearVote — net score, fade and removal thresholds', () => {
  it('does not fade an incident with no clear votes', () => {
    const tally = evaluateClearVote({ clearedCount: 0, confirmationCount: 0 });
    expect(tally.netClearedCount).toBe(0);
    expect(tally.reportedCleared).toBe(false);
    expect(tally.shouldRemove).toBe(false);
  });

  it('fades on a net lead of 1 but keeps the incident on the map', () => {
    const tally = evaluateClearVote({ clearedCount: 1, confirmationCount: 0 });
    expect(tally.netClearedCount).toBe(1);
    expect(tally.reportedCleared).toBe(true);
    expect(tally.shouldRemove).toBe(false);
  });

  it('1 confirm + 1 clear is a TIE: no fade, no removal', () => {
    // The case Seb asked about directly. A tie is two members disagreeing, and a
    // disagreement must not degrade a live hazard's marker in either direction.
    const tally = evaluateClearVote({ clearedCount: 1, confirmationCount: 1 });
    expect(tally.netClearedCount).toBe(0);
    expect(tally.reportedCleared).toBe(false);
    expect(tally.shouldRemove).toBe(false);
  });

  it('removes at exactly 2 NET clear votes', () => {
    const tally = evaluateClearVote({ clearedCount: 2, confirmationCount: 0 });
    expect(tally.netClearedCount).toBe(CLEAR_VOTES_TO_REMOVE);
    expect(tally.shouldRemove).toBe(true);
    // A removed incident is NOT also "faded" — it is gone, and reporting both
    // would ask clients to render a state that no longer exists on the map.
    expect(tally.reportedCleared).toBe(false);
  });

  it('needs the clears to EXCEED the confirms by 2, not merely reach 2', () => {
    // 2 clears against 1 confirm is a net lead of 1 → still on the map, faded.
    const contested = evaluateClearVote({ clearedCount: 2, confirmationCount: 1 });
    expect(contested.shouldRemove).toBe(false);
    expect(contested.reportedCleared).toBe(true);
    // 3 against 1 reaches the net threshold.
    expect(evaluateClearVote({ clearedCount: 3, confirmationCount: 1 }).shouldRemove).toBe(true);
  });

  it('a well-confirmed incident is neither faded nor removable by a couple of clears', () => {
    const tally = evaluateClearVote({ clearedCount: 2, confirmationCount: 5 });
    expect(tally.netClearedCount).toBe(-3);
    expect(tally.reportedCleared).toBe(false);
    expect(tally.shouldRemove).toBe(false);
  });

  it('keeps removing once past the threshold (monotonic in the net lead)', () => {
    for (let cleared = CLEAR_VOTES_TO_REMOVE; cleared <= 10; cleared += 1) {
      expect(evaluateClearVote({ clearedCount: cleared, confirmationCount: 0 }).shouldRemove).toBe(
        true,
      );
    }
  });

  it('reports both counts back unchanged so clients can show both signals', () => {
    const tally = evaluateClearVote({ clearedCount: 4, confirmationCount: 7 });
    expect(tally.clearedCount).toBe(4);
    expect(tally.confirmationCount).toBe(7);
  });
});

describe('reportCleared input parsing — the accuracy / coordinate boundary', () => {
  const valid = {
    incidentId: 'abc123',
    latitude: 57.4874,
    longitude: 12.0757,
    capturedAt: '2026-07-28T10:00:00.000Z',
  };

  it('accepts a well-formed vote, with or without the optional fields', () => {
    expect(parseReportClearedInput(valid).ok).toBe(true);
    expect(parseReportClearedInput({ ...valid, accuracyMeters: 12 }).ok).toBe(true);
    expect(parseReportClearedInput({ ...valid, accuracyMeters: null }).ok).toBe(true);
    expect(parseReportClearedInput({ ...valid, mockLocationReported: true }).ok).toBe(true);
  });

  // THE POINT OF THIS BLOCK. `accuracyMeters` BUFFERS the geofence, so an
  // unbounded value is a way to stand anywhere and still be "inside" the fence
  // (the hole PR #573 closed inside isWithinGeofence). This bound is the second,
  // independent limit at the callable's own input boundary.
  it('rejects an absurd, non-finite or negative accuracy outright', () => {
    expect(parseReportClearedInput({ ...valid, accuracyMeters: 50_000 }).ok).toBe(false);
    expect(parseReportClearedInput({ ...valid, accuracyMeters: 1e9 }).ok).toBe(false);
    expect(parseReportClearedInput({ ...valid, accuracyMeters: Number.MAX_SAFE_INTEGER }).ok).toBe(
      false,
    );
    expect(parseReportClearedInput({ ...valid, accuracyMeters: Number.POSITIVE_INFINITY }).ok).toBe(
      false,
    );
    expect(parseReportClearedInput({ ...valid, accuracyMeters: Number.NaN }).ok).toBe(false);
    expect(parseReportClearedInput({ ...valid, accuracyMeters: -1 }).ok).toBe(false);
  });

  it('accepts accuracy exactly at the shared crownHunt bound and nothing above it', () => {
    expect(
      parseReportClearedInput({ ...valid, accuracyMeters: MAX_REPORTED_ACCURACY_METERS }).ok,
    ).toBe(true);
    expect(
      parseReportClearedInput({ ...valid, accuracyMeters: MAX_REPORTED_ACCURACY_METERS + 1 }).ok,
    ).toBe(false);
  });

  it('rejects a missing position, an out-of-range coordinate or a bad timestamp', () => {
    expect(parseReportClearedInput({ incidentId: 'abc123' }).ok).toBe(false);
    expect(parseReportClearedInput({ ...valid, latitude: 91 }).ok).toBe(false);
    expect(parseReportClearedInput({ ...valid, longitude: 181 }).ok).toBe(false);
    expect(parseReportClearedInput({ ...valid, latitude: Number.NaN }).ok).toBe(false);
    expect(parseReportClearedInput({ ...valid, capturedAt: 'yesterday' }).ok).toBe(false);
    expect(parseReportClearedInput({ ...valid, capturedAt: 1234 }).ok).toBe(false);
  });

  it('rejects a path-traversing incident id and any unknown field', () => {
    expect(parseReportClearedInput({ ...valid, incidentId: '../other' }).ok).toBe(false);
    expect(parseReportClearedInput({ ...valid, incidentId: '..' }).ok).toBe(false);
    expect(parseReportClearedInput({ ...valid, extra: 1 }).ok).toBe(false);
  });
});

describe('clear-vote geofence — you must be near the incident', () => {
  // Kungsbacka, and points at known distances from it.
  const lat = 57.4874;
  const lng = 12.0757;
  const northOf = (meters: number) => lat + meters / 111_320;

  it('accepts a fix at the incident and just inside the radius', () => {
    expect(withinClearFence(lat, lng, lat, lng, null)).toBe(true);
    expect(
      withinClearFence(lat, lng, northOf(INCIDENT_CLEAR_GEOFENCE_RADIUS_METERS - 20), lng, null),
    ).toBe(true);
  });

  it('rejects a fix outside the radius', () => {
    expect(
      withinClearFence(lat, lng, northOf(INCIDENT_CLEAR_GEOFENCE_RADIUS_METERS + 60), lng, null),
    ).toBe(false);
    expect(withinClearFence(lat, lng, northOf(5_000), lng, null)).toBe(false);
  });

  // The regression that matters: a hostile accuracy must not buy distance. Every
  // value here is one the schema would already have rejected OR one it admits;
  // either way the fence itself must hold, so neither guard is load-bearing.
  it('cannot be stretched by a hostile or unusable accuracy', () => {
    const farAway = northOf(5_000);
    for (const accuracy of [
      50_000,
      1e9,
      Number.POSITIVE_INFINITY,
      Number.NaN,
      -1,
      Number.MAX_SAFE_INTEGER,
      MAX_REPORTED_ACCURACY_METERS,
    ]) {
      expect(withinClearFence(lat, lng, farAway, lng, accuracy)).toBe(false);
    }
  });

  it('keeps the effective fence provably within [radius, radius + 50] however accuracy is reported', () => {
    // MAX_GEOFENCE_ACCURACY_METERS (100) x the 0.5 buffer = at most +50 m, and
    // the 2x multiplier cannot bind at a 300 m radius. So a fix 351 m out is
    // ALWAYS rejected and one 300 m out is ALWAYS accepted.
    for (const accuracy of [null, 0, 10, 50, 100, 500, 5_000, MAX_REPORTED_ACCURACY_METERS]) {
      expect(
        withinClearFence(
          lat,
          lng,
          northOf(INCIDENT_CLEAR_GEOFENCE_RADIUS_METERS - 1),
          lng,
          accuracy,
        ),
      ).toBe(true);
      expect(
        withinClearFence(
          lat,
          lng,
          northOf(INCIDENT_CLEAR_GEOFENCE_RADIUS_METERS + 51),
          lng,
          accuracy,
        ),
      ).toBe(false);
    }
  });
});

describe('reportCleared rate limit', () => {
  it('admits up to the cap and throttles at it', () => {
    expect(isUnderIncidentClearRateLimit(0)).toBe(true);
    expect(isUnderIncidentClearRateLimit(INCIDENT_CLEAR_RATE_LIMIT_MAX - 1)).toBe(true);
    expect(isUnderIncidentClearRateLimit(INCIDENT_CLEAR_RATE_LIMIT_MAX)).toBe(false);
    expect(isUnderIncidentClearRateLimit(INCIDENT_CLEAR_RATE_LIMIT_MAX + 100)).toBe(false);
  });

  it('is far tighter than the listNearby poll limit (opposite call shapes)', () => {
    expect(INCIDENT_CLEAR_RATE_LIMIT_MAX).toBeLessThan(INCIDENT_LIST_RATE_LIMIT_MAX);
  });

  it('fails OPEN on a corrupt counter — a bad doc must never block reporting a hazard gone', () => {
    expect(isUnderIncidentClearRateLimit(Number.NaN)).toBe(true);
    expect(isUnderIncidentClearRateLimit(Number.POSITIVE_INFINITY)).toBe(true);
  });

  it('shares the window with the listNearby limiter but never the collection', () => {
    const uid = 'user-1';
    const now = 1_800_000_000_000;
    expect(incidentClearRateLimitDocId(uid, now)).toBe(incidentListRateLimitDocId(uid, now));
    // Same id, DIFFERENT collections — which is what keeps a burst of map
    // refreshes from consuming a member's ability to vote.
    expect(INCIDENT_CLEAR_RATE_LIMIT_COLLECTION).not.toBe(INCIDENT_LIST_RATE_LIMIT_COLLECTION);
  });
});
