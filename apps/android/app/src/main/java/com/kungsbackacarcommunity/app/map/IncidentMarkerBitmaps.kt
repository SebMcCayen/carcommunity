package com.kungsbackacarcommunity.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle

/**
 * Builds (and caches) the bitmap for one incident map marker: the three-band
 * badge described by [IncidentMarkerStyle] with the category glyph punched out
 * in white on top.
 *
 * Bitmaps rather than Compose: the markers are Mapbox `PointAnnotation`s living
 * inside the GL surface, which takes an image, not a composable.
 *
 * CACHING MATTERS HERE. Trafikverket roadwork is about to arrive in volume, so a
 * single viewport can hold dozens of markers — but only ever FIVE distinct
 * images, one per category. The cache is keyed on the (glyph, colour, density)
 * triple that fully determines the pixels, so a screen full of roadwork
 * rasterises one bitmap and reuses it, instead of allocating one per pin on
 * every redraw.
 */
object IncidentMarkerBitmaps {
    private val cache = HashMap<Key, Bitmap>()

    private data class Key(val iconRes: Int, val colorArgb: Int, val densityDpi: Int)

    /**
     * The marker image for [iconRes] on a [colorArgb] badge, at [context]'s
     * density. Returns null if the glyph cannot be loaded, so a missing drawable
     * degrades to "this one marker is not drawn" rather than crashing the map.
     */
    fun marker(context: Context, @DrawableRes iconRes: Int, colorArgb: Int): Bitmap? {
        val densityDpi = context.resources.displayMetrics.densityDpi
        val key = Key(iconRes, colorArgb, densityDpi)
        cache[key]?.let { return it }
        val bitmap = render(context, iconRes, colorArgb) ?: return null
        cache[key] = bitmap
        return bitmap
    }

    private fun render(context: Context, @DrawableRes iconRes: Int, colorArgb: Int): Bitmap? {
        val size = dpToPx(context, IncidentMarkerStyle.DIAMETER_DP).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // Band 1 — outer near-black disc. Only its rim is left visible by the
        // bands drawn over it; that rim is what holds the marker apart from the
        // pale DAY basemap.
        paint.color = IncidentMarkerStyle.OUTER_RING_COLOR
        canvas.drawCircle(centre, centre, centre, paint)

        // Band 2 — white disc, inset by the outer ring. Its visible rim is what
        // holds the marker apart from the dark NIGHT basemap. Between the two,
        // one band is always in high contrast with whatever is behind it.
        val whiteRadius = centre - dpToPxF(context, IncidentMarkerStyle.OUTER_RING_DP)
        paint.color = IncidentMarkerStyle.WHITE
        canvas.drawCircle(centre, centre, whiteRadius.coerceAtLeast(0f), paint)

        // Band 3 — the category fill, carrying the hue. Forced fully opaque: a
        // translucent fill would blend with the basemap and make the same
        // category look like two different ones on day vs night.
        val fillRadius = whiteRadius - dpToPxF(context, IncidentMarkerStyle.WHITE_RING_DP)
        paint.color = opaque(colorArgb)
        canvas.drawCircle(centre, centre, fillRadius.coerceAtLeast(0f), paint)

        // The glyph, tinted white, centred on the badge. Tinting rather than
        // trusting the drawable's own fill keeps every glyph on one colour even
        // if a future drawable is authored in some other one.
        val drawable =
            ContextCompat.getDrawable(context, iconRes)?.mutate() ?: return null
        DrawableCompat.setTint(drawable, IncidentMarkerStyle.WHITE)
        val glyphSize = size * IncidentMarkerStyle.GLYPH_FRACTION
        val half = glyphSize / 2f
        drawable.setBounds(
            (centre - half).toInt(),
            (centre - half).toInt(),
            (centre + half).toInt(),
            (centre + half).toInt(),
        )
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Forces full alpha on a category colour. The palette is already opaque, but
     * the value arrives across the shell seam as a plain Int, so this makes the
     * marker's opacity a property of the marker rather than a trust in its
     * caller.
     */
    private fun opaque(colorArgb: Int): Int =
        Color.argb(
            255,
            Color.red(colorArgb),
            Color.green(colorArgb),
            Color.blue(colorArgb),
        )

    private fun dpToPxF(context: Context, dp: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )

    private fun dpToPx(context: Context, dp: Float): Int = dpToPxF(context, dp).toInt()
}
