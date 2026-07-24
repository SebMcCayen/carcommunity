package com.kungsbackacarcommunity.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client-side image compression (+ EXIF/GPS stripping for public uploads) run
 * BEFORE upload so picked images stay small AND — for anything publicly visible
 * — carry no identifying metadata: full-resolution phone photos are commonly
 * 3–8 MB and embed EXIF — including precise GPS coordinates — which both risks
 * the [MediaUpload] byte caps and would leak the owner's location.
 *
 * Two entry points, one shared re-encode ([reencodeToJpeg]):
 *  - [compress] is a best-effort SIZE optimisation. It decodes, downscales so
 *    the longest side is at most [maxDimension]px (aspect preserved), honours
 *    the source EXIF orientation, and re-encodes as JPEG at [quality] — but
 *    keeps the original bytes when the re-encode would not shrink them. It is
 *    NOT a privacy guarantee (it can hand back EXIF-bearing originals).
 *  - [compressForPublicUpload] is the CANONICAL sanitiser for anything other
 *    members can see. It GUARANTEES the returned bytes are free of every tag in
 *    [STRIP_TAGS] (all GPS + identifying EXIF: make/model/software/artist/
 *    timestamps/…). On the happy path it re-encodes a decoded [Bitmap] to JPEG,
 *    which drops ALL metadata; when the pick cannot be re-encoded it falls back
 *    to [stripOrFail], which physically removes those tags, or returns the
 *    original ONLY when it is provably free of every [STRIP_TAGS] entry, else
 *    FAILS CLOSED (returns null). A null result means the caller MUST skip the
 *    upload — never fall back to the raw pick — so no image carrying GPS or
 *    identifying metadata ever leaves the device.
 *
 * Sanitisation is a CALL-SITE CONTRACT, not an automatic guarantee: the two
 * public-image upload paths — the profile avatar (AuthenticatedApp) and the
 * vehicle photo (GarageRoute) — each call [compressForPublicUpload] on the pick
 * and skip the upload on a null result before handing the sanitised bytes to
 * [ImageUploadCoordinator]. The coordinator itself only enforces the size/type
 * pre-check and uploads; it does NOT compress or strip. Any NEW upload path for
 * a publicly visible image MUST therefore call [compressForPublicUpload] itself
 * — nothing downstream will strip metadata for it.
 *
 * USER CROPPING is expressed as the `crop` PARAMETER of
 * [compressForPublicUpload] ([NormalizedCropRect]), never as a separate
 * crop-then-upload stage. That is deliberate and load-bearing: a crop step that
 * produced its own image (a bitmap, a temp file, a `Uri` from a crop library)
 * would be a second, unsanitised route to Storage — exactly the bypass this
 * class exists to prevent. Because the crop is applied INSIDE [reencodeToJpeg],
 * cropped bytes can only ever come out of the sanitiser, and [decodeForCrop]
 * (which the crop UI uses to draw a preview) returns a [android.graphics.Bitmap]
 * that has no encoded form and therefore nothing to upload.
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
     * Below this many degrees a requested free rotation is treated as "no
     * rotation": the gesture editor emits a float angle that is essentially 0 when
     * the user never twisted, and rotating by a sliver would resample the whole
     * frame (and enlarge its bounding box) for no visible gain.
     */
    private const val ROTATION_EPSILON: Float = 0.01f

    /**
     * Ceiling on the pixels a single decode may allocate, before the crop is cut
     * out of it. Sizing the decode so the CROPPED REGION reaches [maxDimension]
     * necessarily decodes the whole frame larger than the region; on a very
     * large source at a deep zoom that could run to hundreds of megabytes.
     *
     * 32 Mpx is ~128 MB at ARGB_8888 — comfortably above any decode a real pick
     * needs (a 108 MP phone photo at maximum zoom lands around 27 Mpx, and picks
     * are already capped at [MediaUpload.VEHICLE_IMAGE_READ_MAX_BYTES] anyway),
     * so this only ever bites the pathological case.
     */
    private const val MAX_DECODE_PIXELS: Long = 32_000_000L

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
     * profile photo other members can see). The returned bytes are GUARANTEED to
     * be free of every tag in [STRIP_TAGS] (all GPS + identifying EXIF), so a
     * photo taken at the owner's home can never leak their GPS coordinates or
     * device fingerprint.
     *
     * Unlike [compress] this adopts the JPEG re-encode whenever it succeeds —
     * even when it is not smaller than the source — because the decode+re-encode
     * is what drops the metadata (a decoded [Bitmap] has none, so the re-encoded
     * JPEG carries no EXIF at all); shedding metadata matters more than shaving
     * bytes here. When the pick cannot be re-encoded (corrupt / unusual image, or
     * the decode threw) it does NOT give up on privacy: it routes the original
     * through [stripOrFail], which physically removes the [STRIP_TAGS] (or keeps
     * the original only when it is provably free of every one of them) and
     * otherwise FAILS CLOSED. A `null` result therefore means the image could not
     * be proven free of GPS/identifying metadata: the caller MUST skip the upload
     * rather than send unsanitised bytes.
     *
     * Note: on the fallback "return the proven-clean original" branch the bytes
     * may still contain benign EXIF that is NOT in [STRIP_TAGS] (e.g. orientation)
     * — the guarantee is precisely "free of [STRIP_TAGS]", not "zero EXIF". The
     * common (re-encode) path strips everything.
     *
     * @param crop the user's crop window in normalized source coordinates, or
     *   null / [NormalizedCropRect.FULL] for the whole image. Applying the crop
     *   HERE rather than in the UI is what keeps a single sanitised route to
     *   Storage (see this class's KDoc). When a crop is requested but the image
     *   cannot be re-encoded, this FAILS CLOSED rather than falling back to the
     *   physical strip: that fallback returns the WHOLE frame, so it would
     *   upload the very region the user cropped away — which is a privacy
     *   decision (the neighbour's plate, the house number) and not ours to undo.
     */
    suspend fun compressForPublicUpload(
        picked: PickedImage,
        maxDimension: Int = AVATAR_MAX_DIMENSION,
        quality: Int = DEFAULT_JPEG_QUALITY,
        crop: NormalizedCropRect? = null,
        rotationDegrees: Float = 0f,
    ): PickedImage? = withContext(Dispatchers.Default) {
        // A full-frame crop is indistinguishable from no crop, so it must not
        // opt out of the strip fallback.
        val cropsAway = crop != null && !crop.isFullFrame()
        // A free rotation (the gesture editor's arbitrary angle) changes which
        // pixels survive just as a crop does — and the physical-strip fallback
        // can only ever emit the WHOLE, UN-ROTATED frame. So when a rotation is
        // requested, the fallback is no more able to honour it than it can honour
        // a crop: it must fail closed rather than upload the un-rotated original.
        val rotates = rotationDegrees.isFinite() && abs(rotationDegrees) > ROTATION_EPSILON
        val cannotFallBack = cropsAway || rotates
        try {
            // Happy path: a clean re-encoded JPEG. Otherwise physically strip the
            // original's STRIP_TAGS or fail closed — we NEVER return raw picked
            // bytes that might still carry GPS or identifying metadata.
            reencodeToJpeg(picked, maxDimension, quality, crop, rotationDegrees)
                ?: if (cannotFallBack) null else stripOrFail(picked)
        } catch (e: CancellationException) {
            throw e // never swallow cancellation — keep structured concurrency intact
        } catch (_: Exception) {
            // Re-encode blew up unexpectedly; still try the physical-strip
            // fallback (it never throws) before failing closed — unless a crop or
            // rotation was requested, which that fallback cannot honour.
            if (cannotFallBack) null else stripOrFail(picked)
        }
    }

    /**
     * Decodes [picked] for the crop UI to DISPLAY: EXIF-oriented (so the user
     * arranges the photo the right way up) and downscaled to at most
     * [maxDimension] on the longest side, since a full-resolution phone photo is
     * far larger than any screen and would spike memory for no visible gain.
     *
     * Returns null when the image cannot be decoded — the caller must then treat
     * the pick as unusable and skip it, exactly as it would a null from
     * [compressForPublicUpload].
     *
     * Deliberately returns a [Bitmap] and NOT bytes: an in-memory bitmap has no
     * encoded form, so this preview cannot become an upload payload. The crop UI
     * therefore has nothing to hand to Storage but a [NormalizedCropRect], and
     * the actual pixels are re-derived from [picked] inside
     * [compressForPublicUpload]. A decoded bitmap also carries no EXIF of its
     * own, so the preview cannot leak metadata either.
     */
    suspend fun decodeForCrop(
        picked: PickedImage,
        maxDimension: Int = VEHICLE_MAX_DIMENSION,
    ): Bitmap? = withContext(Dispatchers.Default) {
        try {
            decodeOriented(picked.bytes, maxDimension)
        } catch (e: CancellationException) {
            throw e // never swallow cancellation — keep structured concurrency intact
        } catch (_: Exception) {
            null
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
        crop: NormalizedCropRect? = null,
        rotationDegrees: Float = 0f,
    ): PickedImage? {
        // Guard against invalid tuning params before touching any pixels: a
        // non-positive maxDimension would make sampleSizeFor() loop forever, and
        // Bitmap.compress only defines JPEG quality over 0..100. In either case
        // there is nothing sensible to re-encode.
        if (maxDimension <= 0 || quality !in 0..100) return null

        // 1-2. Decode at the nearest power-of-two down-sample, oriented per EXIF.
        // The crop is passed in so the sample size is chosen roughly for the
        // REGION that survives the crop, not the whole frame (see
        // [sampleSizeForCrop]). With a free rotation in play the crop's fractions
        // are measured against the ROTATED bounding box rather than the oriented
        // frame, so the region estimate is approximate — it only tunes decode
        // sharpness/memory, never correctness — and the MAX_DECODE_PIXELS floor
        // still guards against an over-large decode.
        var bitmap = decodeOriented(picked.bytes, maxDimension, crop) ?: return null

        // 3. Apply the gesture editor's free rotation (arbitrary angle) BEFORE the
        // crop, so `crop` is the axis-aligned rect the user framed in the ROTATED
        // image (see [ImageEditGeometry]). One clean resample: orient → rotate →
        // crop → scale. A no-op for the ordinary (un-rotated) crop path.
        try {
            bitmap = rotateArbitrary(bitmap, rotationDegrees)

            // 4. Apply the user's crop, then scale so the longest side fits.
            bitmap = applyCrop(bitmap, crop) ?: return null
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
     * Decodes [bytes] to a [Bitmap] that is EXIF-oriented and whose longest side
     * is at most about [maxDimension] (power-of-two sub-sampling only, so it can
     * land above [maxDimension] — [scaleToMax] does the exact fit when the caller
     * needs one). Returns null when the bytes cannot be decoded.
     *
     * Shared by [reencodeToJpeg] and [decodeForCrop] so the crop UI previews the
     * SAME orientation the re-encode will produce: if only one of them honoured
     * EXIF orientation, the user would frame a sideways photo and get an
     * upright, wrongly-cropped one (or the reverse).
     */
    private fun decodeOriented(
        bytes: ByteArray,
        maxDimension: Int,
        crop: NormalizedCropRect? = null,
    ): Bitmap? {
        if (maxDimension <= 0) return null

        // Read bounds only (no pixels) to compute an efficient sample size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // A [crop]'s fractions are measured against the ORIENTED image — that is
        // what the crop UI showed — while these bounds describe the image as
        // STORED. For a 90°/270°/transpose/transverse orientation the two have
        // their axes swapped, so sizing the decode from the stored bounds
        // measures the retained region along the wrong axis. On the ordinary
        // portrait phone photo (stored 4000x3000 + ROTATE_90) a 16:9 crop then
        // decodes at 1/4 instead of 1/2 and the upload lands 750px on its
        // longest side — under the maxDimension/2 bar. Swap first.
        val orientation = readExifOrientation(bytes)
        val swapsAxes = orientationSwapsAxes(orientation)
        val orientedWidth = if (swapsAxes) bounds.outHeight else bounds.outWidth
        val orientedHeight = if (swapsAxes) bounds.outWidth else bounds.outHeight

        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize =
                    sampleSizeForCrop(orientedWidth, orientedHeight, crop, maxDimension)
            }
        val decoded =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
        return applyExifOrientation(decoded, orientation)
    }

    /**
     * True when [orientation] exchanges the image's width and height — the
     * quarter-turn orientations plus the two that combine a quarter turn with a
     * flip. Anything else (normal, 180°, pure mirrors) preserves the axes.
     *
     * Internal (not private) purely so it is unit-testable.
     */
    internal fun orientationSwapsAxes(orientation: Int): Boolean =
        orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE

    /**
     * The EXIF orientation tag of [bytes], or [ExifInterface.ORIENTATION_NORMAL]
     * when it is absent or unreadable. Read once per decode and threaded through
     * so the sample-size decision and the rotation cannot disagree.
     */
    private fun readExifOrientation(bytes: ByteArray): Int =
        runCatching {
            ByteArrayInputStream(bytes).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * Cuts [crop] out of [bitmap], recycling the source once the cropped copy
     * exists. Returns [bitmap] unchanged for a null or full-frame crop, and null
     * when the rect cannot be resolved to a valid pixel window — in which case
     * the caller must fail closed rather than upload the uncropped frame, since
     * the crop may have been the user removing something identifying.
     *
     * [bitmap] is NOT recycled on the null path: the caller still owns it.
     */
    private fun applyCrop(bitmap: Bitmap, crop: NormalizedCropRect?): Bitmap? {
        if (crop == null || crop.isFullFrame()) return bitmap
        val pixels = crop.toPixels(bitmap.width, bitmap.height) ?: return null
        val cropped =
            Bitmap.createBitmap(bitmap, pixels.x, pixels.y, pixels.width, pixels.height)
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    /**
     * Last line of defence for a pick that could NOT be re-encoded (corrupt /
     * unusual image, or the decode threw). Because [compressForPublicUpload]
     * promises bytes free of every tag in [STRIP_TAGS], we must never upload the
     * raw bytes unless they are provably free of EVERY strip tag, so:
     *  1. physically rewrite the JPEG dropping the [STRIP_TAGS] (works even when
     *     the pixels are undecodable — [ExifInterface] parses the APP1 block),
     *     else
     *  2. keep the original ONLY when it provably carries no [STRIP_TAGS] entry
     *     at all (no GPS AND no identifying tags) AND already advertises an
     *     allowed image content type, else
     *  3. FAIL CLOSED (null) — a pick carrying any strip tag, whose EXIF is
     *     unparseable, or whose content type is unknown/unsupported is dropped,
     *     never uploaded.
     *
     * The content-type guard matters because [PickedImage.contentType] comes
     * straight from `ContentResolver.getType(uri)`, which can be null (or a
     * non-image type). Returning such an original would look like success to the
     * caller yet be rejected by [MediaUpload.precheck] (which requires an
     * allowed image type), producing a confusing "upload failed" downstream — so
     * we reject it here instead. Both re-encode and [stripInPlace] paths always
     * emit `image/jpeg`, so a non-null result is always precheck-valid.
     *
     * `suspend` because [stripInPlace] performs blocking disk I/O on
     * [Dispatchers.IO]; the in-memory [carriesStrippableMetadata] parse stays on
     * the calling (Default) dispatcher.
     */
    private suspend fun stripOrFail(picked: PickedImage): PickedImage? {
        stripInPlace(picked)?.let { return it }
        val provablyClean =
            !carriesStrippableMetadata(picked.bytes) &&
                MediaUpload.isAllowedImageType(picked.contentType)
        return if (provablyClean) picked else null
    }

    /**
     * Physically strips the [STRIP_TAGS] from JPEG [picked] by rewriting it
     * through a temp file (the only way [android.media.ExifInterface] can persist
     * edits — it has no in-memory save). Returns the cleaned JPEG, or null when
     * the bytes are not a strippable JPEG or anything goes wrong (temp dir not
     * writable, save unsupported), so the caller can fall back to fail-closed.
     *
     * The JPEG decision is made from the actual bytes ([looksLikeJpeg] — the
     * JPEG SOI followed by the start of the next marker, i.e. the `FF D8 FF`
     * prefix; not an APP0/JFIF check), NOT [PickedImage.contentType]:
     * `ContentResolver.getType`
     * can misreport the type (a real JPEG typed `null` / `image/jpg` /
     * `application/octet-stream`), and this is the safety net for exactly those
     * undecodable/odd picks, so it must key off the format on disk. The post-
     * rewrite [carriesStrippableMetadata] check still gates the result, so even a
     * misdetection can only fail closed, never leak un-stripped bytes.
     *
     * The temp-file dance (create / write / [ExifInterface] save / read / delete)
     * is blocking disk I/O, so it runs on [Dispatchers.IO] rather than occupying
     * a CPU worker on the caller's [Dispatchers.Default] pool (matters on
     * low-core devices). The cheap [looksLikeJpeg] magic-byte check stays off the
     * IO pool.
     */
    private suspend fun stripInPlace(picked: PickedImage): PickedImage? {
        // ExifInterface.saveAttributes() only rewrites JPEG; other formats either
        // carry no EXIF (png/gif) or are not writable here — let the caller decide.
        if (!looksLikeJpeg(picked.bytes)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File.createTempFile("kcc-strip-", ".jpg")
                try {
                    tmp.writeBytes(picked.bytes)
                    val exif = ExifInterface(tmp.absolutePath)
                    STRIP_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
                    exif.saveAttributes()
                    val cleaned = tmp.readBytes()
                    // Verify the rewrite actually removed EVERY strip tag (GPS and
                    // identifying) before trusting it.
                    if (cleaned.isEmpty() || carriesStrippableMetadata(cleaned)) {
                        null
                    } else {
                        PickedImage(bytes = cleaned, contentType = "image/jpeg")
                    }
                } finally {
                    tmp.delete()
                }
            }.getOrNull()
        }
    }

    /**
     * True when [bytes] begin with the JPEG SOI + marker magic (`FF D8 FF`).
     * Used instead of the reported MIME type to decide whether [stripInPlace] can
     * rewrite the pick, so a real JPEG whose `contentType` is null / `image/jpg` /
     * `application/octet-stream` is still stripped rather than needlessly dropped.
     *
     * Internal (not private) purely so it is JVM-unit-testable without a device.
     */
    internal fun looksLikeJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

    /**
     * Conservative, fail-closed metadata gate for [stripOrFail]/[stripInPlace].
     * Returns true when [bytes] MIGHT still carry any tag we strip for a public
     * upload, so a caller only treats a `false` here as "provably free of every
     * strip tag". Because [compressForPublicUpload] guarantees bytes free of every
     * [STRIP_TAGS] entry (not just no GPS), the gate keys off the FULL [STRIP_TAGS]
     * set — GPS IFD *and* identifying tags (make/model/timestamps/user comment/…).
     * Two
     * deliberate choices keep it from ever passing un-sanitised bytes through:
     *  - if the EXIF cannot be parsed we assume it COULD carry metadata (an
     *    unparseable file must never be treated as clean); and
     *  - ANY strip tag counts — a JPEG with, say, only a device make/model or a
     *    capture timestamp is NOT clean even though it has no GPS.
     *
     * Internal (not private) purely so the instrumented tests can assert the
     * fail-closed gate against real [ExifInterface] parsing without a device rig.
     */
    internal fun carriesStrippableMetadata(bytes: ByteArray): Boolean {
        val exif =
            runCatching { ByteArrayInputStream(bytes).use { ExifInterface(it) } }.getOrNull()
                ?: return true // EXIF unparseable → cannot prove clean → fail closed
        return STRIP_TAGS.any { tag -> exif.getAttribute(tag) != null }
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

    /**
     * [sampleSizeFor], but measured against the region that SURVIVES [crop]
     * rather than the whole frame — because only that region is uploaded.
     *
     * Sizing the decode by the frame is the bug this exists to avoid: sampling
     * is a property of the pixels you KEEP. A 8000x1000 panorama cropped to
     * 16:9 keeps a 1778x1000 region; sampled by the frame (longest side 8000)
     * that decodes at 1/8 and the crop yields a 222px-wide upload — soft and
     * tiny — even though the source held 1778px of it. Sampled by the region it
     * decodes at 1/2 and yields 889px, the same "at least half of
     * [maxDimension]" bar the uncropped path has always met.
     *
     * Deliberately NOT `maxDimension / min(crop.width, crop.height)`: the needed
     * decode depends on the SOURCE's aspect too, and that form ignores it — on a
     * 12000x9000 source at maximum zoom it asks for a full-resolution 108 Mpx
     * decode (~411 MB at ARGB_8888, a near-certain OOM) where sizing by the
     * region asks for 27 Mpx.
     *
     * The result is then floored by [MAX_DECODE_PIXELS], since a decode sized
     * for the region still materialises the whole frame.
     *
     * Internal (not private) purely so it is JVM-unit-testable without a device.
     */
    internal fun sampleSizeForCrop(
        sourceWidth: Int,
        sourceHeight: Int,
        crop: NormalizedCropRect?,
        maxDimension: Int,
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxDimension <= 0) return 1
        val window = crop?.takeIf { it.isValid() } ?: NormalizedCropRect.FULL
        val regionWidth = (window.width * sourceWidth).toInt().coerceAtLeast(1)
        val regionHeight = (window.height * sourceHeight).toInt().coerceAtLeast(1)

        var sample = sampleSizeFor(regionWidth, regionHeight, maxDimension)
        // The decode covers the FULL frame even though the sample size was
        // chosen for the region, so bound what that can allocate.
        val totalPixels = sourceWidth.toLong() * sourceHeight.toLong()
        while (totalPixels / (sample.toLong() * sample.toLong()) > MAX_DECODE_PIXELS) {
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

    /**
     * Rotates [bitmap] by an ARBITRARY [angleDeg] about its centre — the free
     * angle the gesture editor produces — returning a new bitmap sized to the
     * rotated content's axis-aligned bounding box (the source is recycled once the
     * rotated copy exists). A near-zero angle is a no-op and returns [bitmap]
     * unchanged, so the ordinary un-rotated crop path never resamples here.
     *
     * The bounding box this produces is exactly the space [ImageEditGeometry]
     * resolves the crop rect against: `Rw = |cos|·w + |sin|·h`,
     * `Rh = |sin|·w + |cos|·h`. `filter = true` for a smooth rotate.
     */
    private fun rotateArbitrary(bitmap: Bitmap, angleDeg: Float): Bitmap {
        if (!angleDeg.isFinite() || abs(angleDeg) <= ROTATION_EPSILON) return bitmap
        val matrix = Matrix().apply { postRotate(angleDeg) }
        val rotated =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * Rotates/flips [bitmap] to match [orientation] (from [readExifOrientation]).
     *
     * Takes the already-read tag rather than re-parsing the bytes so this and
     * [orientationSwapsAxes] can never disagree about which orientation is in
     * play — the sample size is chosen from the same value that decides the
     * rotation.
     */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
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

    /**
     * Every GPS EXIF tag. The presence of ANY of these means the pick embeds
     * location (or location-adjacent) data — we deliberately do NOT try to decide
     * which GPS fields are "harmless". Covers primary coordinates, destination
     * coordinates, and the rest of the GPS IFD. Reused as the GPS half of
     * [STRIP_TAGS], which [carriesStrippableMetadata] gates the fallback on.
     */
    private val GPS_TAGS =
        listOf(
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
        )

    /** Non-GPS identifying tags cleared alongside [GPS_TAGS] when stripping. */
    private val IDENTIFYING_TAGS =
        listOf(
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

    /**
     * EXIF tags cleared when physically stripping a fallback JPEG: every GPS tag
     * (location, precise to metres) plus common identifying tags (device make /
     * model / serial, capture timestamps, author). Setting each to null and
     * calling saveAttributes() removes it from the rewritten file.
     */
    private val STRIP_TAGS = GPS_TAGS + IDENTIFYING_TAGS
}
