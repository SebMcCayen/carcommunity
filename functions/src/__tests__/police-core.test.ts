/**
 * Pure-logic unit tests for the police-proximity domain (police-core.ts).
 *
 * Covers the three decisions the task calls out — the proximity-alert decision
 * (within threshold + not-already-alerted → alert), the report rate-limit
 * (server admit/reject), and the marker-liveness filter — plus the geo-cell
 * indexing, expiry, input parsing, and builders. No Firebase Admin SDK, no
 * emulator: every function here is pure.
 */

import { describe, expect, it } from 'vitest';

import {
  CELL_SIZE_DEGREES,
  DEFAULT_RADIUS_METERS,
  MAX_RADIUS_METERS,
  MIN_RADIUS_METERS,
  POLICE_ACTIVE_STATUS,
  POLICE_PROXIMITY_ALERT_RADIUS_METERS,
  POLICE_REPORT_RATE_LIMIT_MAX,
  POLICE_REPORT_RATE_LIMIT_WINDOW_MS,
  POLICE_REPORT_TTL_MS,
  POLICE_LIST_RATE_LIMIT_MAX,
  POLICE_VOTE_RATE_LIMIT_MAX,
  boundingBox,
  buildPoliceReportFields,
  chunk,
  clampRadiusMeters,
  geoCellKey,
  geoCellsForRadius,
  isPoliceReportLive,
  isReportable,
  isUnderPoliceListRateLimit,
  isUnderPoliceReportRateLimit,
  isWithinRadius,
  parseListNearbyInput,
  parseReportInput,
  policeExpiryFor,
  policeReportRateLimitDocId,
  policeReportRateLimitExpiry,
  policeReportRateLimitWindowIndex,
  policeVoteRateLimitDocId,
  isValidVoteCount,
  isUnderPoliceVoteRateLimit,
  parseVoteInput,
  readVoteCount,
  shouldAlertForPolice,
} from '../police/police-core';

// A point in central Kungsbacka and a few offsets computed against the shared
// Haversine (via isWithinRadius) so the expectations are self-consistent.
const BASE_LAT = 57.4879;
const BASE_LNG = 12.0756;

/** Moves `metres` north of the base point (1 deg lat ≈ 111_320 m). */
function north(metres: number): { lat: number; lng: number } {
  return { lat: BASE_LAT + metres / 111_320, lng: BASE_LNG };
}

describe('proximity-alert decision (shouldAlertForPolice)', () => {
  const policeId = 'pin-1';

  it('alerts when the driver is within the threshold and the pin has not alerted yet', () => {
    const near = north(POLICE_PROXIMITY_ALERT_RADIUS_METERS - 50);
    expect(
      shouldAlertForPolice({
        driverLat: near.lat,
        driverLng: near.lng,
        policeLat: BASE_LAT,
        policeLng: BASE_LNG,
        policeId,
        alreadyAlerted: new Set<string>(),
      }),
    ).toBe(true);
  });

  it('does NOT alert when the pin is beyond the threshold', () => {
    const far = north(POLICE_PROXIMITY_ALERT_RADIUS_METERS + 200);
    expect(
      shouldAlertForPolice({
        driverLat: far.lat,
        driverLng: far.lng,
        policeLat: BASE_LAT,
        policeLng: BASE_LNG,
        policeId,
        alreadyAlerted: new Set<string>(),
      }),
    ).toBe(false);
  });

  it('does NOT re-alert for a pin already in the alerted set (once-per-pin)', () => {
    const near = north(10);
    expect(
      shouldAlertForPolice({
        driverLat: near.lat,
        driverLng: near.lng,
        policeLat: BASE_LAT,
        policeLng: BASE_LNG,
        policeId,
        alreadyAlerted: new Set<string>([policeId]),
      }),
    ).toBe(false);
  });

  it('honours a caller-supplied radius override', () => {
    const at = north(300);
    const common = {
      driverLat: at.lat,
      driverLng: at.lng,
      policeLat: BASE_LAT,
      policeLng: BASE_LNG,
      policeId,
      alreadyAlerted: new Set<string>(),
    };
    expect(shouldAlertForPolice({ ...common, radiusMeters: 200 })).toBe(false);
    expect(shouldAlertForPolice({ ...common, radiusMeters: 400 })).toBe(true);
  });

  it('never alerts on a corrupt coordinate rather than throwing', () => {
    expect(
      shouldAlertForPolice({
        driverLat: Number.NaN,
        driverLng: BASE_LNG,
        policeLat: BASE_LAT,
        policeLng: BASE_LNG,
        policeId,
        alreadyAlerted: new Set<string>(),
      }),
    ).toBe(false);
    expect(
      shouldAlertForPolice({
        driverLat: BASE_LAT,
        driverLng: BASE_LNG,
        policeLat: 999,
        policeLng: BASE_LNG,
        policeId,
        alreadyAlerted: new Set<string>(),
      }),
    ).toBe(false);
  });

  it('alerts exactly at the boundary distance (inclusive)', () => {
    // A pin essentially on top of the driver is trivially within range.
    expect(
      shouldAlertForPolice({
        driverLat: BASE_LAT,
        driverLng: BASE_LNG,
        policeLat: BASE_LAT,
        policeLng: BASE_LNG,
        policeId,
        alreadyAlerted: new Set<string>(),
        radiusMeters: 0,
      }),
    ).toBe(true);
  });
});

describe('report rate-limit decision (isUnderPoliceReportRateLimit)', () => {
  it('admits below the cap and rejects at/above it', () => {
    expect(isUnderPoliceReportRateLimit(0)).toBe(true);
    expect(isUnderPoliceReportRateLimit(POLICE_REPORT_RATE_LIMIT_MAX - 1)).toBe(true);
    expect(isUnderPoliceReportRateLimit(POLICE_REPORT_RATE_LIMIT_MAX)).toBe(false);
    expect(isUnderPoliceReportRateLimit(POLICE_REPORT_RATE_LIMIT_MAX + 10)).toBe(false);
  });

  it('fails OPEN on a corrupt (non-finite) counter — never locks out a reporter', () => {
    expect(isUnderPoliceReportRateLimit(Number.NaN)).toBe(true);
    expect(isUnderPoliceReportRateLimit(Number.POSITIVE_INFINITY)).toBe(true);
  });

  it('honours a custom cap', () => {
    expect(isUnderPoliceReportRateLimit(2, 3)).toBe(true);
    expect(isUnderPoliceReportRateLimit(3, 3)).toBe(false);
  });

  it('list limiter shares the shape with a looser default cap', () => {
    expect(isUnderPoliceListRateLimit(POLICE_LIST_RATE_LIMIT_MAX - 1)).toBe(true);
    expect(isUnderPoliceListRateLimit(POLICE_LIST_RATE_LIMIT_MAX)).toBe(false);
    expect(POLICE_LIST_RATE_LIMIT_MAX).toBeGreaterThan(POLICE_REPORT_RATE_LIMIT_MAX);
  });

  it('window index + doc id + expiry are deterministic and self-consistent', () => {
    const uid = 'user-abc';
    const t = 5 * POLICE_REPORT_RATE_LIMIT_WINDOW_MS + 123;
    const idx = policeReportRateLimitWindowIndex(t);
    expect(idx).toBe(5);
    expect(policeReportRateLimitDocId(uid, t)).toBe(`${uid}_5`);
    // Same minute → same doc; next minute → different doc.
    expect(policeReportRateLimitDocId(uid, t + 100)).toBe(`${uid}_5`);
    expect(policeReportRateLimitDocId(uid, t + POLICE_REPORT_RATE_LIMIT_WINDOW_MS)).toBe(`${uid}_6`);
    // Expiry is past the window end (window end + grace).
    expect(policeReportRateLimitExpiry(t).getTime()).toBeGreaterThan(
      (idx + 1) * POLICE_REPORT_RATE_LIMIT_WINDOW_MS,
    );
  });
});

describe('marker-liveness filter (isPoliceReportLive)', () => {
  const now = 1_000_000;

  it('is live only while active AND unexpired (strictly future expiry)', () => {
    expect(isPoliceReportLive(POLICE_ACTIVE_STATUS, now + 1, now)).toBe(true);
    // Expiring exactly now is already gone (matches expiresAt > request.time).
    expect(isPoliceReportLive(POLICE_ACTIVE_STATUS, now, now)).toBe(false);
    expect(isPoliceReportLive(POLICE_ACTIVE_STATUS, now - 1, now)).toBe(false);
  });

  it('is not live for a non-active status', () => {
    expect(isPoliceReportLive('cleared', now + 10_000, now)).toBe(false);
    expect(isPoliceReportLive(undefined, now + 10_000, now)).toBe(false);
  });

  it('is not live on a corrupt expiry or clock', () => {
    expect(isPoliceReportLive(POLICE_ACTIVE_STATUS, Number.NaN, now)).toBe(false);
    expect(isPoliceReportLive(POLICE_ACTIVE_STATUS, now + 1, Number.NaN)).toBe(false);
  });
});

describe('expiry (short TTL)', () => {
  it('is a short window in the requested 30–45 min band', () => {
    expect(POLICE_REPORT_TTL_MS).toBeGreaterThanOrEqual(30 * 60 * 1000);
    expect(POLICE_REPORT_TTL_MS).toBeLessThanOrEqual(45 * 60 * 1000);
  });

  it('policeExpiryFor is TTL from now', () => {
    const now = new Date('2026-08-19T10:00:00.000Z');
    expect(policeExpiryFor(now).getTime()).toBe(now.getTime() + POLICE_REPORT_TTL_MS);
  });
});

describe('radius clamp + geo-cell indexing', () => {
  it('clamps radius into [MIN, MAX] and defaults an absent/NaN value', () => {
    expect(clampRadiusMeters(undefined)).toBe(DEFAULT_RADIUS_METERS);
    expect(clampRadiusMeters(Number.NaN)).toBe(DEFAULT_RADIUS_METERS);
    expect(clampRadiusMeters(1)).toBe(MIN_RADIUS_METERS);
    expect(clampRadiusMeters(9_999_999)).toBe(MAX_RADIUS_METERS);
    expect(clampRadiusMeters(1000)).toBe(1000);
  });

  it('geoCellKey is deterministic and matches the cell size', () => {
    const key = geoCellKey(BASE_LAT, BASE_LNG);
    expect(key).toBe(
      `${Math.floor(BASE_LAT / CELL_SIZE_DEGREES)}_${Math.floor(BASE_LNG / CELL_SIZE_DEGREES)}`,
    );
  });

  it('geoCellsForRadius includes the centre cell and stays bounded', () => {
    const cells = geoCellsForRadius(BASE_LAT, BASE_LNG, 5_000);
    expect(cells).toContain(geoCellKey(BASE_LAT, BASE_LNG));
    expect(cells.length).toBeGreaterThan(0);
    expect(cells.length).toBeLessThan(50);
  });

  it('boundingBox brackets the centre', () => {
    const box = boundingBox(BASE_LAT, BASE_LNG, 1_000);
    expect(box.minLat).toBeLessThan(BASE_LAT);
    expect(box.maxLat).toBeGreaterThan(BASE_LAT);
    expect(box.minLng).toBeLessThan(BASE_LNG);
    expect(box.maxLng).toBeGreaterThan(BASE_LNG);
  });

  it('isWithinRadius agrees with a hand-picked near/far pair', () => {
    const near = north(400);
    const far = north(2_000);
    expect(isWithinRadius(BASE_LAT, BASE_LNG, near.lat, near.lng, 500)).toBe(true);
    expect(isWithinRadius(BASE_LAT, BASE_LNG, far.lat, far.lng, 500)).toBe(false);
  });

  it('chunk splits into groups of at most `size`', () => {
    expect(chunk([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]]);
    expect(chunk([], 3)).toEqual([]);
  });
});

describe('input parsing', () => {
  it('accepts a valid report and defaults source to manual via the builder', () => {
    const parsed = parseReportInput({ latitude: BASE_LAT, longitude: BASE_LNG });
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      const fields = buildPoliceReportFields({
        latitude: parsed.input.latitude,
        longitude: parsed.input.longitude,
        reporterUid: 'uid-1',
        source: parsed.input.source,
      });
      expect(fields.source).toBe('manual');
      expect(fields.status).toBe(POLICE_ACTIVE_STATUS);
      expect(fields.reporterUid).toBe('uid-1');
      expect(fields.geoCell).toBe(geoCellKey(BASE_LAT, BASE_LNG));
    }
  });

  it('accepts an explicit convoy source', () => {
    const parsed = parseReportInput({ latitude: BASE_LAT, longitude: BASE_LNG, source: 'convoy' });
    expect(parsed.ok).toBe(true);
    if (parsed.ok) expect(parsed.input.source).toBe('convoy');
  });

  it('rejects an out-of-range coordinate, an unknown source, and extra keys', () => {
    expect(parseReportInput({ latitude: 200, longitude: BASE_LNG }).ok).toBe(false);
    expect(parseReportInput({ latitude: BASE_LAT, longitude: BASE_LNG, source: 'x' }).ok).toBe(
      false,
    );
    expect(
      parseReportInput({ latitude: BASE_LAT, longitude: BASE_LNG, extra: 1 }).ok,
    ).toBe(false);
  });

  it('parses listNearby input with optional radius', () => {
    expect(parseListNearbyInput({ latitude: BASE_LAT, longitude: BASE_LNG }).ok).toBe(true);
    expect(
      parseListNearbyInput({ latitude: BASE_LAT, longitude: BASE_LNG, radiusMeters: 1000 }).ok,
    ).toBe(true);
    expect(parseListNearbyInput({ latitude: BASE_LAT }).ok).toBe(false);
  });

  it('isReportable rejects a NaN coordinate', () => {
    expect(isReportable(BASE_LAT, BASE_LNG)).toBe(true);
    expect(isReportable(Number.NaN, BASE_LNG)).toBe(false);
  });
});

describe('verify ledger helpers', () => {
  it('isValidVoteCount accepts non-negative safe integers only', () => {
    expect(isValidVoteCount(0)).toBe(true);
    expect(isValidVoteCount(7)).toBe(true);
    expect(isValidVoteCount(-1)).toBe(false);
    expect(isValidVoteCount(1.5)).toBe(false);
    expect(isValidVoteCount(Number.NaN)).toBe(false);
    expect(isValidVoteCount(Number.POSITIVE_INFINITY)).toBe(false);
    expect(isValidVoteCount(undefined)).toBe(false);
    expect(isValidVoteCount('3')).toBe(false);
  });

  it('readVoteCount normalises absent/corrupt to 0 but keeps a valid count', () => {
    expect(readVoteCount(undefined)).toBe(0);
    expect(readVoteCount(Number.NaN)).toBe(0);
    expect(readVoteCount(-4)).toBe(0);
    expect(readVoteCount(5)).toBe(5);
  });

  it('parseVoteInput requires a Firestore-safe policeReportId and rejects extras', () => {
    expect(parseVoteInput({ policeReportId: 'abc123' }).ok).toBe(true);
    expect(parseVoteInput({}).ok).toBe(false);
    expect(parseVoteInput({ policeReportId: '' }).ok).toBe(false);
    // Path separators / dot segments are rejected so .doc(id) can't throw.
    expect(parseVoteInput({ policeReportId: 'a/b' }).ok).toBe(false);
    expect(parseVoteInput({ policeReportId: '..' }).ok).toBe(false);
    expect(parseVoteInput({ policeReportId: 'abc', extra: 1 }).ok).toBe(false);
  });

  it('isUnderPoliceVoteRateLimit throttles at the cap and fails OPEN on a corrupt counter', () => {
    expect(isUnderPoliceVoteRateLimit(0)).toBe(true);
    expect(isUnderPoliceVoteRateLimit(POLICE_VOTE_RATE_LIMIT_MAX - 1)).toBe(true);
    expect(isUnderPoliceVoteRateLimit(POLICE_VOTE_RATE_LIMIT_MAX)).toBe(false);
    // A garbled counter must never lock a member out of verifying.
    expect(isUnderPoliceVoteRateLimit(Number.NaN)).toBe(true);
  });

  it('policeVoteRateLimitDocId shares the report window index', () => {
    const nowMs = 1_770_000_000_000;
    expect(policeVoteRateLimitDocId('uid-9', nowMs)).toBe(
      `uid-9_${policeReportRateLimitWindowIndex(nowMs)}`,
    );
  });
});
