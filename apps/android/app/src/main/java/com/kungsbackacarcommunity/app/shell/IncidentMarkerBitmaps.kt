package com.kungsbackacarcommunity.app.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Builds the incident marker images the map draws — a category glyph on a
 * coloured disc, inside the two-tone ring described by [IncidentMarkerStyle].
 *
 * Mapbox annotations take a raster `Bitmap` registered as a style image; there
 * is no vector path. The app's icons are vector drawables (so one asset scales
 * to every density), so they are inflated and rasterised here, once per
 * category, at the device's real density.
 *
 * ## Draw order (outside in)
 *
 * 1. [IncidentMarkerStyle.RING_DARK] hairline — the outermost edge, which is
 *    what separates the marker from a LIGHT basemap.
 * 2. [IncidentMarkerStyle.RING_LIGHT] ring — separates it from a DARK basemap.
 * 3. The category disc — washed out over the light ring when the incident has
 *    been reported gone (see below).
 * 4. The glyph, tinted for contrast against that disc.
 * 5. On a reported-gone marker only, a diagonal strike-through bar.
 *
 * The two rings are why ONE image works on both the day and night basemaps; see
 * [IncidentMarkerStyle]'s KDoc for the measured contrast and why per-mode icon
 * sets would not survive this map's day/night mechanism anyway.
 *
 * ## The "reported gone" state
 *
 * An incident somebody has voted gone — but which has not reached the backend's
 * removal threshold — is still drawn, dimmed. Two things make that legible
 * rather than merely faint, and neither is hue:
 *
 *  - the disc is composited at [IncidentMarkerStyle.CLEARED_DISC_ALPHA] over the
 *    marker's OWN opaque light ring, which is already painted underneath it by
 *    step 2. That is why the wash is deterministic: it never touches the
 *    basemap, so the same bitmap reads identically on the day and night maps and
 *    the resulting colour is the exact one the unit tests measure; and
 *  - a diagonal bar is struck across the badge, which is a SHAPE difference and
 *    therefore survives any colour-vision deficiency — and, unlike lightness, is
 *    readable without a normal marker beside it to compare against.
 *
 * Both rings stay fully opaque, so a questioned marker is exactly as easy to
 * FIND on either basemap as a normal one. Dimming a hazard into invisibility is
 * the one outcome this design will not accept.
 */
internal object IncidentMarkerBitmaps {
    /**
     * A stable style-image name for one category's marker.
     *
     * Keyed on everything that changes the pixels — the glyph and both colours —
     * so two categories can never collide on one name, and a palette change
     * produces a new image rather than silently reusing the old one.
     */
    fun imageId(
        @DrawableRes iconRes: Int,
        discColorArgb: Int,
        glyphColorArgb: Int,
        reportedCleared: Boolean = false,
    ): String =
        "kcc-incident-$iconRes-$discColorArgb-$glyphColorArgb" +
            if (reportedCleared) "-cleared" else ""

    /**
     * Rasterises one marker. Returns null if the drawable cannot be inflated, so
     * a missing/!broken asset degrades to "this marker is not drawn" rather than
     * crashing the map — the same defensive posture as the rest of this surface.
     */
    fun create(
        context: Context,
        @DrawableRes iconRes: Int,
        discColorArgb: Int,
        glyphColorArgb: Int,
        reportedCleared: Boolean = false,
    ): Bitmap? {
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = dp * density

        val discDiameter = px(IncidentMarkerStyle.DISC_DIAMETER_DP)
        val lightRing = px(IncidentMarkerStyle.RING_LIGHT_WIDTH_DP)
        val darkRing = px(IncidentMarkerStyle.RING_DARK_WIDTH_DP)
        // Both rings sit OUTSIDE the disc, so the image is the disc plus twice
        // each ring width.
        val size = discDiameter + 2f * (lightRing + darkRing)
        // CEIL, never round: `size` is a dp→px float, so rounding down would
        // give a bitmap fractionally smaller than the geometry drawn into it and
        // clip the outermost hairline — which is the element carrying contrast
        // against a light basemap. Losing it to a rounding artefact at some
        // densities would quietly undo the legibility guarantee.
        val sizePx = ceil(size).toInt().coerceAtLeast(1)

        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val centre = sizePx / 2f
        // Radii are derived from the bitmap's ACTUAL size rather than the float
        // it was rounded up from, so the centre and the outer edge can never
        // disagree about where the edge is.
        val outerRadius = sizePx / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Outer dark hairline (carries a light basemap).
        paint.color = IncidentMarkerStyle.RING_DARK
        canvas.drawCircle(centre, centre, outerRadius, paint)

        // 2. Light ring (carries a dark basemap).
        paint.color = IncidentMarkerStyle.RING_LIGHT
        canvas.drawCircle(centre, centre, outerRadius - darkRing, paint)

        // 3. Category disc. The caller passes the ALREADY-COMPOSITED colour for a
        // reported-gone marker (IncidentMarkerStyle.discColorArgb), so this stays
        // one opaque fill either way — the wash is computed in the pure,
        // unit-tested style object rather than by handing Canvas an alpha here
        // and hoping the result matches what the tests measured.
        paint.color = discColorArgb
        canvas.drawCircle(centre, centre, discDiameter / 2f, paint)

        // 4. Glyph, tinted for contrast against the disc it sits on.
        val drawable =
            runCatching { ContextCompat.getDrawable(context, iconRes) }.getOrNull()
                ?: return null
        // Mutate before tinting: drawables share a constant state, so tinting the
        // shared instance would recolour every other user of this resource.
        val glyph = drawable.mutate()
        val glyphSize = discDiameter * IncidentMarkerStyle.GLYPH_SCALE
        val half = glyphSize / 2f
        glyph.setBounds(
            (centre - half).roundToInt(),
            (centre - half).roundToInt(),
            (centre + half).roundToInt(),
            (centre + half).roundToInt(),
        )
        glyph.setColorFilter(glyphColorArgb, PorterDuff.Mode.SRC_IN)
        glyph.draw(canvas)

        // 5. The strike-through, LAST so it reads over both the disc and the
        // glyph — the non-colour channel that says "someone reports this is
        // gone". Clipped to the disc so it never crosses the rings that carry
        // the marker's edge against the basemap.
        if (reportedCleared) {
            canvas.save()
            val clip =
                Path().apply {
                    addCircle(centre, centre, discDiameter / 2f, Path.Direction.CW)
                }
            canvas.clipPath(clip)
            paint.color = IncidentMarkerStyle.CLEARED_SLASH_COLOR
            paint.strokeWidth = discDiameter * IncidentMarkerStyle.CLEARED_SLASH_WIDTH_SCALE
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.STROKE
            val reach = discDiameter * IncidentMarkerStyle.CLEARED_SLASH_LENGTH_SCALE / 2f
            val offset = reach / sqrtTwo
            canvas.drawLine(
                centre - offset,
                centre + offset,
                centre + offset,
                centre - offset,
                paint,
            )
            canvas.restore()
        }
        return bitmap
    }

    /**
     * Half the diagonal of a unit square — the factor that turns the slash's
     * LENGTH into equal horizontal and vertical offsets from the centre, so the
     * bar is 45 degrees whatever size the badge is in pixels.
     */
    private val sqrtTwo = sqrt(2f)
}
