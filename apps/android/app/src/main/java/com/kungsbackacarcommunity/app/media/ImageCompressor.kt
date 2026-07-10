package com.kungsbackacarcommunity.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client-side image compression run BEFORE upload so avatars (and any other
 * picked image) stay small: full-resolution phone photos are commonly 3–8 MB,
 * which both risks the [MediaUpload] byte caps and wastes Cloud Storage. We
 * decode the pick, downscale so its longest side is at most [maxDimension]px
 * (aspect preserved), honour the source EXIF orientation, and re-encode as
 * JPEG at [quality]. The result almost always lands well under the caps.
 *
 * This is a best-effort optimisation: if decoding fails (e.g. a corrupt or
 * unusual image) the original [PickedImage] is returned unchanged so the
 * upload still proceeds and the existing upload-time pre-check remains the
 * source of truth for rejection.
 */
object ImageCompressor {

    /** Longest-side cap for a downscaled avatar; plenty for a circular crop. */
    const val AVATAR_MAX_DIMENSION: Int = 1024

    /** JPEG quality for re-encode — a good size/quality trade-off for photos. */
    const val DEFAULT_JPEG_QUALITY: Int = 80

    /**
     * Downscales + re-encodes [picked] to JPEG off the main thread. Returns a
     * new [PickedImage] with `contentType = image/jpeg`, or the original pick
     * unchanged when it cannot be decoded or the re-encode would not shrink it.
     */
    suspend fun compress(
        picked: PickedImage,
        maxDimension: Int = AVATAR_MAX_DIMENSION,
        quality: Int = DEFAULT_JPEG_QUALITY,
    ): PickedImage = withContext(Dispatchers.Default) {
        runCatching { compressBlocking(picked, maxDimension, quality) }.getOrDefault(picked)
    }

    private fun compressBlocking(
        picked: PickedImage,
        maxDimension: Int,
        quality: Int,
    ): PickedImage {
        val source = picked.bytes

        // 1. Read bounds only (no pixels) to compute an efficient sample size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return picked

        // 2. Decode at the nearest power-of-two down-sample to save memory.
        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            }
        val decoded =
            BitmapFactory.decodeByteArray(source, 0, source.size, decodeOptions) ?: return picked

        // 3. Orient per EXIF, then scale exactly so the longest side fits.
        var bitmap = decoded
        try {
            bitmap = applyExifOrientation(bitmap, source)
            bitmap = scaleToMax(bitmap, maxDimension)

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val encoded = out.toByteArray()

            // Only adopt the re-encode when it actually shrinks the payload
            // (a small, already-optimised JPEG may round-trip larger).
            return if (encoded.size < source.size) {
                PickedImage(bytes = encoded, contentType = "image/jpeg")
            } else {
                picked
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Largest power of two that keeps both dimensions above [maxDimension]. */
    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxDimension && h / 2 >= maxDimension) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** Exactly scales [bitmap] so its longest side equals [maxDimension] (never up). */
    private fun scaleToMax(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        val scaled =
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** Rotates/flips [bitmap] to match the source [bytes] EXIF orientation. */
    private fun applyExifOrientation(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation =
            runCatching {
                ByteArrayInputStream(bytes).use { input ->
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
