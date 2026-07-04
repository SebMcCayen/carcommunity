/**
 * Crown Hunt geographic and position-validation helpers (Phase 9h) — ported
 * verbatim from services/api/src/lib/crown-hunt-geo.ts per
 * docs/migration/backend-domain-mapping.md ("Must preserve all validation
 * logic").
 *
 * Small, testable pure functions — no database or service dependencies.
 *
 * Safety rules encoded here:
 *  - Coordinates must be valid WGS-84 values.
 *  - Positions must be fresh (not older than MAX_POSITION_AGE_SECONDS).
 *  - Speed must be at or below MAX_CLAIM_SPEED_MPS (~5 km/h) to allow a claim.
 *  - Geofence check accounts for reported GPS accuracy (conservative).
 *  - Distance is computed server-side; client-supplied distance is never trusted.
 *  - No route history is created here.
 *  - No coordinates are logged.
 */

import { MAX_CLAIM_SPEED_MPS, MAX_POSITION_AGE_SECONDS } from './crownhunt-core';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const EARTH_RADIUS_METERS = 6_371_000;

// ---------------------------------------------------------------------------
// Coordinate validation
// ---------------------------------------------------------------------------

/** Returns true when latitude is a valid WGS-84 value. */
export function isValidLatitude(lat: number): boolean {
  return Number.isFinite(lat) && lat >= -90 && lat <= 90;
}

/** Returns true when longitude is a valid WGS-84 value. */
export function isValidLongitude(lon: number): boolean {
  return Number.isFinite(lon) && lon >= -180 && lon <= 180;
}

/** Returns true when both latitude and longitude are valid. */
export function isValidCoordinate(lat: number, lon: number): boolean {
  return isValidLatitude(lat) && isValidLongitude(lon);
}

// ---------------------------------------------------------------------------
// Haversine distance
// ---------------------------------------------------------------------------

/**
 * Calculates the great-circle distance between two WGS-84 coordinates.
 *
 * @returns Distance in meters.
 */
export function haversineDistanceMeters(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number,
): number {
  const toRad = (deg: number) => (deg * Math.PI) / 180;

  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);

  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);

  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return EARTH_RADIUS_METERS * c;
}

// ---------------------------------------------------------------------------
// Freshness check
// ---------------------------------------------------------------------------

/**
 * Returns true when a position is fresh enough for a Kronjakt claim.
 *
 * @param recordedAt - ISO 8601 timestamp from the device GPS.
 * @param nowMs      - Current epoch milliseconds (injectable for testing).
 * @param maxAgeSeconds - Maximum allowed age in seconds. Defaults to MAX_POSITION_AGE_SECONDS.
 */
export function isPositionFresh(
  recordedAt: string,
  nowMs: number = Date.now(),
  maxAgeSeconds: number = MAX_POSITION_AGE_SECONDS,
): boolean {
  const recordedMs = new Date(recordedAt).getTime();
  if (!Number.isFinite(recordedMs)) return false;
  const ageSeconds = (nowMs - recordedMs) / 1000;
  return ageSeconds >= 0 && ageSeconds <= maxAgeSeconds;
}

// ---------------------------------------------------------------------------
// Speed check
// ---------------------------------------------------------------------------

/**
 * Returns true when the reported speed is safe enough to allow a claim.
 *
 * A null or undefined speed is treated as safe (speed not reported by device).
 * The backend still validates other signals in that case.
 *
 * @param speedMps     - Reported speed in meters per second (may be null).
 * @param maxSpeedMps  - Maximum allowed speed. Defaults to MAX_CLAIM_SPEED_MPS.
 */
export function isSpeedSafe(
  speedMps: number | null | undefined,
  maxSpeedMps: number = MAX_CLAIM_SPEED_MPS,
): boolean {
  if (speedMps === null || speedMps === undefined) return true;
  // DELIBERATE deviation from the legacy port (which treated invalid values
  // as safe): a negative or non-finite speed is client-controlled input and
  // treating it as safe made the stopped-speed safety gate bypassable with
  // e.g. speed = -1. Invalid values are now UNSAFE; the callable schema
  // additionally rejects them as invalid-argument.
  if (!Number.isFinite(speedMps) || speedMps < 0) return false;
  return speedMps <= maxSpeedMps;
}

// ---------------------------------------------------------------------------
// Geofence check
// ---------------------------------------------------------------------------

/**
 * Returns true when the user is within the geofence of a Kronjakt point,
 * accounting conservatively for the reported GPS accuracy.
 *
 * The effective threshold is: geofenceRadius + (accuracyMeters * accuracyBuffer).
 * This ensures a user with poor GPS accuracy is not unfairly rejected at the boundary.
 * The buffer is intentionally kept small (0.5) so that accuracy cannot be used to
 * claim from far outside the intended stopping area.
 *
 * @param distanceMeters     - Distance from user to point (server-computed via Haversine).
 * @param geofenceRadiusMeters - Configured point geofence radius.
 * @param accuracyMeters     - Reported horizontal GPS accuracy (may be null).
 */
export function isWithinGeofence(
  distanceMeters: number,
  geofenceRadiusMeters: number,
  accuracyMeters: number | null | undefined,
): boolean {
  const accuracyBuffer = 0.5; // conservative multiplier
  const accuracy = accuracyMeters !== null && accuracyMeters !== undefined && accuracyMeters > 0
    ? accuracyMeters
    : 0;
  const effectiveRadius = geofenceRadiusMeters + accuracy * accuracyBuffer;
  return distanceMeters <= effectiveRadius;
}

// ---------------------------------------------------------------------------
// Impossible jump check
// ---------------------------------------------------------------------------

/**
 * Returns true when a position jump from a trusted reference position is
 * geographically plausible given the elapsed time.
 *
 * Uses a generous maximum realistic speed (130 m/s ≈ 468 km/h) to only
 * catch clearly impossible jumps (teleportation), not normal fast driving.
 *
 * @param prevLat        - Previous trusted latitude.
 * @param prevLon        - Previous trusted longitude.
 * @param prevRecordedAt - ISO 8601 timestamp of the previous position.
 * @param newLat         - New reported latitude.
 * @param newLon         - New reported longitude.
 * @param nowMs          - Current epoch milliseconds.
 * @param maxSpeedMps    - Maximum physically plausible speed (default: 130 m/s).
 */
export function isPlausibleJump(
  prevLat: number,
  prevLon: number,
  prevRecordedAt: string,
  newLat: number,
  newLon: number,
  nowMs: number = Date.now(),
  maxSpeedMps: number = 130,
): boolean {
  const prevMs = new Date(prevRecordedAt).getTime();
  if (!Number.isFinite(prevMs)) return true; // cannot verify — allow
  const elapsedSeconds = Math.max(0, (nowMs - prevMs) / 1000);
  if (elapsedSeconds <= 0) return false; // same instant — suspicious
  const distance = haversineDistanceMeters(prevLat, prevLon, newLat, newLon);
  const impliedSpeed = distance / elapsedSeconds;
  return impliedSpeed <= maxSpeedMps;
}
