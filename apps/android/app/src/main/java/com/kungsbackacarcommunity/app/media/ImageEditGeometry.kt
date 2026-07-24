package com.kungsbackacarcommunity.app.media

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure, Android-free geometry behind the shared gesture image editor
 * ([ImageEditScreen]). This maths decides WHICH PIXELS get uploaded — and, with
 * free rotation in play, whether a rotated image ever leaves an empty triangular
 * corner inside the crop frame — so it lives here as fast JVM-testable functions
 * rather than only inside the on-device Compose UI.
 *
 * The UI model (see [ImageEditScreen]): a FIXED, axis-aligned crop frame is
 * painted on screen and the image moves beneath it under a single continuous
 * gesture — pan + pinch-zoom + two-finger free rotation, all at once. The image
 * is drawn with `graphicsLayer { scaleX = scaleY = s; rotationZ = angle;
 * translationX/Y = pan }` about its centre, so the display transform of an
 * image-local point p (origin at the image centre) is:
 *
 *     screen(p) = R(angle) · (s · p) + pan
 *
 * a similarity transform (uniform scale + rotation + translation), which is
 * exactly invertible. Everything below is derived from that one transform.
 *
 * Two coordinate frames matter:
 *  - the ORIENTED source image (EXIF applied), the space [NormalizedCropRect] is
 *    measured in for an un-rotated crop; and
 *  - the ROTATED source (the oriented source turned by `angle`), whose
 *    axis-aligned bounding box is what [ImageCompressor] actually cuts the crop
 *    out of. Because the on-screen frame is axis-aligned and the image shares the
 *    same rotation, the frame maps to an AXIS-ALIGNED rectangle in the rotated
 *    source — so the editor resolves to an `(angle, NormalizedCropRect)` pair and
 *    the compressor rotates once, then crops that rect.
 */
object ImageEditGeometry {

    /**
     * Lower bound on the absolute zoom multiple (relative to the no-rotation
     * cover scale). The frame can never be MORE zoomed-out than the cover scale,
     * so 1f is the floor at 0°; [minCoverScale] raises the effective floor as the
     * image is twisted.
     */
    const val MIN_ZOOM: Float = 1f

    /** How far past the cover scale the user may zoom in before pixels run out. */
    const val MAX_ZOOM: Float = 6f

    /**
     * The absolute scale (screen px per image px) at which an image of half-
     * extents ([imageHalfW], [imageHalfH]) exactly COVERS an axis-aligned frame of
     * half-extents ([frameHalfW], [frameHalfH]) when the image is rotated by
     * [angleDeg] about its centre — i.e. the frame is fully inside the rotated,
     * scaled image with no empty corner.
     *
     * Derivation: transform the frame into the image's own (un-rotated) axes by
     * rotating it by −angle; the axis-aligned frame becomes a rotated rectangle
     * whose half-extents along the image axes are `(c·Fx + s·Fy, s·Fx + c·Fy)`
     * with `c = |cos|`, `s = |sin|`. Requiring each to fit inside the image's
     * half-extent and solving for the scale gives the max of the two ratios.
     *
     * Returns 0f for a degenerate image (not yet measured), which callers treat as
     * "no transform yet".
     */
    fun coverScaleForFrame(
        angleDeg: Float,
        frameHalfW: Float,
        frameHalfH: Float,
        imageHalfW: Float,
        imageHalfH: Float,
    ): Float {
        if (imageHalfW <= 0f || imageHalfH <= 0f || frameHalfW <= 0f || frameHalfH <= 0f) return 0f
        val c = abs(cos(Math.toRadians(angleDeg.toDouble()))).toFloat()
        val s = abs(sin(Math.toRadians(angleDeg.toDouble()))).toFloat()
        val needX = (c * frameHalfW + s * frameHalfH) / imageHalfW
        val needY = (s * frameHalfW + c * frameHalfH) / imageHalfH
        return max(needX, needY)
    }

    /**
     * The minimum zoom — expressed as a MULTIPLE of the no-rotation cover scale —
     * required to keep a frame of aspect [frameAspect] fully covered by an image
     * of aspect [imageAspect] rotated by [angleDeg].
     *
     * This is the crux of the "no empty corner" guarantee: as the image twists,
     * the corners of the axis-aligned frame poke toward the image's own corners,
     * so the image must grow to keep them inside. The result is 1f at 0° (the
     * cover scale already fills the frame) and rises toward its peak near 45°,
     * where the frame's diagonal reach into the rotated image is greatest. For a
     * SQUARE frame-and-image the curve is symmetric about 45° and does repeat
     * every 90°; but for NON-SQUARE aspects a 90° turn swaps which axis binds the
     * cover (width↔height), so the value at θ and θ+90° differ — it is NOT
     * generally 90°-periodic and only returns to itself at 180°.
     *
     * Scale-invariant, so it depends only on the two aspect ratios: the frame is
     * modelled with half-extents `(frameAspect, 1)` and the image `(imageAspect,
     * 1)`. Returns 1f for a degenerate aspect.
     */
    fun minCoverScale(angleDeg: Float, frameAspect: Float, imageAspect: Float): Float {
        if (frameAspect <= 0f || imageAspect <= 0f ||
            !frameAspect.isFinite() || !imageAspect.isFinite()
        ) {
            return 1f
        }
        val at0 = coverScaleForFrame(0f, frameAspect, 1f, imageAspect, 1f)
        if (at0 <= 0f) return 1f
        val atAngle = coverScaleForFrame(angleDeg, frameAspect, 1f, imageAspect, 1f)
        // Never report below MIN_ZOOM: the frame cannot be less covered than the
        // no-rotation cover, and float noise near 0° must not dip under 1f.
        return max(MIN_ZOOM, atAngle / at0)
    }

    /**
     * Clamps the image-centre offset [dX]/[dY] — the vector from the image centre
     * to the FRAME centre, in screen px, in the rotated (screen-aligned) space —
     * so the frame stays fully inside the rotated, scaled image (no empty corner
     * can be panned into view).
     *
     * [imageScaledHalfW]/[imageScaledHalfH] are the image's half-extents at the
     * current scale, in screen px. When the image is too small on an axis to cover
     * the frame there (the caller has not yet enforced [coverScaleForFrame]) the
     * offset is centred on that axis — a gap cannot be panned away, so pinning it
     * to an edge would only look broken.
     */
    fun clampImageOffset(
        angleDeg: Float,
        dX: Float,
        dY: Float,
        frameHalfW: Float,
        frameHalfH: Float,
        imageScaledHalfW: Float,
        imageScaledHalfH: Float,
    ): Pair<Float, Float> {
        if (!dX.isFinite() || !dY.isFinite()) return 0f to 0f
        val rad = Math.toRadians(angleDeg.toDouble())
        val cos = cos(rad).toFloat()
        val sin = sin(rad).toFloat()
        val c = abs(cos)
        val s = abs(sin)

        // Frame centre expressed in the image's own (un-rotated) axes: rotate the
        // offset by −angle.
        val localX = cos * dX + sin * dY
        val localY = -sin * dX + cos * dY

        // Half-extent of the (rotated) frame along each image axis.
        val frameExtentX = c * frameHalfW + s * frameHalfH
        val frameExtentY = s * frameHalfW + c * frameHalfH

        val limitX = imageScaledHalfW - frameExtentX
        val limitY = imageScaledHalfH - frameExtentY

        val clampedLocalX = if (limitX <= 0f) 0f else localX.coerceIn(-limitX, limitX)
        val clampedLocalY = if (limitY <= 0f) 0f else localY.coerceIn(-limitY, limitY)

        // Rotate the clamped offset back into screen (rotated) space.
        val outX = cos * clampedLocalX - sin * clampedLocalY
        val outY = sin * clampedLocalX + cos * clampedLocalY
        return outX to outY
    }

    /**
     * Resolves the current display transform into the axis-aligned crop rectangle
     * inside the ROTATED source, as a [NormalizedCropRect] (fractions of the
     * rotated source's bounding box). Pair this with the same `angle` and hand
     * both to [ImageCompressor.compressForPublicUpload], which rotates the source
     * by `angle` and then cuts this rect out.
     *
     * All lengths are in ONE consistent unit (screen px in the UI; the result is
     * unit-invariant because it is normalized):
     *  - [dX]/[dY]: FRAME centre relative to the IMAGE centre, in the rotated
     *    (screen-aligned) space — i.e. `frameCentre − pan`.
     *  - [frameHalfW]/[frameHalfH]: the crop frame's half-extents.
     *  - [imageScaledHalfW]/[imageScaledHalfH]: the image's half-extents at the
     *    current scale.
     *
     * Because the frame is axis-aligned in the SAME rotated space as the image,
     * the map from screen to rotated-source is a pure scale+translate (the shared
     * rotation cancels), so the frame is an axis-aligned rectangle there. The
     * rotated bounding box has half-extents `(c·iw + s·ih, s·iw + c·ih)`.
     *
     * Returns [NormalizedCropRect.FULL] for any degenerate input, and coerces the
     * rect inside the unit square, falling back to FULL if it still is not valid —
     * "no measurable crop" must upload the whole image, never a NaN rect.
     */
    fun resolveCrop(
        angleDeg: Float,
        dX: Float,
        dY: Float,
        frameHalfW: Float,
        frameHalfH: Float,
        imageScaledHalfW: Float,
        imageScaledHalfH: Float,
    ): NormalizedCropRect {
        if (imageScaledHalfW <= 0f || imageScaledHalfH <= 0f ||
            frameHalfW <= 0f || frameHalfH <= 0f ||
            !dX.isFinite() || !dY.isFinite() || !angleDeg.isFinite()
        ) {
            return NormalizedCropRect.FULL
        }
        val c = abs(cos(Math.toRadians(angleDeg.toDouble()))).toFloat()
        val s = abs(sin(Math.toRadians(angleDeg.toDouble()))).toFloat()
        val rhx = c * imageScaledHalfW + s * imageScaledHalfH
        val rhy = s * imageScaledHalfW + c * imageScaledHalfH
        if (rhx <= 0f || rhy <= 0f) return NormalizedCropRect.FULL

        // Frame rectangle in the rotated source, origin at the bbox centre, then
        // shifted to a top-left origin (+rhx, +rhy) and normalized by the full
        // bbox extents (2·rhx, 2·rhy).
        val left = (dX + rhx - frameHalfW) / (2f * rhx)
        val top = (dY + rhy - frameHalfH) / (2f * rhy)
        val width = frameHalfW / rhx
        val height = frameHalfH / rhy

        val clampedLeft = left.coerceIn(0f, 1f)
        val clampedTop = top.coerceIn(0f, 1f)
        val rect =
            NormalizedCropRect(
                left = clampedLeft,
                top = clampedTop,
                width = width.coerceIn(0f, 1f - clampedLeft),
                height = height.coerceIn(0f, 1f - clampedTop),
            )
        return if (rect.isValid()) rect else NormalizedCropRect.FULL
    }

    /**
     * The output pixel dimensions for a cropped region of [cropWidthPx] x
     * [cropHeightPx] source pixels once [ImageCompressor] scales it so its LONGEST
     * side is at most [maxDimension] (never up-scaling). Exactly ONE dimension is
     * capped and the other is derived from the crop's own aspect, so the output
     * aspect equals the crop frame's aspect — the anti-stretch rule. This mirrors
     * [ImageCompressor]'s `scaleToMax` and exists so the output-dimension contract
     * is unit-testable without a device.
     *
     * Returns `(0, 0)` for a degenerate crop.
     */
    fun outputDimensions(cropWidthPx: Int, cropHeightPx: Int, maxDimension: Int): Pair<Int, Int> {
        if (cropWidthPx <= 0 || cropHeightPx <= 0 || maxDimension <= 0) return 0 to 0
        val longest = max(cropWidthPx, cropHeightPx)
        if (longest <= maxDimension) return cropWidthPx to cropHeightPx
        val ratio = maxDimension.toFloat() / longest
        val w = (cropWidthPx * ratio).roundToInt().coerceAtLeast(1)
        val h = (cropHeightPx * ratio).roundToInt().coerceAtLeast(1)
        return w to h
    }
}
