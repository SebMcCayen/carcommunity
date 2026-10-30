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
import androidx.compose.ui.graphics.toArgb
import com.kungsbackacarcommunity.app.design.KccPalette
import com.kungsbackacarcommunity.app.crownhunt.CrownMarkerStyle
import com.kungsbackacarcommunity.app.crownhunt.CrownRarity
import com.kungsbackacarcommunity.app.crownhunt.crownGlyphRes
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Compact crown tiles: one source glyph, one hairline and a non-colour rank cue.
 * No stacked glyph outlines, fuzzy halo or repeated idle animation. Images are
 * cached per appearance rather than generated in the animation frame loop.
 */
internal object CrownMarkerBitmaps {
    fun imageId(
        @DrawableRes iconRes: Int,
        discColorArgb: Int,
        glyphColorArgb: Int,
        glowColorArgb: Int?,
        collectedBadge: Boolean = false,
        rarity: CrownRarity? = null,
        rewardPoints: Int? = null,
        mapMode: MapMode = MapMode.Night,
    ): String =
        "kcc-crown-tile-v4-$iconRes-$discColorArgb-$glyphColorArgb-${glowColorArgb ?: 0}" +
            "-rank${rank(rarity, rewardPoints)}" +
            (if (usesLightSurface(mapMode, discColorArgb, collectedBadge)) "-day" else "-night") +
            if (collectedBadge) "-collected" else ""

    /** Curated points use their actual reward's band, never a fabricated rarity. */
    internal fun rank(rarity: CrownRarity?, rewardPoints: Int?): Int =
        rarity?.ordinal ?: when {
            rewardPoints == null -> 0
            rewardPoints >= CrownRarity.LEGENDARY.rewardPoints -> 3
            rewardPoints >= CrownRarity.RARE.rewardPoints -> 2
            rewardPoints >= CrownRarity.UNCOMMON.rewardPoints -> 1
            else -> 0
        }

    internal fun accent(rank: Int): Int = when (rank) {
        0 -> KccPalette.crownCommon.toArgb()
        1 -> KccPalette.crownUncommon.toArgb()
        2 -> KccPalette.crownRare.toArgb()
        else -> KccPalette.crownGold.toArgb()
    }

    // Android map-only light palette. Keep global/generated tokens and night/ready
    // colours intact; these paired surfaces/inks are the approved light variant.
    internal fun lightSurface(rank: Int): Int = when (rank) {
        0 -> 0xFFECE9E3.toInt()
        1 -> 0xFFE6F3EB.toInt()
        2 -> 0xFFEEE7FA.toInt()
        else -> 0xFFFFF0CC.toInt()
    }

    internal fun lightAccent(rank: Int): Int = when (rank) {
        0 -> 0xFF5B6168.toInt()
        1 -> 0xFF1B6E4B.toInt()
        2 -> 0xFF6640A0.toInt()
        else -> 0xFF946000.toInt()
    }

    private fun usesLightSurface(mapMode: MapMode, discColorArgb: Int, collectedBadge: Boolean): Boolean =
        mapMode == MapMode.Day && discColorArgb == CrownMarkerStyle.OUT_OF_RANGE_DISC && !collectedBadge

    /** Accent adapter also used by existing crown detail/list components. */
    internal fun ringAccent(legacyDisc: Int): Int = when (legacyDisc) {
        CrownMarkerStyle.discColorArgb(CrownRarity.COMMON),
        CrownMarkerStyle.OUT_OF_RANGE_DISC -> accent(0)
        CrownMarkerStyle.discColorArgb(CrownRarity.UNCOMMON) -> accent(1)
        CrownMarkerStyle.discColorArgb(CrownRarity.RARE) -> accent(2)
        CrownMarkerStyle.ADMIN_POINT_DISC,
        CrownMarkerStyle.discColorArgb(CrownRarity.LEGENDARY) -> accent(3)
        CrownMarkerStyle.COLLECTED_DISC -> CrownMarkerStyle.COLLECTED_DISC
        else -> legacyDisc
    }

    fun create(
        context: Context,
        @DrawableRes iconRes: Int,
        discColorArgb: Int,
        glyphColorArgb: Int,
        glowColorArgb: Int?,
        collectedBadge: Boolean = false,
        rarity: CrownRarity? = null,
        rewardPoints: Int? = null,
        mapMode: MapMode = MapMode.Night,
    ): Bitmap? {
        val glyph = runCatching { ContextCompat.getDrawable(context, iconRes)?.mutate() }
            .getOrNull() ?: return null
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = dp * density
        // Legacy callers still supply a real source glyph; recover its tier if
        // they predate explicit presentation metadata. No reward is invented.
        val resolvedRarity = rarity ?: if (rewardPoints == null) {
            CrownRarity.entries.firstOrNull { crownGlyphRes(it) == iconRes }
        } else null
        val rank = rank(resolvedRarity, rewardPoints)
        val side = 26f + 2f * rank
        val padding = 1f
        val size = ceil(px(side + padding * 2f)).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val centre = size / 2f
        val half = px(side) / 2f
        val left = centre - half
        val top = centre - half
        val right = centre + half
        val bottom = centre + half
        val corner = px(8f)
        val colour = accent(rank)
        val inRange = discColorArgb != CrownMarkerStyle.OUT_OF_RANGE_DISC && !collectedBadge
        val ink = KccPalette.nightOverlay.toArgb()
        val lightDistant = usesLightSurface(mapMode, discColorArgb, collectedBadge)
        val foreground = when {
            inRange -> ink
            lightDistant -> lightAccent(rank)
            else -> colour
        }

        // Only distant crowns on a day map use the light paired palette.
        // Ready, night and legacy collected appearances retain their old pixels.
        paint.color = when {
            inRange -> colour
            lightDistant -> lightSurface(rank)
            else -> ink
        }
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px(if (inRange) 1.5f else 1f)
        paint.color = if (inRange) KccPalette.warmIvory.toArgb() else foreground
        canvas.drawRoundRect(
            left + paint.strokeWidth / 2f, top + paint.strokeWidth / 2f,
            right - paint.strokeWidth / 2f, bottom - paint.strokeWidth / 2f,
            corner, corner, paint,
        )
        paint.style = Paint.Style.FILL

        // Draw the existing licensed silhouette ONCE. Outlining it repeatedly
        // filled the small gaps and made the previous design look smudged.
        val glyphSide = px(17f + rank)
        val glyphY = centre - px(2f)
        glyph.setBounds(
            (centre - glyphSide / 2f).roundToInt(), (glyphY - glyphSide / 2f).roundToInt(),
            (centre + glyphSide / 2f).roundToInt(), (glyphY + glyphSide / 2f).roundToInt(),
        )
        glyph.setColorFilter(foreground, PorterDuff.Mode.SRC_IN)
        glyph.draw(canvas)

        // 1–4 equal bars retain the reward hierarchy without relying on colour
        // or tiny text. Exact, server-provided KP stays in the selected popup.
        val count = rank + 1
        val barWidth = px(3f)
        val gap = px(2f)
        val start = centre - (count * barWidth + (count - 1) * gap) / 2f
        paint.color = foreground
        for (i in 0 until count) {
            val x = start + i * (barWidth + gap)
            canvas.drawRoundRect(x, bottom - px(6f), x + barWidth, bottom - px(4f), px(1f), px(1f), paint)
        }

        // Compatibility for older preview callers only. Production filters
        // already-collected crowns before this renderer receives its next set.
        if (collectedBadge) {
            val x = right - px(4f)
            val y = bottom - px(4f)
            paint.color = KccPalette.nightOverlay.toArgb()
            canvas.drawCircle(x, y, px(6f), paint)
            paint.color = CrownMarkerStyle.COLLECTED_BADGE_FILL
            canvas.drawCircle(x, y, px(5f), paint)
            paint.color = KccPalette.warmIvory.toArgb()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = px(1.5f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(Path().apply {
                moveTo(x - px(2.5f), y)
                lineTo(x - px(0.5f), y + px(2f))
                lineTo(x + px(3f), y - px(2f))
            }, paint)
        }
        return bitmap
    }
}
