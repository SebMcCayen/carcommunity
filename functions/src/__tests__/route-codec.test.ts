/**
 * Unit tests for the server-side route.bin decoder (drives/route-codec.ts).
 *
 * The wire format ("CCRB" v1) is owned by the Android client
 * (apps/android/.../drives/RouteCodec.kt); this decoder must stay byte-for-byte
 * compatible. These tests pin the round-trip (encode↔decode), gzip handling, and
 * the defensive "return null, never throw" behaviour on corrupt input.
 */

import { gzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import { decodeRoute, encodeRoute, type RoutePoint } from '../drives/route-codec';

const SAMPLE: RoutePoint[] = [
  { latitude: 57.48, longitude: 12.076, timestampMs: 1_700_000_000_000 },
  { latitude: 57.4805, longitude: 12.0771, timestampMs: 1_700_000_001_000 },
  { latitude: 57.481, longitude: 12.0782, timestampMs: 1_700_000_002_000 },
  { latitude: 57.4802, longitude: 12.0765, timestampMs: 1_700_000_003_500 },
];

describe('decodeRoute round-trip', () => {
  it('round-trips a raw (ungzipped) route within 1e-5 precision', () => {
    const decoded = decodeRoute(encodeRoute(SAMPLE, false));
    expect(decoded).not.toBeNull();
    expect(decoded).toHaveLength(SAMPLE.length);
    decoded!.forEach((p, i) => {
      expect(p.latitude).toBeCloseTo(SAMPLE[i]!.latitude, 5);
      expect(p.longitude).toBeCloseTo(SAMPLE[i]!.longitude, 5);
      expect(p.timestampMs).toBe(SAMPLE[i]!.timestampMs);
    });
  });

  it('round-trips a gzip-wrapped payload (client uploads gzip)', () => {
    const decoded = decodeRoute(encodeRoute(SAMPLE, true));
    expect(decoded).not.toBeNull();
    expect(decoded).toHaveLength(SAMPLE.length);
    expect(decoded![0]!.latitude).toBeCloseTo(57.48, 5);
  });

  it('decodes a well-formed empty route to an empty list (distinct from null)', () => {
    const decoded = decodeRoute(encodeRoute([], false));
    expect(decoded).toEqual([]);
  });

  it('handles negative deltas (route that doubles back)', () => {
    const decoded = decodeRoute(encodeRoute(SAMPLE, false));
    // Point 4 is south-west of point 3 — the delta is negative and must survive.
    expect(decoded![3]!.latitude).toBeCloseTo(57.4802, 4);
    expect(decoded![3]!.longitude).toBeCloseTo(12.0765, 4);
  });
});

describe('decodeRoute is total and defensive', () => {
  it('returns null for null / empty input', () => {
    expect(decodeRoute(null)).toBeNull();
    expect(decodeRoute(undefined)).toBeNull();
    expect(decodeRoute(new Uint8Array())).toBeNull();
  });

  it('returns null for wrong magic', () => {
    const bad = encodeRoute(SAMPLE, false);
    bad[0] = 0x00;
    expect(decodeRoute(bad)).toBeNull();
  });

  it('returns null for an unknown version', () => {
    const bad = encodeRoute(SAMPLE, false);
    bad[4] = 0x02;
    expect(decodeRoute(bad)).toBeNull();
  });

  it('returns null for a truncated body (varint runs off the end)', () => {
    const full = encodeRoute(SAMPLE, false);
    const truncated = full.slice(0, full.length - 3);
    expect(decodeRoute(truncated)).toBeNull();
  });

  it('returns null for a header that claims more points than the body holds', () => {
    // magic + version + flags + count(=5) but no point bytes.
    const bytes = Uint8Array.from([0x43, 0x43, 0x52, 0x42, 0x01, 0x00, 0x05]);
    expect(decodeRoute(bytes)).toBeNull();
  });

  it('returns null for corrupt gzip', () => {
    const corrupt = gzipSync(Uint8Array.from([1, 2, 3, 4]));
    corrupt[8] = corrupt[8]! ^ 0xff; // scramble a byte inside the deflate stream
    expect(decodeRoute(new Uint8Array(corrupt))).toBeNull();
  });
});
