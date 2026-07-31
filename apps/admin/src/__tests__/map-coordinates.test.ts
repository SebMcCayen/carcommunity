/**
 * Unit tests for the pure coordinate helpers behind the admin MapLocationPicker.
 *
 * These are the parts that must stay correct independent of MapLibre GL (which
 * cannot render under jsdom). They cover the repo's known traps:
 *   - a blank/unset pair must NOT be coerced to (0, 0) / Null Island; it is
 *     `null` (no pick) — but an explicit (0, 0) is a valid coordinate; and
 *   - non-finite (NaN / ±Infinity) and out-of-WGS-84-bounds values are rejected.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  clampCoordinate,
  formatLatLng,
  getMapStyleUrl,
  isMapAvailable,
  isValidCoordinate,
  parseLatLng,
  roundCoordinate,
} from '@/components/map/coordinates';

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

describe('getMapStyleUrl / isMapAvailable', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('returns empty and unavailable when unset (graceful fallback)', () => {
    vi.stubEnv('VITE_MAP_STYLE_URL', '');
    expect(getMapStyleUrl()).toBe('');
    expect(isMapAvailable()).toBe(false);
  });

  it('returns the trimmed style URL and available when set', () => {
    vi.stubEnv('VITE_MAP_STYLE_URL', '  https://example.com/style.json  ');
    expect(getMapStyleUrl()).toBe('https://example.com/style.json');
    expect(isMapAvailable()).toBe(true);
  });
});
