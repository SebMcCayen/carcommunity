import { describe, expect, it } from 'vitest';
import { DAY_MS } from '../drives/driveHistory-core';
import {
  buildDriveStatSample,
  parseDriveStatsInput,
  resolveMonthRange,
  scanDriveStats,
  type DriveStatSample,
} from '../drives/driveStats-core';

const NOW = Date.parse('2026-08-15T12:00:00.000Z');
// A valid "August 2026" local month straddling NOW.
const AUG_START = Date.parse('2026-08-01T00:00:00.000Z');
const AUG_END = Date.parse('2026-09-01T00:00:00.000Z');

describe('drive-stats input parsing', () => {
  it('accepts an empty request and a paired month range', () => {
    expect(parseDriveStatsInput({})).toEqual({ ok: true, input: {} });
    expect(
      parseDriveStatsInput({ monthStartMillis: AUG_START, monthEndMillis: AUG_END }),
    ).toEqual({ ok: true, input: { monthStartMillis: AUG_START, monthEndMillis: AUG_END } });
  });

  it('rejects unknown fields, non-integers, and a half-supplied month range', () => {
    expect(parseDriveStatsInput({ extra: true }).ok).toBe(false);
    expect(parseDriveStatsInput({ monthStartMillis: 1.5, monthEndMillis: 2 }).ok).toBe(false);
    // NaN / Infinity are rejected by z.number().int() under zod v4.
    expect(parseDriveStatsInput({ monthStartMillis: Number.NaN, monthEndMillis: 2 }).ok).toBe(false);
    expect(parseDriveStatsInput({ monthStartMillis: AUG_START }).ok).toBe(false);
    expect(parseDriveStatsInput({ monthEndMillis: AUG_END }).ok).toBe(false);
  });
});

describe('drive-stats month-range validation', () => {
  it('resolves to null when no range is supplied', () => {
    expect(resolveMonthRange({}, NOW)).toEqual({ ok: true, range: null });
  });

  it('accepts a valid current-month range straddling server time', () => {
    const result = resolveMonthRange({ monthStartMillis: AUG_START, monthEndMillis: AUG_END }, NOW);
    expect(result).toEqual({ ok: true, range: { startMillis: AUG_START, endMillis: AUG_END } });
  });

  it('rejects a range that does not straddle server time (entirely in the past)', () => {
    const start = NOW - 60 * DAY_MS;
    const end = NOW - 30 * DAY_MS;
    expect(resolveMonthRange({ monthStartMillis: start, monthEndMillis: end }, NOW).ok).toBe(false);
  });

  it('rejects a range entirely in the future', () => {
    const start = NOW + DAY_MS;
    const end = NOW + 31 * DAY_MS;
    expect(resolveMonthRange({ monthStartMillis: start, monthEndMillis: end }, NOW).ok).toBe(false);
  });

  it('rejects an oversized span (more than 32 days)', () => {
    const start = NOW - 20 * DAY_MS;
    const end = NOW + 20 * DAY_MS; // 40-day span
    expect(resolveMonthRange({ monthStartMillis: start, monthEndMillis: end }, NOW).ok).toBe(false);
  });

  it('rejects an undersized span (fewer than 27 days)', () => {
    const start = NOW - 5 * DAY_MS;
    const end = NOW + 5 * DAY_MS; // 10-day span
    expect(resolveMonthRange({ monthStartMillis: start, monthEndMillis: end }, NOW).ok).toBe(false);
  });

  it('rejects an inverted range (start after end)', () => {
    expect(resolveMonthRange({ monthStartMillis: AUG_END, monthEndMillis: AUG_START }, NOW).ok).toBe(
      false,
    );
  });
});

describe('drive-stats sample validation (buildDriveStatSample)', () => {
  const base = {
    distanceMeters: 1_000,
    durationSeconds: 600,
    averageSpeedMps: 5,
    maxSpeedMps: 10,
    createdAtMillis: NOW,
  };

  it('builds a sample from valid fields', () => {
    expect(buildDriveStatSample(base)).toEqual({
      distanceMeters: 1_000,
      durationSeconds: 600,
      averageSpeedMps: 5,
      maxSpeedMps: 10,
      createdAtMillis: NOW,
    });
  });

  it('DROPS a drive whose durationSeconds is negative or non-integer (no negative total leaks in)', () => {
    expect(buildDriveStatSample({ ...base, durationSeconds: -600 })).toBeNull();
    expect(buildDriveStatSample({ ...base, durationSeconds: 600.5 })).toBeNull();
    expect(buildDriveStatSample({ ...base, durationSeconds: Number.NaN })).toBeNull();
    expect(buildDriveStatSample({ ...base, durationSeconds: 'x' })).toBeNull();

    // End to end: a negative-duration doc is dropped before the scan, so the
    // total stays 0 rather than going negative — the regression Copilot flagged.
    const samples = [
      buildDriveStatSample({ ...base, durationSeconds: -600 }),
      buildDriveStatSample({ ...base, durationSeconds: 600 }),
    ].filter((s): s is DriveStatSample => s != null);
    expect(samples).toHaveLength(1);
    const scanned = scanDriveStats(samples, null);
    expect(scanned.totalDurationSeconds).toBe(600);
    expect(scanned.totalDurationSeconds).toBeGreaterThanOrEqual(0);
  });

  it('DROPS a drive with a missing/invalid createdAt', () => {
    expect(buildDriveStatSample({ ...base, createdAtMillis: null })).toBeNull();
    expect(buildDriveStatSample({ ...base, createdAtMillis: Number.NaN })).toBeNull();
  });

  it('degrades malformed distance/speed fields to null without dropping the drive', () => {
    const sample = buildDriveStatSample({
      ...base,
      distanceMeters: -50,
      averageSpeedMps: 'nope',
      maxSpeedMps: Number.POSITIVE_INFINITY,
    });
    expect(sample).not.toBeNull();
    expect(sample!.distanceMeters).toBeNull();
    expect(sample!.averageSpeedMps).toBeNull();
    expect(sample!.maxSpeedMps).toBeNull();
    // The valid duration is preserved, so the drive still counts.
    expect(sample!.durationSeconds).toBe(600);
  });
});

describe('drive-stats in-memory scan', () => {
  const samples: DriveStatSample[] = [
    // In August (this month).
    { distanceMeters: 1_000, durationSeconds: 100, averageSpeedMps: 10, maxSpeedMps: 20, createdAtMillis: AUG_START + DAY_MS },
    { distanceMeters: 5_000, durationSeconds: 200, averageSpeedMps: 25, maxSpeedMps: 40, createdAtMillis: AUG_START + 2 * DAY_MS },
    // Outside August (previous month) — must NOT count toward thisMonth, but
    // DOES contribute to the totals and max/longest figures (still tier-visible).
    { distanceMeters: 9_000, durationSeconds: 300, averageSpeedMps: 30, maxSpeedMps: 55, createdAtMillis: AUG_START - 5 * DAY_MS },
    // Summary-only save: null distance/speeds must never lower a maximum, and its
    // null distance is skipped in the distance total but its duration still counts.
    { distanceMeters: null, durationSeconds: 400, averageSpeedMps: null, maxSpeedMps: null, createdAtMillis: AUG_START + 3 * DAY_MS },
  ];

  it('computes totals and maxima across ALL visible samples, month tallies over the range only', () => {
    const scanned = scanDriveStats(samples, { startMillis: AUG_START, endMillis: AUG_END });
    expect(scanned.totalDistanceMeters).toBe(15_000); // 1000+5000+9000 (null skipped)
    expect(scanned.totalDurationSeconds).toBe(1_000); // 100+200+300+400
    expect(scanned.longestDriveMeters).toBe(9_000);
    expect(scanned.fastestAverageSpeedMps).toBe(30);
    expect(scanned.highestMaxSpeedMps).toBe(55);
    // Three August drives, but only two carry a distance.
    expect(scanned.thisMonthDrives).toBe(3);
    expect(scanned.thisMonthDistanceMeters).toBe(6_000);
  });

  it('zeroes the month tallies when no range is given but still reports maxima', () => {
    const scanned = scanDriveStats(samples, null);
    expect(scanned.thisMonthDrives).toBe(0);
    expect(scanned.thisMonthDistanceMeters).toBe(0);
    expect(scanned.longestDriveMeters).toBe(9_000);
  });

  it('treats the month window as half-open [start, end)', () => {
    const scanned = scanDriveStats(
      [
        { distanceMeters: 1, durationSeconds: 10, averageSpeedMps: null, maxSpeedMps: null, createdAtMillis: AUG_START },
        { distanceMeters: 2, durationSeconds: 20, averageSpeedMps: null, maxSpeedMps: null, createdAtMillis: AUG_END },
      ],
      { startMillis: AUG_START, endMillis: AUG_END },
    );
    // The start instant is included; the end instant belongs to the next month.
    expect(scanned.thisMonthDrives).toBe(1);
    expect(scanned.thisMonthDistanceMeters).toBe(1);
  });

  it('returns all-zero maxima for an empty visible set', () => {
    const scanned = scanDriveStats([], { startMillis: AUG_START, endMillis: AUG_END });
    expect(scanned).toEqual({
      totalDistanceMeters: 0,
      totalDurationSeconds: 0,
      longestDriveMeters: 0,
      fastestAverageSpeedMps: 0,
      highestMaxSpeedMps: 0,
      thisMonthDrives: 0,
      thisMonthDistanceMeters: 0,
    });
  });
});
