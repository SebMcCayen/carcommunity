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
    fun `sampleSizeForCrop with no crop matches the plain frame sampling`() {
        listOf(null, NormalizedCropRect.FULL).forEach { crop ->
            assertEquals(
                4,
                ImageCompressor.sampleSizeForCrop(4000, 3000, crop, 1024),
            )
        }
    }

    @Test
    fun `sampleSizeForCrop keeps a cropped panorama sharp`() {
        // Regression: sizing the decode by the FRAME gave sample 8 on this
        // source, so a 16:9 crop (1778x1000 of real pixels) came back 222px
        // wide. Sizing by the surviving REGION gives sample 2 — 889px, the same
        // "at least half of maxDimension" bar the uncropped path meets.
        val crop = NormalizedCropRect(left = 0.3f, top = 0f, width = 1778f / 8000f, height = 1f)

        val sample = ImageCompressor.sampleSizeForCrop(8000, 1000, crop, 1600)

        assertEquals(2, sample)
        val croppedWidth = (crop.width * 8000).toInt() / sample
        assertTrue(
            "a cropped panorama must not decode to a sliver (got ${croppedWidth}px)",
            croppedWidth >= 1600 / 2,
        )
    }

    @Test
    fun `sampleSizeForCrop keeps a panorama cropped to a square at full size`() {
        // The sharpest form of the bug: a 6000x1000 panorama cropped to the
        // middle 1000x1000 square retains 1000x1000 REAL pixels. Sizing the
        // decode by the frame sees a width fraction of 1/6, picks sample 4, and
        // hands back a 250x250 square — a 16x pixel loss with the pixels sitting
        // right there in the source.
        val square = NormalizedCropRect(left = 1f / 3f, top = 0f, width = 1f / 6f, height = 1f)

        val sample = ImageCompressor.sampleSizeForCrop(6000, 1000, square, 1600)

        assertEquals("the retained square must not be sub-sampled at all", 1, sample)
        assertEquals(1000, (square.width * 6000).toInt() / sample)
        assertEquals(1000, (square.height * 1000).toInt() / sample)
    }

    @Test
    fun `sampleSizeForCrop bounds the decode on a huge source at deep zoom`() {
        // 108 MP at 5x zoom. `maxDimension / min(fraction)` would ask for a
        // full-resolution decode here (~411 MB at ARGB_8888); sizing by the
        // region asks for 27 Mpx, under the pixel budget.
        val crop = NormalizedCropRect(left = 0.4f, top = 0.44f, width = 0.2f, height = 0.1125f)

        val sample = ImageCompressor.sampleSizeForCrop(12000, 9000, crop, 1600)

        val decodedPixels = (12000L / sample) * (9000L / sample)
        assertTrue(
            "decode must stay within the pixel budget (got ${decodedPixels / 1_000_000} Mpx)",
            decodedPixels <= 32_000_000L,
        )
        // ...while still leaving the crop enough pixels to fill maxDimension.
        assertTrue((crop.width * 12000).toInt() / sample >= 1600 / 2)
    }

    @Test
    fun `sampleSizeForCrop is defensive about degenerate input`() {
        assertEquals(1, ImageCompressor.sampleSizeForCrop(0, 100, null, 1024))
        assertEquals(1, ImageCompressor.sampleSizeForCrop(100, 100, null, 0))
        // An invalid rect must fall back to full-frame sampling, not divide by it.
        val invalid = NormalizedCropRect(0f, 0f, 0f, 1f)
        assertEquals(
            ImageCompressor.sampleSizeForCrop(4000, 3000, null, 1024),
            ImageCompressor.sampleSizeForCrop(4000, 3000, invalid, 1024),
        )
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
