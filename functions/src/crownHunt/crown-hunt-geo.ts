/**
 * Crown Hunt geographic and position-validation helpers (Phase 9h) — based on
 * services/api/src/lib/crown-hunt-geo.ts per
 * docs/migration/backend-domain-mapping.md ("Must preserve all validation
 * logic").
 *
 * NOT a verbatim port. Migration parity is preserved except where the legacy
 * behaviour was itself a hole; each deviation below is deliberate, so a
 * parity audit should expect it rather than "fix" it back:
 *
 *  1. `isSpeedSafe` returns FALSE for a non-finite or negative speed. Legacy
 *     treated those as safe, which let a claim bypass the stopped-vehicle
 *     check by reporting a negative speed.
 *  2. The geofence buffer derived from the client-supplied accuracy is
 *     bounded (see {@link effectiveGeofenceRadiusMeters}). Legacy applied it
 *     unbounded, which let a claim inflate a 150 m fence to kilometres.
 *
 * Small, testable pure functions — no database or service dependencies.
 *
 * Safety rules encoded here:
 *  - Coordinates must be valid WGS-84 values.
 *  - Positions must be fresh (not older than MAX_POSITION_AGE_SECONDS).
 *  - A *reported* speed must be at or below MAX_CLAIM_SPEED_MPS (~5 km/h) to
 *    allow a claim. Known gap, stated here so the rule is not read as stronger
 *    than it is: `speedMetersPerSecond` is optional on the callable and an
 *    absent/null speed is treated as safe, so a client that simply omits the
 *    field is never speed-checked and gains no risk score for it. That is
 *    legacy behaviour, deliberately left alone by the accuracy-bound change;
 *    closing it is a separate, client-affecting decision.
 *  - Geofence check accounts for reported GPS accuracy conservatively AND
 *    boundedly: client-supplied accuracy can never inflate the fence beyond
 *    MAX_EFFECTIVE_GEOFENCE_MULTIPLIER × the configured radius.
 *  - Distance is computed server-side; client-supplied distance is never trusted.
 *  - No route history is created here.
 *  - No coordinates are logged.
 */

import { MAX_CLAIM_SPEED_MPS, MAX_POSITION_AGE_SECONDS } from './crownhunt-core';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const EARTH_RADIUS_METERS = 6_371_000;

/** Multiplier applied to the (clamped) reported accuracy when buffering. */
const GEOFENCE_ACCURACY_BUFFER = 0.5;

/**
 * Largest reported GPS accuracy (meters) that may contribute to the geofence
 * buffer. A legitimate phone fix used to claim at a 20–150 m point is well
 * under this; anything larger is either unusable for the claim or a hostile
 * value, so it is clamped rather than trusted.
 */
export const MAX_GEOFENCE_ACCURACY_METERS = 100;

/**
 * Hard ceiling on the accuracy-buffered geofence, as a multiple of the point's
 * configured radius. However the client reports accuracy, the effective fence
 * can never be more than twice the radius an admin approved.
 */
export const MAX_EFFECTIVE_GEOFENCE_MULTIPLIER = 2;

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
 * KNOWN GAP (legacy behaviour, unchanged here): a null or undefined speed is
 * treated as SAFE, on the assumption that the device did not report one. The
 * callable's `speedMetersPerSecond` is optional and `evaluateClaimRisk` adds
 * no signal for a missing speed, so a client that omits the field skips this
 * gate entirely and is not penalised for it. The other gates (freshness,
 * server-computed distance, bounded geofence, impossible-jump) still run.
 * Closing this would reject honest fixes that carry no speed, so it is a
 * separate client-affecting decision, not part of the geofence-accuracy fix.
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
 * Computes the accuracy-buffered geofence radius actually used for a claim.
 *
 * The buffer exists so a member with a mediocre-but-honest GPS fix is not
 * unfairly rejected right at the boundary. `accuracyMeters` is CLIENT-SUPPLIED,
 * so the buffer is bounded twice — an unbounded buffer let a claim declare a
 * huge accuracy and inflate a 75 m fence into kilometres:
 *
 *  1. the accuracy that feeds the buffer is clamped to
 *     MAX_GEOFENCE_ACCURACY_METERS (a non-finite or non-positive value
 *     contributes nothing at all), and
 *  2. the result is capped at MAX_EFFECTIVE_GEOFENCE_MULTIPLIER × the
 *     configured radius.
 *
 * For any finite, positive `geofenceRadiusMeters` r, the result is
 * therefore always within
 * `[r, min(r + MAX_GEOFENCE_ACCURACY_METERS * GEOFENCE_ACCURACY_BUFFER,
 * r * MAX_EFFECTIVE_GEOFENCE_MULTIPLIER)]` (with today's constants: `[r,
 * min(r + 50, 2r)]`) — enforced, not merely intended, and asserted over a grid
 * of radii × accuracies in crownhunt-core.test.ts.
 *
 * A radius that is not a finite positive number — a point document whose
 * `geofenceRadiusMeters` is missing, null, or non-numeric, which reaches
 * submitClaim behind a bare `as number` cast — returns NaN, and `distance <=
 * NaN` is false. Such a point therefore rejects every claim (fail CLOSED)
 * instead of collapsing to a zero-radius fence that an exact-coordinate
 * spoof would satisfy.
 *
 * @param geofenceRadiusMeters - Configured point geofence radius.
 * @param accuracyMeters       - Reported horizontal GPS accuracy (may be null).
 */
export function effectiveGeofenceRadiusMeters(
  geofenceRadiusMeters: number,
  accuracyMeters: number | null | undefined,
): number {
  // A point without a usable radius must reject every claim, not degenerate
  // into a zero-radius fence (which an exact-coordinate spoof would satisfy).
  if (
    typeof geofenceRadiusMeters !== 'number' ||
    !Number.isFinite(geofenceRadiusMeters) ||
    geofenceRadiusMeters <= 0
  ) {
    return Number.NaN;
  }
  const accuracy =
    typeof accuracyMeters === 'number' && Number.isFinite(accuracyMeters) && accuracyMeters > 0
      ? Math.min(accuracyMeters, MAX_GEOFENCE_ACCURACY_METERS)
      : 0;
  return Math.min(
    geofenceRadiusMeters + accuracy * GEOFENCE_ACCURACY_BUFFER,
    geofenceRadiusMeters * MAX_EFFECTIVE_GEOFENCE_MULTIPLIER,
  );
}

/**
 * Returns true when the user is within the geofence of a Kronjakt point,
 * accounting conservatively — and boundedly — for the reported GPS accuracy.
 *
 * See {@link effectiveGeofenceRadiusMeters} for the bounds enforced on the
 * client-supplied accuracy.
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
  return distanceMeters <= effectiveGeofenceRadiusMeters(geofenceRadiusMeters, accuracyMeters);
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
