package com.kungsbackacarcommunity.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client-side image compression + metadata stripping run BEFORE upload so every
 * picked image (avatars, vehicle photos, and any FUTURE image upload) stays
 * small AND carries no identifying metadata: full-resolution phone photos are
 * commonly 3–8 MB and embed EXIF — including precise GPS coordinates — which
 * both risks the [MediaUpload] byte caps and would leak the owner's location.
 *
 * The happy path decodes the pick, downscales so its longest side is at most
 * [maxDimension]px (aspect preserved), honours the source EXIF orientation, and
 * re-encodes as JPEG at [quality]. Because a decoded [Bitmap] has NO metadata,
 * the re-encoded JPEG is inherently EXIF-free (no GPS, no make/model, no
 * timestamps) — stripping is a free by-product of the re-encode.
 *
 * The FALLBACK (when the pick cannot be decoded/shrunk) never returns raw
 * bytes blindly: it physically rewrites the JPEG without metadata via
 * [ExifInterface], and if it cannot guarantee a clean result it FAILS CLOSED
 * (returns null) rather than upload geotagged bytes. See [stripOrFail].
 *
 * Compression is centralised in [ImageUploadCoordinator], so callers get it by
 * construction and cannot accidentally upload an un-processed pick.
 */
object ImageCompressor {

    /** Longest-side cap for a downscaled avatar; plenty for a circular crop. */
    const val AVATAR_MAX_DIMENSION: Int = 1024

    /**
     * Longest-side cap for a vehicle photo — larger than an avatar so engine-bay
     * / detail shots keep enough resolution, still JPEG-compressed + stripped and
     * comfortably under [MediaUpload.VEHICLE_IMAGE_MAX_BYTES].
     */
    const val VEHICLE_MAX_DIMENSION: Int = 1600

    /** JPEG quality for re-encode — a good size/quality trade-off for photos. */
    const val DEFAULT_JPEG_QUALITY: Int = 80

    /**
     * Downscales + re-encodes [picked] to a metadata-free JPEG off the main
     * thread. Returns:
     *  - a new [PickedImage] (`contentType = image/jpeg`) with no EXIF, or
     *  - the original pick when it is provably free of location metadata but
     *    could not be re-encoded, or
     *  - `null` when the pick cannot be decoded AND cannot be proven clean —
     *    the caller MUST treat null as a failed upload (never fall back to the
     *    raw bytes), guaranteeing no geotagged image ever leaves the device.
     */
    suspend fun compress(
        picked: PickedImage,
        maxDimension: Int = AVATAR_MAX_DIMENSION,
        quality: Int = DEFAULT_JPEG_QUALITY,
    ): PickedImage? = withContext(Dispatchers.Default) {
        val reencoded =
            try {
                compressBlocking(picked, maxDimension, quality)
            } catch (e: CancellationException) {
                throw e // never swallow cancellation — keep structured concurrency intact
            } catch (_: Exception) {
                null // decode/re-encode blew up unexpectedly; fall through to strip-or-fail
            }
        // Happy path: a clean re-encoded JPEG. Otherwise physically strip the
        // original's metadata or fail closed — we NEVER return raw picked bytes
        // that might still carry GPS.
        reencoded ?: stripOrFail(picked)
    }

    /**
     * Decode → orient → downscale → re-encode. Returns a metadata-free JPEG
     * [PickedImage], or null when the pick cannot be decoded or the re-encode
     * fails (the caller then routes the original through [stripOrFail]). Note it
     * NEVER returns the original bytes: the decode path always yields a clean
     * JPEG, so any non-null result here is guaranteed EXIF-free.
     */
    private fun compressBlocking(
        picked: PickedImage,
        maxDimension: Int,
        quality: Int,
    ): PickedImage? {
        // Guard against invalid tuning params before touching any pixels: a
        // non-positive maxDimension would make sampleSizeFor() loop forever, and
        // Bitmap.compress only defines JPEG quality over 0..100.
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

            // Adopt the re-encode whenever it produced a valid buffer. A decoded
            // Bitmap has no metadata, so this JPEG is inherently EXIF-free (no
            // GPS). We deliberately DO NOT keep the original when the re-encode
            // rounds larger — uploading a slightly bigger but clean JPEG beats
            // leaking the raw pick's location; the upload cap is enforced later.
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
     * Last line of defence for a pick that could NOT be re-encoded (corrupt /
     * unusual image, or the decode threw). We must never upload the raw bytes if
     * they might carry location, so:
     *  1. physically rewrite the JPEG without metadata (works even when the
     *     pixels are undecodable — [ExifInterface] parses the APP1 block), else
     *  2. keep the original ONLY when it provably carries no GPS, else
     *  3. FAIL CLOSED (null) — a geotagged pick is dropped, never uploaded.
     */
    private fun stripOrFail(picked: PickedImage): PickedImage? {
        stripInPlace(picked)?.let { return it }
        return if (carriesLocation(picked.bytes)) null else picked
    }

    /**
     * Physically strips GPS + identifying EXIF from JPEG [picked] by rewriting it
     * through a temp file (the only way [android.media.ExifInterface] can persist
     * edits — it has no in-memory save). Returns the cleaned JPEG, or null when
     * the format is not a strippable JPEG or anything goes wrong (temp dir not
     * writable, save unsupported), so the caller can fall back to fail-closed.
     */
    private fun stripInPlace(picked: PickedImage): PickedImage? {
        // ExifInterface.saveAttributes() only rewrites JPEG; other types either
        // carry no EXIF (png/gif) or are not writable here — let the caller decide.
        val isJpeg = picked.contentType?.trim()?.lowercase() == "image/jpeg"
        if (!isJpeg) return null
        return runCatching {
            val tmp = File.createTempFile("kcc-strip-", ".jpg")
            try {
                tmp.writeBytes(picked.bytes)
                val exif = ExifInterface(tmp.absolutePath)
                STRIP_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
                exif.saveAttributes()
                val cleaned = tmp.readBytes()
                // Verify the rewrite actually removed location before trusting it.
                if (cleaned.isEmpty() || carriesLocation(cleaned)) {
                    null
                } else {
                    PickedImage(bytes = cleaned, contentType = "image/jpeg")
                }
            } finally {
                tmp.delete()
            }
        }.getOrNull()
    }

    /** True when [bytes] embed any GPS location EXIF tag (fail-closed signal). */
    private fun carriesLocation(bytes: ByteArray): Boolean {
        val exif =
            runCatching { ByteArrayInputStream(bytes).use { ExifInterface(it) } }.getOrNull()
                ?: return false // couldn't parse EXIF → no readable location metadata
        return GPS_LOCATION_TAGS.any { tag -> exif.getAttribute(tag) != null }
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
     *
     * Internal (not private) purely so it is JVM-unit-testable without a device.
     */
    internal fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
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

    /** GPS tags whose presence means the pick embeds a location (fail-closed). */
    private val GPS_LOCATION_TAGS =
        listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
        )

    /**
     * EXIF tags cleared when physically stripping a fallback JPEG: every GPS tag
     * (location, precise to metres) plus common identifying tags (device make /
     * model / serial, capture timestamps, author). Setting each to null and
     * calling saveAttributes() removes it from the rewritten file.
     */
    private val STRIP_TAGS =
        listOf(
            // --- GPS: location, the critical privacy leak ---
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DIFFERENTIAL,
            ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_STATUS,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_VERSION_ID,
            // --- Identifying: device + capture provenance ---
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
        )
}
