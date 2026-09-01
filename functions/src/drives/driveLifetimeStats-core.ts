/**
 * Pure logic for the TRUE-LIFETIME saved-drive statistics aggregate
 * (drives.lifetimeStats).
 *
 * Unlike drives.stats — which deliberately aggregates over ONLY the caller's
 * TIER-VISIBLE drives (Community newest 5, Plus rolling 90 days, Supporter all)
 * so deeper stats are a paid benefit — this aggregate spans ALL of the owner's
 * drives regardless of subscription tier. It exists because the profile "my
 * stats" fold and the badge system (e.g. the Vägfarare lifetime-distance badge)
 * measure genuine lifetime achievement, which must NOT be paywalled: a member
 * who downgrades to Community does not lose the distance they have already
 * driven. There is no tier window and no month range here.
 *
 * This module holds only the pure, Admin-SDK-free field-guarding and reducer,
 * so both are unit-testable without the emulator. It mirrors
 * driveStats-core.ts's non-negative-safe-integer field guarding (a malformed
 * durationSeconds drops the whole drive from the sums; distance/speed values
 * degrade to null and are simply excluded from their sums/maxima), but it does
 * NOT require a createdAt — legacy drives saved before that field existed must
 * still contribute to a lifetime total.
 */

/** One owner ride reduced to the fields the lifetime scan needs. */
export interface LifetimeDriveSample {
  distanceMeters: number | null;
  durationSeconds: number;
  averageSpeedMps: number | null;
  maxSpeedMps: number | null;
}

function nonNegativeFiniteOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}

/**
 * Validates one ride's raw stored fields into a scan sample, or returns null to
 * DROP the drive from the sums entirely. Mirrors driveStats-core's
 * buildDriveStatSample: a durationSeconds that is not a non-negative safe
 * integer skips the whole drive so a corrupt value can never produce a negative
 * or nonsensical total. distanceMeters / averageSpeedMps / maxSpeedMps degrade
 * to null when malformed and are then simply excluded from their sums and
 * maxima — never coerced to a negative or a false 0.
 *
 * NOTE the deliberate divergence from driveStats-core: there is no createdAt
 * requirement here. The lifetime aggregate has no month window, so a drive that
 * predates the createdAt field still counts toward the lifetime totals.
 */
export function buildLifetimeSample(input: {
  distanceMeters: unknown;
  durationSeconds: unknown;
  averageSpeedMps: unknown;
  maxSpeedMps: unknown;
}): LifetimeDriveSample | null {
  if (
    typeof input.durationSeconds !== 'number' ||
    !Number.isSafeInteger(input.durationSeconds) ||
    input.durationSeconds < 0
  ) {
    return null;
  }
  return {
    distanceMeters: nonNegativeFiniteOrNull(input.distanceMeters),
    durationSeconds: input.durationSeconds,
    averageSpeedMps: nonNegativeFiniteOrNull(input.averageSpeedMps),
    maxSpeedMps: nonNegativeFiniteOrNull(input.maxSpeedMps),
  };
}

/** The figures derived from the in-memory scan of the owner's ride samples. */
export interface ScannedLifetimeStats {
  totalDistanceMeters: number;
  totalDurationSeconds: number;
  longestDriveMeters: number;
  fastestAverageSpeedMps: number;
  highestMaxSpeedMps: number;
}

/**
 * Reduces the owner's ride samples to the lifetime sums and max/longest
 * figures. A missing figure (null) never lowers a maximum; when none exists the
 * maximum stays 0 (read as "no drive with this stat"), never a false negative.
 * Mirrors scanDriveStats minus the month tallies.
 */
export function scanLifetimeStats(
  samples: Iterable<LifetimeDriveSample>,
): ScannedLifetimeStats {
  let totalDistanceMeters = 0;
  let totalDurationSeconds = 0;
  let longestDriveMeters = 0;
  let fastestAverageSpeedMps = 0;
  let highestMaxSpeedMps = 0;
  for (const sample of samples) {
    if (sample.distanceMeters != null) totalDistanceMeters += sample.distanceMeters;
    totalDurationSeconds += sample.durationSeconds;
    if (sample.distanceMeters != null && sample.distanceMeters > longestDriveMeters) {
      longestDriveMeters = sample.distanceMeters;
    }
    if (sample.averageSpeedMps != null && sample.averageSpeedMps > fastestAverageSpeedMps) {
      fastestAverageSpeedMps = sample.averageSpeedMps;
    }
    if (sample.maxSpeedMps != null && sample.maxSpeedMps > highestMaxSpeedMps) {
      highestMaxSpeedMps = sample.maxSpeedMps;
    }
  }
  return {
    totalDistanceMeters,
    totalDurationSeconds,
    longestDriveMeters,
    fastestAverageSpeedMps,
    highestMaxSpeedMps,
  };
}

/**
 * True-lifetime statistics response. Every figure is derived from ALL of the
 * caller's drives, regardless of subscription tier — the un-paywalled counter-
 * part to drives.stats. Fields mirror the Android profile fold
 * (DriveStatsCalculator) minus the "this month" tallies, so the later Android
 * migration can consume this in place of its direct owner-drives read.
 *
 * Every figure — totalDrives, the sums and the maxima — is derived from a
 * SINGLE in-memory scan of the owner's rides (no count()/sum() aggregation), so
 * they are always one consistent snapshot (mirrors drives.stats). totalDrives
 * is the count of VALID drives in that snapshot: a drive with a malformed
 * durationSeconds is dropped so it can never corrupt a total.
 * `averageDriveMeters` is totalDistanceMeters / totalDrives (0 when no drives),
 * so summary-only saves with a null distance still count toward the denominator.
 * A maximum figure (longest/fastest/highestMax) is 0 when no drive carries that
 * stat, never a false negative. serverNowMillis lets the client label the scope
 * without trusting device time.
 */
export interface DriveLifetimeStatsResponse {
  serverNowMillis: number;
  totalDrives: number;
  totalDistanceMeters: number;
  totalDurationSeconds: number;
  longestDriveMeters: number;
  averageDriveMeters: number;
  fastestAverageSpeedMps: number;
  highestMaxSpeedMps: number;
}
