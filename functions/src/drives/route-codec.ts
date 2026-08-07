/**
 * Server-side decoder for a saved drive's route file,
 * `rideRoutes/{uid}/{rideId}/route.bin` (member-gated Cloud Storage).
 *
 * ## Why this exists
 * The canonical wire format ("CCRB" v1) is DEFINED by the Android client
 * (apps/android/.../drives/RouteCodec.kt): the `drives.save` callable returns
 * the storage PATH but the CLIENT uploads the gzipped binary polyline. Nothing
 * server-side read that file until now. The partner drive-heat aggregation
 * (partnerInsights/driveHeatAggregation.ts) needs the full driven track to bin
 * it into H3 cells, so this is a faithful TypeScript port of the Kotlin decoder,
 * kept byte-for-byte compatible with it. [encode] mirrors the Kotlin encoder so
 * the round-trip can be unit-tested and the two ends provably cannot drift.
 *
 * ## Wire format ("CCRB" v1)
 * ```
 * offset  bytes   field
 * 0       4       magic = 0x43 0x43 0x52 0x42  ("CCRB")
 * 4       1       version = 0x01
 * 5       1       flags   = 0x00 (reserved; must be 0 in v1)
 * 6       varint  pointCount (unsigned LEB128)
 * then, per point, deltas from the previous point (previous starts at 0,0,0):
 *         svarint dLatE5   zig-zag( round(lat*1e5) - prevLatE5 )
 *         svarint dLngE5   zig-zag( round(lng*1e5) - prevLngE5 )
 *         varint  dTms     ( timestampMs - prevTimestampMs ), non-negative
 * ```
 * The payload MAY be gzip-wrapped (storage rules permit `application/gzip`);
 * [decodeRoute] auto-detects the gzip magic (0x1f 0x8b) and inflates first.
 *
 * ## Decoding is total and defensive
 * [decodeRoute] NEVER throws and NEVER returns a partial route: any malformed
 * input — wrong/short magic, unknown version, a varint that runs off the end, an
 * implausibly large count, or a coordinate outside earthly bounds — returns
 * `null` ("route unavailable"). A well-formed file with zero points decodes to
 * an empty list (a valid summary-only drive), which is distinct from `null`.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { gunzipSync, gzipSync } from 'node:zlib';

/** One decoded GPS fix of a saved drive's route. Mirrors the Kotlin `RoutePoint`. */
export interface RoutePoint {
  latitude: number;
  longitude: number;
  timestampMs: number;
}

/** Fixed-point scale for coordinates: 1e-5° ≈ 1.1 m (Google-polyline precision). */
export const COORD_SCALE = 1e5;

const MAGIC = [0x43, 0x43, 0x52, 0x42]; // "CCRB"
const VERSION = 1;
const HEADER_SIZE = 6; // magic(4) + version(1) + flags(1)

/** Absolute sanity cap on the decoded point count — above the backend 20 000 cap. */
const MAX_DECODABLE_POINTS = 1_000_000;

/** Largest permissible zig-zag coordinate delta: a 32-bit unsigned value. */
const MAX_COORD_DELTA_ZIGZAG = 0xffffffff;

/** Fixed-point coordinate bounds at COORD_SCALE: ±90° / ±180°. */
const LAT_E5_LIMIT = 9_000_000;
const LNG_E5_LIMIT = 18_000_000;

/** Hard ceiling on the inflated size of a gzipped payload (gzip-bomb guard). */
const MAX_INFLATED_BYTES = 24 * 1024 * 1024; // 24 MiB

const GZIP_MAGIC_0 = 0x1f;
const GZIP_MAGIC_1 = 0x8b;

// ---------------------------------------------------------------------------
// Decode
// ---------------------------------------------------------------------------

/**
 * Decodes a `route.bin` payload into ordered route points, or `null` when the
 * bytes are missing/empty/corrupt/truncated (never throws). A well-formed empty
 * route decodes to an empty list.
 */
export function decodeRoute(bytes: Uint8Array | null | undefined): RoutePoint[] | null {
  if (!bytes || bytes.length === 0) return null;

  let raw: Uint8Array = bytes;
  if (bytes.length >= 2 && bytes[0] === GZIP_MAGIC_0 && bytes[1] === GZIP_MAGIC_1) {
    const inflated = gunzipOrNull(bytes);
    if (inflated === null) return null;
    raw = inflated;
  }

  if (raw.length < HEADER_SIZE) return null;
  for (let i = 0; i < MAGIC.length; i += 1) {
    if (raw[i] !== MAGIC[i]) return null;
  }
  if (raw[4] !== VERSION) return null;
  // flags (raw[5]) reserved; ignored in v1 so a future flag can't fail an old reader.

  const cursor = new Cursor(raw, HEADER_SIZE);
  const count = cursor.readUVarint();
  if (count === null || count < 0 || count > MAX_DECODABLE_POINTS) return null;

  const points: RoutePoint[] = [];
  let latE5 = 0;
  let lngE5 = 0;
  let tms = 0;
  for (let i = 0; i < count; i += 1) {
    const dLat = cursor.readUVarint();
    const dLng = cursor.readUVarint();
    const dTms = cursor.readUVarint();
    if (dLat === null || dLng === null || dTms === null) return null;
    // A legit coordinate delta is a 32-bit unsigned zig-zag; a larger value
    // would silently truncate and forge a small in-range delta.
    if (dLat < 0 || dLat > MAX_COORD_DELTA_ZIGZAG) return null;
    if (dLng < 0 || dLng > MAX_COORD_DELTA_ZIGZAG) return null;
    // Timestamp deltas are non-negative; guard against overflow of the clock
    // beyond the safe-integer range.
    if (dTms < 0 || dTms > Number.MAX_SAFE_INTEGER - tms) return null;
    latE5 += unZigZag(dLat);
    lngE5 += unZigZag(dLng);
    tms += dTms;
    // Anything outside earthly coordinate bounds is corruption (fail closed).
    if (latE5 < -LAT_E5_LIMIT || latE5 > LAT_E5_LIMIT) return null;
    if (lngE5 < -LNG_E5_LIMIT || lngE5 > LNG_E5_LIMIT) return null;
    points.push({
      latitude: latE5 / COORD_SCALE,
      longitude: lngE5 / COORD_SCALE,
      timestampMs: tms,
    });
  }
  return points;
}

// ---------------------------------------------------------------------------
// Encode (mirrors the Kotlin encoder; used for round-trip tests + reference)
// ---------------------------------------------------------------------------

/**
 * Serializes [points] to the `route.bin` wire format, byte-compatible with the
 * Android encoder. Points are assumed ordered by timestamp; any backwards time
 * delta is clamped to 0 so the stream stays well-formed.
 */
export function encodeRoute(points: readonly RoutePoint[], gzip = false): Uint8Array {
  const body: number[] = [...MAGIC, VERSION, 0];
  writeUVarint(body, points.length);

  let prevLatE5 = 0;
  let prevLngE5 = 0;
  let prevTms = 0;
  for (const point of points) {
    const latE5 = Math.round(point.latitude * COORD_SCALE);
    const lngE5 = Math.round(point.longitude * COORD_SCALE);
    writeUVarint(body, zigZag(latE5 - prevLatE5));
    writeUVarint(body, zigZag(lngE5 - prevLngE5));
    writeUVarint(body, Math.max(0, point.timestampMs - prevTms));
    prevLatE5 = latE5;
    prevLngE5 = lngE5;
    prevTms = point.timestampMs;
  }

  const raw = Uint8Array.from(body);
  return gzip ? new Uint8Array(gzipSync(raw)) : raw;
}

// ---------------------------------------------------------------------------
// varint / zig-zag primitives
// ---------------------------------------------------------------------------

function writeUVarint(out: number[], value: number): void {
  let v = value;
  while (v >= 0x80) {
    out.push((v & 0x7f) | 0x80);
    v = Math.floor(v / 128);
  }
  out.push(v & 0x7f);
}

/** Zig-zag a signed 32-bit int into an unsigned domain so small magnitudes stay small. */
function zigZag(n: number): number {
  // >>> 0 keeps the result in the unsigned 32-bit range, matching Kotlin's mask.
  return (((n << 1) ^ (n >> 31)) >>> 0);
}

function unZigZag(n: number): number {
  // Reconstruct the signed 32-bit int; `| 0` re-applies the sign like Kotlin's toInt().
  return ((n >>> 1) ^ -(n & 1)) | 0;
}

/** A forward-only reader over a byte array that fails closed on truncation. */
class Cursor {
  private pos: number;
  constructor(
    private readonly data: Uint8Array,
    start: number,
  ) {
    this.pos = start;
  }

  /** Reads a LEB128 varint, or null if the bytes run out / overflow 53 bits. */
  readUVarint(): number | null {
    let result = 0;
    let shift = 0;
    // 8 groups of 7 bits = 56 bits; cap below to keep values in the safe-integer
    // range (deltas are at most 32-bit coord / 53-bit clock values in practice).
    while (shift < 56) {
      if (this.pos >= this.data.length) return null; // truncated
      const b = this.data[this.pos]!;
      this.pos += 1;
      result += (b & 0x7f) * 2 ** shift;
      if ((b & 0x80) === 0) {
        return result <= Number.MAX_SAFE_INTEGER ? result : null;
      }
      shift += 7;
    }
    return null; // varint too long ⇒ corrupt
  }
}

// ---------------------------------------------------------------------------
// gzip helpers
// ---------------------------------------------------------------------------

function gunzipOrNull(bytes: Uint8Array): Uint8Array | null {
  try {
    const out = gunzipSync(bytes, { maxOutputLength: MAX_INFLATED_BYTES });
    return new Uint8Array(out.buffer, out.byteOffset, out.byteLength);
  } catch {
    // I/O error OR the inflated output exceeded maxOutputLength (fail closed).
    return null;
  }
}
