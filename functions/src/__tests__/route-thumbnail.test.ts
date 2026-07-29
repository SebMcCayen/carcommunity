/**
 * Unit tests for the History route thumbnail (route-thumbnail.ts): the
 * shape-preserving simplification, the polyline encoding, the degenerate cases
 * the History card must never render as an empty box, and the stored size for a
 * realistic long drive. No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  buildRouteThumbnail,
  encodePolyline,
  simplifyRoute,
  THUMBNAIL_MAX_POINTS,
  THUMBNAIL_POLYLINE_PRECISION,
} from '../drives/route-thumbnail';
import type { TimedPoint } from '../drives/drive-calculations';

/** Reference decoder — the algorithm Android's PolylineCodec implements. */
function decodePolyline(
  encoded: string,
  precision: number = THUMBNAIL_POLYLINE_PRECISION,
): Array<{ latitude: number; longitude: number }> {
  const points: Array<{ latitude: number; longitude: number }> = [];
  let index = 0;
  let lat = 0;
  let lon = 0;
  while (index < encoded.length) {
    let result = 1;
    let shift = 0;
    let b: number;
    do {
      b = (encoded.charCodeAt(index++) as number) - 63 - 1;
      result += b << shift;
      shift += 5;
    } while (b >= 0x1f && index < encoded.length);
    lat += result & 1 ? ~(result >> 1) : result >> 1;

    result = 1;
    shift = 0;
    do {
      b = (encoded.charCodeAt(index++) as number) - 63 - 1;
      result += b << shift;
      shift += 5;
    } while (b >= 0x1f && index < encoded.length);
    lon += result & 1 ? ~(result >> 1) : result >> 1;

    points.push({ latitude: lat / precision, longitude: lon / precision });
  }
  return points;
}

/** A straight run of `count` fixes heading north from Kungsbacka, 1 Hz. */
function straightRun(count: number, startIndex = 0): TimedPoint[] {
  return Array.from({ length: count }, (_, i) => ({
    latitude: 57.487 + (startIndex + i) * 0.0002,
    longitude: 12.076,
    timestampMs: 1_751_392_800_000 + (startIndex + i) * 1_000,
  }));
}

describe('simplifyRoute (Ramer–Douglas–Peucker)', () => {
  it('returns short routes untouched', () => {
    const points = straightRun(10);
    expect(simplifyRoute(points)).toEqual(points);
  });

  it('fits a 20 000-point track (the callable maximum) into the point budget', () => {
    const long = Array.from({ length: 20_000 }, (_, i) => ({
      // A meandering route, not a straight line: a sine wiggle so the
      // simplification has real shape to preserve rather than a trivial line.
      latitude: 57.487 + i * 0.00002,
      longitude: 12.076 + Math.sin(i / 400) * 0.01,
      timestampMs: 1_751_392_800_000 + i * 1_000,
    }));
    const simplified = simplifyRoute(long);
    expect(simplified.length).toBeLessThanOrEqual(THUMBNAIL_MAX_POINTS);
    expect(simplified.length).toBeGreaterThan(2);
    // Endpoints are never dropped — a truncating downsample would lose the end
    // of the drive, which is exactly what the card is showing.
    expect(simplified[0]).toEqual(long[0]);
    expect(simplified[simplified.length - 1]).toEqual(long[long.length - 1]);
  });

  it('KEEPS a lone sharp turn that stride sampling would drop', () => {
    // 200 fixes north, one hard right, 200 fixes east. The corner is the only
    // thing that distinguishes this route from a straight line — and it sits
    // between stride samples, so "every Nth point" can drop it entirely.
    const north = straightRun(200);
    const cornerLat = 57.487 + 200 * 0.0002;
    const east: TimedPoint[] = Array.from({ length: 200 }, (_, i) => ({
      latitude: cornerLat,
      longitude: 12.076 + (i + 1) * 0.0002,
      timestampMs: 1_751_392_800_000 + (200 + i) * 1_000,
    }));
    const route = [...north, ...east];
    const corner = north[north.length - 1] as TimedPoint;

    const simplified = simplifyRoute(route);
    expect(simplified.length).toBeLessThanOrEqual(THUMBNAIL_MAX_POINTS);
    // The corner survives: some kept point is within a few metres of it.
    const keptTheCorner = simplified.some(
      (point) =>
        Math.abs(point.latitude - corner.latitude) < 1e-4 &&
        Math.abs(point.longitude - corner.longitude) < 1e-4,
    );
    expect(keptTheCorner).toBe(true);
    // And the simplification really is aggressive: a straight leg collapses, so
    // a two-leg route needs nowhere near the budget.
    expect(simplified.length).toBeLessThan
      (10);
  });

  it('collapses a straight line to its endpoints', () => {
    const simplified = simplifyRoute(straightRun(5_000));
    expect(simplified).toHaveLength(2);
  });
});

describe('encodePolyline', () => {
  it('round-trips through the polyline decoder Android uses', () => {
    const points = [
      { latitude: 57.4871, longitude: 12.0761 },
      { latitude: 57.4899, longitude: 12.0812 },
      { latitude: 57.4712, longitude: 12.0433 },
    ];
    const decoded = decodePolyline(encodePolyline(points));
    expect(decoded).toHaveLength(points.length);
    decoded.forEach((point, i) => {
      expect(point.latitude).toBeCloseTo((points[i] as (typeof points)[0]).latitude, 4);
      expect(point.longitude).toBeCloseTo((points[i] as (typeof points)[0]).longitude, 4);
    });
  });

  it('encodes negative and crossing-zero coordinates', () => {
    const points = [
      { latitude: -0.0002, longitude: -0.0003 },
      { latitude: 0.0004, longitude: 0.0005 },
    ];
    const decoded = decodePolyline(encodePolyline(points));
    expect(decoded[0]?.latitude).toBeCloseTo(-0.0002, 5);
    expect(decoded[1]?.longitude).toBeCloseTo(0.0005, 5);
  });

  it('encodes nothing for no points', () => {
    expect(encodePolyline([])).toBe('');
  });
});

describe('buildRouteThumbnail', () => {
  it('is null for every case the card cannot draw as a route', () => {
    // No points at all (a summary-only save), and no field at all on drives
    // saved before this existed — the card takes the same placeholder path.
    expect(buildRouteThumbnail(undefined)).toBeNull();
    expect(buildRouteThumbnail([])).toBeNull();
    // A single fix.
    expect(
      buildRouteThumbnail([{ latitude: 57.487, longitude: 12.076, timestampMs: 1 }]),
    ).toBeNull();
    // Every fix identical — a phone parked with GPS on.
    expect(
      buildRouteThumbnail([
        { latitude: 57.487, longitude: 12.076, timestampMs: 1 },
        { latitude: 57.487, longitude: 12.076, timestampMs: 2_000 },
        { latitude: 57.487, longitude: 12.076, timestampMs: 3_000 },
      ]),
    ).toBeNull();
    // Fixes that differ only below the encoding precision (~1 m): the decoded
    // shape would be a single dot, so say "no thumbnail" rather than store one.
    expect(
      buildRouteThumbnail([
        { latitude: 57.487, longitude: 12.076, timestampMs: 1 },
        { latitude: 57.4870000001, longitude: 12.0760000001, timestampMs: 2_000 },
      ]),
    ).toBeNull();
  });

  it('encodes a two-point drive', () => {
    const encoded = buildRouteThumbnail([
      { latitude: 57.487, longitude: 12.076, timestampMs: 1 },
      { latitude: 57.497, longitude: 12.086, timestampMs: 600_000 },
    ]);
    expect(encoded).not.toBeNull();
    expect(decodePolyline(encoded as string)).toHaveLength(2);
  });

  it('stores a realistic long drive in a few hundred bytes', () => {
    // ~90 minutes at 1 Hz along a winding road — 5 400 fixes, the shape of a
    // real Sunday drive rather than a straight line.
    const drive: TimedPoint[] = Array.from({ length: 5_400 }, (_, i) => ({
      latitude: 57.487 + i * 0.00008 + Math.sin(i / 120) * 0.004,
      longitude: 12.076 + i * 0.00005 + Math.cos(i / 90) * 0.006,
      timestampMs: 1_751_392_800_000 + i * 1_000,
    }));
    const encoded = buildRouteThumbnail(drive) as string;
    const bytes = Buffer.byteLength(encoded, 'utf8');
    expect(decodePolyline(encoded).length).toBeLessThanOrEqual(THUMBNAIL_MAX_POINTS);
    // The whole point of the design: a card's shape costs a few hundred bytes
    // on a document the list already reads, not a Storage fetch.
    expect(bytes).toBeGreaterThan(100);
    expect(bytes).toBeLessThan(1_000);
    // Encoded polylines are printable ASCII, so bytes === characters, and the
    // schema's 1000-character bound holds.
    expect(encoded.length).toBe(bytes);
  });
});
