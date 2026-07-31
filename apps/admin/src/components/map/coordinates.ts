/**
 * Pure coordinate helpers for the admin map location picker.
 *
 * These functions contain NO Mapbox GL / DOM / WebGL code so they are fully
 * unit-testable under jsdom. The React component (MapLocationPicker) keeps all
 * GL-callback wiring thin and delegates the value transforms here.
 */

export interface LatLng {
  lat: number;
  lng: number;
}

/** WGS-84 valid ranges. */
export const LAT_MIN = -90;
export const LAT_MAX = 90;
export const LNG_MIN = -180;
export const LNG_MAX = 180;

/**
 * Default map viewport centre when no coordinate has been picked yet.
 *
 * Deliberately NOT (0, 0): a fresh map must never suggest — or be able to
 * silently submit — Null Island. Centred on Kungsbacka, Sweden (the community's
 * home area) so the operator starts near the relevant region.
 */
export const DEFAULT_CENTER: LatLng = { lat: 57.4874, lng: 12.0761 };
export const DEFAULT_ZOOM = 11;

/**
 * True when `value` is a finite coordinate inside the WGS-84 bounds.
 *
 * IMPORTANT: `null` (no pick made) is invalid, but an explicit `{lat:0,lng:0}`
 * IS valid — Null Island is a real coordinate. The "don't submit (0,0) by
 * accident" guard lives in the pick flow (value stays `null` until the operator
 * actually places the pin), NOT here. See `parseLatLng`.
 */
export function isValidCoordinate(value: LatLng | null | undefined): value is LatLng {
  if (!value) return false;
  const { lat, lng } = value;
  return (
    Number.isFinite(lat) &&
    Number.isFinite(lng) &&
    lat >= LAT_MIN &&
    lat <= LAT_MAX &&
    lng >= LNG_MIN &&
    lng <= LNG_MAX
  );
}

/**
 * Parse the paired latitude/longitude form strings into a coordinate.
 *
 * Returns `null` when EITHER field is blank (no pick / partial entry), or when
 * the values are non-finite or out of WGS-84 bounds. A blank pair is never
 * coerced to (0, 0).
 */
export function parseLatLng(
  latitude: string,
  longitude: string,
): LatLng | null {
  if (latitude.trim() === '' || longitude.trim() === '') return null;
  const lat = Number(latitude);
  const lng = Number(longitude);
  const candidate: LatLng = { lat, lng };
  return isValidCoordinate(candidate) ? candidate : null;
}

/**
 * Round a picked coordinate to a sensible fixed precision (~1 cm) so the form
 * fields do not fill with floating-point noise from marker drags.
 */
export function roundCoordinate(value: number): number {
  return Math.round(value * 1e7) / 1e7;
}

/** Convert a picked coordinate to the paired form strings. */
export function formatLatLng(value: LatLng | null): {
  latitude: string;
  longitude: string;
} {
  if (!value) return { latitude: '', longitude: '' };
  return {
    latitude: String(roundCoordinate(value.lat)),
    longitude: String(roundCoordinate(value.lng)),
  };
}

/**
 * Clamp a longitude into [-180, 180] and a latitude into [-90, 90]. Used when
 * a raw GL event could momentarily yield an out-of-range value (e.g. dragging
 * across the antimeridian on a wrapped map).
 */
export function clampCoordinate(value: LatLng): LatLng {
  return {
    lat: Math.min(LAT_MAX, Math.max(LAT_MIN, value.lat)),
    lng: Math.min(LNG_MAX, Math.max(LNG_MIN, value.lng)),
  };
}

/**
 * The public Mapbox GL JS access token, read from the Vite env at build time.
 *
 * Returns an empty string when unset so callers can degrade gracefully to the
 * manual latitude/longitude inputs rather than rendering a broken map. Never
 * hardcode a token here.
 */
export function getMapboxToken(): string {
  const token = import.meta.env.VITE_MAPBOX_TOKEN as string | undefined;
  return typeof token === 'string' ? token.trim() : '';
}

/** Whether a usable Mapbox token is configured for this build. */
export function isMapAvailable(): boolean {
  return getMapboxToken() !== '';
}
