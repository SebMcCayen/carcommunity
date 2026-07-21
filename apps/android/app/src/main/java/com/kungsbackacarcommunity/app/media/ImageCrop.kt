package com.kungsbackacarcommunity.app.media

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A crop window expressed in NORMALIZED source coordinates: `0f..1f` fractions
 * of the (EXIF-oriented) source image's width and height, origin top-left.
 *
 * Normalized rather than pixels ON PURPOSE. The crop UI works on a DOWNSCALED
 * preview bitmap (a full-resolution phone photo would not fit in memory at
 * display size), while the crop is APPLIED to a separately-decoded bitmap inside
 * [ImageCompressor], possibly at a different sample size. A pixel rect measured
 * against the preview would silently mis-crop the real decode; a fraction is
 * resolution-independent, so both agree by construction.
 *
 * Note what this type deliberately is NOT: image bytes. The crop UI hands back
 * ONE of these and nothing else, so there is no "cropped file" for an upload
 * path to pick up — every route from a picked image to Storage still runs
 * through [ImageCompressor.compressForPublicUpload], which is where EXIF/GPS
 * sanitisation lives. See [ImageCompressor]'s KDoc for that contract.
 */
data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    /**
     * True when this rect is a usable, finite window inside the unit square.
     * A degenerate or out-of-range rect (NaN from a divide-by-zero, a zero-width
     * window, a window running past the right edge) must never reach
     * [android.graphics.Bitmap.createBitmap], which throws on such input.
     */
    fun isValid(): Boolean =
        left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite() &&
            left >= 0f && top >= 0f && width > 0f && height > 0f &&
            left + width <= 1f + EPSILON && top + height <= 1f + EPSILON

    /**
     * Converts to integer pixels against a source of [sourceWidth] x
     * [sourceHeight], or null when this rect is not usable for that source.
     *
     * The rounded rect is clamped back inside the source: rounding each edge
     * independently can otherwise push `x + width` one pixel past the right edge
     * (e.g. left=0.9995 of 1601px), and `Bitmap.createBitmap` throws
     * IllegalArgumentException — a crash on an image whose only sin was an
     * awkward dimension. Width and height are floored to at least 1px for the
     * same reason: a very thin crop of a small source can round to zero.
     */
    fun toPixels(sourceWidth: Int, sourceHeight: Int): CropPixels? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || !isValid()) return null
        val x = (left * sourceWidth).roundToInt().coerceIn(0, sourceWidth - 1)
        val y = (top * sourceHeight).roundToInt().coerceIn(0, sourceHeight - 1)
        val w = (width * sourceWidth).roundToInt().coerceIn(1, sourceWidth - x)
        val h = (height * sourceHeight).roundToInt().coerceIn(1, sourceHeight - y)
        return CropPixels(x = x, y = y, width = w, height = h)
    }

    /** True when this rect selects (essentially) the whole source image. */
    fun isFullFrame(): Boolean =
        left <= EPSILON && top <= EPSILON && width >= 1f - EPSILON && height >= 1f - EPSILON

    companion object {
        /** Tolerance for float accumulation in the gesture → rect math. */
        const val EPSILON: Float = 1e-4f

        /** The whole source image, uncropped. */
        val FULL: NormalizedCropRect = NormalizedCropRect(0f, 0f, 1f, 1f)
    }
}

/** An integer pixel crop window, ready for `Bitmap.createBitmap`. */
data class CropPixels(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The output SHAPE options the user picks on the vehicle-photo crop screen.
 *
 * The crop box is drawn at the selected aspect ratio and the image moves beneath
 * it, so whichever option is chosen the crop stays a faithful, UN-STRETCHED cut
 * of the source (a single uniform scale is applied to both axes — see
 * [ImageCrop]); only the framing changes, never the proportions.
 *
 *  - [ORIGINAL] keeps the source's own ratio — no shape change at all, just the
 *    downscale + sanitisation every path applies.
 *  - [SQUARE] / [RATIO_4_3] / [RATIO_16_9] crop to that fixed ratio.
 *
 * [DEFAULT] is [SQUARE] because My Garage renders vehicle photos in a CIRCLE: a
 * circle clip of a square fills it edge-to-edge with no surprise centre-crop,
 * so the square option is what pairs cleanly with the round display.
 */
enum class CropAspect {
    ORIGINAL,
    SQUARE,
    RATIO_4_3,
    RATIO_16_9,
    ;

    /**
     * The crop box aspect ratio (width / height) for a source of
     * [sourceWidth] x [sourceHeight]. [ORIGINAL] returns the source's own ratio;
     * the fixed ratios ignore the source. Falls back to 1f for a degenerate
     * source so the box is always a measurable, finite ratio.
     */
    fun ratio(sourceWidth: Float, sourceHeight: Float): Float =
        when (this) {
            ORIGINAL ->
                if (sourceWidth > 0f && sourceHeight > 0f && sourceWidth.isFinite() &&
                    sourceHeight.isFinite()
                ) {
                    sourceWidth / sourceHeight
                } else {
                    1f
                }
            SQUARE -> 1f
            RATIO_4_3 -> 4f / 3f
            RATIO_16_9 -> ImageCrop.VEHICLE_ASPECT_RATIO
        }

    companion object {
        /** Default shape: pairs with the circular render in My Garage. */
        val DEFAULT: CropAspect = SQUARE
    }
}

/**
 * Pure geometry behind the vehicle-photo crop UI. Kept free of Android and
 * Compose types so the gesture maths — the part that actually decides which
 * pixels get uploaded — is covered by fast JVM unit tests rather than only by an
 * on-device test.
 *
 * The UI model: a FIXED-aspect crop box is painted on screen and the image moves
 * beneath it (zoom + pan), exactly like `ContentScale.Crop` with a user-supplied
 * transform. The image is laid out at [coverScale] x [zoom], so it always fills
 * the box and the crop window can never include empty space — the user cannot
 * produce a letterboxed photo.
 */
object ImageCrop {

    /**
     * The 16:9 crop option ([CropAspect.RATIO_16_9]) and the ratio the public
     * member-profile card still renders at. The crop screen no longer FORCES this
     * ratio — the user chooses a shape ([CropAspect]) — but 16:9 remains one of
     * the offered options and the widescreen reference ratio, so it is named here.
     */
    const val VEHICLE_ASPECT_RATIO: Float = 16f / 9f

    /** Fully zoomed out = the image exactly covers the crop box. */
    const val MIN_ZOOM: Float = 1f

    /** Far enough in to frame a detail; beyond this the source pixels run out. */
    const val MAX_ZOOM: Float = 5f

    /**
     * Scale at which an [imageWidth] x [imageHeight] image exactly COVERS a
     * [boxWidth] x [boxHeight] box (the larger of the two axis ratios), i.e.
     * `ContentScale.Crop`. Returns 0f for a degenerate input so callers can
     * treat "not laid out yet" as "no crop".
     */
    fun coverScale(
        imageWidth: Float,
        imageHeight: Float,
        boxWidth: Float,
        boxHeight: Float,
    ): Float {
        if (imageWidth <= 0f || imageHeight <= 0f || boxWidth <= 0f || boxHeight <= 0f) return 0f
        return max(boxWidth / imageWidth, boxHeight / imageHeight)
    }

    /**
     * Clamps a pan [offset] (top-left of the scaled image relative to the box,
     * so <= 0 when the image overhangs) so the scaled image never leaves a gap
     * inside the box. When the image is somehow SMALLER than the box on this
     * axis it is centred instead — a gap is impossible to pan away, so pinning
     * it to an edge would only look broken.
     */
    fun clampOffset(scaledSize: Float, boxSize: Float, offset: Float): Float {
        if (!offset.isFinite()) return 0f
        val minOffset = boxSize - scaledSize
        return if (minOffset >= 0f) minOffset / 2f else offset.coerceIn(minOffset, 0f)
    }

    /**
     * The offset that CENTRES a scaled image of [scaledSize] inside a box of
     * [boxSize] on one axis: `(boxSize - scaledSize) / 2`. Negative when the
     * image overhangs (so the box sits over the middle of the image) and positive
     * when it is smaller (a centred gap) — either way the midpoint.
     *
     * Used to seed the DEFAULT framing so a fresh crop (or a shape switch) starts
     * on the centre of the photo, not the top-left corner: [clampOffset] alone
     * leaves an overhanging image pinned at offset 0 (its top-left), which would
     * make the default confirmed window a corner-crop. The result is always inside
     * [clampOffset]'s valid range, so later pans/zooms clamp from a centred start.
     */
    fun centeredOffset(scaledSize: Float, boxSize: Float): Float =
        if (!scaledSize.isFinite() || !boxSize.isFinite()) 0f else (boxSize - scaledSize) / 2f

    /**
     * The portion of the source image currently visible inside the crop box,
     * as a [NormalizedCropRect].
     *
     * [offsetX]/[offsetY] are the scaled image's top-left relative to the box's
     * top-left, in the same pixel space as [boxWidth]/[boxHeight]; they are
     * clamped here too, so a caller that has not yet clamped its gesture state
     * still gets an in-bounds rect. Returns [NormalizedCropRect.FULL] for any
     * degenerate input (zero-size box before layout, zero-size image) — "no
     * measurable crop" must mean "upload the whole image", never a NaN rect.
     */
    fun visibleRect(
        imageWidth: Float,
        imageHeight: Float,
        boxWidth: Float,
        boxHeight: Float,
        zoom: Float,
        offsetX: Float,
        offsetY: Float,
    ): NormalizedCropRect {
        val base = coverScale(imageWidth, imageHeight, boxWidth, boxHeight)
        if (base <= 0f || !zoom.isFinite() || zoom <= 0f) return NormalizedCropRect.FULL
        val scale = base * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (scale <= 0f || !scale.isFinite()) return NormalizedCropRect.FULL

        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        val clampedX = clampOffset(scaledWidth, boxWidth, offsetX)
        val clampedY = clampOffset(scaledHeight, boxHeight, offsetY)

        // Box edges, expressed in SOURCE pixels, then normalized.
        val left = (-clampedX / scale) / imageWidth
        val top = (-clampedY / scale) / imageHeight
        val width = (boxWidth / scale) / imageWidth
        val height = (boxHeight / scale) / imageHeight

        val rect =
            NormalizedCropRect(
                left = left.coerceIn(0f, 1f),
                top = top.coerceIn(0f, 1f),
                // Never let float drift push the window past the source edge —
                // toPixels() would reject it and the crop would silently be lost.
                width = width.coerceIn(0f, 1f - left.coerceIn(0f, 1f)),
                height = height.coerceIn(0f, 1f - top.coerceIn(0f, 1f)),
            )
        return if (rect.isValid()) rect else NormalizedCropRect.FULL
    }
}
