package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
 * A car/game-inspired glyph: a dark road strip studded with spikes, ringed by a
 * faint dashed "only you can see this" halo. Expired traps are filtered against a
 * moving now ([nowProvider]) so a trap that runs out while the map is open drops
 * off without a Firestore re-emit.
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
                drawSpikeStrip(Offset(point.x, point.y), pulse)
            }
        }
    }
}

/**
 * The own-dot SHIELD aura + DOUBLE-POINTS effect. Both hang on the member's OWN
 * live position marker: a pulsing protective ring for an active Sköld, and a
 * rotating sparkle ring for an active Dubbla poäng. Non-blocking, decorative, and
 * placer-only by construction (it reads the caller's own position + own effect
 * windows). Renders nothing when neither effect is active or the own position is
 * unknown (not sharing yet).
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
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1_400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "own_dot_pulse",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(animation = tween(3_600, easing = LinearEasing)),
        label = "own_dot_spin",
    )

    Box(modifier = modifier.fillMaxSize().testTag(OWN_DOT_PERK_OVERLAY_TAG)) {
        camera ?: return@Box
        if (!shieldActive && !boostActive) return@Box
        val lat = ownLatitude ?: return@Box
        val lng = ownLongitude ?: return@Box

        Canvas(modifier = Modifier.fillMaxSize()) {
            val point = mapSurface.screenPositionFor(lat, lng) ?: return@Canvas
            if (!point.trustworthy) return@Canvas
            val centre = Offset(point.x, point.y)
            if (boostActive) drawBoostRing(centre, spin)
            if (shieldActive) drawShieldAura(centre, pulse)
        }
    }
}

// ---------------------------------------------------------------------------
// Drawing — self-contained, palette-free (game-y fixed accents so the perk
// indicators read the same in day/night map styles).
// ---------------------------------------------------------------------------

/** Shield blue. */
private val SHIELD_COLOR = Color(0xFF3D9BFF)

/** Double-points gold. */
private val BOOST_COLOR = Color(0xFFFFC233)

/** Spike-strip road-dark + steel. */
private val SPIKE_BASE_COLOR = Color(0xFF2B2B2B)
private val SPIKE_STEEL_COLOR = Color(0xFFC7CDD4)

/** A slow 0..1 shimmer shared by the spike markers. */
@Composable
private fun rememberPerkPulse(): Float {
    val transition = rememberInfiniteTransition(label = "spike_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1_600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "spike_pulse_value",
    )
    return pulse
}

/** A pulsing protective ring (an expanding, fading halo + a solid inner ring). */
private fun DrawScope.drawShieldAura(centre: Offset, pulse: Float) {
    val base = 26.dp.toPx()
    // Expanding, fading outer pulse.
    drawCircle(
        color = SHIELD_COLOR.copy(alpha = 0.30f * (1f - pulse)),
        radius = base + pulse * 16.dp.toPx(),
        center = centre,
        style = Stroke(width = 4.dp.toPx()),
    )
    // Solid inner protective ring.
    drawCircle(
        color = SHIELD_COLOR.copy(alpha = 0.85f),
        radius = base,
        center = centre,
        style = Stroke(width = 3.dp.toPx()),
    )
}

/** A rotating gold ring of sparkle ticks — the "double points" active effect. */
private fun DrawScope.drawBoostRing(centre: Offset, spinDegrees: Float) {
    val radius = 34.dp.toPx()
    drawCircle(
        color = BOOST_COLOR.copy(alpha = 0.55f),
        radius = radius,
        center = centre,
        style = Stroke(width = 2.dp.toPx()),
    )
    // Twelve sparkle ticks rotating around the dot.
    rotate(degrees = spinDegrees, pivot = centre) {
        val ticks = 12
        val tick = 5.dp.toPx()
        for (i in 0 until ticks) {
            val angle = (2.0 * Math.PI * i / ticks)
            val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            val inner = centre + dir * (radius - tick)
            val outer = centre + dir * (radius + tick)
            drawLine(
                color = BOOST_COLOR,
                start = inner,
                end = outer,
                strokeWidth = 2.5.dp.toPx(),
            )
        }
    }
}

/** A compact spike-strip glyph: a dark strip studded with steel spikes + a halo. */
private fun DrawScope.drawSpikeStrip(centre: Offset, pulse: Float) {
    val halfW = 14.dp.toPx()
    val halfH = 4.dp.toPx()
    val spikeH = 6.dp.toPx()

    // Faint dashed "only you can see this" halo, gently pulsing.
    drawCircle(
        color = SPIKE_STEEL_COLOR.copy(alpha = 0.25f + 0.20f * pulse),
        radius = halfW + 8.dp.toPx(),
        center = centre,
        style = Stroke(width = 1.5.dp.toPx()),
    )

    // The road strip (a dark rounded bar).
    drawRoundRect(
        color = SPIKE_BASE_COLOR.copy(alpha = 0.95f),
        topLeft = Offset(centre.x - halfW, centre.y - halfH),
        size = androidx.compose.ui.geometry.Size(halfW * 2, halfH * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(halfH, halfH),
    )

    // Steel spikes across the top edge.
    val spikes = 5
    val step = (halfW * 2) / spikes
    for (i in 0 until spikes) {
        val cx = centre.x - halfW + step * (i + 0.5f)
        val baseY = centre.y - halfH
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx - step * 0.35f, baseY)
            lineTo(cx, baseY - spikeH)
            lineTo(cx + step * 0.35f, baseY)
            close()
        }
        drawPath(path, color = SPIKE_STEEL_COLOR)
    }
}

private operator fun Offset.times(scalar: Float): Offset = Offset(x * scalar, y * scalar)
