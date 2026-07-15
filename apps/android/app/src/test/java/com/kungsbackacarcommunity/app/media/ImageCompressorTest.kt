package com.kungsbackacarcommunity.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only coverage for the pure, Android-free parts of [ImageCompressor].
 *
 * The pixel pipeline (decode → EXIF-orient → downscale → JPEG re-encode) and the
 * fallback metadata strip both need Android's Bitmap/BitmapFactory/ExifInterface,
 * which are stubbed to throw in local unit tests, so they MUST be covered by an
 * on-device instrumentation test (androidTest). Those tests should assert:
 *  - a large source is downscaled so its longest side <= the requested max,
 *  - the re-encoded output is JPEG and contains NO EXIF (round-trip a geotagged
 *    JPEG and confirm getLatLong()/GPS tags are absent),
 *  - the fallback on an undecodable but geotagged JPEG either strips GPS or
 *    returns null (never the raw geotagged bytes),
 *  - vehicle max-dimension (1600) yields more detail than avatar (1024).
 * Here we cover the sample-size math and tuning constants that need no device.
 */
class ImageCompressorTest {

    @Test
    fun `sampleSizeFor is 1 when the longest side already fits`() {
        assertEquals(1, ImageCompressor.sampleSizeFor(800, 600, 1024))
        assertEquals(1, ImageCompressor.sampleSizeFor(1024, 1024, 1024))
    }

    @Test
    fun `sampleSizeFor halves by powers of two based on the longest side`() {
        // 2000 -> /2 = 1000 (<=1024) => sample 2.
        assertEquals(2, ImageCompressor.sampleSizeFor(2000, 1500, 1024))
        // 4000 -> 2000 -> 1000 => sample 4 (portrait, longest side is height).
        assertEquals(4, ImageCompressor.sampleSizeFor(1000, 4000, 1024))
        // 5000 -> 2500 -> 1250 -> 625 => sample 8.
        assertEquals(8, ImageCompressor.sampleSizeFor(5000, 3000, 1024))
    }

    @Test
    fun `sampleSizeFor keys off the LONGEST side for extreme aspect ratios`() {
        // 4000x500 with max 1024: only the width needs shrinking (4000 -> 1000).
        assertEquals(4, ImageCompressor.sampleSizeFor(4000, 500, 1024))
    }

    @Test
    fun `vehicle max dimension is larger than avatar for detail`() {
        assertTrue(ImageCompressor.VEHICLE_MAX_DIMENSION > ImageCompressor.AVATAR_MAX_DIMENSION)
    }

    @Test
    fun `jpeg quality is a valid percentage`() {
        assertTrue(ImageCompressor.DEFAULT_JPEG_QUALITY in 1..100)
    }

    @Test
    fun `looksLikeJpeg detects the SOI magic regardless of reported MIME`() {
        // Real JPEG bytes start with FF D8 FF — the strip fallback keys off this,
        // not contentType, so a JPEG mis-typed as null/image-jpg/octet-stream is
        // still recognised and stripped rather than needlessly dropped.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertTrue(ImageCompressor.looksLikeJpeg(jpeg))
    }

    @Test
    fun `looksLikeJpeg rejects non-jpeg and too-short buffers`() {
        // PNG signature, a short buffer, and empty bytes are all not strippable JPEGs.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertFalse(ImageCompressor.looksLikeJpeg(png))
        assertFalse(ImageCompressor.looksLikeJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertFalse(ImageCompressor.looksLikeJpeg(ByteArray(0)))
    }
}
