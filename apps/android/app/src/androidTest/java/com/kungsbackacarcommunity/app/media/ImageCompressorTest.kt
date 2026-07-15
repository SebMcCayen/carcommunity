package com.kungsbackacarcommunity.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private fun jpegWithExif(attrs: ExifInterface.() -> Unit): ByteArray {
        val bitmap = gradientBitmap(64, 64)
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
