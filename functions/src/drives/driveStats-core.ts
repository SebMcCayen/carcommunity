/**
 * Pure logic for the saved-drive statistics aggregate (drives.stats).
 *
 * drives.stats aggregates all of the caller's retained drives for every tier.
 * Only aggregate figures are exposed; individual history visibility is separate.
 *
 * This module holds the untrusted-input validation (including the strict
 * "this month" boundary checks, which depend on server time and so cannot live
 * in the zod schema) and the in-memory scan reducer, both of which are pure so
 * they are unit-testable without the emulator.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';
import { DAY_MS } from './driveHistory-core';
import type { SubscriptionTier } from '../subscription/subscription-core';

/** A valid "this month" range must span at least this long (short February guard). */
export const MONTH_MIN_SPAN_MS = 27 * DAY_MS;
/** …and at most 32 days — a 31-day month plus a full day of slack for timezone/DST. */
export const MONTH_MAX_SPAN_MS = 32 * DAY_MS;
/**
 * The month may start at most ~13 months before server time. The straddle and
 * span checks already force the start to within ~32 days of now, so this is
 * defence in depth — it still bounds the request if a later edit relaxes those.
 */
export const MONTH_MAX_PAST_MS = 13 * 31 * DAY_MS;
/** …and end at most ~32 days after server time (a month just begun in the easternmost zone). */
export const MONTH_MAX_FUTURE_MS = 32 * DAY_MS;

const driveStatsInputSchema = z
  .object({
    // z.number().int() under zod v4 already rejects NaN/±Infinity and non-integers.
    monthStartMillis: z.number().int().optional(),
    monthEndMillis: z.number().int().optional(),
  })
  .strict()
  .refine(
    (value) => (value.monthStartMillis === undefined) === (value.monthEndMillis === undefined),
    { message: 'monthStartMillis and monthEndMillis must be provided together.' },
  );

export type DriveStatsInput = z.infer<typeof driveStatsInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseDriveStatsInput(data: unknown): ParseResult<DriveStatsInput> {
  const parsed = driveStatsInputSchema.safeParse(data ?? {});
  if (!parsed.success) {
    return {
      ok: false,
      message:
        'Expected { monthStartMillis?: integer, monthEndMillis?: integer } supplied together.',
    };
  }
  return { ok: true, input: parsed.data };
}

/** Validated local-calendar-month window [startMillis, endMillis). */
export interface MonthRange {
  startMillis: number;
  endMillis: number;
}

export type MonthRangeResult =
  { ok: true; range: MonthRange | null } | { ok: false; message: string };

/**
 * Applies the server-time-relative boundary checks to a parsed input. Absent
 * bounds resolve to `range: null` (no "this month" section). A supplied range
 * must straddle server time, span a single calendar month (27–32 days), and be
 * bounded near the present. All failures are invalid-argument at the call site.
 */
export function resolveMonthRange(
  input: DriveStatsInput,
  serverNowMillis: number,
): MonthRangeResult {
  if (input.monthStartMillis === undefined || input.monthEndMillis === undefined) {
    return { ok: true, range: null };
  }
  const startMillis = input.monthStartMillis;
  const endMillis = input.monthEndMillis;
  if (!(startMillis < serverNowMillis && serverNowMillis < endMillis)) {
    return { ok: false, message: 'The month range must straddle the current server time.' };
  }
  const span = endMillis - startMillis;
  if (span < MONTH_MIN_SPAN_MS || span > MONTH_MAX_SPAN_MS) {
    return {
      ok: false,
      message: 'The month range must span a single calendar month (27–32 days).',
    };
  }
  if (startMillis < serverNowMillis - MONTH_MAX_PAST_MS) {
    return { ok: false, message: 'The month range starts too far in the past.' };
  }
  if (endMillis > serverNowMillis + MONTH_MAX_FUTURE_MS) {
    return { ok: false, message: 'The month range ends too far in the future.' };
  }
  return { ok: true, range: { startMillis, endMillis } };
}

/** One owner ride reduced to the fields the stats scan needs. */
export interface DriveStatSample {
  distanceMeters: number | null;
  durationSeconds: number;
  averageSpeedMps: number | null;
  maxSpeedMps: number | null;
  createdAtMillis: number | null;
}

function nonNegativeFiniteOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}

/**
 * Validates one ride's raw stored fields into a scan sample, or returns null to
 * DROP a drive with an invalid duration. Missing/invalid dates are preserved as
 * null: legacy drives count toward lifetime figures but not month figures.
 * distanceMeters / averageSpeedMps / maxSpeedMps degrade to null when malformed
 * and are then simply excluded from their sums and maxima — never coerced to a
 * negative or a false 0. The caller extracts createdAtMillis from the Firestore
 * Timestamp (null when absent) so this stays a pure, Admin-SDK-free function.
 */
export function buildDriveStatSample(input: {
  distanceMeters: unknown;
  durationSeconds: unknown;
  averageSpeedMps: unknown;
  maxSpeedMps: unknown;
  createdAtMillis: number | null;
}): DriveStatSample | null {
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
    createdAtMillis:
      input.createdAtMillis != null && Number.isFinite(input.createdAtMillis)
        ? input.createdAtMillis
        : null,
  };
}

/**
 * Figures from one projected owner-only scan. Summing in the same pass as the
 * maxima costs no additional document reads and keeps the figures consistent.
 */
export interface ScannedDriveStats {
  totalDistanceMeters: number;
  totalDurationSeconds: number;
  longestDriveMeters: number;
  fastestAverageSpeedMps: number;
  highestMaxSpeedMps: number;
  thisMonthDrives: number;
  thisMonthDistanceMeters: number;
}

/**
 * Reduces all retained owner rides to totals, maxima and month tallies. Only
 * aggregate figures are returned, not individually hidden history. The month window is
 * half-open [start, end) so the boundary instant belongs to the next month.
 * A missing figure (null) never lowers a maximum; when none exists the maximum
 * stays 0 (read as "no drive with this stat"), never a false negative.
 */
export function scanDriveStats(
  samples: Iterable<DriveStatSample>,
  monthRange: MonthRange | null,
): ScannedDriveStats {
  let totalDistanceMeters = 0;
  let totalDurationSeconds = 0;
  let longestDriveMeters = 0;
  let fastestAverageSpeedMps = 0;
  let highestMaxSpeedMps = 0;
  let thisMonthDrives = 0;
  let thisMonthDistanceMeters = 0;
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
    if (
      monthRange &&
      sample.createdAtMillis != null &&
      sample.createdAtMillis >= monthRange.startMillis &&
      sample.createdAtMillis < monthRange.endMillis
    ) {
      thisMonthDrives += 1;
      if (sample.distanceMeters != null) thisMonthDistanceMeters += sample.distanceMeters;
    }
  }
  return {
    totalDistanceMeters,
    totalDurationSeconds,
    longestDriveMeters,
    fastestAverageSpeedMps,
    highestMaxSpeedMps,
    thisMonthDrives,
    thisMonthDistanceMeters,
  };
}

/**
 * Server-authoritative statistics response. Every figure is derived from the
 * caller's retained drives regardless of tier. `thisMonth*` fields
 * are 0 when no month range was supplied — the client renders no "this month"
 * section rather than a spurious zero. Tier is retained for wire compatibility;
 * serverNowMillis supplies authoritative time.
 */
export interface DriveStatsResponse {
  tier: SubscriptionTier;
  serverNowMillis: number;
  totalDrives: number;
  totalDistanceMeters: number;
  totalDurationSeconds: number;
  longestDriveMeters: number;
  /**
   * Mean distance per retained drive (totalDistanceMeters / totalDrives).
   * Summary-only saves (null distance) still count toward the denominator, so
   * this is "average metres recorded per drive", not "average of drives that
   * have a distance". 0 when there are no valid retained drives.
   */
  averageDriveMeters: number;
  fastestAverageSpeedMps: number;
  highestMaxSpeedMps: number;
  thisMonthDrives: number;
  thisMonthDistanceMeters: number;
}
