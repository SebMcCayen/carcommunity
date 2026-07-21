package com.kungsbackacarcommunity.app.drives

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/**
 * A single decoded GPS point of a saved drive's driven route, read back from the
 * Cloud Storage `route.bin` file for History replay. Pure data (no Android /
 * Firebase types) so the codec is fully JVM-unit-testable.
 *
 * Mirrors the backend `routePoint` contract
 * (contracts/schemas/saved-drives.schema.json) and the recorder's
 * [RecordedPoint]: latitude/longitude in degrees, [timestampMs] a Unix
 * millisecond timestamp. Coordinates survive an encode→decode round-trip at
 * [RouteCodec.COORD_SCALE] precision (1e-5°, ≈ 1.1 m — the same precision Google
 * Encoded Polyline uses), which is far finer than consumer GPS and invisible on
 * a replay map.
 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
)

/**
 * The canonical serializer/deserializer for a saved drive's route file,
 * `rideRoutes/{uid}/{rideId}/route.bin` (member-gated Cloud Storage — see
 * firebase/storage.rules and docs/firebase-data-model.md).
 *
 * ## Why this format is defined here
 * The backend `drives-save` callable (functions/src/drives/saveDrive.ts) takes
 * the route points in the callable JSON, computes stats server-side, and returns
 * the canonical `route.bin` PATH — but it deliberately does NOT write the file:
 * the established contract has the CLIENT upload the compressed route (saveDrive
 * KDoc: "The client then uploads the compressed route file … to the canonical
 * Cloud Storage paths"). No writer existed in the codebase yet, so this object
 * IS the canonical wire format — a compact binary polyline — for both the reader
 * (this task) and the future client uploader ([encode] is the one-liner that
 * uploader needs). Keeping encode + decode together and round-trip-tested is
 * what guarantees the two ends can never drift.
 *
 * ## Wire format ("CCRB" v1) — raw payload, big questions answered inline
 * All multi-byte integers are LEB128 varints. Coordinates and timestamps are
 * DELTA-encoded against the previous point (the first point deltas against
 * lat=0/lng=0/t=0), so successive fixes — which differ by metres and ~1 s — cost
 * a byte or two each. This is the binary analogue of Google Encoded Polyline
 * (delta + zig-zag + variable-length), plus a parallel timestamp stream so the
 * decoded points keep their times (needed for the History top-speed sentence).
 *
 * ```
 * offset  bytes   field
 * 0       4       magic = 0x43 0x43 0x52 0x42  ("CCRB")
 * 4       1       version = 0x01
 * 5       1       flags   = 0x00 (reserved; must be 0 in v1)
 * 6       varint  pointCount (unsigned)
 * then, per point, deltas from the previous point (previous starts at 0,0,0):
 *         svarint dLatE5   zig-zag( round(lat*1e5) - prevLatE5 )
 *         svarint dLngE5   zig-zag( round(lng*1e5) - prevLngE5 )
 *         varint  dTms     ( timestampMs - prevTimestampMs ), non-negative
 * ```
 *
 * The whole payload MAY be gzip-wrapped (storage.rules permits
 * `application/gzip`); [decode] auto-detects the gzip magic (0x1f 0x8b) and
 * inflates before parsing, so a raw or gzipped file both decode.
 *
 * ## Decoding is total and defensive
 * [decode] NEVER throws and NEVER returns a partial route: any malformed input —
 * wrong/short magic, unknown version, a varint that runs off the end (a
 * truncated file), or an implausibly large count — returns `null` ("route
 * unavailable"), which the reader turns into a clean empty state rather than a
 * crash. A well-formed file with zero points decodes to an empty list (a valid
 * summary-only drive with no route to draw), which is distinct from `null`.
 */
object RouteCodec {
    /** Fixed-point scale for coordinates: 1e-5° ≈ 1.1 m (Google-polyline precision). */
    const val COORD_SCALE: Double = 1e5

    private val MAGIC = byteArrayOf(0x43, 0x43, 0x52, 0x42) // "CCRB"
    private const val VERSION = 1
    private const val HEADER_SIZE = 6 // magic(4) + version(1) + flags(1)

    /**
     * Absolute sanity cap on the decoded point count, so a corrupt length prefix
     * can never trigger a huge allocation. Comfortably above the backend
     * MAX_ROUTE_POINTS (20 000); anything larger is treated as corruption.
     */
    private const val MAX_DECODABLE_POINTS = 1_000_000

    /**
     * The largest a single coordinate delta varint may be: [encode] masks every
     * zig-zag delta to a 32-bit unsigned value (`and 0xFFFFFFFF`), so any varint
     * above this — or one whose 64-bit value comes back negative — is corruption.
     * Rejecting it prevents a large delta from silently truncating in
     * `unZigZag().toInt()` and forging a small, in-range delta.
     */
    private const val MAX_COORD_DELTA_ZIGZAG = 0xFFFFFFFFL

    /**
     * Fixed-point bounds for a valid decoded coordinate, at [COORD_SCALE] (1e5):
     * latitude ∈ [-90°, 90°] ⇒ ±9e6, longitude ∈ [-180°, 180°] ⇒ ±1.8e7. Both fit
     * comfortably in `Int` (« Int.MAX ≈ 2.1e9), so the accumulators stay `Int`; a
     * point that walks outside these bounds is corruption and fails closed. The
     * check also catches any `Int`-accumulator wrap: a wrap displaces the value by
     * ~2^32, so a wrapped result always lands ≥ ~2.1e9 in magnitude — far outside
     * these bounds — rather than sneaking back into range.
     */
    private const val LAT_E5_LIMIT = 9_000_000 // 90° * 1e5
    private const val LNG_E5_LIMIT = 18_000_000 // 180° * 1e5

    /**
     * Hard ceiling on the INFLATED size of a gzipped route payload. The compressed
     * download is already capped upstream
     * (FirebaseRouteReplayRepository.MAX_ROUTE_BYTES, 16 MiB), but that does NOT
     * bound the inflated size — a few-KB gzip bomb can inflate to gigabytes — so
     * [gunzipOrNull] enforces this cap while decompressing and fails closed if it
     * is exceeded. Sized just above the largest payload the parser would ever
     * accept: HEADER_SIZE + MAX_DECODABLE_POINTS (1e6) points at up to 20 varint
     * bytes each (two 5-byte 32-bit coord deltas + a 10-byte 64-bit timestamp
     * delta) ≈ 20 MiB.
     */
    private const val MAX_INFLATED_BYTES = 24 * 1024 * 1024 // 24 MiB

    private const val GZIP_MAGIC_0 = 0x1f.toByte()
    private const val GZIP_MAGIC_1 = 0x8b.toByte()

    /**
     * Serializes [points] to the `route.bin` wire format. Used by the reader's
     * round-trip tests today and ready for the client uploader (which will call
     * `encode(points, gzip = true)` and `putBytes` to
     * `rideRoutes/{uid}/{rideId}/route.bin`). Points are assumed ordered by
     * [RoutePoint.timestampMs] (the recorder guarantees it); any accidental
     * backwards time delta is clamped to 0 so the stream stays well-formed.
     */
    fun encode(points: List<RoutePoint>, gzip: Boolean = false): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(MAGIC)
        body.write(VERSION)
        body.write(0) // flags
        writeUVarint(body, points.size.toLong())

        var prevLatE5 = 0
        var prevLngE5 = 0
        var prevTms = 0L
        for (point in points) {
            val latE5 = (point.latitude * COORD_SCALE).roundToInt()
            val lngE5 = (point.longitude * COORD_SCALE).roundToInt()
            writeUVarint(body, zigZag(latE5 - prevLatE5).toLong())
            writeUVarint(body, zigZag(lngE5 - prevLngE5).toLong())
            // Ordered by contract, so the delta is non-negative; clamp defensively.
            writeUVarint(body, (point.timestampMs - prevTms).coerceAtLeast(0L))
            prevLatE5 = latE5
            prevLngE5 = lngE5
            prevTms = point.timestampMs
        }

        val raw = body.toByteArray()
        return if (gzip) gzip(raw) else raw
    }

    /**
     * Decodes a `route.bin` payload back into ordered route points, or `null`
     * when the bytes are missing/empty/corrupt/truncated (never throws). A
     * well-formed empty route decodes to an empty list.
     */
    fun decode(bytes: ByteArray?): List<RoutePoint>? {
        if (bytes == null || bytes.isEmpty()) return null
        val raw =
            if (bytes.size >= 2 && bytes[0] == GZIP_MAGIC_0 && bytes[1] == GZIP_MAGIC_1) {
                gunzipOrNull(bytes) ?: return null
            } else {
                bytes
            }

        if (raw.size < HEADER_SIZE) return null
        for (i in MAGIC.indices) if (raw[i] != MAGIC[i]) return null
        if (raw[4].toInt() != VERSION) return null
        // flags (raw[5]) reserved; ignored in v1 so a future flag can't fail an old reader.

        val cursor = Cursor(raw, HEADER_SIZE)
        val count = cursor.readUVarint() ?: return null
        if (count < 0 || count > MAX_DECODABLE_POINTS) return null

        val points = ArrayList<RoutePoint>(count.toInt().coerceAtMost(1024))
        var latE5 = 0
        var lngE5 = 0
        var tms = 0L
        repeat(count.toInt()) {
            val dLat = cursor.readUVarint() ?: return null
            val dLng = cursor.readUVarint() ?: return null
            val dTms = cursor.readUVarint() ?: return null
            // A legit coordinate delta is a 32-bit unsigned zig-zag (see
            // MAX_COORD_DELTA_ZIGZAG). A larger varint — or one read back as a
            // negative Long — would silently truncate in unZigZag().toInt() and
            // forge a small in-range delta, so reject it outright (fail closed).
            if (dLat < 0L || dLat > MAX_COORD_DELTA_ZIGZAG) return null
            if (dLng < 0L || dLng > MAX_COORD_DELTA_ZIGZAG) return null
            latE5 += unZigZag(dLat)
            lngE5 += unZigZag(dLng)
            tms += dTms
            // Range-check the decoded point: anything outside earthly coordinate
            // bounds is corruption (this also catches an Int-accumulator wrap — a
            // wrap lands ≥ ~2.1e9 in magnitude, far outside these limits).
            if (latE5 < -LAT_E5_LIMIT || latE5 > LAT_E5_LIMIT) return null
            if (lngE5 < -LNG_E5_LIMIT || lngE5 > LNG_E5_LIMIT) return null
            points.add(
                RoutePoint(
                    latitude = latE5 / COORD_SCALE,
                    longitude = lngE5 / COORD_SCALE,
                    timestampMs = tms,
                ),
            )
        }
        return points
    }

    // ---- varint / zig-zag primitives ----------------------------------------

    private fun writeUVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    /** Zig-zag map a signed int to an unsigned domain so small magnitudes stay small. */
    private fun zigZag(n: Int): Long = ((n shl 1) xor (n shr 31)).toLong() and 0xFFFFFFFFL

    private fun unZigZag(n: Long): Int = ((n ushr 1) xor -(n and 1)).toInt()

    /** A forward-only reader over a byte array that fails closed on truncation. */
    private class Cursor(private val data: ByteArray, start: Int) {
        private var pos = start

        /** Reads a LEB128 varint, or null if the bytes run out / overflow 64 bits. */
        fun readUVarint(): Long? {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= data.size) return null // truncated
                val b = data[pos].toInt() and 0xFF
                pos++
                result = result or ((b.toLong() and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null // varint longer than 10 bytes ⇒ corrupt
        }
    }

    // ---- gzip helpers --------------------------------------------------------

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    /**
     * Inflates a gzipped payload, streaming with a hard cap ([MAX_INFLATED_BYTES])
     * on the inflated size so an attacker-controlled gzip bomb can never force an
     * unbounded allocation. Returns null on any I/O error OR the moment the
     * inflated output would exceed the cap (fail closed, matching the codec's
     * convention). Deliberately does NOT `readBytes()` the whole stream first.
     */
    private fun gunzipOrNull(bytes: ByteArray): ByteArray? =
        runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_INFLATED_BYTES) return@runCatching null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }.getOrNull()
}
