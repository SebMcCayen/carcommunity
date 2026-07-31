package com.kungsbackacarcommunity.app.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.kungsbackacarcommunity.app.billboards.BillboardMarkerStyle
import com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle
import kotlin.math.ceil

/**
 * Builds the sponsored-billboard marker image the map draws — a magenta plaque
 * on a short post, inside the same two-tone outline the incident and crown
 * badges use.
 *
 * The sibling of [IncidentMarkerBitmaps] and [CrownMarkerBitmaps], and a
 * SEPARATE object for the same reason those two are separate from each other:
 * this marker is not a disc at all, so there is no parameterisation of a
 * disc-and-glyph rasteriser that would produce it without turning that function
 * into a shape switch. The ring CONSTANTS are shared
 * ([IncidentMarkerStyle.RING_DARK] / [IncidentMarkerStyle.RING_LIGHT] and their
 * widths), because the light/dark-basemap contrast guarantee is the part that
 * must never diverge between layers.
 *
 * Unlike the other two this draws no vector drawable — the whole marker is
 * canvas geometry, so there is no asset to inflate and no null-drawable
 * degradation path. See [BillboardMarkerStyle] for why the shape rather than
 * the colour carries the meaning.
 *
 * ## Draw order (outside in)
 *
 * 1. The dark hairline silhouette — plaque and post — which is what separates
 *    the marker from a LIGHT basemap.
 * 2. The light silhouette inset by the hairline, separating it from a DARK one.
 * 3. The magenta plaque and the white post, inset by both.
 * 4. Two white bars inside the plaque, the abstraction of a message.
 *
 * The bitmap is anchored at the BOTTOM by the layer that places it, so the
 * post's foot is the billboard's coordinate.
 */
internal object BillboardMarkerBitmaps {
    /**
     * The style-image name.
     *
     * Namespaced `kcc-billboard-` so it cannot collide with `kcc-incident-`,
     * `kcc-crown-` or `kcc-event-marker`, and keyed on the plaque colour so a
     * palette change produces a NEW image rather than silently reusing the old
     * one from the style's image cache. There is only ever one billboard image
     * — unlike incidents (per category) and crowns (per rarity), a billboard
     * has no variants to draw.
     */
    fun imageId(plaqueColorArgb: Int = BillboardMarkerStyle.PLAQUE_COLOR): String =
        "kcc-billboard-$plaqueColorArgb"

    /**
     * Rasterises the marker at [density] (device pixels per dp).
     *
     * Takes a raw density rather than a `Context` because nothing here needs
     * resources — which also makes the geometry reachable from a JVM test.
     */
    fun create(
        density: Float,
        plaqueColorArgb: Int = BillboardMarkerStyle.PLAQUE_COLOR,
    ): Bitmap? {
        if (!density.isFinite() || density <= 0f) return null
        fun px(dp: Float) = dp * density

        val darkRing = px(IncidentMarkerStyle.RING_DARK_WIDTH_DP)
        val lightRing = px(IncidentMarkerStyle.RING_LIGHT_WIDTH_DP)
        val border = darkRing + lightRing

        val plaqueWidth = px(BillboardMarkerStyle.PLAQUE_WIDTH_DP)
        val plaqueHeight = px(BillboardMarkerStyle.PLAQUE_HEIGHT_DP)
        val postHeight = px(BillboardMarkerStyle.POST_HEIGHT_DP)
        val postWidth = px(BillboardMarkerStyle.POST_WIDTH_DP)
        val radius = px(BillboardMarkerStyle.PLAQUE_CORNER_RADIUS_DP)

        // CEIL, never round: these are dp→px floats, and rounding DOWN would
        // give a bitmap fractionally smaller than the geometry drawn into it,
        // clipping the outermost dark hairline — the element carrying contrast
        // against a light basemap. Losing it to a rounding artefact at some
        // densities would quietly undo a legibility guarantee.
        val widthPx = ceil(plaqueWidth + 2f * border).toInt().coerceAtLeast(1)
        val heightPx = ceil(plaqueHeight + postHeight + 2f * border).toInt().coerceAtLeast(1)

        val bitmap = createBitmap(widthPx, heightPx)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val centreX = widthPx / 2f

        // The plaque's OUTER bottom edge — where the post starts.
        val plaqueOuterBottom = plaqueHeight + 2f * border

        // One helper for all three passes: the same silhouette, inset by a
        // growing amount and filled with a different colour. Insetting rather
        // than stroking keeps the plaque's rounded corners concentric, which a
        // stroke of changing width would not.
        //
        // The post is drawn FIRST and overlaps upward into the plaque by the
        // corner radius, so the joint is covered by the plaque painted over it
        // and the two never show a seam at any density.
        fun drawSilhouette(inset: Float, plaqueColor: Int, postColor: Int) {
            val postHalf = postWidth / 2f + (border - inset)
            if (postHalf > 0f) {
                paint.color = postColor
                canvas.drawRect(
                    centreX - postHalf,
                    plaqueOuterBottom - inset - radius,
                    centreX + postHalf,
                    heightPx - inset,
                    paint,
                )
            }
            val plaque = RectF(inset, inset, widthPx - inset, plaqueOuterBottom - inset)
            if (plaque.width() <= 0f || plaque.height() <= 0f) return
            paint.color = plaqueColor
            canvas.drawRoundRect(plaque, radius, radius, paint)
        }

        // 1 + 2. The two-tone outline. The post is drawn in the ring colours
        // too, so the marker has ONE continuous silhouette rather than a plaque
        // with an unoutlined stick under it.
        drawSilhouette(0f, IncidentMarkerStyle.RING_DARK, IncidentMarkerStyle.RING_DARK)
        drawSilhouette(darkRing, IncidentMarkerStyle.RING_LIGHT, IncidentMarkerStyle.RING_LIGHT)
        // 3. The marker proper. The post keeps the light ring's colour so it
        // stays a visible stalk against the plaque above it.
        drawSilhouette(border, plaqueColorArgb, BillboardMarkerStyle.PLAQUE_CONTENT_COLOR)

        // 4. The two content bars — "there is a message on this panel". Sized
        // from the plaque's INNER box so they scale with the border, and drawn
        // last so they sit over the fill.
        val innerLeft = border
        val innerTop = border
        val innerWidth = plaqueWidth
        val innerHeight = plaqueHeight
        val barThickness = innerHeight * BillboardMarkerStyle.CONTENT_BAR_THICKNESS_FRACTION
        val gap = innerHeight * BillboardMarkerStyle.CONTENT_BAR_GAP_FRACTION
        val longWidth = innerWidth * BillboardMarkerStyle.CONTENT_BAR_LONG_FRACTION
        val shortWidth = innerWidth * BillboardMarkerStyle.CONTENT_BAR_SHORT_FRACTION
        // Centre the pair vertically inside the plaque.
        val blockHeight = barThickness * 2f + gap
        var barTop = innerTop + (innerHeight - blockHeight) / 2f
        paint.color = BillboardMarkerStyle.PLAQUE_CONTENT_COLOR
        for (barWidth in listOf(longWidth, shortWidth)) {
            val left = innerLeft + (innerWidth - barWidth) / 2f
            canvas.drawRoundRect(
                RectF(left, barTop, left + barWidth, barTop + barThickness),
                barThickness / 2f,
                barThickness / 2f,
                paint,
            )
            barTop += barThickness + gap
        }
        return bitmap
    }
}
