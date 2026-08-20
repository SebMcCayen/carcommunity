package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.shell.MapProjection
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** Test tag on the placer-only spike-strip marker layer. */
const val SPIKE_STRIP_OVERLAY_TAG = "perk_spike_strip_overlay"

/** Test tag on the own-dot perk effects (shield / double-points) layer. */
const val OWN_DOT_PERK_OVERLAY_TAG = "perk_own_dot_overlay"

/**
 * PLACER-ONLY hidden-trap markers: draws each of the caller's OWN armed
 * spike-strip traps at its map position. A Spikmatta is invisible to everyone
 * else (firestore.rules scopes the `activePerks` read to the placer), so this
 * only ever renders the caller's own traps — the fix for "the placer sees
 * nothing after dropping a trap". A Compose layer projected through [mapSurface]
 * (same seam as the nearby-live overlay), NOT a Mapbox annotation, so it stays
 * out of the map-surface file and is testable without a device.
 *
 * A car/game-inspired glyph: a purple bear-trap — two opposing jaws of
 * inward-pointing teeth around a spring plate — with a slow purple pulse and a
 * faint "only you can see this" halo. Expired traps are filtered against a moving
 * now ([nowProvider]) so a trap that runs out while the map is open drops off
 * without a Firestore re-emit.
 */
@Composable
fun SpikeStripOverlay(
    mapSurface: MapProjection,
    traps: List<OwnTrapMarker>,
    modifier: Modifier = Modifier,
    nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    val camera by mapSurface.cameraSnapshot.collectAsState()

    // A coarse ticker so a just-expired trap drops off, and the spike glyph has a
    // gentle idle shimmer, without a per-frame recomposition storm.
    var now by remember { mutableLongStateOf(nowProvider()) }
    LaunchedEffect(traps) {
        while (true) {
            now = nowProvider()
            delay(1_000L)
        }
    }
    val pulse = rememberPerkPulse()
    // One reusable Path for the bear-trap teeth, so the continuously-animated
    // Canvas re-fills it (reset per tooth) instead of allocating a fresh Path each
    // frame — the tooth geometry is static, only the purple pulse animates.
    val toothPath = remember { Path() }

    Box(modifier = modifier.fillMaxSize().testTag(SPIKE_STRIP_OVERLAY_TAG)) {
        camera ?: return@Box
        val live = PerkMapVisuals.liveTraps(traps, now)
        if (live.isEmpty()) return@Box

        Canvas(modifier = Modifier.fillMaxSize()) {
            for (trap in live) {
                val point = mapSurface.screenPositionFor(trap.latitude, trap.longitude)
                if (point == null || !point.trustworthy) continue
                if (point.x < 0f || point.y < 0f || point.x > size.width || point.y > size.height) {
                    continue
                }
                drawSpikeStrip(Offset(point.x, point.y), pulse, toothPath)
            }
        }
    }
}

/**
 * The own-dot SHIELD aura + DOUBLE-POINTS effect. Both hang on the member's OWN
 * map position: a slowly pulsing GREEN shield badge + halo for an active Sköld,
 * and semi-transparent BLUE "+" glyphs that fade / scale in staggered around the
 * dot for an active Dubbla poäng. Non-blocking, decorative, and placer-only by
 * construction (it reads the caller's own position + own effect windows).
 *
 * The own position is supplied by the caller ([ownLatitude] / [ownLongitude]):
 * the published live marker while live-sharing, else a device-location fallback,
 * so the effects draw over the dot whether or not the member is sharing. Renders
 * nothing when neither effect is active or the own position is unknown.
 */
@Composable
fun OwnDotPerkOverlay(
    mapSurface: MapProjection,
    ownLatitude: Double?,
    ownLongitude: Double?,
    shieldActiveUntilMillis: Long?,
    boostActiveUntilMillis: Long?,
    modifier: Modifier = Modifier,
    nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    val camera by mapSurface.cameraSnapshot.collectAsState()

    var now by remember { mutableLongStateOf(nowProvider()) }
    LaunchedEffect(shieldActiveUntilMillis, boostActiveUntilMillis) {
        while (true) {
            now = nowProvider()
            delay(1_000L)
        }
    }

    val shieldActive = PerkMapVisuals.isEffectActive(shieldActiveUntilMillis, now)
    val boostActive = PerkMapVisuals.isEffectActive(boostActiveUntilMillis, now)

    val transition = rememberInfiniteTransition(label = "own_dot_perk")
    // A slow, smooth 0..1..0 breath (~2 s each leg) for the green shield pulse.
    val shieldPulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(2_000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "own_dot_shield_pulse",
    )
    // A slow 0..1 sweep that staggers the blue "+" glyphs into view (and, on the
    // reverse leg, gently back out) so double-points "slowly appears" around the dot.
    val boostAppear by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(2_600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "own_dot_boost_appear",
    )

    // Reusable Paths for the shield silhouette + its check tick, so the animated
    // Canvas re-fills them (reset per frame) rather than allocating fresh Paths —
    // both are static shapes that only scale/pulse.
    val shieldPath = remember { Path() }
    val tickPath = remember { Path() }

    Box(modifier = modifier.fillMaxSize().testTag(OWN_DOT_PERK_OVERLAY_TAG)) {
        camera ?: return@Box
        if (!shieldActive && !boostActive) return@Box
        val lat = ownLatitude ?: return@Box
        val lng = ownLongitude ?: return@Box

        Canvas(modifier = Modifier.fillMaxSize()) {
            val point = mapSurface.screenPositionFor(lat, lng) ?: return@Canvas
            if (!point.trustworthy) return@Canvas
            val centre = Offset(point.x, point.y)
            if (boostActive) drawBoostPluses(centre, boostAppear)
            if (shieldActive) drawShieldAura(centre, shieldPulse, shieldPath, tickPath)
        }
    }
}

// ---------------------------------------------------------------------------
// Drawing — self-contained, palette-free (game-y fixed accents so the perk
// indicators read the same in day/night map styles).
// ---------------------------------------------------------------------------

/** Shield green ("protected"). */
private val SHIELD_COLOR = Color(0xFF34C759)

/** Double-points blue. */
private val BOOST_COLOR = Color(0xFF2F8BFF)

/** Spike-strip / bear-trap purple. */
private val TRAP_PURPLE = Color(0xFF9B5CFF)

/** A slow 0..1 shimmer shared by the spike (bear-trap) markers. */
@Composable
private fun rememberPerkPulse(): Float {
    val transition = rememberInfiniteTransition(label = "spike_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1_900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "spike_pulse_value",
    )
    return pulse
}

/**
 * The own-dot SHIELD: a slowly pulsing GREEN shield hung on the member's position
 * — an expanding, fading halo ("pulsates around the dot") plus a shield badge that
 * gently breathes with the same [pulse]. Re-fills the caller's reusable
 * [shieldPath] / [tickPath] each frame (both static shapes that only scale) so the
 * animated draw allocates no per-frame Path garbage.
 */
private fun DrawScope.drawShieldAura(
    centre: Offset,
    pulse: Float,
    shieldPath: Path,
    tickPath: Path,
) {
    // Expanding, fading outer pulse ring.
    val haloBase = 24.dp.toPx()
    drawCircle(
        color = SHIELD_COLOR.copy(alpha = 0.35f * (1f - pulse)),
        radius = haloBase + pulse * 18.dp.toPx(),
        center = centre,
        style = Stroke(width = 4.dp.toPx()),
    )

    // The shield badge, gently scaling with the pulse.
    val scale = 1f + 0.10f * pulse
    val halfW = 15.dp.toPx() * scale
    val top = centre.y - 20.dp.toPx() * scale
    val shoulder = centre.y - 6.dp.toPx() * scale
    val bottom = centre.y + 16.dp.toPx() * scale
    // Scale the bottom-Bezier control inset with the badge too, so the whole shape
    // scales uniformly through the pulse instead of distorting near the point.
    val controlInset = 8.dp.toPx() * scale

    shieldPath.reset()
    shieldPath.moveTo(centre.x, top)
    shieldPath.lineTo(centre.x + halfW, shoulder)
    shieldPath.quadraticBezierTo(centre.x + halfW, bottom - controlInset, centre.x, bottom)
    shieldPath.quadraticBezierTo(centre.x - halfW, bottom - controlInset, centre.x - halfW, shoulder)
    shieldPath.close()
    // Soft translucent fill + a solid green outline so it reads on any map style.
    drawPath(shieldPath, color = SHIELD_COLOR.copy(alpha = 0.22f + 0.10f * pulse))
    drawPath(shieldPath, color = SHIELD_COLOR.copy(alpha = 0.90f), style = Stroke(width = 3.dp.toPx()))

    // A check tick to read as "protected".
    tickPath.reset()
    tickPath.moveTo(centre.x - 6.dp.toPx(), centre.y + 1.dp.toPx())
    tickPath.lineTo(centre.x - 1.dp.toPx(), centre.y + 6.dp.toPx())
    tickPath.lineTo(centre.x + 7.dp.toPx(), centre.y - 5.dp.toPx())
    drawPath(tickPath, color = SHIELD_COLOR.copy(alpha = 0.95f), style = Stroke(width = 3.dp.toPx()))
}

/**
 * The own-dot DOUBLE-POINTS effect: semi-transparent blue "+" glyphs arranged
 * around the dot that slowly fade / scale in one after another ([appear] 0..1,
 * staggered per-glyph through [PerkMapVisuals.staggeredAppearAlpha]).
 */
private fun DrawScope.drawBoostPluses(centre: Offset, appear: Float) {
    val count = 6
    val ringRadius = 30.dp.toPx()
    val armMax = 6.dp.toPx()
    val stroke = 3.dp.toPx()
    for (i in 0 until count) {
        val a = PerkMapVisuals.staggeredAppearAlpha(i, count, appear)
        if (a <= 0.01f) continue
        // Start at the top and space evenly around the dot.
        val angle = (-Math.PI / 2) + (2.0 * Math.PI * i / count)
        val pos = centre + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * ringRadius
        val arm = armMax * (0.5f + 0.5f * a) // scale-in
        val alpha = 0.65f * a // semi-transparent, fading in
        drawLine(
            color = BOOST_COLOR.copy(alpha = alpha),
            start = Offset(pos.x - arm, pos.y),
            end = Offset(pos.x + arm, pos.y),
            strokeWidth = stroke,
        )
        drawLine(
            color = BOOST_COLOR.copy(alpha = alpha),
            start = Offset(pos.x, pos.y - arm),
            end = Offset(pos.x, pos.y + arm),
            strokeWidth = stroke,
        )
    }
}

/**
 * One bear-trap tooth as STATIC unit directions (radius 1) from the trap centre:
 * the two base corners on the ring and the inward apex. Precomputed once — the jaw
 * geometry never changes frame to frame, only the purple pulse animates — so the
 * animated Canvas just scales these by the current ring radius per frame.
 */
private class TrapTooth(val base1: Offset, val base2: Offset, val apex: Offset)

/** The 12 static bear-trap teeth (two opposing jaws), computed a single time. */
private val TRAP_TEETH: List<TrapTooth> =
    run {
        val teethPerJaw = 6
        val half = Math.toRadians(7.0)
        val jaws = listOf(20.0 to 160.0, 200.0 to 340.0)
        buildList {
            for ((startDeg, endDeg) in jaws) {
                for (i in 0 until teethPerJaw) {
                    val t = i / (teethPerJaw - 1.0)
                    val ang = Math.toRadians(startDeg + (endDeg - startDeg) * t)
                    add(TrapTooth(dir(ang - half), dir(ang + half), dir(ang)))
                }
            }
        }
    }

/** Static hinge directions (left / right) as unit vectors, computed once. */
private val TRAP_HINGES: List<Offset> = listOf(dir(0.0), dir(Math.toRadians(180.0)))

/**
 * The placer-only SPIKMATTA marker: a bear-trap glyph — two opposing jaws of
 * inward-pointing teeth around a spring plate, tinted purple with a slow purple
 * [pulse]. Drawn with Compose Canvas (no external asset). The tooth geometry is
 * precomputed ([TRAP_TEETH] / [TRAP_HINGES]) and each tooth re-fills the caller's
 * single reusable [toothPath], so this continuously-animated draw allocates no
 * per-frame Path/list garbage — only the purple paint varies.
 */
private fun DrawScope.drawSpikeStrip(centre: Offset, pulse: Float, toothPath: Path) {
    val ringR = 15.dp.toPx() * (1f + 0.06f * pulse)
    val toothLen = 6.dp.toPx()
    val alpha = 0.55f + 0.45f * pulse

    // Pulsing "only you can see this" halo.
    drawCircle(
        color = TRAP_PURPLE.copy(alpha = 0.28f * (1f - pulse)),
        radius = ringR + 10.dp.toPx() * pulse,
        center = centre,
        style = Stroke(width = 2.dp.toPx()),
    )

    // The spring plate (soft fill + purple ring).
    drawCircle(color = TRAP_PURPLE.copy(alpha = 0.18f), radius = ringR, center = centre)
    drawCircle(
        color = TRAP_PURPLE.copy(alpha = alpha),
        radius = ringR,
        center = centre,
        style = Stroke(width = 2.5.dp.toPx()),
    )

    // Two opposing jaws of inward-pointing teeth (static offsets scaled per frame).
    val apexR = ringR - toothLen
    for (tooth in TRAP_TEETH) {
        val base1 = centre + tooth.base1 * ringR
        val base2 = centre + tooth.base2 * ringR
        val apex = centre + tooth.apex * apexR
        toothPath.reset()
        toothPath.moveTo(base1.x, base1.y)
        toothPath.lineTo(apex.x, apex.y)
        toothPath.lineTo(base2.x, base2.y)
        toothPath.close()
        drawPath(toothPath, color = TRAP_PURPLE.copy(alpha = alpha))
    }

    // Spring nubs at the two hinges (left / right).
    val nubR = ringR + 4.dp.toPx()
    for (hinge in TRAP_HINGES) {
        drawCircle(
            color = TRAP_PURPLE.copy(alpha = alpha),
            radius = 3.dp.toPx(),
            center = centre + hinge * nubR,
        )
    }

    // The pressure plate at the centre.
    drawCircle(color = TRAP_PURPLE.copy(alpha = alpha), radius = 3.5.dp.toPx(), center = centre)
}

private fun dir(angleRad: Double): Offset =
    Offset(cos(angleRad).toFloat(), sin(angleRad).toFloat())

private operator fun Offset.times(scalar: Float): Offset = Offset(x * scalar, y * scalar)
