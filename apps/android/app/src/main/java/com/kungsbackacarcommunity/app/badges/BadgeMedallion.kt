package com.kungsbackacarcommunity.app.badges

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ONE parameterised badge medallion, drawn on a [Canvas], for all 28 badges.
 *
 * WHY A COMPOSABLE AND NOT 28 DRAWABLES. The icon system (BADGE_ICON_SYSTEM in
 * functions/src/badges/badge-core.ts) is explicitly compositional: an outer ring
 * whose colour and pip COUNT encode the tier, a constant dark field, and one
 * per-ladder glyph. That is 11 silhouettes × 5 ring treatments, and shipping it
 * as 28 hand-written vector XMLs would duplicate identical ring/pip geometry 28
 * times — one drifted copy and Guld starts showing two pips. Here ring, pips and
 * glyph are separate functions of (ladder, tier), so the system cannot drift,
 * a new rung is one catalog entry with no new asset, and the locked/greyed state
 * is a colour argument rather than a second set of 28 files.
 *
 * ACCESSIBILITY. Tier is carried by ring colour AND by a countable number of
 * pips notched into the ring (1/2/3/4), and Platina additionally by a second
 * concentric hairline ring — so the top tier differs in SILHOUETTE, not only in
 * hue. Ladders are told apart by glyph shape alone: rendered as a pure
 * black-on-white stencil the set still reads. Nothing in the artwork depicts or
 * implies speed — Vägfarare is a road receding to a horizon with a milestone
 * stone, never a speedometer, needle, motion line or vehicle.
 */

/** Ring colours, verbatim from the backend icon system. */
private val RING_BRONS = Color(0xFFA9683A)
private val RING_SILVER = Color(0xFFB9C3CB)
private val RING_GULD = Color(0xFFE0A83A)
private val RING_PLATINA = Color(0xFFCFE3EA)

/** Non-tiered milestones: a plain unnotched pewter ring, zero pips. */
private val RING_PEWTER = Color(0xFF7C8792)

/** Constant dark slate field across the whole set. */
private val FIELD_SLATE = Color(0xFF232A31)

/** The single light ink every glyph is drawn in. */
private val GLYPH_INK = Color(0xFFE9EEF2)

private val LOCKED_RING = Color(0xFF9AA1A8)
private val LOCKED_FIELD = Color(0xFF2E3339)
private val LOCKED_INK = Color(0xFF6E767E)

/** Default rendering size — the size the art is designed at. */
val BadgeMedallionSize: Dp = 48.dp

private fun ringColorFor(tier: BadgeTier?): Color =
    when (tier) {
        BadgeTier.BRONS -> RING_BRONS
        BadgeTier.SILVER -> RING_SILVER
        BadgeTier.GULD -> RING_GULD
        BadgeTier.PLATINA -> RING_PLATINA
        null -> RING_PEWTER
    }

/** Pip count per tier; the standalone milestones carry none. */
private fun pipCountFor(tier: BadgeTier?): Int =
    when (tier) {
        BadgeTier.BRONS -> 1
        BadgeTier.SILVER -> 2
        BadgeTier.GULD -> 3
        BadgeTier.PLATINA -> 4
        null -> 0
    }

/**
 * Which silhouette to draw. A ladder rung draws its ladder's glyph; a milestone
 * draws its own; an unrecognised key falls back to a neutral disc rather than
 * rendering nothing.
 */
sealed interface BadgeGlyph {
    data class Ladder(val id: BadgeLadderId) : BadgeGlyph

    data class Milestone(val key: String) : BadgeGlyph

    companion object {
        fun forBadgeKey(badgeKey: String): BadgeGlyph =
            rungForBadgeKey(badgeKey)?.let { (ladder, _) -> Ladder(ladder.id) }
                ?: Milestone(badgeKey)
    }
}

/**
 * @param glyph which silhouette to draw.
 * @param tier the rung's tier, or null for a standalone milestone (pewter ring).
 * @param earned false renders the locked/greyed treatment — same silhouette and
 *   same pip count, so a locked rung is still identifiable, just not lit.
 */
@Composable
fun BadgeMedallion(
    glyph: BadgeGlyph,
    tier: BadgeTier?,
    earned: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = BadgeMedallionSize,
) {
    val ring = if (earned) ringColorFor(tier) else LOCKED_RING
    val field = if (earned) FIELD_SLATE else LOCKED_FIELD
    val ink = if (earned) GLYPH_INK else LOCKED_INK

    Canvas(
        modifier =
            modifier
                .size(size)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
    ) {
        drawMedallion(
            glyph = glyph,
            tier = tier,
            ringColor = ring,
            fieldColor = field,
            inkColor = ink,
        )
    }
}

private fun DrawScope.drawMedallion(
    glyph: BadgeGlyph,
    tier: BadgeTier?,
    ringColor: Color,
    fieldColor: Color,
    inkColor: Color,
) {
    val w = kotlin.math.min(size.width, size.height)
    val centre = Offset(size.width / 2f, size.height / 2f)
    // 4 dp at 48 dp, scaled — the ring never gets thinner than the spec ratio.
    val ringWidth = w / 12f
    val outerRadius = w / 2f
    val ringRadius = outerRadius - ringWidth / 2f
    val fieldRadius = outerRadius - ringWidth

    drawCircle(color = fieldColor, radius = fieldRadius, center = centre)
    drawCircle(
        color = ringColor,
        radius = ringRadius,
        center = centre,
        style = Stroke(width = ringWidth),
    )

    // Platina only: a second concentric hairline ring inside the main one, so the
    // top tier is distinguishable in pure silhouette and not merely by hue.
    if (tier == BadgeTier.PLATINA) {
        drawCircle(
            color = ringColor,
            radius = fieldRadius - ringWidth * 0.45f,
            center = centre,
            style = Stroke(width = w / 40f),
        )
    }

    drawTierPips(
        pipCount = pipCountFor(tier),
        centre = centre,
        ringRadius = ringRadius,
        ringWidth = ringWidth,
        pipColor = fieldColor,
    )

    // The glyph box: a centred square well inside the field, leaving clear space
    // between the artwork and the ring at every angle.
    val half = fieldRadius * 0.66f
    val box = Rect(centre.x - half, centre.y - half, centre.x + half, centre.y + half)
    drawGlyph(glyph, box, inkColor, fieldColor)
}

/**
 * Countable pips notched into the ring, centred on 6 o'clock. Drawn in the FIELD
 * colour so each reads as a bite taken out of the ring — visible in a
 * black-and-white stencil, which is what makes the tier countable without
 * relying on the ring's hue.
 */
private fun DrawScope.drawTierPips(
    pipCount: Int,
    centre: Offset,
    ringRadius: Float,
    ringWidth: Float,
    pipColor: Color,
) {
    if (pipCount <= 0) return
    // Screen coordinates: y grows downward, so 90° is 6 o'clock.
    val angles =
        when (pipCount) {
            1 -> listOf(90f)
            2 -> listOf(75f, 105f)
            3 -> listOf(66f, 90f, 114f)
            else -> listOf(57f, 79f, 101f, 123f)
        }
    val pipRadius = ringWidth * 0.42f
    for (deg in angles) {
        val rad = deg * PI.toFloat() / 180f
        drawCircle(
            color = pipColor,
            radius = pipRadius,
            center =
                Offset(
                    centre.x + ringRadius * cos(rad),
                    centre.y + ringRadius * sin(rad),
                ),
        )
    }
}

// ---------------------------------------------------------------------------
// Glyphs
//
// Every glyph is authored in a normalised 0..1 box (x right, y down) and mapped
// into the medallion's glyph square, so one set of coordinates renders at any
// size. Shapes are solid fills (or strokes no thinner than the 2 dp-at-48 dp
// floor); details are "punched" by redrawing in the field colour.
// ---------------------------------------------------------------------------

private fun Rect.at(x: Float, y: Float) = Offset(left + width * x, top + height * y)

private fun Rect.len(v: Float) = width * v

private fun Rect.polygon(points: List<Pair<Float, Float>>): Path =
    Path().apply {
        points.forEachIndexed { index, (x, y) ->
            val p = at(x, y)
            if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }

private fun DrawScope.fillPolygon(box: Rect, color: Color, points: List<Pair<Float, Float>>) {
    drawPath(box.polygon(points), color)
}

private fun DrawScope.fillRect(
    box: Rect,
    color: Color,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) {
    val topLeft = box.at(left, top)
    drawRect(
        color = color,
        topLeft = topLeft,
        size = Size(box.width * (right - left), box.height * (bottom - top)),
    )
}

private fun DrawScope.fillDot(box: Rect, color: Color, x: Float, y: Float, r: Float) {
    drawCircle(color = color, radius = box.len(r), center = box.at(x, y))
}

private fun DrawScope.drawGlyph(glyph: BadgeGlyph, box: Rect, ink: Color, field: Color) {
    when (glyph) {
        is BadgeGlyph.Ladder ->
            when (glyph.id) {
                BadgeLadderId.KRONJAGARE -> drawCrownGlyph(box, ink)
                BadgeLadderId.VAGFARARE -> drawRoadGlyph(box, ink, field)
                BadgeLadderId.TRAFFRAV -> drawFoxGlyph(box, ink, field)
                BadgeLadderId.TROGEN -> drawFlameGlyph(box, ink, field)
                BadgeLadderId.KONVOJLEDARE -> drawConvoyGlyph(box, ink)
                BadgeLadderId.SAMLARE -> drawArchGlyph(box, ink)
            }

        is BadgeGlyph.Milestone ->
            when (glyph.key) {
                "first_event" -> drawPinGlyph(box, ink, field)
                "five_events" -> drawPinArcGlyph(box, ink)
                "helpful_member" -> drawHandsGlyph(box, ink)
                "early_member" -> drawSunriseGlyph(box, ink)
                "garage_created" -> drawGarageDoorGlyph(box, ink)
                // An unrecognised key still renders a medallion rather than an
                // empty ring: a neutral disc, deliberately unlike every glyph.
                else -> fillDot(box, ink, 0.5f, 0.5f, 0.30f)
            }
    }
}

/**
 * Kronjägare — a five-point crown seen straight on (flat-bottomed trapezoid base
 * with three triangular spikes) over a single round map-marker dot. Wide and
 * flat-bottomed, unmistakable against the pin and arch glyphs.
 */
private fun DrawScope.drawCrownGlyph(box: Rect, ink: Color) {
    fillPolygon(
        box,
        ink,
        listOf(
            0.06f to 0.44f,
            0.20f to 0.06f,
            0.34f to 0.30f,
            0.50f to 0.00f,
            0.66f to 0.30f,
            0.80f to 0.06f,
            0.94f to 0.44f,
            0.88f to 0.62f,
            0.12f to 0.62f,
        ),
    )
    fillDot(box, ink, 0.50f, 0.85f, 0.13f)
}

/**
 * Vägfarare — a road ribbon receding to a horizon, with a milestone stone.
 *
 * NO SPEED IMAGERY, by standing product rule: no speedometer, no needle, no
 * motion lines, no chequered flag, no vehicle. The glyph is about DISTANCE
 * COVERED — horizon, road, milestone.
 */
private fun DrawScope.drawRoadGlyph(box: Rect, ink: Color, field: Color) {
    // Horizon bar across the upper third.
    fillRect(box, ink, 0.02f, 0.22f, 0.98f, 0.30f)
    // Road narrowing upward toward the horizon.
    fillPolygon(
        box,
        ink,
        listOf(0.18f to 1.00f, 0.82f to 1.00f, 0.60f to 0.32f, 0.40f to 0.32f),
    )
    // Two centre-line dashes punched out of the road surface.
    fillRect(box, field, 0.465f, 0.82f, 0.535f, 0.97f)
    fillRect(box, field, 0.475f, 0.55f, 0.525f, 0.66f)
    // Milestone stone standing at the lower left, clear of the road.
    fillRect(box, ink, 0.02f, 0.68f, 0.15f, 0.94f)
    fillDot(box, ink, 0.085f, 0.68f, 0.065f)
}

/**
 * Träffräv — a fox head front-on: two tall triangular ears on a broad skull that
 * tapers to a narrow muzzle, with two notched eye cut-outs.
 */
private fun DrawScope.drawFoxGlyph(box: Rect, ink: Color, field: Color) {
    fillPolygon(
        box,
        ink,
        listOf(
            0.08f to 0.00f,
            0.30f to 0.26f,
            0.70f to 0.26f,
            0.92f to 0.00f,
            0.96f to 0.42f,
            0.70f to 0.68f,
            0.58f to 0.98f,
            0.42f to 0.98f,
            0.30f to 0.68f,
            0.04f to 0.42f,
        ),
    )
    fillDot(box, field, 0.34f, 0.45f, 0.085f)
    fillDot(box, field, 0.66f, 0.45f, 0.085f)
}

/**
 * Trogen — a flame rising from a solid horizontal plinth. The plinth is what
 * separates it from every other rounded glyph at small sizes.
 */
private fun DrawScope.drawFlameGlyph(box: Rect, ink: Color, field: Color) {
    val flame =
        Path().apply {
            val tip = box.at(0.50f, 0.00f)
            moveTo(tip.x, tip.y)
            val r1 = box.at(0.90f, 0.34f)
            val r2 = box.at(0.86f, 0.64f)
            val bottom = box.at(0.50f, 0.80f)
            cubicTo(r1.x, r1.y, r2.x, r2.y, bottom.x, bottom.y)
            val l2 = box.at(0.14f, 0.64f)
            val l1 = box.at(0.10f, 0.34f)
            cubicTo(l2.x, l2.y, l1.x, l1.y, tip.x, tip.y)
            close()
        }
    drawPath(flame, ink)
    // One inner notch, so the flame is not a plain teardrop.
    fillPolygon(box, field, listOf(0.50f to 0.30f, 0.66f to 0.60f, 0.50f to 0.72f, 0.34f to 0.60f))
    fillRect(box, ink, 0.16f, 0.86f, 0.84f, 1.00f)
}

/**
 * Konvojledare — three chevrons in V-formation seen from above: one large
 * leading chevron with two smaller ones trailing behind and outward. Purely
 * angular, so the silhouette reads as "formation".
 */
private fun DrawScope.drawConvoyGlyph(box: Rect, ink: Color) {
    fun chevron(cx: Float, cy: Float, hw: Float, h: Float, t: Float) {
        fillPolygon(
            box,
            ink,
            listOf(
                cx to cy,
                cx + hw to cy + h,
                cx + hw - t * 0.85f to cy + h + t,
                cx to cy + t,
                cx - hw + t * 0.85f to cy + h + t,
                cx - hw to cy + h,
            ),
        )
    }
    chevron(0.50f, 0.00f, 0.34f, 0.30f, 0.20f)
    chevron(0.24f, 0.52f, 0.22f, 0.20f, 0.15f)
    chevron(0.76f, 0.52f, 0.22f, 0.20f, 0.15f)
}

/**
 * Samlare — a garage arch (wide semicircular roofline on two short legs) with
 * three round dots in a row underneath, one per collected car.
 */
private fun DrawScope.drawArchGlyph(box: Rect, ink: Color) {
    val strokeWidth = box.len(0.15f)
    val arch =
        Path().apply {
            val leftFoot = box.at(0.10f, 0.62f)
            moveTo(leftFoot.x, leftFoot.y)
            val topLeft = box.at(0.10f, 0.38f)
            lineTo(topLeft.x, topLeft.y)
            arcTo(
                rect =
                    Rect(
                        box.at(0.10f, 0.04f).x,
                        box.at(0.10f, 0.04f).y,
                        box.at(0.90f, 0.72f).x,
                        box.at(0.90f, 0.72f).y,
                    ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            val rightFoot = box.at(0.90f, 0.62f)
            lineTo(rightFoot.x, rightFoot.y)
        }
    drawPath(arch, ink, style = Stroke(width = strokeWidth))
    fillDot(box, ink, 0.24f, 0.90f, 0.09f)
    fillDot(box, ink, 0.50f, 0.90f, 0.09f)
    fillDot(box, ink, 0.76f, 0.90f, 0.09f)
}

/** first_event — a single map pin, teardrop body with a round hole punched through. */
private fun DrawScope.drawPinGlyph(box: Rect, ink: Color, field: Color) {
    fillDot(box, ink, 0.50f, 0.34f, 0.32f)
    fillPolygon(box, ink, listOf(0.24f to 0.52f, 0.76f to 0.52f, 0.50f to 1.00f))
    fillDot(box, field, 0.50f, 0.34f, 0.13f)
}

/** five_events — five pins fanned along a shallow arc, the centre pin tallest. */
private fun DrawScope.drawPinArcGlyph(box: Rect, ink: Color) {
    val xs = listOf(0.08f, 0.29f, 0.50f, 0.71f, 0.92f)
    val ys = listOf(0.44f, 0.26f, 0.14f, 0.26f, 0.44f)
    xs.forEachIndexed { index, x ->
        val y = ys[index]
        fillDot(box, ink, x, y, 0.105f)
        fillPolygon(
            box,
            ink,
            listOf(x - 0.075f to y + 0.08f, x + 0.075f to y + 0.08f, x to y + 0.32f),
        )
    }
}

/** helpful_member — two open hands cupped into a bowl with a shape resting in them. */
private fun DrawScope.drawHandsGlyph(box: Rect, ink: Color) {
    val bowl =
        Path().apply {
            val start = box.at(0.02f, 0.48f)
            moveTo(start.x, start.y)
            arcTo(
                rect =
                    Rect(
                        box.at(0.02f, 0.14f).x,
                        box.at(0.02f, 0.14f).y,
                        box.at(0.98f, 0.98f).x,
                        box.at(0.98f, 0.98f).y,
                    ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = -180f,
                forceMoveTo = false,
            )
        }
    drawPath(bowl, ink, style = Stroke(width = box.len(0.15f)))
    fillDot(box, ink, 0.50f, 0.24f, 0.16f)
}

/** early_member — a sunrise: a half-disc on a horizon bar with three short rays. */
private fun DrawScope.drawSunriseGlyph(box: Rect, ink: Color) {
    val disc =
        Path().apply {
            val start = box.at(0.16f, 0.72f)
            moveTo(start.x, start.y)
            arcTo(
                rect =
                    Rect(
                        box.at(0.16f, 0.38f).x,
                        box.at(0.16f, 0.38f).y,
                        box.at(0.84f, 1.06f).x,
                        box.at(0.84f, 1.06f).y,
                    ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            close()
        }
    drawPath(disc, ink)
    fillRect(box, ink, 0.00f, 0.74f, 1.00f, 0.84f)
    fillRect(box, ink, 0.46f, 0.02f, 0.54f, 0.24f)
    fillRect(box, ink, 0.06f, 0.28f, 0.14f, 0.50f)
    fillRect(box, ink, 0.86f, 0.28f, 0.94f, 0.50f)
}

/** garage_created — a squared garage door with three slats, the top one raised. */
private fun DrawScope.drawGarageDoorGlyph(box: Rect, ink: Color) {
    fillRect(box, ink, 0.06f, 0.06f, 0.94f, 0.18f)
    fillRect(box, ink, 0.06f, 0.36f, 0.94f, 0.50f)
    fillRect(box, ink, 0.06f, 0.56f, 0.94f, 0.70f)
    fillRect(box, ink, 0.06f, 0.76f, 0.94f, 0.90f)
}
