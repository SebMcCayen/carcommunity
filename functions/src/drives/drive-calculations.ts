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

/**
 * Maximum plausible forward ACCELERATION for a car (m/s²), used ONLY by
 * {@link maxSpeedMps} to reject a GPS position glitch that stays UNDER
 * MAX_PLAUSIBLE_SPEED_MPS and so slips past that absolute cap.
 *
 * Implied speed is distance ÷ elapsed time, so a fix that jumps ~100 m over a
 * normal ~5 s cadence implies ~20 m/s (~70 km/h) of extra speed — well under
 * 200 km/h, so the absolute cap waves it through, yet a maximum takes that
 * single worst sample. Distance averages such a fix away; a maximum cannot, so
 * it needs a tighter guard: a segment's speed counts toward the max only if it
 * is REACHABLE from the last trustworthy speed without super-car acceleration.
 * 3.5 m/s² is ~0–100 km/h in ~8 s — generous for real cars (genuine brisk
 * acceleration is never clipped) but far below the tens of m/s² a lone glitch
 * implies. Kept in parity with the Android client's MAX_PLAUSIBLE_ACCEL_MPS2
 * (DriveRecording.kt) so the stored value, the History card, and the in-app
 * top-speed marker agree.
 */
const MAX_PLAUSIBLE_ACCEL_MPS2 = 3.5;

/**
 * The acceleration budget is MAX_PLAUSIBLE_ACCEL_MPS2 × the segment's elapsed
 * seconds, but the elapsed time is capped here first. Fixes normally arrive
 * every ~2–5 s, so this is a no-op for a healthy stream; it only bites after a
 * gap (lost signal), where an uncapped budget would grow big enough to admit a
 * glitch coinciding with that gap. Capping keeps the per-segment jump ceiling
 * bounded (~21 m/s ≈ 75 km/h) regardless of gap length. Parity with the Android
 * client's ACCEL_WINDOW_CAP_SECONDS.
 */
const ACCEL_WINDOW_CAP_SECONDS = 6;

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
 * Rejects GPS spikes two ways. First the SAME filters as
 * {@link totalDistanceMetres}: a non-positive time delta is skipped, and so is
 * any segment implying more than MAX_PLAUSIBLE_SPEED_MPS (~200 km/h); non-finite
 * results are dropped defensively.
 *
 * Second — and this is what distance does NOT need — an acceleration guard. The
 * absolute cap is load-bearing here yet insufficient: distance averages a bad
 * fix away over a whole drive, but a maximum takes the single worst sample, and
 * a position glitch that stays UNDER 200 km/h (e.g. a ~150 km/h spike on a real
 * 80 km/h drive) would otherwise put an inflated number on the drive's card. So
 * a segment's implied speed counts toward the max only if it is reachable from
 * the last accepted speed without impossible acceleration
 * (MAX_PLAUSIBLE_ACCEL_MPS2 × the window-capped elapsed time). A rejected
 * segment does not advance the trusted anchor, so a single glitchy fix — which
 * corrupts the two segments that touch it (out, then back) — is rejected on both
 * halves. Only positive jumps are gated (a slowdown can never inflate a
 * maximum); a genuinely high SUSTAINED speed is admitted segment after segment
 * and never clipped. The first segment has no prior speed to compare and rests
 * on the absolute cap alone.
 */
export function maxSpeedMps(points: readonly TimedPoint[]): number | null {
  if (points.length < 2) return null;

  let max: number | null = null;
  let anchor: number | null = null;

  for (let i = 1; i < points.length; i++) {
    const prev = points[i - 1] as TimedPoint;
    const curr = points[i] as TimedPoint;

    const deltaMs = curr.timestampMs - prev.timestampMs;
    if (deltaMs <= 0) {
      continue;
    }

    const elapsedSeconds = deltaMs / 1000;
    const distanceM = haversineDistanceMetres(
      prev.latitude,
      prev.longitude,
      curr.latitude,
      curr.longitude,
    );
    const impliedSpeed = distanceM / elapsedSeconds;
    // Absolute >200 km/h backstop (+ non-finite guard). A rejected segment must
    // not advance the anchor.
    if (!Number.isFinite(impliedSpeed) || impliedSpeed > MAX_PLAUSIBLE_SPEED_MPS) {
      continue;
    }

    // Acceleration guard: drop a segment implying an impossible jump up from the
    // last trustworthy speed. Only positive jumps are implausible.
    if (anchor !== null) {
      const budgetSeconds = Math.min(elapsedSeconds, ACCEL_WINDOW_CAP_SECONDS);
      const maxIncrease = MAX_PLAUSIBLE_ACCEL_MPS2 * budgetSeconds;
      if (impliedSpeed - anchor > maxIncrease) {
        continue;
      }
    }

    anchor = impliedSpeed;
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
