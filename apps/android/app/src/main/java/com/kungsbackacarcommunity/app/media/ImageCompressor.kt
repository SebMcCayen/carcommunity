package com.kungsbackacarcommunity.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
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
     *
     * Size-optimising and best-effort: NOT a privacy guarantee. Because it can
     * hand back the original bytes (EXIF intact) when the re-encode does not
     * shrink the payload, callers uploading a PUBLICLY visible image must use
     * [compressForPublicUpload] instead so location EXIF can never leak.
     */
    suspend fun compress(
        picked: PickedImage,
        maxDimension: Int = AVATAR_MAX_DIMENSION,
        quality: Int = DEFAULT_JPEG_QUALITY,
    ): PickedImage = withContext(Dispatchers.Default) {
        try {
            val reencoded = reencodeToJpeg(picked, maxDimension, quality)
            // Best-effort size optimisation: adopt the re-encode only when it is
            // actually smaller; otherwise keep the original pick unchanged.
            if (reencoded != null && reencoded.sizeBytes < picked.sizeBytes) reencoded else picked
        } catch (e: CancellationException) {
            throw e // never swallow cancellation — keep structured concurrency intact
        } catch (_: Exception) {
            picked // best-effort: fall back to the original pick (Errors like OOM propagate)
        }
    }

    /**
     * Sanitises [picked] for upload to a PUBLICLY visible location (e.g. a car
     * profile photo other members can see): the returned bytes are GUARANTEED to
     * carry no EXIF metadata, so a photo taken at the owner's home can never leak
     * their GPS coordinates.
     *
     * Unlike [compress] this adopts the JPEG re-encode whenever it succeeds —
     * even when it is not smaller than the source — because the decode+re-encode
     * is what strips the EXIF (GPS included); dropping metadata matters more than
     * shaving bytes here. Returns `null` when the image cannot be decoded/encoded
     * at all: the original bytes may still contain GPS EXIF, so the caller MUST
     * fail closed and skip the upload rather than send unsanitised bytes.
     */
    suspend fun compressForPublicUpload(
        picked: PickedImage,
        maxDimension: Int = AVATAR_MAX_DIMENSION,
        quality: Int = DEFAULT_JPEG_QUALITY,
    ): PickedImage? = withContext(Dispatchers.Default) {
        try {
            reencodeToJpeg(picked, maxDimension, quality)
        } catch (e: CancellationException) {
            throw e // never swallow cancellation — keep structured concurrency intact
        } catch (_: Exception) {
            null // fail closed: never fall back to the un-sanitised original
        }
    }

    /**
     * Decodes, orients, downscales and re-encodes [picked] to a fresh JPEG whose
     * bytes carry no EXIF. Returns the re-encoded [PickedImage] on success, or
     * `null` when the image cannot be decoded or the encode produced no bytes.
     * The size-vs-original decision is left to the caller so both the best-effort
     * ([compress]) and privacy-guaranteed ([compressForPublicUpload]) paths can
     * share the exact same re-encode.
     */
    private fun reencodeToJpeg(
        picked: PickedImage,
        maxDimension: Int,
        quality: Int,
    ): PickedImage? {
        // Guard against invalid tuning params before touching any pixels: a
        // non-positive maxDimension would make sampleSizeFor() loop forever, and
        // Bitmap.compress only defines JPEG quality over 0..100. In either case
        // there is nothing sensible to re-encode.
        if (maxDimension <= 0 || quality !in 0..100) return null

        val source = picked.bytes

        // 1. Read bounds only (no pixels) to compute an efficient sample size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 2. Decode at the nearest power-of-two down-sample to save memory.
        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            }
        val decoded =
            BitmapFactory.decodeByteArray(source, 0, source.size, decodeOptions) ?: return null

        // 3. Orient per EXIF, then scale exactly so the longest side fits.
        var bitmap = decoded
        try {
            bitmap = applyExifOrientation(bitmap, source)
            bitmap = scaleToMax(bitmap, maxDimension)

            val out = ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val encoded = out.toByteArray()

            // Bitmap.compress returns false on failure and can leave an empty/
            // partial buffer, so only treat a successful, non-empty encode as a
            // valid re-encode. The re-encoded JPEG carries no EXIF regardless of
            // its size; whether to prefer it over the source is the caller's call.
            return if (ok && encoded.isNotEmpty()) {
                PickedImage(bytes = encoded, contentType = "image/jpeg")
            } else {
                null
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Largest power-of-two [BitmapFactory.Options.inSampleSize] that brings the
     * LONGEST side to at most [maxDimension]. Basing the decision on the longest
     * side (not requiring BOTH sides to shrink) means a very wide/tall image —
     * e.g. 4000x500 with maxDimension 1024 — still down-samples (to 1000x125 at
     * sample 4) instead of decoding at full resolution and spiking peak memory.
     * The result is a power of two; the later [scaleToMax] step downscales the
     * longest side to at most [maxDimension] (it never scales up, and leaves the
     * bitmap smaller when inSampleSize already brought it below [maxDimension]).
     */
    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest > maxDimension) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /** Downscales [bitmap] so its longest side is at most [maxDimension] (returns it
     * unchanged when already smaller; never scales up). */
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
            // Transpose = rotate 90 then flip horizontally; transverse = rotate
            // 270 then flip horizontally. Same postRotate-then-postScale order as
            // the branches above so the combined transform is applied correctly.
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
