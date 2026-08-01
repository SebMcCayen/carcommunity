/**
 * Unit tests for the pure coordinate helpers behind the admin MapLocationPicker.
 *
 * These are the parts that must stay correct independent of Mapbox GL (which
 * cannot render under jsdom). They cover the repo's known traps:
 *   - a blank/unset pair must NOT be coerced to (0, 0) / Null Island; it is
 *     `null` (no pick) — but an explicit (0, 0) is a valid coordinate; and
 *   - non-finite (NaN / ±Infinity) and out-of-WGS-84-bounds values are rejected.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  DEFAULT_CENTER,
  DEFAULT_ZOOM,
  clampCoordinate,
  formatLatLng,
  geofenceCirclePolygon,
  getMapboxToken,
  isMapAvailable,
  isValidCoordinate,
  parseLatLng,
  roundCoordinate,
} from '@/components/map/coordinates';

describe('DEFAULT_CENTER / DEFAULT_ZOOM (empty-picker default)', () => {
  it('is a valid coordinate and never Null Island (0, 0)', () => {
    expect(isValidCoordinate(DEFAULT_CENTER)).toBe(true);
    expect(DEFAULT_CENTER).not.toEqual({ lat: 0, lng: 0 });
  });

  it('is centred on Kungsbacka town centre, Sweden (~57.49 N, ~12.08 E)', () => {
    // Kungsbacka town centre is ≈ 57.4878 N, 12.0754 E. Assert the default
    // sits within a tight ~2 km box of it so an accidental edit to a wrong
    // region (or back to 0,0) fails the suite.
    expect(DEFAULT_CENTER.lat).toBeGreaterThan(57.47);
    expect(DEFAULT_CENTER.lat).toBeLessThan(57.51);
    expect(DEFAULT_CENTER.lng).toBeGreaterThan(12.05);
    expect(DEFAULT_CENTER.lng).toBeLessThan(12.1);
  });

  it('uses a town-level default zoom (frames the town, not the region/street)', () => {
    expect(DEFAULT_ZOOM).toBeGreaterThanOrEqual(11);
    expect(DEFAULT_ZOOM).toBeLessThanOrEqual(14);
  });
});

describe('isValidCoordinate', () => {
  it('accepts an in-bounds finite coordinate', () => {
    expect(isValidCoordinate({ lat: 57.4874, lng: 12.0761 })).toBe(true);
  });

  it('accepts an explicit (0, 0) — Null Island is a real coordinate', () => {
    expect(isValidCoordinate({ lat: 0, lng: 0 })).toBe(true);
  });

  it('rejects null / undefined (no pick made)', () => {
    expect(isValidCoordinate(null)).toBe(false);
    expect(isValidCoordinate(undefined)).toBe(false);
  });

  it('rejects out-of-bounds latitude/longitude', () => {
    expect(isValidCoordinate({ lat: 91, lng: 0 })).toBe(false);
    expect(isValidCoordinate({ lat: -90.1, lng: 0 })).toBe(false);
    expect(isValidCoordinate({ lat: 0, lng: 181 })).toBe(false);
    expect(isValidCoordinate({ lat: 0, lng: -181 })).toBe(false);
  });

  it('rejects non-finite values (NaN, ±Infinity)', () => {
    expect(isValidCoordinate({ lat: NaN, lng: 0 })).toBe(false);
    expect(isValidCoordinate({ lat: 0, lng: Infinity })).toBe(false);
    expect(isValidCoordinate({ lat: -Infinity, lng: 0 })).toBe(false);
  });
});

describe('parseLatLng', () => {
  it('returns null when either field is blank (never coerced to 0,0)', () => {
    expect(parseLatLng('', '')).toBeNull();
    expect(parseLatLng('57.5', '')).toBeNull();
    expect(parseLatLng('', '12.1')).toBeNull();
    expect(parseLatLng('   ', '   ')).toBeNull();
  });

  it('parses a valid pair', () => {
    expect(parseLatLng('57.4874', '12.0761')).toEqual({
      lat: 57.4874,
      lng: 12.0761,
    });
  });

  it('parses an explicit "0"/"0" pair as (0, 0)', () => {
    expect(parseLatLng('0', '0')).toEqual({ lat: 0, lng: 0 });
  });

  it('returns null for out-of-bounds or non-numeric input', () => {
    expect(parseLatLng('200', '0')).toBeNull();
    expect(parseLatLng('abc', '12')).toBeNull();
    expect(parseLatLng('Infinity', '0')).toBeNull();
  });
});

describe('formatLatLng', () => {
  it('maps null to blank strings', () => {
    expect(formatLatLng(null)).toEqual({ latitude: '', longitude: '' });
  });

  it('round-trips through parseLatLng', () => {
    const formatted = formatLatLng({ lat: 57.4874, lng: 12.0761 });
    expect(parseLatLng(formatted.latitude, formatted.longitude)).toEqual({
      lat: 57.4874,
      lng: 12.0761,
    });
  });

  it('rounds off floating-point drag noise to ~1cm precision', () => {
    expect(roundCoordinate(57.48740000000123)).toBe(57.4874);
  });
});

describe('clampCoordinate', () => {
  it('clamps out-of-range values into WGS-84 bounds', () => {
    expect(clampCoordinate({ lat: 95, lng: 200 })).toEqual({
      lat: 90,
      lng: 180,
    });
    expect(clampCoordinate({ lat: -95, lng: -200 })).toEqual({
      lat: -90,
      lng: -180,
    });
  });
});

describe('geofenceCirclePolygon', () => {
  const allFinite = (poly: ReturnType<typeof geofenceCirclePolygon>) =>
    poly.geometry.coordinates[0]!.every(
      ([lng, lat]) => Number.isFinite(lng) && Number.isFinite(lat),
    );

  it('produces a closed ring around a mid-latitude centre', () => {
    const poly = geofenceCirclePolygon({ lat: 57.4874, lng: 12.0761 }, 100);
    const ring = poly.geometry.coordinates[0]!;
    expect(ring.length).toBe(65); // steps + 1
    expect(ring[0]).toEqual(ring[ring.length - 1]); // closed
    expect(allFinite(poly)).toBe(true);
  });

  it('never emits Infinity/NaN at the poles (cos(lat) -> 0 guard)', () => {
    for (const lat of [90, -90, 89.9999, -89.9999]) {
      const poly = geofenceCirclePolygon({ lat, lng: 0 }, 150);
      expect(allFinite(poly)).toBe(true);
    }
  });

  it('scales the ring with the radius', () => {
    const center = { lat: 0, lng: 0 };
    const small = geofenceCirclePolygon(center, 100).geometry.coordinates[0]![0]!;
    const large = geofenceCirclePolygon(center, 1000).geometry.coordinates[0]![0]!;
    expect(Math.abs(large[0])).toBeGreaterThan(Math.abs(small[0]));
  });
});

describe('getMapboxToken / isMapAvailable', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('returns empty and unavailable when unset (graceful fallback)', () => {
    vi.stubEnv('VITE_MAPBOX_TOKEN', '');
    expect(getMapboxToken()).toBe('');
    expect(isMapAvailable()).toBe(false);
  });

  it('returns the trimmed pk. token and available when set', () => {
    vi.stubEnv('VITE_MAPBOX_TOKEN', '  pk.eyJfake.token  ');
    expect(getMapboxToken()).toBe('pk.eyJfake.token');
    expect(isMapAvailable()).toBe(true);
  });
});
