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

    /** LEB128 unsigned varint writer, mirroring the codec, for crafting fixtures. */
    private fun writeUVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    @Suppress("unused")
    private fun gzipRaw(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }
}
