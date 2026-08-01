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

/** A GeoJSON Polygon feature (the shape a Mapbox GL geojson source accepts). */
export interface CirclePolygonFeature {
  type: 'Feature';
  geometry: { type: 'Polygon'; coordinates: [number, number][][] };
  properties: Record<string, never>;
}

/**
 * Build a GeoJSON polygon approximating a circle of `radiusMeters` around
 * `center`. Pure and testable — kept out of the GL callbacks.
 *
 * Pole guard: the east/west spread divides by cos(latitude), which collapses to
 * ~0 at ±90°, so an unguarded formula emits Infinity / huge longitudes that
 * break GeoJSON rendering. `cos` is floored to a small positive value so the
 * output is always finite (the ring simply widens in longitude very near the
 * poles, which is geometrically expected).
 */
export function geofenceCirclePolygon(
  center: LatLng,
  radiusMeters: number,
  steps = 64,
): CirclePolygonFeature {
  const coords: [number, number][] = [];
  const earth = 6378137; // metres
  const latRad = (center.lat * Math.PI) / 180;
  // cos(lat) >= 0 for lat in [-90, 90]; floor it away from 0 to stay finite.
  const cosLat = Math.max(Math.cos(latRad), 1e-3);
  for (let i = 0; i <= steps; i += 1) {
    const angle = (i / steps) * 2 * Math.PI;
    const dx = (radiusMeters * Math.cos(angle)) / (earth * cosLat);
    const dy = (radiusMeters * Math.sin(angle)) / earth;
    coords.push([
      center.lng + (dx * 180) / Math.PI,
      center.lat + (dy * 180) / Math.PI,
    ]);
  }
  return {
    type: 'Feature',
    geometry: { type: 'Polygon', coordinates: [coords] },
    properties: {},
  };
}

/**
 * The Mapbox Standard style URL — the SAME v3 style the member Android app
 * renders (`Style.STANDARD` = `mapbox://styles/mapbox/standard`). Rendering it
 * requires Mapbox GL JS v3 (MapLibre cannot render Standard), so the admin
 * picker loads it via `mapbox-gl` with a public access token.
 */
export const MAPBOX_STANDARD_STYLE = 'mapbox://styles/mapbox/standard';

/**
 * The public Mapbox access token (`pk.…`), read from the Vite env at build
 * time. Mapbox GL JS needs `mapboxgl.accessToken` set before it can fetch the
 * `mapbox://` style and tiles.
 *
 * `pk.` tokens are public-by-design — they ship in the client bundle and are
 * scoped/referrer-restricted on the Mapbox side — so the value lives as a
 * GitHub Actions VARIABLE (not a secret) and is never hardcoded here.
 *
 * Returns an empty string when unset so callers degrade gracefully to the
 * manual latitude/longitude inputs rather than rendering a broken map.
 */
export function getMapboxToken(): string {
  const token = import.meta.env.VITE_MAPBOX_TOKEN as string | undefined;
  return typeof token === 'string' ? token.trim() : '';
}

/** Whether a usable Mapbox token is configured for this build. */
export function isMapAvailable(): boolean {
  return getMapboxToken() !== '';
}
