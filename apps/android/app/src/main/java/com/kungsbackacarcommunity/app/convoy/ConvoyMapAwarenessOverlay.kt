package com.kungsbackacarcommunity.app.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.diagnostics.rememberClientErrorReporter
import com.kungsbackacarcommunity.app.location.LivePositionRejectionReport
import com.kungsbackacarcommunity.app.map.ConvoyArrowPlanner
import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry
import com.kungsbackacarcommunity.app.map.MapAwarenessDiagnostics
import com.kungsbackacarcommunity.app.map.MapAwarenessReport
import com.kungsbackacarcommunity.app.map.ConvoyMemberPlacement
import com.kungsbackacarcommunity.app.map.ConvoyMemberPosition
import com.kungsbackacarcommunity.app.map.LiveMarkerSmoother
import com.kungsbackacarcommunity.app.map.LiveMarkerSmoothing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.MapProjection
import kotlin.math.roundToInt

/** Test tag on the whole convoy awareness overlay. */
const val CONVOY_AWARENESS_OVERLAY_TAG = "convoy_awareness_overlay"

/** Test tag prefix on one off-screen direction arrow (suffixed with the uid). */
const val CONVOY_EDGE_ARROW_TAG = "convoy_edge_arrow_"

/**
 * Draws where the other people in your convoy are, relative to what the map is
 * currently showing.
 *
 * A member inside the viewport gets a normal marker at their position. A member
 * OUTSIDE it gets an arrow pinned to the screen edge, pointing the way they
 * actually lie, so you know a convoy is still around you when the map has moved
 * on. As they drive back into view the arrow disappears and the marker takes
 * over — one member is never both at once, because both come out of a single
 * pass in [ConvoyArrowPlanner.plan].
 *
 * ## Why a Compose overlay rather than map annotations
 * The identity of a convoy member is their garage MAIN-CAR photo, which is a
 * remote image behind a Storage path. Getting that into a Mapbox annotation
 * means resolving a URL, decoding a bitmap and registering it in the style per
 * member; getting it into Compose is [AsyncImage]. Drawing markers and arrows in
 * ONE Compose layer also means the two states share a single chip composable, so
 * a member crossing the viewport edge visibly keeps their identity instead of
 * morphing between two different representations. The cost is that the markers
 * are repositioned per settled camera frame rather than composited in the GL
 * layer, so during a fling they lag the basemap by a frame.
 *
 * ## Why the markers glide
 * Positions arrive as sparse published fixes, not a stream (see
 * [LiveMarkerSmoothing]), so drawing each one where it lands makes the person
 * driving beside you sit still for five seconds and then jump ~100 m. Every
 * roster snapshot therefore goes through a [LiveMarkerSmoother] first, which
 * throws away impossible fixes and animates the marker between believable ones —
 * see [rememberSmoothedMembers].
 *
 * All the maths is in `map/ConvoyEdgeGeometry.kt`, `map/ConvoyArrowPlanner.kt`
 * and `map/LiveMarkerSmoothing.kt` (pure and unit-tested); this file only draws
 * what they return. In particular the rotation and pitch corrections are NOT
 * here — see those files.
 *
 * Renders nothing at all when not in a convoy, when no member position is known,
 * or on a surface with no camera (the stub, i.e. CI and the token-less build).
 */
@Composable
fun ConvoyMapAwarenessOverlay(
    mapSurface: MapProjection,
    members: List<ConvoyMemberPosition>,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
    focusFitActive: Boolean = false,
) {
    val camera by mapSurface.cameraSnapshot.collectAsState()
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Field telemetry for the "convoy fit drops members off screen" bug: while the
    // keep-everyone-framed mode is on, a fresh member the projection places
    // OFF-SCREEN right after the camera settled is the fit failing to contain
    // them. A run of such frames escalates one bucketed report. See
    // MapAwarenessDiagnostics; a null reporter (config-less build) just fills the
    // device-local ring buffer.
    val fitErrorReporter = rememberClientErrorReporter()
    val fitLog = remember { MapAwarenessDiagnostics.ConvoyFitLog() }

    // Deliberately OUTSIDE the Box: the smoother's per-member glide state must
    // survive the frames on which the Box bails out early (no camera yet, empty
    // viewport during a resize). Remembering it past a conditional return inside
    // the Box would reset every marker to a standing start each time.
    val smoothedMembers = rememberSmoothedMembers(members = members, nowMillis = nowMillis)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .testTag(CONVOY_AWARENESS_OVERLAY_TAG),
    ) {
        val snapshot = camera ?: return@Box
        // smoothedMembers, not members: the smoother also DROPS anyone whose
        // only known position is undrawable, and a roster of nothing but those
        // has nothing to plan.
        if (smoothedMembers.isEmpty() || viewportSize.width <= 0 || viewportSize.height <= 0) {
            return@Box
        }

        val edgeInsetPx = with(LocalDensity.current) { EDGE_INSET.toPx() }
        // The inside/outside margin is the chip's RADIUS, converted at the
        // current density, so a member flips to an arrow just as their chip would
        // start being clipped by the edge. A fixed px constant would only be
        // right at mdpi and would leave a 3x device drawing half-clipped markers.
        val viewportMarginPx = with(LocalDensity.current) { (CHIP_SIZE / 2).toPx() }

        // TIME is the fourth thing that can change the answer, and unlike the
        // camera, the roster and the viewport, nothing emits when it passes.
        //
        // A member who loses signal mid-drive does NOT disappear from [members]:
        // their RTDB `latest` node is left behind by the publisher and keeps
        // re-arriving unchanged (see LiveLocationRepository.recordedAtIso). So
        // with the camera settled — parked at the meet point waiting for the rest
        // of the convoy, the single most common convoy state — every `remember`
        // key below stays equal, `plan` is never re-run, and the arrow goes on
        // pointing confidently at a position its owner left ten minutes ago.
        // That is exactly the outcome ConvoyArrowPlanner's STALE_AFTER_MS exists
        // to prevent, and without this tick the planner is simply never asked
        // again to apply it.
        //
        // So: re-ask on a timer. The tick is derived from STALE_AFTER_MS rather
        // than written as its own constant, so the two cannot drift apart.
        var staleTick by remember { mutableStateOf(nowMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(STALE_TICK_MS)
                staleTick = nowMillis()
            }
        }

        // Recomputed whenever the camera settles somewhere new, the roster
        // changes, the viewport is resized, or the staleness tick fires — the
        // four things that can change the answer. The projection call reaches
        // into the live map, which is why it is keyed on the snapshot rather than
        // memoised on the members alone.
        val placements =
            remember(
                snapshot,
                smoothedMembers,
                viewportSize,
                edgeInsetPx,
                viewportMarginPx,
                staleTick,
            ) {
                ConvoyArrowPlanner.plan(
                    members = smoothedMembers,
                    cameraLatitude = snapshot.latitude,
                    cameraLongitude = snapshot.longitude,
                    cameraBearing = snapshot.bearing,
                    viewportWidth = viewportSize.width.toFloat(),
                    viewportHeight = viewportSize.height.toFloat(),
                    edgeInsetPx = edgeInsetPx,
                    viewportMarginPx = viewportMarginPx,
                    // The tick IS the clock reading, so the value that decided to
                    // recompute is the same one the staleness test uses.
                    nowMillis = staleTick,
                    project = { member ->
                        // Drop an UNTRUSTWORTHY projection (a point folded/clamped
                        // from behind the tilted camera — see
                        // MapScreenPoint.trustworthy): the planner then treats the
                        // member as off-screen and draws an edge arrow from their
                        // bearing, instead of a marker stuck at the folded pixel.
                        mapSurface.screenPositionFor(member.latitude, member.longitude)
                            ?.takeIf { it.trustworthy }
                            ?.let { ConvoyEdgeGeometry.ProjectedPoint(it.x, it.y) }
                    },
                )
            }

        // While the fit is framing everyone, a fresh member the projection puts
        // off-screen right after the camera settled is the bug-1 signature. The
        // planner already dropped stale members, so every off-screen arrow here
        // stands for a member who SHOULD be in frame. (Arrows merge members, so
        // the true off-screen count folds in each arrow's +N.)
        val fitFrame =
            if (focusFitActive) {
                val offScreenMembers = placements.offScreen.sumOf { 1 + it.extraCount }
                MapAwarenessDiagnostics.ConvoyFitFrame(
                    memberCount = placements.onScreen.size + offScreenMembers,
                    offScreenNonStale = offScreenMembers,
                )
            } else {
                null
            }
        // Keyed on the per-settle inputs (camera snapshot + the staleness tick +
        // whether the fit is active), NOT on `fitFrame`: ConvoyFitFrame is a data
        // class, so consecutive settled frames with the same member/off-screen
        // counts compare EQUAL and keying on it would suppress re-recording — the
        // fitLog would never count a RUN of faulty fits (the escalation condition)
        // while the off-screen count holds steady. Keying on the settle inputs
        // records each settled frame; recordFrame's one-shot guard still reports
        // only once per fit session.
        LaunchedEffect(snapshot, staleTick, focusFitActive) {
            if (fitFrame == null) {
                // The fit stopped being applied (mode off, panned away, or nothing
                // left to fit). Start the next fit session clean: clear the counts
                // AND the one-shot escalation flag, so a later fit can report again
                // and does not inherit a previous session's tallies.
                fitLog.reset()
                return@LaunchedEffect
            }
            if (fitLog.recordFrame(fitFrame)) {
                // ONE summary snapshot for both the message and the dedup code, so
                // they cannot describe different states if a concurrent frame
                // mutates the log between two reads.
                val summary = fitLog.summary()
                fitErrorReporter?.report(
                    feature = MapAwarenessReport.FEATURE_FIT,
                    message = MapAwarenessReport.fitMessage(summary),
                    code = MapAwarenessReport.fitCode(summary),
                )
            }
        }

        placements.onScreen.forEach { placement ->
            ConvoyMemberChip(
                member = placement.member,
                centreX = placement.point.x,
                centreY = placement.point.y,
                arrowAngleDegrees = null,
                extraCount = 0,
                contentDescription =
                    stringResource(
                        R.string.convoy_awarenessMemberOnMap,
                        placement.member.spokenName(),
                    ),
                modifier = Modifier,
            )
        }

        placements.offScreen.forEach { placement ->
            ConvoyMemberChip(
                member = placement.member,
                centreX = placement.point.x,
                centreY = placement.point.y,
                arrowAngleDegrees = placement.angleDegrees,
                extraCount = placement.extraCount,
                contentDescription =
                    directionDescription(placement),
                modifier = Modifier.testTag(CONVOY_EDGE_ARROW_TAG + placement.member.uid),
            )
        }
    }
}

/**
 * [members], but with each position replaced by where that marker should be
 * drawn RIGHT NOW — impossible fixes discarded, believable ones glided to.
 *
 * ## How the animation is driven
 * A fresh roster restarts the effect, which is what "cancel and replace the
 * running animation when a newer fix arrives" amounts to here: there is at most
 * one loop, and it is torn down and restarted rather than stacked. The loop then
 * republishes a render time until every marker has settled, and stops — a parked
 * convoy costs nothing.
 *
 * Progress is measured on the FRAME clock rather than by re-reading [nowMillis],
 * for two reasons: the frame clock is monotonic (a wall-clock correction
 * mid-glide cannot make a marker jump or freeze), and it is the clock a Compose
 * test drives, so a test that moves a member terminates instead of spinning
 * against a frozen `nowMillis`. [nowMillis] still seeds the epoch, so the
 * smoother's own bookkeeping stays on the same scale as the reported fix
 * timestamps it compares against.
 */
@Composable
private fun rememberSmoothedMembers(
    members: List<ConvoyMemberPosition>,
    nowMillis: () -> Long,
): List<ConvoyMemberPosition> {
    // A BURST of discarded fixes is worth exactly one report. The smoother fires
    // this at most once per composition, with a bucketed, coordinate-free summary
    // (see LivePositionRejectionReport), so "everyone's markers were jumping" is
    // diagnosable after the fact instead of unreproducible. A null reporter
    // (config-less build) simply leaves the evidence in the smoother's bounded,
    // device-local ring buffer.
    val errorReporter = rememberClientErrorReporter()
    val smoother =
        remember(errorReporter) {
            LiveMarkerSmoother(
                onRejectionBurst = { message, code ->
                    errorReporter?.report(
                        feature = LivePositionRejectionReport.FEATURE,
                        message = message,
                        code = code,
                    )
                },
            )
        }
    // The instant the markers are currently drawn AT. Every published change is
    // one recomposition of the overlay, which is why the loop below throttles.
    var renderAtMillis by remember { mutableLongStateOf(nowMillis()) }

    LaunchedEffect(members) {
        val startMillis = nowMillis()
        smoother.onPositions(members, startMillis)
        renderAtMillis = startMillis

        var frameBaseMillis: Long? = null
        var publishedAtMillis = startMillis
        var atMillis = startMillis
        while (smoother.isGliding(atMillis)) {
            val frameMillis = withFrameMillis { it }
            // The first frame of this glide sets the origin the rest measure from.
            val base = frameBaseMillis ?: frameMillis
            frameBaseMillis = base
            atMillis = startMillis + (frameMillis - base)
            if (atMillis - publishedAtMillis >= LiveMarkerSmoothing.GLIDE_FRAME_INTERVAL_MS) {
                publishedAtMillis = atMillis
                renderAtMillis = atMillis
            }
        }
        // Land exactly on the target: the throttle above can skip the last
        // frame, and a marker parked a metre short of its reported position
        // would quietly desynchronise from the convoy fit camera.
        renderAtMillis = atMillis
    }

    return remember(members, renderAtMillis) { smoother.rendered(members, renderAtMillis) }
}

/**
 * The member's name as a screen reader should say it.
 *
 * A live position can arrive without a display name, and `orEmpty()` would then
 * build descriptions that open with nothing at all — " in the convoy", " is off
 * the map, at 2 o'clock, 400 m away". Fall back to the same generic
 * `convoy_unknownMember` label the rest of the convoy UI already uses, so the
 * sentence always has a subject.
 */
@Composable
private fun ConvoyMemberPosition.spokenName(): String =
    displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.convoy_unknownMember)

/**
 * The spoken description of an off-screen member: who, roughly how far, and —
 * because an arrow means nothing to a screen reader — which way as a clock
 * direction relative to the way the map is facing.
 */
@Composable
private fun directionDescription(placement: ConvoyMemberPlacement.OffScreen): String {
    val name = placement.member.spokenName()
    val clock = clockPositionOf(placement.angleDegrees)
    val km = (placement.distanceMeters / 1000.0)
    val distance =
        if (km < 1.0) {
            stringResource(R.string.convoy_awarenessDistanceMeters, placement.distanceMeters.roundToInt())
        } else {
            // Locale-aware: `Double.toString()` always emits a '.' decimal
            // separator, so a Swedish screen reader would announce "1.2 km"
            // instead of "1,2 km". This value is spoken, not parsed, so it
            // formats for the USER's locale.
            //
            // Read through LocalLocale rather than Locale.getDefault(): the
            // latter is not observable state, so a composable that reads it does
            // not recompose when the user changes their locale and would keep
            // announcing distances with the old separator until something else
            // happened to invalidate it. (Android lint: NonObservableLocale.)
            stringResource(
                R.string.convoy_awarenessDistanceKm,
                String.format(LocalLocale.current.platformLocale, "%.1f", km),
            )
        }
    return if (placement.extraCount > 0) {
        stringResource(
            R.string.convoy_awarenessArrowGroup,
            name,
            placement.extraCount,
            clock,
            distance,
        )
    } else {
        stringResource(R.string.convoy_awarenessArrowSingle, name, clock, distance)
    }
}

/**
 * Screen angle → clock position (1–12), the direction convention people already
 * use in a car. 0 degrees (straight up the screen, i.e. the way the map faces)
 * is twelve o'clock.
 */
internal fun clockPositionOf(angleDegrees: Double): Int {
    val normalized = ConvoyEdgeGeometry.normalizeDegrees(angleDegrees)
    val hour = Math.round(normalized / 30.0).toInt() % 12
    return if (hour == 0) 12 else hour
}

/**
 * One convoy member on the overlay: their main-car photo in a ring, optionally
 * with an arrow behind it pointing off-screen and a `+N` badge for the other
 * members this one is speaking for.
 *
 * [centreX] / [centreY] are the member's position in view pixels; the chip is
 * centred on them, which is why the offset subtracts half the chip.
 */
@Composable
private fun ConvoyMemberChip(
    member: ConvoyMemberPosition,
    centreX: Float,
    centreY: Float,
    arrowAngleDegrees: Double?,
    extraCount: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val chipPx = with(density) { CHIP_SIZE.toPx() }
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier =
            modifier
                .offset {
                    IntOffset(
                        x = (centreX - chipPx / 2f).roundToInt(),
                        y = (centreY - chipPx / 2f).roundToInt(),
                    )
                }
                .size(CHIP_SIZE)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (arrowAngleDegrees != null) {
            // The pointer itself, drawn behind the photo and rotated to the
            // member's direction. Drawn rather than an icon so it can sit
            // exactly on the chip's circumference at any angle.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawDirectionPointer(angleDegrees = arrowAngleDegrees, color = accent)
            }
        }

        ConvoyMemberPhoto(
            imagePath = member.imagePath,
            ringColor = accent,
            modifier = Modifier.size(PHOTO_SIZE),
        )

        if (extraCount > 0) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(accent)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                        // The count is already spoken in the chip's own
                        // description; repeating it as a separate node just makes
                        // a screen reader say the number twice.
                        .clearAndSetSemantics {},
            ) {
                Text(
                    text = "+$extraCount",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/**
 * The member's garage main-car photo — the same identity the live marker
 * already carries — in a circular ring. Falls back to a car glyph while the
 * Storage URL resolves, or forever if they have no main-car photo.
 */
@Composable
private fun ConvoyMemberPhoto(
    imagePath: String?,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    val url = rememberStorageImageUrl(LocalContext.current, imagePath)
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .background(ringColor)
                .padding(2.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.DirectionsCar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PHOTO_SIZE * 0.55f),
            )
        }
    }
}

/**
 * Draws a triangular pointer on the rim of the chip, rotated so it points along
 * [angleDegrees] (clockwise from straight up, the same convention the geometry
 * uses).
 */
private fun DrawScope.drawDirectionPointer(angleDegrees: Double, color: Color) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    rotate(degrees = angleDegrees.toFloat(), pivot = centre) {
        val halfWidth = size.width * 0.18f
        val tipY = 0f
        val baseY = size.height * 0.16f
        val path =
            Path().apply {
                moveTo(centre.x, tipY)
                lineTo(centre.x - halfWidth, baseY)
                lineTo(centre.x + halfWidth, baseY)
                close()
            }
        drawPath(path = path, color = color)
    }
}

// The whole chip, including room for the pointer on its rim.
private val CHIP_SIZE = 48.dp

// The photo inside the chip.
private val PHOTO_SIZE = 34.dp

// How far in from the viewport edge the arrows are pinned, so the chip and its
// pointer stay fully on screen and clear of the rounded display corners.
private val EDGE_INSET = 36.dp

/**
 * How often the overlay re-asks the planner purely because time has passed.
 *
 * A QUARTER of [ConvoyArrowPlanner.STALE_AFTER_MS] (so: 1 min against the 4-minute
 * staleness window), derived rather than hard-coded so the two cannot drift.
 *
 * The interval sets the worst-case overshoot: a position that crosses the
 * staleness threshold immediately after a tick keeps its arrow until the next
 * one, so a stale member is visible for at most
 * `STALE_AFTER_MS + STALE_TICK_MS` — **5 minutes**, against a 4-minute bound.
 * That slack is deliberate. Tightening it buys accuracy nobody can perceive (the
 * arrow is already four minutes wrong at the moment it is due to vanish; the
 * argument for removing it does not get materially stronger in the following
 * minute) and costs a wakeup and a full replan every minute for the whole time a
 * convoy is on screen. One minute keeps the guarantee honest — the arrow always
 * goes, and goes soon — at one replan a minute, each O(n log n) over at most
 * MAX_CONVOY_SIZE (25) members of cheap trigonometry. (Was written against
 * MAX_CONVOY_INVITEES, which is the 50-invitee bound on the CREATE call, not the
 * cap on how many people can end up in the convoy.)
 */
internal val STALE_TICK_MS: Long = ConvoyArrowPlanner.STALE_AFTER_MS / 4
