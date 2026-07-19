package com.kungsbackacarcommunity.app.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle
import kotlin.math.roundToInt

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
 * 3. The category disc.
 * 4. The glyph, tinted for contrast against that disc.
 *
 * The two rings are why ONE image works on both the day and night basemaps; see
 * [IncidentMarkerStyle]'s KDoc for the measured contrast and why per-mode icon
 * sets would not survive this map's day/night mechanism anyway.
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
    ): String = "kcc-incident-$iconRes-$discColorArgb-$glyphColorArgb"

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
    ): Bitmap? {
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = dp * density

        val discDiameter = px(IncidentMarkerStyle.DISC_DIAMETER_DP)
        val lightRing = px(IncidentMarkerStyle.RING_LIGHT_WIDTH_DP)
        val darkRing = px(IncidentMarkerStyle.RING_DARK_WIDTH_DP)
        // Both rings sit OUTSIDE the disc, so the image is the disc plus twice
        // each ring width.
        val size = discDiameter + 2f * (lightRing + darkRing)
        val sizePx = size.roundToInt().coerceAtLeast(1)

        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val centre = sizePx / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Outer dark hairline (carries a light basemap).
        paint.color = IncidentMarkerStyle.RING_DARK
        canvas.drawCircle(centre, centre, size / 2f, paint)

        // 2. Light ring (carries a dark basemap).
        paint.color = IncidentMarkerStyle.RING_LIGHT
        canvas.drawCircle(centre, centre, size / 2f - darkRing, paint)

        // 3. Category disc.
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
        return bitmap
    }
}
