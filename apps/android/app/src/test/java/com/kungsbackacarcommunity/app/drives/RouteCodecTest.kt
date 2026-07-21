package com.kungsbackacarcommunity.app.drives

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correctness lives in the decoder, so it is tested hard: a hand-computed
 * known-bytes fixture (independent of [RouteCodec.encode]), an encode↔decode
 * round-trip, gzip, the empty route, and every corruption/truncation path that
 * must degrade to `null` rather than crash or return a partial route.
 */
class RouteCodecTest {

    // ---- Known-bytes fixtures, built by hand from the "CCRB" v1 spec ---------
    // Header: 43 43 52 42 ("CCRB") | 01 (version) | 00 (flags).
    // Point 0: lat 0.00001 (latE5=1), lng 0.00002 (lngE5=2), t=1.
    //   dLatE5 = 1-0 = 1  → zig-zag(1)=2  → varint 0x02
    //   dLngE5 = 2-0 = 2  → zig-zag(2)=4  → varint 0x04
    //   dTms   = 1-0 = 1  →                  varint 0x01
    // Point 1: lat -0.00001 (latE5=-1), lng 0.00002 (lngE5=2), t=3.
    //   dLatE5 = -1-1 = -2 → zig-zag(-2)=3 → varint 0x03
    //   dLngE5 = 2-2 = 0   → zig-zag(0)=0  → varint 0x00
    //   dTms   = 3-1 = 2   →                 varint 0x02

    private val singlePointBytes =
        byteArrayOf(0x43, 0x43, 0x52, 0x42, 0x01, 0x00, /*count*/ 0x01, 0x02, 0x04, 0x01)

    private val singlePoint = RoutePoint(latitude = 0.00001, longitude = 0.00002, timestampMs = 1)

    private val twoPointBytes =
        byteArrayOf(
            0x43, 0x43, 0x52, 0x42, 0x01, 0x00, /*count*/ 0x02,
            0x02, 0x04, 0x01, // point 0
            0x03, 0x00, 0x02, // point 1
        )

    private val twoPoints =
        listOf(
            RoutePoint(latitude = 0.00001, longitude = 0.00002, timestampMs = 1),
            RoutePoint(latitude = -0.00001, longitude = 0.00002, timestampMs = 3),
        )

    @Test
    fun `decodes a hand-built single-point fixture to the expected point`() {
        val decoded = RouteCodec.decode(singlePointBytes)
        assertEquals(listOf(singlePoint), decoded)
    }

    @Test
    fun `decodes a hand-built two-point fixture, deltas across sign`() {
        assertEquals(twoPoints, RouteCodec.decode(twoPointBytes))
    }

    @Test
    fun `encode produces exactly the spec bytes for the fixtures`() {
        // Proves the writer matches the documented wire format byte-for-byte, so
        // the future uploader and this reader can never diverge.
        assertArrayEquals(singlePointBytes, RouteCodec.encode(listOf(singlePoint)))
        assertArrayEquals(twoPointBytes, RouteCodec.encode(twoPoints))
    }

    @Test
    fun `round-trips a realistic route within coordinate precision`() {
        val route =
            (0 until 500).map { i ->
                RoutePoint(
                    latitude = 57.4874 + i * 0.00012,
                    longitude = 12.0757 + i * 0.00009,
                    timestampMs = 1_700_000_000_000L + i * 1000L,
                )
            }
        val decoded = RouteCodec.decode(RouteCodec.encode(route))
        assertNotNull(decoded)
        assertEquals(route.size, decoded!!.size)
        route.forEachIndexed { i, expected ->
            val actual = decoded[i]
            // 1e-5° precision ⇒ within ~half a scale unit either way.
            assertEquals(expected.latitude, actual.latitude, 1e-5)
            assertEquals(expected.longitude, actual.longitude, 1e-5)
            assertEquals(expected.timestampMs, actual.timestampMs)
        }
    }

    @Test
    fun `round-trips through gzip and auto-detects it on decode`() {
        val route =
            listOf(
                RoutePoint(57.4, 12.0, 1_700_000_000_000L),
                RoutePoint(57.41, 12.01, 1_700_000_002_000L),
            )
        val gz = RouteCodec.encode(route, gzip = true)
        // It really is gzip (magic 0x1f 0x8b), not the raw payload.
        assertEquals(0x1f.toByte(), gz[0])
        assertEquals(0x8b.toByte(), gz[1])
        assertEquals(route, RouteCodec.decode(gz))
    }

    @Test
    fun `empty route round-trips to an empty list, not null`() {
        val encoded = RouteCodec.encode(emptyList())
        val decoded = RouteCodec.decode(encoded)
        assertNotNull(decoded)
        assertTrue(decoded!!.isEmpty())
    }

    @Test
    fun `null and empty input decode to null`() {
        assertNull(RouteCodec.decode(null))
        assertNull(RouteCodec.decode(ByteArray(0)))
    }

    @Test
    fun `truncated payload decodes to null at every cut`() {
        // Cutting anywhere past a valid header must fail closed, never partial.
        for (len in 0 until twoPointBytes.size) {
            assertNull(
                "cut to $len bytes should be unavailable",
                RouteCodec.decode(twoPointBytes.copyOf(len)),
            )
        }
    }

    @Test
    fun `wrong magic decodes to null`() {
        val bad = twoPointBytes.copyOf()
        bad[0] = 0x00
        assertNull(RouteCodec.decode(bad))
    }

    @Test
    fun `unknown version decodes to null`() {
        val bad = twoPointBytes.copyOf()
        bad[4] = 0x02 // version 2
        assertNull(RouteCodec.decode(bad))
    }

    @Test
    fun `implausibly large count decodes to null`() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x43, 0x43, 0x52, 0x42, 0x01, 0x00))
        writeUVarint(out, 5_000_000L) // above the sanity cap
        // Even with no point data following, the count alone is rejected.
        assertNull(RouteCodec.decode(out.toByteArray()))
    }

    @Test
    fun `count larger than available point data decodes to null`() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x43, 0x43, 0x52, 0x42, 0x01, 0x00))
        writeUVarint(out, 3L) // claims 3 points…
        out.write(byteArrayOf(0x02, 0x04, 0x01)) // …but supplies only 1
        assertNull(RouteCodec.decode(out.toByteArray()))
    }

    @Test
    fun `gzip magic with garbage body decodes to null, not crash`() {
        val bad = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0x01, 0x02, 0x03, 0x04)
        assertNull(RouteCodec.decode(bad))
    }

    // ---- Corruption on the decoder of untrusted input (route.bin is a member
    // upload) must FAIL CLOSED, not produce a bogus-but-non-null route. ---------

    @Test
    fun `a coordinate delta wider than 32 bits decodes to null, not a truncated point`() {
        // 0x2_0000_0004 zig-zag-decodes then TRUNCATES (unZigZag().toInt()) to a
        // small, in-range latE5=+2 (0.00002°). Before the fix decode() emitted that
        // forged point (non-null); the delta is outside the 32-bit zig-zag domain
        // encode() produces, so it must be rejected outright.
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x43, 0x43, 0x52, 0x42, 0x01, 0x00))
        writeUVarint(out, 1L) // one point
        writeUVarint(out, 0x2_0000_0004L) // dLat, > 32-bit ⇒ corrupt
        writeUVarint(out, 0L) // dLng
        writeUVarint(out, 0L) // dTms
        assertNull(RouteCodec.decode(out.toByteArray()))
    }

    @Test
    fun `a negative timestamp delta decodes to null`() {
        // Timestamp deltas are non-negative by the wire contract. A 10-byte varint
        // with bit 63 set reads back as a negative Long; before the fix `tms +=
        // dTms` accepted it and produced a bogus (backwards/overflowed) clock.
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x43, 0x43, 0x52, 0x42, 0x01, 0x00))
        writeUVarint(out, 1L)
        writeUVarint(out, 0x02L) // dLat = zig-zag(+1), in range
        writeUVarint(out, 0x00L) // dLng
        writeUVarint(out, -1L) // dTms: encodes 0xFFFF…FF, read back as a negative Long
        assertNull(RouteCodec.decode(out.toByteArray()))
    }

    @Test
    fun `a decoded coordinate outside earthly range decodes to null`() {
        // A perfectly in-domain 32-bit delta whose accumulated coordinate lands
        // past the pole (latE5 = 9_000_001 ⇒ 90.00001°). The delta itself is legal,
        // so only the coordinate range-check can reject it — before the fix this
        // returned a bogus non-null point.
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x43, 0x43, 0x52, 0x42, 0x01, 0x00))
        writeUVarint(out, 1L)
        writeUVarint(out, 18_000_002L) // zig-zag of +9_000_001 ⇒ 90.00001°, past the pole
        writeUVarint(out, 0L)
        writeUVarint(out, 0L)
        assertNull(RouteCodec.decode(out.toByteArray()))
    }

    @Test
    fun `coordinates exactly at the poles and antimeridian still decode`() {
        // Guards against the range-check being too tight: the exact bounds
        // (±90°, ±180°) are valid and must survive a round-trip.
        val route =
            listOf(
                RoutePoint(90.0, 180.0, 1_000L),
                RoutePoint(-90.0, -180.0, 2_000L),
            )
        val decoded = RouteCodec.decode(RouteCodec.encode(route))
        assertNotNull(decoded)
        assertEquals(2, decoded!!.size)
        assertEquals(90.0, decoded[0].latitude, 1e-9)
        assertEquals(180.0, decoded[0].longitude, 1e-9)
        assertEquals(-90.0, decoded[1].latitude, 1e-9)
        assertEquals(-180.0, decoded[1].longitude, 1e-9)
    }

    @Test
    fun `a gzip payload that inflates past the cap decodes to null`() {
        // A valid 1-point route (which would parse fine) followed by ~25 MiB of
        // padding the parser would otherwise ignore. Uncapped inflation
        // (readBytes) returned that bogus non-null route; the inflate cap makes an
        // over-large — i.e. gzip-bomb-shaped — payload fail closed instead.
        val valid = RouteCodec.encode(listOf(RoutePoint(57.4, 12.0, 1_700_000_000_000L)))
        val padded = valid + ByteArray(25 * 1024 * 1024)
        assertNull(RouteCodec.decode(gzipRaw(padded)))
    }

    /** LEB128 unsigned varint writer, mirroring the codec, for crafting fixtures. */
    private fun writeUVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    private fun gzipRaw(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }
}
