/**
 * Drive calculation helpers (Phase 9d) — ported verbatim from
 * services/api/src/lib/drive-calculations.ts per
 * docs/migration/backend-domain-mapping.md ("Same logic ported to saveDrive
 * callable function").
 *
 * These are intentionally dependency-light pure functions.
 * All calculations are estimates, not authoritative measurements.
 *
 * Privacy:
 *  - Maximum speed IS calculated here and stored on the ride document. This
 *    module previously stated "No top-speed calculation or storage"; that rule
 *    was reversed by an explicit product decision (2026-07), and the reversal is
 *    recorded rather than quietly dropped. What has NOT changed is the rule the
 *    old wording actually protected: speed is never rewarded. The figure is
 *    presented as one neutral fact beside distance and duration — no record, no
 *    personal best, no ranking, no comparison between drives, no celebratory
 *    styling (docs/gamification-system.md).
 *  - No driving-quality scores.
 *  - Coordinates are treated as raw numbers; never logged.
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

    // Skip segments with non-positive time delta (out-of-order or duplicate timestamps).
    if (deltaMs <= 0) {
      continue;
    }

    // Skip clearly invalid jumps (teleports or GPS glitches).
    const impliedSpeed = distanceM / (deltaMs / 1000);
    if (impliedSpeed > MAX_PLAUSIBLE_SPEED_MPS) {
      continue;
    }

    total += distanceM;
  }

  return total;
}

/**
 * Highest plausible instantaneous speed in m/s implied by consecutive points,
 * or null when none can be derived (fewer than 2 points, or every segment is
 * filtered out).
 *
 * Applies the SAME filters as {@link totalDistanceMetres}: a non-positive time
 * delta is skipped, and so is any segment implying more than
 * MAX_PLAUSIBLE_SPEED_MPS (~200 km/h). That filter is load-bearing here, more
 * so than for distance: distance averages a bad fix away over a whole drive,
 * but a maximum takes the single worst sample — one GPS glitch, unfiltered,
 * would put an absurd number on the drive's card. Non-finite results are
 * dropped defensively for the same reason.
 */
export function maxSpeedMps(points: readonly TimedPoint[]): number | null {
  if (points.length < 2) return null;

  let max: number | null = null;

  for (let i = 1; i < points.length; i++) {
    const prev = points[i - 1] as TimedPoint;
    const curr = points[i] as TimedPoint;

    const deltaMs = curr.timestampMs - prev.timestampMs;
    if (deltaMs <= 0) {
      continue;
    }

    const distanceM = haversineDistanceMetres(
      prev.latitude,
      prev.longitude,
      curr.latitude,
      curr.longitude,
    );
    const impliedSpeed = distanceM / (deltaMs / 1000);
    if (!Number.isFinite(impliedSpeed) || impliedSpeed > MAX_PLAUSIBLE_SPEED_MPS) {
      continue;
    }

    if (max === null || impliedSpeed > max) {
      max = impliedSpeed;
    }
  }

  return max;
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
