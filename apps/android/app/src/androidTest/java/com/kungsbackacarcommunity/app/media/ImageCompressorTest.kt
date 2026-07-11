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
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bmp.setPixel(x, y, Color.rgb(x * 255 / width, y * 255 / height, (x + y) % 256))
            }
        }
        return bmp
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
        val picked = PickedImage(bytes = source, contentType = "image/jpeg")

        val result = ImageCompressor.compress(picked)

        assertTrue(
            "compressor must never enlarge the payload",
            result.bytes.size <= source.size,
        )
    }

    @Test
    fun compress_largeImage_recompressesToSmallerJpeg() = runBlocking {
        // A large PNG far exceeds the downscaled JPEG re-encode, so compression
        // both shrinks it and switches the content type to image/jpeg.
        val big = gradientBitmap(2000, 1500)
        val source = pngBytes(big)
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
        file.writeBytes(jpegBytes(landscape, 100))
        ExifInterface(file.absolutePath).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }
        val source = file.readBytes()
        file.delete()
        val picked = PickedImage(bytes = source, contentType = "image/jpeg")

        val result = ImageCompressor.compress(picked)

        val bounds = decodeBounds(result.bytes)
        assertTrue(
            "ORIENTATION_ROTATE_90 must swap a landscape pick to portrait " +
                "(${bounds.outWidth}x${bounds.outHeight})",
            bounds.outHeight > bounds.outWidth,
        )
    }
}
