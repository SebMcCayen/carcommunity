/**
 * Saved drives — drive calculation unit tests.
 *
 * Covers:
 *  - Drive duration calculation
 *  - Haversine distance between known coordinates
 *  - Total distance with multiple points
 *  - Invalid coordinate jump exclusion
 *  - Average speed calculation
 *  - Top speed is never calculated or stored (structural test)
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  averageSpeedMps,
  driveDurationSeconds,
  haversineDistanceMetres,
  totalDistanceMetres,
  type TimedPoint,
} from './lib/drive-calculations.js';

// ---------------------------------------------------------------------------
// driveDurationSeconds
// ---------------------------------------------------------------------------

test('driveDurationSeconds: returns correct duration for 10-minute drive', () => {
  const start = new Date('2026-06-22T10:00:00.000Z');
  const end = new Date('2026-06-22T10:10:00.000Z');
  assert.equal(driveDurationSeconds(start, end), 600);
});

test('driveDurationSeconds: returns 0 when end is before start', () => {
  const start = new Date('2026-06-22T10:10:00.000Z');
  const end = new Date('2026-06-22T10:00:00.000Z');
  assert.equal(driveDurationSeconds(start, end), 0);
});

test('driveDurationSeconds: returns 0 when end equals start', () => {
  const t = new Date('2026-06-22T10:00:00.000Z');
  assert.equal(driveDurationSeconds(t, t), 0);
});

test('driveDurationSeconds: handles 1-second duration', () => {
  const start = new Date('2026-06-22T10:00:00.000Z');
  const end = new Date('2026-06-22T10:00:01.000Z');
  assert.equal(driveDurationSeconds(start, end), 1);
});

// ---------------------------------------------------------------------------
// haversineDistanceMetres
// ---------------------------------------------------------------------------

test('haversineDistanceMetres: identical points return 0', () => {
  assert.equal(haversineDistanceMetres(57.7, 12.0, 57.7, 12.0), 0);
});

test('haversineDistanceMetres: known distance between Gothenburg and Kungsbacka approx 25 km', () => {
  // Gothenburg city centre: ~57.706, 11.967
  // Kungsbacka: ~57.506, 12.076
  const dist = haversineDistanceMetres(57.706, 11.967, 57.506, 12.076);
  // Expect roughly 23–28 km
  assert.ok(dist > 22_000, `Expected > 22 000 m, got ${dist}`);
  assert.ok(dist < 28_000, `Expected < 28 000 m, got ${dist}`);
});

test('haversineDistanceMetres: symmetrical — same result in both directions', () => {
  const a = haversineDistanceMetres(57.706, 11.967, 57.506, 12.076);
  const b = haversineDistanceMetres(57.506, 12.076, 57.706, 11.967);
  assert.ok(Math.abs(a - b) < 0.001, 'Distance should be symmetrical');
});

// ---------------------------------------------------------------------------
// totalDistanceMetres
// ---------------------------------------------------------------------------

test('totalDistanceMetres: returns 0 for empty array', () => {
  assert.equal(totalDistanceMetres([]), 0);
});

test('totalDistanceMetres: returns 0 for single point', () => {
  const pts: TimedPoint[] = [{ latitude: 57.7, longitude: 12.0, timestampMs: 0 }];
  assert.equal(totalDistanceMetres(pts), 0);
});

test('totalDistanceMetres: two-point route returns positive distance', () => {
  const pts: TimedPoint[] = [
    { latitude: 57.706, longitude: 11.967, timestampMs: 0 },
    { latitude: 57.506, longitude: 12.076, timestampMs: 60_000 },
  ];
  const dist = totalDistanceMetres(pts);
  assert.ok(dist > 0, 'Should return positive distance');
  assert.ok(dist > 22_000, `Expected > 22 000 m, got ${dist}`);
});

test('totalDistanceMetres: excludes invalid coordinate jump (teleport)', () => {
  // First segment: 1 metre movement over 1 second — valid
  // Second segment: 10 000 km over 1 second — invalid jump (teleport to other side of Earth)
  const pts: TimedPoint[] = [
    { latitude: 57.706, longitude: 11.967, timestampMs: 0 },
    { latitude: 57.706_001, longitude: 11.967, timestampMs: 1_000 },  // ~0.11 m, valid
    { latitude: -33.86, longitude: 151.2, timestampMs: 2_000 },        // Sydney, teleport — invalid
  ];
  const dist = totalDistanceMetres(pts);
  // Should only count the first valid segment (~0.11 m), not the Sydney teleport
  assert.ok(dist < 1, `Expected < 1 m (only valid segment counted), got ${dist}`);
});

test('totalDistanceMetres: accumulates distance across multiple valid points', () => {
  // Three-point route with realistic driving distances
  const pts: TimedPoint[] = [
    { latitude: 57.706, longitude: 11.967, timestampMs: 0 },
    { latitude: 57.606, longitude: 12.020, timestampMs: 300_000 },   // ~5 min
    { latitude: 57.506, longitude: 12.076, timestampMs: 600_000 },   // ~5 more min
  ];
  const dist = totalDistanceMetres(pts);
  assert.ok(dist > 20_000, `Expected > 20 000 m, got ${dist}`);
});

// ---------------------------------------------------------------------------
// averageSpeedMps
// ---------------------------------------------------------------------------

test('averageSpeedMps: returns correct speed for 10 km in 600 seconds', () => {
  const speed = averageSpeedMps(10_000, 600);
  // 10 000 m / 600 s = 16.67 m/s ≈ 60 km/h
  assert.ok(speed !== null && Math.abs(speed - 16.67) < 0.01, `Expected ~16.67 m/s, got ${speed}`);
});

test('averageSpeedMps: returns null when durationSeconds is 0', () => {
  assert.equal(averageSpeedMps(10_000, 0), null);
});

test('averageSpeedMps: returns null when durationSeconds is negative', () => {
  assert.equal(averageSpeedMps(10_000, -1), null);
});

// ---------------------------------------------------------------------------
// Top speed — structural test
// ---------------------------------------------------------------------------

test('drive-calculations: module exports do not include any top-speed function', async () => {
  // Import the module and verify no top-speed-related export is present.
  const mod = await import('./lib/drive-calculations.js');
  const exports = Object.keys(mod);
  const topSpeedExports = exports.filter((k) =>
    k.toLowerCase().includes('topspeed') || k.toLowerCase().includes('top_speed'),
  );
  assert.deepEqual(topSpeedExports, [], `Found unexpected top-speed exports: ${topSpeedExports.join(', ')}`);
});
