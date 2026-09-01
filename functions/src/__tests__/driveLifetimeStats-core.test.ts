import { describe, expect, it } from 'vitest';
import {
  buildLifetimeSample,
  scanLifetimeStats,
  type LifetimeDriveSample,
} from '../drives/driveLifetimeStats-core';

describe('lifetime-stats sample validation (buildLifetimeSample)', () => {
  const base = {
    distanceMeters: 1_000,
    durationSeconds: 600,
    averageSpeedMps: 5,
    maxSpeedMps: 10,
  };

  it('builds a sample from valid fields', () => {
    expect(buildLifetimeSample(base)).toEqual({
      distanceMeters: 1_000,
      durationSeconds: 600,
      averageSpeedMps: 5,
      maxSpeedMps: 10,
    });
  });

  it('DROPS a drive whose durationSeconds is negative or non-integer (no negative total leaks in)', () => {
    expect(buildLifetimeSample({ ...base, durationSeconds: -600 })).toBeNull();
    expect(buildLifetimeSample({ ...base, durationSeconds: 600.5 })).toBeNull();
    expect(buildLifetimeSample({ ...base, durationSeconds: Number.NaN })).toBeNull();
    expect(buildLifetimeSample({ ...base, durationSeconds: 'x' })).toBeNull();

    // End to end: a negative-duration doc is dropped before the scan, so the
    // total stays 0 rather than going negative.
    const samples = [
      buildLifetimeSample({ ...base, durationSeconds: -600 }),
      buildLifetimeSample({ ...base, durationSeconds: 600 }),
    ].filter((s): s is LifetimeDriveSample => s != null);
    expect(samples).toHaveLength(1);
    const scanned = scanLifetimeStats(samples);
    expect(scanned.totalDurationSeconds).toBe(600);
    expect(scanned.totalDurationSeconds).toBeGreaterThanOrEqual(0);
  });

  it('does NOT require a createdAt — a legacy drive lacking it still counts (divergence from driveStats)', () => {
    // buildLifetimeSample takes no createdAt at all; a valid drive is never
    // dropped for a missing timestamp, so lifetime totals span pre-createdAt
    // drives too.
    expect(buildLifetimeSample(base)).not.toBeNull();
  });

  it('degrades malformed distance/speed fields to null without dropping the drive', () => {
    const sample = buildLifetimeSample({
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

describe('lifetime-stats in-memory scan', () => {
  const samples: LifetimeDriveSample[] = [
    { distanceMeters: 1_000, durationSeconds: 100, averageSpeedMps: 10, maxSpeedMps: 20 },
    { distanceMeters: 5_000, durationSeconds: 200, averageSpeedMps: 25, maxSpeedMps: 40 },
    { distanceMeters: 9_000, durationSeconds: 300, averageSpeedMps: 30, maxSpeedMps: 55 },
    // Summary-only save: null distance/speeds must never lower a maximum; its
    // null distance is skipped in the distance total but its duration counts.
    { distanceMeters: null, durationSeconds: 400, averageSpeedMps: null, maxSpeedMps: null },
  ];

  it('computes lifetime totals and maxima across ALL samples', () => {
    const scanned = scanLifetimeStats(samples);
    expect(scanned.totalDistanceMeters).toBe(15_000); // 1000+5000+9000 (null skipped)
    expect(scanned.totalDurationSeconds).toBe(1_000); // 100+200+300+400
    expect(scanned.longestDriveMeters).toBe(9_000);
    expect(scanned.fastestAverageSpeedMps).toBe(30);
    expect(scanned.highestMaxSpeedMps).toBe(55);
  });

  it('returns all-zero figures for an empty set', () => {
    expect(scanLifetimeStats([])).toEqual({
      totalDistanceMeters: 0,
      totalDurationSeconds: 0,
      longestDriveMeters: 0,
      fastestAverageSpeedMps: 0,
      highestMaxSpeedMps: 0,
    });
  });

  it('never lets a null figure lower a maximum', () => {
    const scanned = scanLifetimeStats([
      { distanceMeters: null, durationSeconds: 10, averageSpeedMps: null, maxSpeedMps: null },
      { distanceMeters: 2_000, durationSeconds: 20, averageSpeedMps: 7, maxSpeedMps: 14 },
    ]);
    expect(scanned.longestDriveMeters).toBe(2_000);
    expect(scanned.fastestAverageSpeedMps).toBe(7);
    expect(scanned.highestMaxSpeedMps).toBe(14);
  });
});
