package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.MapProjection
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Test tag on the placer-only spike-strip marker layer. */
const val SPIKE_STRIP_OVERLAY_TAG = "perk_spike_strip_overlay"

/** Test-tag PREFIX on a single trap's tap target (suffixed with the trap id). */
const val SPIKE_STRIP_TAP_TAG = "perk_spike_strip_tap_"

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
 * faint "only you can see this" halo. Under each glyph a SMALL purple bar depletes
 * full→empty as the trap nears expiry ([PerkMapVisuals.remainingLifeFraction]) —
 * numbers-free at map scale. Expired traps are filtered against a moving now
 * ([nowProvider]) so a trap that runs out while the map is open drops off without a
 * Firestore re-emit.
 *
 * When [onTrapTap] is supplied each drawn glyph gets a small, LOCALIZED tap target
 * (a positioned clickable, exactly the nearby-live-chip pattern — NOT a full-screen
 * gesture, so map pan/tap elsewhere is untouched) that reports the tapped trap so
 * the host can open its detail popup. The layer is placer-only by construction, so
 * a tap is inherently owner-only; the caller passes null to keep the markers purely
 * decorative.
 */
@Composable
fun SpikeStripOverlay(
    mapSurface: MapProjection,
    traps: List<OwnTrapMarker>,
    modifier: Modifier = Modifier,
    onTrapTap: ((OwnTrapMarker) -> Unit)? = null,
    nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    val camera by mapSurface.cameraSnapshot.collectAsState()

    // A coarse ticker so a just-expired trap drops off, and the spike glyph has a
    // gentle idle shimmer, without a per-frame recomposition storm. Runs ONLY while
    // there is at least one still-live trap: with none there is nothing to render,
    // so the per-second wake is pointless (keyed on [traps], so it restarts when a
    // trap appears), and it stops once the last trap has expired.
    var now by remember { mutableLongStateOf(nowProvider()) }
    LaunchedEffect(traps) {
        if (traps.isEmpty()) return@LaunchedEffect
        while (true) {
            now = nowProvider()
            // Stop once the last trap has expired — but only AFTER publishing this
            // tick, so the just-expired trap is dropped from the render first.
            if (PerkMapVisuals.liveTraps(traps, now).isEmpty()) break
            delay(1_000L)
        }
    }
    // Viewport size, so an off-screen trap's target is neither drawn nor laid out.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .testTag(SPIKE_STRIP_OVERLAY_TAG),
    ) {
        val snapshot = camera ?: return@Box
        val live = PerkMapVisuals.liveTraps(traps, now)
        // Bail BEFORE composing the animated child: with no live trap there is nothing
        // to draw, so no pulse transition should even EXIST to recompose at frame rate.
        // The raw `traps` snapshot can still carry expired rows (the query has a fixed
        // lower bound), so this filtered check — not `traps` — is what gates the
        // animation, keeping the layer idle-cheap per #957.
        if (live.isEmpty()) return@Box

        // Project the live traps ONCE, memoized on the settled camera, the live set and
        // the viewport. This is a plain remember (NOT an animated transition), and this
        // parent does not read the pulse, so it recomposes only on the 1 s now-tick /
        // camera settle — never at frame rate. `live` is value-equal frame to frame
        // (data-class markers), so the now-tick alone never re-projects.
        val placements =
            remember(snapshot, live, viewportSize) {
                if (viewportSize.width <= 0 || viewportSize.height <= 0) {
                    emptyList()
                } else {
                    val w = viewportSize.width.toFloat()
                    val h = viewportSize.height.toFloat()
                    buildList {
                        for (trap in live) {
                            val point =
                                mapSurface.screenPositionFor(trap.latitude, trap.longitude) ?: continue
                            if (!point.trustworthy) continue
                            if (point.x < 0f || point.y < 0f || point.x > w || point.y > h) continue
                            add(TrapPlacement(trap, point.x, point.y))
                        }
                    }
                }
            }
        // All live traps off-viewport → still nothing to draw, so still no animated
        // child: the pulse never runs with nothing on screen.
        if (placements.isEmpty()) return@Box

        ActiveTrapLayer(placements = placements, now = now, onTrapTap = onTrapTap)
    }
}

/**
 * The ANIMATED half of the spike-strip layer, composed ONLY when there is at least
 * one live, on-screen own trap to draw (the projection is already done — [placements]).
 * Isolating the `rememberInfiniteTransition` pulse HERE rather than in
 * [SpikeStripOverlay] is what keeps the layer idle-cheap: with no live traps the
 * parent early-returns before composing this, so no transition exists and nothing
 * recomposes at frame rate — the #957 discipline. The pulse only re-draws the glyphs;
 * it never re-projects (that stayed in the parent's plain remember).
 */
@Composable
private fun ActiveTrapLayer(
    placements: List<TrapPlacement>,
    now: Long,
    onTrapTap: ((OwnTrapMarker) -> Unit)?,
) {
    val pulse = rememberPerkPulse()
    // One reusable Path for the bear-trap teeth, so the continuously-animated Canvas
    // re-fills it (reset per tooth) instead of allocating a fresh Path each frame —
    // the tooth geometry is static, only the purple pulse animates.
    val toothPath = remember { Path() }
    val density = LocalDensity.current
    // Half the tap target, so the positioned clickable's footprint is CENTRED on the
    // projected coordinate (offset places by top-left). Computed once per density.
    val tapHalfPx = remember(density) { with(density) { (TRAP_TAP_TARGET / 2).toPx() } }

    Canvas(modifier = Modifier.fillMaxSize()) {
        for (placement in placements) {
            val centre = Offset(placement.x, placement.y)
            drawSpikeStrip(centre, pulse, toothPath)
            // The depleting lifetime bar, beneath the glyph. Numbers-free; the exact
            // remaining time is in the tapped-trap popup. Allocation-free.
            drawTrapLifeBar(
                centre,
                PerkMapVisuals.remainingLifeFraction(
                    expiresAtMillis = placement.trap.expiresAtMillis,
                    deployedAtMillis = placement.trap.deployedAtMillis,
                    nowMillis = now,
                ),
            )
        }
    }

    // Localized, per-trap tap targets ON TOP of the Canvas (only when the host wants
    // taps). A positioned clickable per trap — not a full-screen gesture — so a tap
    // anywhere else still reaches the map. Placer-only ⇒ owner-only, but guard
    // defensively: only the caller's own traps ever reach this layer.
    val onTap = onTrapTap
    if (onTap != null) {
        // One spoken label for every trap target — the marker is an empty Box, so
        // without this TalkBack would announce an unlabelled button. Mirrors the
        // nearby-live chip's contentDescription; read once outside the loop.
        val tapLabel = stringResource(R.string.crownHunt_perkTrapMapTapLabel)
        for (placement in placements) {
            val trap = placement.trap
            key(trap.trapId) {
                Box(
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(
                                    (placement.x - tapHalfPx).roundToInt(),
                                    (placement.y - tapHalfPx).roundToInt(),
                                )
                            }
                            .size(TRAP_TAP_TARGET)
                            .testTag(SPIKE_STRIP_TAP_TAG + trap.trapId)
                            // clickable BEFORE semantics so the content description and
                            // the click action merge onto the SAME actionable node
                            // (matching NearbyLiveOverlay). The reverse order can strand
                            // the label on an outer, non-actionable node — TalkBack then
                            // announces an unlabelled button.
                            .clickable(role = Role.Button) { onTap(trap) }
                            .semantics { contentDescription = tapLabel },
                )
            }
        }
    }
}

/** One live trap projected to the map view's pixel space (its glyph/tap centre). */
private class TrapPlacement(val trap: OwnTrapMarker, val x: Float, val y: Float)

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

    // A coarse ticker that runs ONLY while an effect is actually live: the expiry
    // timestamps stay non-null after an effect ends, so an unconditional loop would
    // wake every second forever with nothing to draw. Publish each tick first, then
    // stop once both effects are expired (the just-expired effect is cleared from
    // the render first); re-triggers on a new activation (keyed on the expiries).
    var now by remember { mutableLongStateOf(nowProvider()) }
    LaunchedEffect(shieldActiveUntilMillis, boostActiveUntilMillis) {
        while (true) {
            now = nowProvider()
            val anyActive =
                PerkMapVisuals.isEffectActive(shieldActiveUntilMillis, now) ||
                    PerkMapVisuals.isEffectActive(boostActiveUntilMillis, now)
            if (!anyActive) break
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

/**
 * The tap-target footprint reserved around each trap glyph — a comfortable touch
 * size a bit larger than the drawn glyph, matching the platform minimum, so the
 * placer can reliably open the trap's detail popup.
 */
private val TRAP_TAP_TARGET = 48.dp

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

/**
 * The small horizontal lifetime bar BENEATH a trap glyph: a faint purple track with
 * a solid purple fill whose WIDTH is [fraction] (0..1) of the full bar, so it
 * depletes right→empty as the trap approaches expiry. Numbers-free by design — the
 * exact "N min N s kvar" lives in the tapped-trap detail popup. Allocation-free
 * (two rounded rects, no Path), so it costs nothing in the per-frame animated draw.
 */
private fun DrawScope.drawTrapLifeBar(centre: Offset, fraction: Float) {
    val f = fraction.coerceIn(0f, 1f)
    val barWidth = 30.dp.toPx()
    val barHeight = 4.dp.toPx()
    // Sit clear of the glyph's spring nubs / halo, directly under the marker.
    val top = centre.y + 24.dp.toPx()
    val left = centre.x - barWidth / 2f
    val corner = CornerRadius(barHeight / 2f, barHeight / 2f)

    // Faint full-width track so the "how much is gone" is legible even near empty.
    drawRoundRect(
        color = TRAP_PURPLE.copy(alpha = 0.22f),
        topLeft = Offset(left, top),
        size = Size(barWidth, barHeight),
        cornerRadius = corner,
    )
    // The remaining-life fill. Hidden at exactly empty so a 0-width rounded rect's
    // caps don't leave a stray purple dot.
    if (f > 0f) {
        drawRoundRect(
            color = TRAP_PURPLE.copy(alpha = 0.92f),
            topLeft = Offset(left, top),
            size = Size(barWidth * f, barHeight),
            cornerRadius = corner,
        )
    }
}

private fun dir(angleRad: Double): Offset =
    Offset(cos(angleRad).toFloat(), sin(angleRad).toFloat())

private operator fun Offset.times(scalar: Float): Offset = Offset(x * scalar, y * scalar)
