package com.kungsbackacarcommunity.app.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.kungsbackacarcommunity.app.crownhunt.CrownMarkerStyle
import com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Builds the Kronjakt crown marker images the map draws — a crown glyph on a
 * rarity-coloured disc, inside the same two-tone ring the incident badges use,
 * with a soft halo on the legendary tier only.
 *
 * The sibling of [IncidentMarkerBitmaps], and deliberately a SEPARATE object
 * rather than a parameter on that one. Sharing would mean threading an optional
 * glow, a second disc diameter and a second glyph scale through a function whose
 * whole value is that it does one simple thing predictably — and the two layers
 * would then be one edit away from accidentally restyling each other. The ring
 * constants ARE shared ([IncidentMarkerStyle]), because that is the part that
 * must never diverge.
 *
 * ## Draw order (outside in)
 *
 * 1. The legendary halo, when the tier has one — a radial gradient fading to
 *    transparent, so it reads as a glow rather than a second ring.
 * 2. [IncidentMarkerStyle.RING_DARK] hairline — separates the marker from a
 *    LIGHT basemap.
 * 3. [IncidentMarkerStyle.RING_LIGHT] ring — separates it from a DARK basemap.
 * 4. The rarity disc.
 * 5. The crown glyph, tinted for contrast against that disc.
 *
 * Mapbox annotations take a raster `Bitmap` registered as a style image; there
 * is no vector path. The crowns are vector drawables (one asset, every density),
 * so they are inflated and rasterised here, once per tier, at the device's real
 * density.
 */
internal object CrownMarkerBitmaps {
    /**
     * A stable style-image name for one tier's marker.
     *
     * Namespaced `kcc-crown-` so it cannot collide with `kcc-incident-` (or with
     * the `kcc-event-marker` image the events layer registers), and keyed on
     * everything that changes the pixels — glyph, disc, glyph tint and glow — so
     * a palette change produces a NEW image rather than silently reusing the old
     * one from the style's image cache.
     */
    fun imageId(
        @DrawableRes iconRes: Int,
        discColorArgb: Int,
        glyphColorArgb: Int,
        glowColorArgb: Int?,
        collectedBadge: Boolean = false,
    ): String =
        "kcc-crown-$iconRes-$discColorArgb-$glyphColorArgb-${glowColorArgb ?: 0}" +
            if (collectedBadge) "-collected" else ""

    /**
     * Rasterises one crown marker. Returns null if the drawable cannot be
     * inflated, so a missing or broken asset degrades to "this crown is not
     * drawn" rather than crashing the map — the same defensive posture as the
     * rest of this surface.
     */
    fun create(
        context: Context,
        @DrawableRes iconRes: Int,
        discColorArgb: Int,
        glyphColorArgb: Int,
        glowColorArgb: Int?,
        collectedBadge: Boolean = false,
    ): Bitmap? {
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = dp * density

        val discDiameter = px(CrownMarkerStyle.DISC_DIAMETER_DP)
        val lightRing = px(IncidentMarkerStyle.RING_LIGHT_WIDTH_DP)
        val darkRing = px(IncidentMarkerStyle.RING_DARK_WIDTH_DP)
        val glow = if (glowColorArgb != null) px(CrownMarkerStyle.GLOW_WIDTH_DP) else 0f
        // Rings and the halo all sit OUTSIDE the disc; the marker's own radius is
        // that whole stack. Computed from the dp constants — NOT derived from the
        // bitmap size — so growing the bitmap to fit a badge (below) cannot grow
        // the marker with it.
        val ringOuterRadius = discDiameter / 2f + darkRing + lightRing
        val markerRadius = ringOuterRadius + glow

        // The check badge sits on the ring circle at the lower-right (45°) and
        // overhangs the marker, so the bitmap must be large enough to hold it. A
        // collected crown never glows, so the two are mutually exclusive and the
        // canvas simply takes whichever reach is larger. The badge is baked in
        // here (not a second annotation) so it scales with the crown at every
        // zoom rather than being culled independently (#867/#897).
        val badgeDiameter = if (collectedBadge) px(CrownMarkerStyle.COLLECTED_BADGE_DIAMETER_DP) else 0f
        val badgeRadius = badgeDiameter / 2f
        val badgeReach = if (collectedBadge) ringOuterRadius * SIN_45 + badgeRadius else 0f
        val halfExtent = maxOf(markerRadius, badgeReach)
        // CEIL, never round: `halfExtent` is a dp->px float, and rounding DOWN
        // would give a bitmap fractionally smaller than the geometry drawn into
        // it, clipping the outermost element — the dark hairline on a plain crown,
        // the halo on a legendary, or the badge on a collected one. Losing any to
        // a rounding artefact would quietly undo a guarantee the tests assert.
        val sizePx = ceil(2f * halfExtent).toInt().coerceAtLeast(1)

        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        // The MARKER stays centred on the point (the annotation is CENTER-anchored)
        // whether or not the bitmap grew for a badge, because the disc is drawn at
        // the bitmap centre and only the surrounding canvas grew.
        val centre = sizePx / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Legendary halo. A radial gradient from the ring's edge outwards,
        //    opaque at the marker and fully transparent at the bitmap edge, so
        //    it fades into the map instead of ending in a hard circle. Drawn
        //    first and then painted over, so the rings and disc stay crisp.
        if (glowColorArgb != null && glow > 0f && ringOuterRadius > 0f) {
            val transparent = glowColorArgb and 0x00FFFFFF
            paint.shader =
                RadialGradient(
                    centre,
                    centre,
                    markerRadius,
                    intArrayOf(glowColorArgb, glowColorArgb, transparent),
                    // The glow holds full strength out to the ring's edge, then
                    // falls off across the halo band. Without the middle stop it
                    // would already be half-faded where it leaves the marker,
                    // which reads as a blur rather than a glow.
                    floatArrayOf(0f, ringOuterRadius / markerRadius, 1f),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawCircle(centre, centre, markerRadius, paint)
            paint.shader = null
        }

        // 2. Outer dark hairline (carries a light basemap).
        paint.color = IncidentMarkerStyle.RING_DARK
        canvas.drawCircle(centre, centre, ringOuterRadius, paint)

        // 3. Light ring (carries a dark basemap).
        paint.color = IncidentMarkerStyle.RING_LIGHT
        canvas.drawCircle(centre, centre, ringOuterRadius - darkRing, paint)

        // 4. Rarity disc.
        paint.color = discColorArgb
        canvas.drawCircle(centre, centre, discDiameter / 2f, paint)

        // 5. Crown glyph, tinted for contrast against the disc it sits on.
        val drawable =
            runCatching { ContextCompat.getDrawable(context, iconRes) }.getOrNull()
                ?: return null
        // Mutate before tinting: drawables share a constant state, so tinting the
        // shared instance would recolour every other user of this resource.
        val glyph = drawable.mutate()
        val glyphSize = discDiameter * CrownMarkerStyle.GLYPH_SCALE
        val half = glyphSize / 2f
        glyph.setBounds(
            (centre - half).roundToInt(),
            (centre - half).roundToInt(),
            (centre + half).roundToInt(),
            (centre + half).roundToInt(),
        )
        glyph.setColorFilter(glyphColorArgb, PorterDuff.Mode.SRC_IN)
        glyph.draw(canvas)

        // 6. The "collected by you" check badge, lower-right, when this is a
        //    shared crown the member already picked up. A white-ringed green disc
        //    with a tick — the "checkpoint cleared" cue — carrying the meaning on
        //    a SHAPE channel that survives any colour-vision deficiency, so a
        //    collected crown never reads as merely out-of-range.
        if (collectedBadge && badgeRadius > 0f) {
            val bcx = centre + ringOuterRadius * SIN_45
            val bcy = centre + ringOuterRadius * SIN_45
            // A light collar so the badge separates from both the disc and the
            // basemap, reusing the same ring colour the marker already carries.
            paint.color = IncidentMarkerStyle.RING_LIGHT
            canvas.drawCircle(bcx, bcy, badgeRadius, paint)
            paint.color = CrownMarkerStyle.COLLECTED_BADGE_FILL
            canvas.drawCircle(bcx, bcy, badgeRadius - darkRing, paint)
            val tick =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = CrownMarkerStyle.collectedBadgeGlyphColorArgb()
                    style = Paint.Style.STROKE
                    strokeWidth = (badgeRadius * 0.30f).coerceAtLeast(1f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
            val path =
                Path().apply {
                    moveTo(bcx - badgeRadius * 0.42f, bcy + badgeRadius * 0.02f)
                    lineTo(bcx - badgeRadius * 0.08f, bcy + badgeRadius * 0.34f)
                    lineTo(bcx + badgeRadius * 0.46f, bcy - badgeRadius * 0.36f)
                }
            canvas.drawPath(path, tick)
        }
        return bitmap
    }

    /** sin 45° — the badge sits on the ring circle at the lower-right diagonal. */
    private const val SIN_45 = 0.70710677f
}
