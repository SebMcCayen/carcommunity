package com.kungsbackacarcommunity.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ImageCompressor] — it depends on real
 * android.graphics / android.media.ExifInterface implementations (this module
 * has no Robolectric), so coverage lives in androidTest. Covers: the payload is
 * never enlarged, a shrinkable pick comes back as image/jpeg, and EXIF
 * orientation is honoured.
 */
@RunWith(AndroidJUnit4::class)
class ImageCompressorTest {

    private fun jpegBytes(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    private fun pngBytes(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

    /** A colourful gradient so the PNG payload is large yet JPEG-compressible. */
    private fun gradientBitmap(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = Color.rgb(x * 255 / width, y * 255 / height, (x + y) % 256)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * A large, high-entropy image: random per-pixel RGB defeats PNG's DEFLATE so
     * the source stays multi-megabyte, while the compressor's downscale + JPEG
     * re-encode lands far smaller — making the "recompress large images" path
     * genuinely fire. Seeded so the payload is deterministic across runs.
     */
    private fun noisyBitmap(width: Int, height: Int): Bitmap {
        val rng = Random(0xC0FFEE)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] =
                    Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun decodeBounds(bytes: ByteArray): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        }

    @Test
    fun compress_neverEnlargesPayload() = runBlocking {
        // A tiny, already-compressed noise JPEG: re-encoding it can only grow the
        // payload, so the compressor must fall back to the original pick.
        val noise = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                noise.setPixel(x, y, Random.nextInt())
            }
        }
        val source = jpegBytes(noise, 80)
        noise.recycle()
        val picked = PickedImage(bytes = source, contentType = "image/jpeg")

        val result = ImageCompressor.compress(picked)

        assertTrue(
            "compressor must never enlarge the payload",
            result.bytes.size <= source.size,
        )
    }

    @Test
    fun compress_largeImage_recompressesToSmallerJpeg() = runBlocking {
        // A large, high-entropy PNG cannot be DEFLATE-compressed, so the source
        // stays multi-megabyte while the downscaled JPEG re-encode is a fraction
        // of that. The compressor therefore both shrinks the payload and switches
        // the content type to image/jpeg. (A smooth gradient would NOT work here:
        // PNG compresses it so well that the re-encode is not smaller, and the
        // compressor — correctly — keeps the original PNG unchanged.)
        val big = noisyBitmap(2000, 1500)
        val source = pngBytes(big)
        big.recycle()
        val picked = PickedImage(bytes = source, contentType = "image/png")

        val result = ImageCompressor.compress(picked)

        assertEquals("image/jpeg", result.contentType)
        assertTrue("recompress must shrink the payload", result.bytes.size < source.size)
        val bounds = decodeBounds(result.bytes)
        assertTrue(
            "longest side must be capped at AVATAR_MAX_DIMENSION",
            maxOf(bounds.outWidth, bounds.outHeight) <= ImageCompressor.AVATAR_MAX_DIMENSION,
        )
    }

    @Test
    fun compress_honoursExifRotate90() = runBlocking {
        // A landscape source (wider than tall) tagged ORIENTATION_ROTATE_90 must
        // come out portrait once the compressor applies the EXIF orientation.
        val landscape = gradientBitmap(2000, 1000)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("exif-rotate", ".jpg", context.cacheDir)
        val source =
            try {
                file.writeBytes(jpegBytes(landscape, 100))
                ExifInterface(file.absolutePath).apply {
                    setAttribute(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_ROTATE_90.toString(),
                    )
                    saveAttributes()
                }
                file.readBytes()
            } finally {
                file.delete()
                landscape.recycle()
            }
        val picked = PickedImage(bytes = source, contentType = "image/jpeg")

        val result = ImageCompressor.compress(picked)

        val bounds = decodeBounds(result.bytes)
        assertTrue(
            "ORIENTATION_ROTATE_90 must swap a landscape pick to portrait " +
                "(${bounds.outWidth}x${bounds.outHeight})",
            bounds.outHeight > bounds.outWidth,
        )
    }

    /** Writes [attrs] onto a fresh JPEG and returns the resulting bytes. */
    private fun jpegWithExif(attrs: ExifInterface.() -> Unit): ByteArray =
        jpegWithExif(gradientBitmap(64, 64), attrs)

    /** Writes [attrs] onto a JPEG encoded from [bitmap] (which is recycled). */
    private fun jpegWithExif(bitmap: Bitmap, attrs: ExifInterface.() -> Unit): ByteArray {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("exif-meta", ".jpg", context.cacheDir)
        return try {
            file.writeBytes(jpegBytes(bitmap, 90))
            ExifInterface(file.absolutePath).apply(attrs).saveAttributes()
            file.readBytes()
        } finally {
            file.delete()
            bitmap.recycle()
        }
    }

    /**
     * True when [bytes] expose usable coordinates. `android.media.ExifInterface`
     * has no `latLong` property (that is androidx's); it fills a float[2] and
     * reports whether it found a fix.
     */
    private fun hasLatLong(bytes: ByteArray): Boolean =
        ByteArrayInputStream(bytes).use { ExifInterface(it) }.getLatLong(FloatArray(2))

    /** The GPS + device tags a phone camera embeds; the audit's leak vector. */
    private val geotag: ExifInterface.() -> Unit = {
        setAttribute(ExifInterface.TAG_GPS_LATITUDE, "57/1,29/1,13/1")
        setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "12/1,4/1,15/1")
        setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
        setAttribute(ExifInterface.TAG_MAKE, "ACME")
        setAttribute(ExifInterface.TAG_MODEL, "Phone X")
        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:01:01 12:00:00")
    }

    /** A bitmap whose left half is red and right half is blue. */
    private fun halvesBitmap(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (x < width / 2) Color.RED else Color.BLUE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------------
    // Crop step (vehicle photo). The crop is a PARAMETER of the sanitiser, so
    // these tests are simultaneously the crop's correctness proof and the proof
    // that cropping did not open a route around EXIF/GPS stripping.
    // ---------------------------------------------------------------------

    @Test
    fun compressForPublicUpload_croppedGeotaggedPhoto_isStillStripped() = runBlocking {
        // THE regression test for the audit finding, extended to the crop flow:
        // a geotagged photo that the user cropped must come back cropped AND
        // free of every strip tag. If a future change ever crops OUTSIDE the
        // compressor and uploads those bytes, this fails.
        val source = jpegWithExif(halvesBitmap(1600, 1200), geotag)
        assertTrue(
            "precondition: the source really is geotagged",
            ImageCompressor.carriesStrippableMetadata(source),
        )
        val picked = PickedImage(bytes = source, contentType = "image/jpeg")
        // The right (blue) half, trimmed to the 16:9 the cards render at.
        val crop = NormalizedCropRect(left = 0.5f, top = 0.125f, width = 0.5f, height = 0.75f)

        val result =
            ImageCompressor.compressForPublicUpload(
                picked,
                maxDimension = ImageCompressor.VEHICLE_MAX_DIMENSION,
                crop = crop,
            )

        assertNotNull("a decodable photo must sanitise successfully", result)
        requireNotNull(result)
        assertEquals("image/jpeg", result.contentType)
        assertFalse(
            "a CROPPED vehicle photo must still carry no GPS/identifying EXIF — " +
                "cropping must not become a route around compressForPublicUpload",
            ImageCompressor.carriesStrippableMetadata(result.bytes),
        )
        assertFalse(
            "the cropped upload must expose no coordinates",
            hasLatLong(result.bytes),
        )
    }

    @Test
    fun compressForPublicUpload_cropSelectsTheRequestedRegion() = runBlocking {
        // Proves the crop is genuinely applied (and to the right region): the
        // source is red|blue, the crop takes the blue half, so every sampled
        // pixel of the output must be blue-dominant.
        val source = jpegWithExif(halvesBitmap(1600, 1200), geotag)
        val picked = PickedImage(bytes = source, contentType = "image/jpeg")
        val crop = NormalizedCropRect(left = 0.5f, top = 0.125f, width = 0.5f, height = 0.75f)

        val result =
            ImageCompressor.compressForPublicUpload(
                picked,
                maxDimension = ImageCompressor.VEHICLE_MAX_DIMENSION,
                crop = crop,
            )
        requireNotNull(result)

        val decoded = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        assertNotNull(decoded)
        try {
            // 16:9 out of a 4:3 source cropped to half width: 800x900 source
            // pixels, so the output must be that ratio (JPEG dimensions are
            // exact; allow a pixel of rounding).
            val ratio = decoded.width.toFloat() / decoded.height.toFloat()
            assertEquals(
                "cropped output must carry the requested aspect ratio",
                800f / 900f,
                ratio,
                0.02f,
            )
            listOf(0.1f, 0.5f, 0.9f).forEach { fx ->
                listOf(0.1f, 0.5f, 0.9f).forEach { fy ->
                    val pixel =
                        decoded.getPixel(
                            (decoded.width * fx).toInt().coerceIn(0, decoded.width - 1),
                            (decoded.height * fy).toInt().coerceIn(0, decoded.height - 1),
                        )
                    assertTrue(
                        "crop must keep only the BLUE half — sampled ($fx,$fy) got " +
                            "r=${Color.red(pixel)} b=${Color.blue(pixel)}",
                        Color.blue(pixel) > Color.red(pixel),
                    )
                }
            }
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun compressForPublicUpload_croppedWideSource_staysSharp() = runBlocking {
        // End-to-end cover for the sample-size regression: the decode must be
        // sized for the region that survives the crop, not the frame. Sized by
        // the frame, this 16:9 crop of a wide source came back ~220px on its
        // longest side; sized by the region it clears maxDimension/2.
        val source = jpegBytes(gradientBitmap(6400, 800), 95)
        val picked = PickedImage(bytes = source, contentType = "image/jpeg")
        // Full height, 16:9 => 1422px of the 6400px width.
        val crop = NormalizedCropRect(left = 0.3f, top = 0f, width = 1422f / 6400f, height = 1f)

        val result =
            ImageCompressor.compressForPublicUpload(
                picked,
                maxDimension = ImageCompressor.VEHICLE_MAX_DIMENSION,
                crop = crop,
            )
        requireNotNull(result)

        val bounds = decodeBounds(result.bytes)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        assertTrue(
            "a cropped wide source must not decode to a sliver — got " +
                "${bounds.outWidth}x${bounds.outHeight}, expected the longest side " +
                "at least ${ImageCompressor.VEHICLE_MAX_DIMENSION / 2}",
            longest >= ImageCompressor.VEHICLE_MAX_DIMENSION / 2,
        )
    }

    @Test
    fun compressForPublicUpload_panoramaCroppedToSquare_keepsFullResolution() = runBlocking {
        // End-to-end form of the sample-size regression, asserting the ACTUAL
        // output dimensions rather than a bound that would hold either way.
        // A 6000x1000 panorama cropped to its middle 1000x1000 square retains
        // 1000x1000 real pixels, and scaleToMax never upscales, so the output
        // must be exactly that. Sized by the frame it came out 250x250.
        val source = jpegBytes(gradientBitmap(6000, 1000), 95)
        val picked = PickedImage(bytes = source, contentType = "image/jpeg")
        val square = NormalizedCropRect(left = 1f / 3f, top = 0f, width = 1f / 6f, height = 1f)

        val result =
            ImageCompressor.compressForPublicUpload(
                picked,
                maxDimension = ImageCompressor.VEHICLE_MAX_DIMENSION,
                crop = square,
            )
        requireNotNull(result)

        val bounds = decodeBounds(result.bytes)
        // Exact, not a bound: 1000x1000 is every retained pixel. The pre-fix
        // code produced 250x250 here, which passes any ">= something small"
        // assertion, so the dimensions are pinned directly.
        assertEquals(
            "cropped width (got ${bounds.outWidth}x${bounds.outHeight})",
            1000,
            bounds.outWidth,
        )
        assertEquals(
            "cropped height (got ${bounds.outWidth}x${bounds.outHeight})",
            1000,
            bounds.outHeight,
        )
        // The sanitiser still ran on this path: a re-encode carries no EXIF.
        assertFalse(ImageCompressor.carriesStrippableMetadata(result.bytes))
    }

    @Test
    fun compressForPublicUpload_cropOnUndecodableImage_failsClosed() = runBlocking {
        // The strip fallback can only produce the WHOLE frame, which would
        // upload exactly the region the user cropped away (the house number, the
        // neighbour's plate). With a crop requested there is no safe fallback,
        // so it must fail closed and the caller must skip the upload.
        val garbage = ByteArray(2048) { 0x7F }
        val picked = PickedImage(bytes = garbage, contentType = "image/jpeg")
        val crop = NormalizedCropRect(left = 0.25f, top = 0.25f, width = 0.5f, height = 0.28f)

        val result =
            ImageCompressor.compressForPublicUpload(
                picked,
                maxDimension = ImageCompressor.VEHICLE_MAX_DIMENSION,
                crop = crop,
            )

        assertNull("an undecodable pick with a crop must fail closed", result)
    }

    @Test
    fun compressForPublicUpload_fullFrameCropBehavesLikeNoCrop() = runBlocking {
        // A full-frame rect is what the crop screen emits when the box has not
        // been measured. It must NOT opt out of the strip fallback, or an
        // unmeasured crop box would turn a recoverable pick into a failed one.
        val source = jpegWithExif(geotag)
        val picked = PickedImage(bytes = source, contentType = "image/jpeg")

        val cropped =
            ImageCompressor.compressForPublicUpload(picked, crop = NormalizedCropRect.FULL)
        val uncropped = ImageCompressor.compressForPublicUpload(picked, crop = null)

        assertNotNull(cropped)
        assertNotNull(uncropped)
        requireNotNull(cropped)
        assertFalse(ImageCompressor.carriesStrippableMetadata(cropped.bytes))
    }

    @Test
    fun decodeForCrop_orientsThePreviewLikeTheReencode() = runBlocking {
        // The crop UI must show the photo the same way up as the re-encode will
        // save it; otherwise the user frames a sideways photo and gets an
        // upright, wrongly-cropped one.
        val landscape = gradientBitmap(2000, 1000)
        val source =
            jpegWithExif(landscape) {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_ROTATE_90.toString(),
                )
            }
        val picked = PickedImage(bytes = source, contentType = "image/jpeg")

        val preview = ImageCompressor.decodeForCrop(picked)

        assertNotNull(preview)
        requireNotNull(preview)
        try {
            assertTrue(
                "ORIENTATION_ROTATE_90 must make the PREVIEW portrait too " +
                    "(${preview.width}x${preview.height})",
                preview.height > preview.width,
            )
            assertTrue(
                "preview must be downscaled for display",
                maxOf(preview.width, preview.height) <= ImageCompressor.VEHICLE_MAX_DIMENSION * 2,
            )
        } finally {
            preview.recycle()
        }
    }

    @Test
    fun decodeForCrop_undecodableImage_returnsNull() = runBlocking {
        assertNull(
            ImageCompressor.decodeForCrop(
                PickedImage(bytes = ByteArray(1024) { 0x11 }, contentType = "image/jpeg"),
            ),
        )
    }

    @Test
    fun naiveCropLibraryOutput_wouldLeakGps() {
        // Teeth by contrast, and the reason the crop step returns a WINDOW
        // rather than an image: this is what a typical crop library hands back —
        // it re-encodes the crop and deliberately copies the source EXIF forward
        // so the result "keeps its metadata". Uploading that Uri/byte array
        // directly, as the obvious integration would, republishes the owner's
        // coordinates from a photo that LOOKS freshly encoded.
        //
        // Nothing in production may produce bytes this way; if this ever stops
        // leaking, the contrast is moot but the guarantee above still stands.
        val source = jpegWithExif(halvesBitmap(800, 600), geotag)
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size)
        val cropped = Bitmap.createBitmap(decoded, 400, 0, 400, 600)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("naive-crop", ".jpg", context.cacheDir)
        val leaked =
            try {
                file.writeBytes(jpegBytes(cropped, 90))
                val from = ByteArrayInputStream(source).use { ExifInterface(it) }
                ExifInterface(file.absolutePath).apply {
                    setAttribute(
                        ExifInterface.TAG_GPS_LATITUDE,
                        from.getAttribute(ExifInterface.TAG_GPS_LATITUDE),
                    )
                    setAttribute(
                        ExifInterface.TAG_GPS_LATITUDE_REF,
                        from.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF),
                    )
                    setAttribute(
                        ExifInterface.TAG_GPS_LONGITUDE,
                        from.getAttribute(ExifInterface.TAG_GPS_LONGITUDE),
                    )
                    setAttribute(
                        ExifInterface.TAG_GPS_LONGITUDE_REF,
                        from.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF),
                    )
                    saveAttributes()
                }
                file.readBytes()
            } finally {
                file.delete()
                cropped.recycle()
                decoded.recycle()
            }

        assertTrue(
            "a crop-library-shaped output carries the source's GPS forward — " +
                "which is exactly why the crop step must not produce upload bytes",
            ImageCompressor.carriesStrippableMetadata(leaked),
        )
        assertTrue(hasLatLong(leaked))
    }

    @Test
    fun carriesStrippableMetadata_identifyingExifWithoutGps_isNotClean() {
        // The core fail-closed guarantee: a JPEG carrying only IDENTIFYING EXIF
        // (device make/model + capture time) and NO GPS must still count as
        // un-sanitised, so the stripOrFail fallback fails closed rather than
        // uploading it as-is. Regression test for the GPS-only gate that used to
        // wave these bytes through.
        val bytes =
            jpegWithExif {
                setAttribute(ExifInterface.TAG_MAKE, "ACME")
                setAttribute(ExifInterface.TAG_MODEL, "Phone X")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:01:01 12:00:00")
            }

        assertTrue(
            "identifying EXIF (no GPS) must NOT be treated as clean",
            ImageCompressor.carriesStrippableMetadata(bytes),
        )
    }

    @Test
    fun carriesStrippableMetadata_gpsExif_isNotClean() {
        // A geotagged JPEG is obviously not clean — the gate must catch GPS too.
        val bytes =
            jpegWithExif {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "57/1,29/1,13/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "12/1,4/1,15/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            }

        assertTrue(
            "GPS EXIF must NOT be treated as clean",
            ImageCompressor.carriesStrippableMetadata(bytes),
        )
    }

    @Test
    fun carriesStrippableMetadata_freshlyEncodedJpeg_isClean() {
        // A JPEG freshly encoded straight from a Bitmap carries no EXIF at all, so
        // the gate must report it clean (otherwise nothing would ever pass).
        val bitmap = gradientBitmap(64, 64)
        val bytes = jpegBytes(bitmap, 90)
        bitmap.recycle()

        assertFalse(
            "a metadata-free JPEG must be treated as clean",
            ImageCompressor.carriesStrippableMetadata(bytes),
        )
    }
}
