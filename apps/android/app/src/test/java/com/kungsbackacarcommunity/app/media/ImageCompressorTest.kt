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

    // ------------------------------------------------------------------
    // rotationDownscaleFactor — the free rotation must not blow the decode
    // budget. sampleSizeForCrop caps the UN-ROTATED decode, but rotating grows
    // the axis-aligned bounding box (worst near 45deg for a wide/tall source),
    // so the factor folded into the rotation Matrix is what keeps peak memory
    // bounded and the intermediate allocation from OOMing.
    // ------------------------------------------------------------------

    /** The rotated bounding-box pixel count for a w x h source at [deg]. */
    private fun rotatedPixels(w: Int, h: Int, deg: Double): Double {
        val rad = Math.toRadians(deg)
        val c = kotlin.math.abs(kotlin.math.cos(rad))
        val s = kotlin.math.abs(kotlin.math.sin(rad))
        return (c * w + s * h) * (s * w + c * h)
    }

    @Test
    fun `rotationDownscaleFactor is 1 when the rotated box already fits`() {
        // A modest source stays well under the 32 Mpx budget at any angle, so no
        // downscale is folded in and the rotate is a pure rotate.
        listOf(0f, 15f, 45f, 90f, 137f, -45f).forEach { angle ->
            assertEquals(
                "angle=$angle",
                1f,
                ImageCompressor.rotationDownscaleFactor(2000, 1500, angle),
                0f,
            )
        }
    }

    @Test
    fun `rotationDownscaleFactor shrinks a wide source rotated near 45 degrees`() {
        // The OOM case: 8000x4000 is exactly at the 32 Mpx cap un-rotated, but at
        // 45deg its bounding box is ~8485x8485 ~= 72 Mpx (~288 MB at ARGB_8888).
        val w = 8000
        val h = 4000
        val angle = 45f
        assertTrue(
            "precondition: the rotated box must genuinely exceed the budget",
            rotatedPixels(w, h, angle.toDouble()) > 32_000_000.0,
        )

        val factor = ImageCompressor.rotationDownscaleFactor(w, h, angle)

        assertTrue("a downscale must be requested (got $factor)", factor < 1f)
        assertTrue("the factor must stay positive (got $factor)", factor > 0f)
        // The whole point: after folding the factor in, the allocation fits.
        val scaledPixels = rotatedPixels(w, h, angle.toDouble()) * factor * factor
        assertTrue(
            "the scaled rotated box must fit the budget (got ${scaledPixels / 1_000_000} Mpx)",
            scaledPixels <= 32_000_000.0 * 1.001,
        )
    }

    @Test
    fun `rotationDownscaleFactor keeps every angle of a huge source within budget`() {
        // Sweep: whatever the angle, the allocation the Matrix produces is bounded.
        val w = 12000
        val h = 9000
        (0..180 step 5).forEach { deg ->
            val factor = ImageCompressor.rotationDownscaleFactor(w, h, deg.toFloat())
            assertTrue("deg=$deg factor must be in (0,1] (got $factor)", factor > 0f && factor <= 1f)
            val scaledPixels = rotatedPixels(w, h, deg.toDouble()) * factor * factor
            assertTrue(
                "deg=$deg must fit the budget (got ${scaledPixels / 1_000_000} Mpx)",
                scaledPixels <= 32_000_000.0 * 1.001,
            )
        }
    }

    @Test
    fun `rotationDownscaleFactor is defensive about degenerate input`() {
        // Degenerate dimensions and a non-finite angle must not produce NaN or 0 —
        // they mean "no scaling", leaving the existing non-finite guard in charge.
        assertEquals(1f, ImageCompressor.rotationDownscaleFactor(0, 100, 45f), 0f)
        assertEquals(1f, ImageCompressor.rotationDownscaleFactor(100, 0, 45f), 0f)
        assertEquals(1f, ImageCompressor.rotationDownscaleFactor(9000, 9000, Float.NaN), 0f)
        assertEquals(
            1f,
            ImageCompressor.rotationDownscaleFactor(9000, 9000, Float.POSITIVE_INFINITY),
            0f,
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
