/**
 * Unit tests for the saved drives pure logic (drives-core.ts +
 * drive-calculations.ts). No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  averageSpeedMps,
  driveDurationSeconds,
  haversineDistanceMetres,
  maxSpeedMps,
  totalDistanceMetres,
} from '../drives/drive-calculations';
import {
  buildRideDocument,
  computeDriveStats,
  computeRouteThumbnail,
  guardDriveTimes,
  guardRoutePoints,
  parseDeleteDriveInput,
  parseSaveDriveInput,
  ridePreviewPath,
  rideRoutePath,
  rideStoragePrefix,
} from '../drives/drives-core';

const serverTimestamp = () => 'SERVER_TS';

const validSave = {
  startedAt: '2026-07-01T10:00:00.000Z',
  endedAt: '2026-07-01T11:00:00.000Z',
};

describe('drive-calculations (ported legacy logic)', () => {
  it('computes a plausible haversine distance (Stockholm–Uppsala ≈ 63–68 km)', () => {
    const d = haversineDistanceMetres(59.3293, 18.0686, 59.8586, 17.6389);
    expect(d).toBeGreaterThan(60_000);
    expect(d).toBeLessThan(70_000);
  });

  it('excludes implausible GPS jumps (>200 km/h implied speed) from distance', () => {
    const base = { latitude: 59.3293, longitude: 18.0686 };
    const points = [
      { ...base, timestampMs: 1_000 },
      // ~1.1 km in 60 s ≈ 67 km/h — plausible.
      { latitude: 59.3393, longitude: 18.0686, timestampMs: 61_000 },
      // ~55 km in 1 s — teleport; must be excluded.
      { latitude: 59.8586, longitude: 17.6389, timestampMs: 62_000 },
    ];
    const distance = totalDistanceMetres(points);
    expect(distance).toBeGreaterThan(1_000);
    expect(distance).toBeLessThan(2_000);
  });

  it('skips out-of-order segments and handles trivial inputs', () => {
    expect(totalDistanceMetres([])).toBe(0);
    expect(
      totalDistanceMetres([
        { latitude: 59.33, longitude: 18.07, timestampMs: 2_000 },
        { latitude: 59.34, longitude: 18.07, timestampMs: 1_000 },
      ]),
    ).toBe(0);
    expect(driveDurationSeconds(new Date(1_000), new Date(61_000))).toBe(60);
    expect(driveDurationSeconds(new Date(61_000), new Date(1_000))).toBe(0);
    expect(averageSpeedMps(100, 0)).toBeNull();
    expect(averageSpeedMps(100, 10)).toBe(10);
  });
});

describe('maxSpeedMps (stored since the 2026-07 decision)', () => {
  it('returns the fastest plausible segment, not the fastest segment', () => {
    const points = [
      { latitude: 59.3293, longitude: 18.0686, timestampMs: 0 },
      // ~1.11 km in 60 s ≈ 18.5 m/s.
      { latitude: 59.3393, longitude: 18.0686, timestampMs: 60_000 },
      // ~1.11 km in 30 s ≈ 37 m/s — the fastest PLAUSIBLE segment.
      { latitude: 59.3493, longitude: 18.0686, timestampMs: 90_000 },
      // ~1.11 km in 1 s ≈ 1112 m/s — a GPS glitch, and the whole reason the
      // 55.6 m/s filter is ported here: a maximum takes the single worst
      // sample, so without it one bad fix puts an absurd number on the card.
      { latitude: 59.3593, longitude: 18.0686, timestampMs: 91_000 },
    ];
    const max = maxSpeedMps(points);
    expect(max).not.toBeNull();
    expect(max as number).toBeGreaterThan(35);
    expect(max as number).toBeLessThan(40);
  });

  it('is null when there is nothing plausible to derive', () => {
    expect(maxSpeedMps([])).toBeNull();
    expect(maxSpeedMps([{ latitude: 59.33, longitude: 18.07, timestampMs: 1_000 }])).toBeNull();
    // Every segment is a glitch → null, never a fabricated 0.
    expect(
      maxSpeedMps([
        { latitude: 59.3293, longitude: 18.0686, timestampMs: 0 },
        { latitude: 59.8586, longitude: 17.6389, timestampMs: 1_000 },
      ]),
    ).toBeNull();
    // Duplicate/backwards timestamps are skipped like the distance scan; a
    // zero-length time delta must not divide by zero into Infinity.
    expect(
      maxSpeedMps([
        { latitude: 59.3293, longitude: 18.0686, timestampMs: 1_000 },
        { latitude: 59.3393, longitude: 18.0686, timestampMs: 1_000 },
      ]),
    ).toBeNull();
  });

  it('is 0 for a genuinely stationary recording (0 is a fact, absence is null)', () => {
    const parked = [
      { latitude: 59.3293, longitude: 18.0686, timestampMs: 0 },
      { latitude: 59.3293, longitude: 18.0686, timestampMs: 60_000 },
    ];
    expect(maxSpeedMps(parked)).toBe(0);
  });
});

describe('drives-core input parsing and guards', () => {
  it('accepts a summary-only save and rejects malformed inputs', () => {
    expect(parseSaveDriveInput(validSave).ok).toBe(true);
    expect(parseSaveDriveInput({ startedAt: 'yesterday', endedAt: validSave.endedAt }).ok).toBe(
      false,
    );
    expect(parseSaveDriveInput({ ...validSave, title: '' }).ok).toBe(false);
    expect(parseSaveDriveInput({ ...validSave, extra: 1 }).ok).toBe(false);
    expect(parseSaveDriveInput({ ...validSave, sourceSessionId: 'has spaces' }).ok).toBe(false);
    expect(parseDeleteDriveInput({ rideId: 'r1' }).ok).toBe(true);
    expect(parseDeleteDriveInput({}).ok).toBe(false);
    // Firestore-unsafe IDs fail as invalid-argument instead of throwing in doc().
    expect(parseDeleteDriveInput({ rideId: 'rides/other' }).ok).toBe(false);
    expect(parseDeleteDriveInput({ rideId: '..' }).ok).toBe(false);
    expect(parseDeleteDriveInput({ rideId: 'uid_session.2026-07-04' }).ok).toBe(true);
  });

  it('rejects out-of-range coordinates and unordered points', () => {
    expect(
      parseSaveDriveInput({
        ...validSave,
        routePoints: [{ latitude: 91, longitude: 18, timestampMs: 1 }],
      }).ok,
    ).toBe(false);
    const unordered = guardRoutePoints([
      { latitude: 59.33, longitude: 18.07, timestampMs: 2_000 },
      { latitude: 59.34, longitude: 18.07, timestampMs: 1_000 },
    ]);
    expect(unordered.ok).toBe(false);
  });

  it('requires endedAt strictly after startedAt', () => {
    expect(guardDriveTimes(validSave.startedAt, validSave.endedAt).ok).toBe(true);
    expect(guardDriveTimes(validSave.startedAt, validSave.startedAt).ok).toBe(false);
    expect(guardDriveTimes(validSave.endedAt, validSave.startedAt).ok).toBe(false);
  });
});

describe('drives-core stats and document builder', () => {
  it('returns null distance/speed for summary-only saves (legacy parity)', () => {
    const parsed = parseSaveDriveInput(validSave);
    if (!parsed.ok) throw new Error('expected ok');
    const stats = computeDriveStats(parsed.input);
    expect(stats.durationSeconds).toBe(3600);
    expect(stats.distanceMeters).toBeNull();
    expect(stats.averageSpeedMetersPerSecond).toBeNull();
  });

  it('computes distance and average speed from points', () => {
    const parsed = parseSaveDriveInput({
      ...validSave,
      routePoints: [
        { latitude: 59.3293, longitude: 18.0686, timestampMs: 1_000 },
        { latitude: 59.3393, longitude: 18.0686, timestampMs: 601_000 },
      ],
    });
    if (!parsed.ok) throw new Error('expected ok');
    const stats = computeDriveStats(parsed.input);
    expect(stats.distanceMeters).toBeGreaterThan(1_000);
    expect(stats.averageSpeedMetersPerSecond).toBeCloseTo(
      (stats.distanceMeters as number) / 3600,
      6,
    );
  });

  it('builds the ride document with canonical member-gated storage paths', () => {
    const parsed = parseSaveDriveInput({ ...validSave, title: 'Kustvägen', sourceSessionId: 's1' });
    if (!parsed.ok) throw new Error('expected ok');
    const stats = computeDriveStats(parsed.input);
    const docData = buildRideDocument(
      parsed.input,
      { userId: 'u1', rideId: 'r1', stats, routeThumbnail: computeRouteThumbnail(parsed.input) },
      serverTimestamp,
    );
    expect(docData.userId).toBe('u1');
    expect(docData.title).toBe('Kustvägen');
    expect(docData.routePath).toBe('rideRoutes/u1/r1/route.bin');
    expect(docData.previewImagePath).toBe('rideRoutes/u1/r1/preview.png');
    expect(docData.sourceSessionId).toBe('s1');
    // REVERSED 2026-07 by an explicit product decision. This line used to read
    //   expect(docData).not.toHaveProperty('topSpeed');
    // and pinned the rule that no top-speed field was ever stored. Maximum
    // speed IS stored now, as `maxSpeedMetersPerSecond`, so the assertion is
    // rewritten to pin the NEW contract rather than deleted: the field is on
    // every document the builder writes — null here, because this save carries
    // no route points — and so is the route thumbnail. The old `topSpeed` name
    // is still never written, so a future regression that resurrects the old
    // shape is still caught.
    expect(docData).toHaveProperty('maxSpeedMetersPerSecond');
    expect(docData.maxSpeedMetersPerSecond).toBeNull();
    expect(docData).toHaveProperty('routeThumbnail');
    expect(docData.routeThumbnail).toBeNull();
    expect(docData).not.toHaveProperty('topSpeed');
    expect(rideRoutePath('u1', 'r1')).toBe('rideRoutes/u1/r1/route.bin');
    expect(ridePreviewPath('u1', 'r1')).toBe('rideRoutes/u1/r1/preview.png');
    expect(rideStoragePrefix('u1', 'r1')).toBe('rideRoutes/u1/r1/');
  });
});
