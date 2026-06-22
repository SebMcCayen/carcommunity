/**
 * Drive calculation helpers.
 *
 * These are intentionally dependency-light pure functions.
 * All calculations are estimates, not authoritative measurements.
 *
 * Privacy:
 *  - No top-speed calculation or storage.
 *  - No driving-quality scores.
 *  - Coordinates are treated as raw numbers; never logged.
 *
 * TODO: Populate distance and speed calculations once TemporaryDrivePoint
 *       collection is implemented. The current summary-only MVP derives
 *       only durationSeconds from session timestamps.
 */

/**
 * Approximate radius of the Earth in metres (mean spherical).
 * Used for Haversine calculations.
 */
const EARTH_RADIUS_METRES = 6_371_000;

/**
 * Maximum plausible speed between two consecutive points (m/s).
 * Points that imply a faster speed than this are considered invalid jumps
 * and excluded from distance totals.
 * 200 km/h = ~55.6 m/s — generous but safely above road speeds.
 */
const MAX_PLAUSIBLE_SPEED_MPS = 55.6;

function toRadians(degrees: number): number {
  return (degrees * Math.PI) / 180;
}

/**
 * Haversine distance between two geographic coordinates in metres.
 * Returns 0 for identical points.
 */
export function haversineDistanceMetres(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number,
): number {
  const dLat = toRadians(lat2 - lat1);
  const dLon = toRadians(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRadians(lat1)) *
      Math.cos(toRadians(lat2)) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return EARTH_RADIUS_METRES * c;
}

/**
 * Point with a timestamp used for coordinate-jump detection.
 */
export interface TimedPoint {
  latitude: number;
  longitude: number;
  /** Unix timestamp in milliseconds. */
  timestampMs: number;
}

/**
 * Calculate total distance in metres across an ordered sequence of timed points.
 *
 * Invalid coordinate jumps (implied speed > MAX_PLAUSIBLE_SPEED_MPS) are
 * excluded to reduce GPS noise and teleport artifacts.
 *
 * Returns 0 for fewer than 2 points.
 */
export function totalDistanceMetres(points: readonly TimedPoint[]): number {
  if (points.length < 2) return 0;

  let total = 0;

  for (let i = 1; i < points.length; i++) {
    const prev = points[i - 1] as TimedPoint;
    const curr = points[i] as TimedPoint;

    const distanceM = haversineDistanceMetres(
      prev.latitude,
      prev.longitude,
      curr.latitude,
      curr.longitude,
    );

    const deltaMs = curr.timestampMs - prev.timestampMs;

    // Skip clearly invalid jumps (teleports or GPS glitches).
    if (deltaMs > 0) {
      const impliedSpeed = distanceM / (deltaMs / 1000);
      if (impliedSpeed > MAX_PLAUSIBLE_SPEED_MPS) {
        continue;
      }
    }

    total += distanceM;
  }

  return total;
}

/**
 * Calculate drive duration in whole seconds from start and end timestamps.
 * Returns 0 if endedAt is before or equal to startedAt.
 */
export function driveDurationSeconds(startedAt: Date, endedAt: Date): number {
  const ms = endedAt.getTime() - startedAt.getTime();
  return ms > 0 ? Math.round(ms / 1000) : 0;
}

/**
 * Calculate average speed in metres per second.
 * Returns null if durationSeconds is zero.
 */
export function averageSpeedMps(
  distanceMetres: number,
  durationSeconds: number,
): number | null {
  if (durationSeconds <= 0) return null;
  return distanceMetres / durationSeconds;
}
