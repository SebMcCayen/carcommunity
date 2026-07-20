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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.map.ConvoyArrowPlanner
import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry
import com.kungsbackacarcommunity.app.map.ConvoyMemberPlacement
import com.kungsbackacarcommunity.app.map.ConvoyMemberPosition
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.MapSurface
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
 * All the maths is in `map/ConvoyEdgeGeometry.kt` and `map/ConvoyArrowPlanner.kt`
 * (pure and unit-tested); this file only draws what they return. In particular
 * the rotation and pitch corrections are NOT here — see those files.
 *
 * Renders nothing at all when not in a convoy, when no member position is known,
 * or on a surface with no camera (the stub, i.e. CI and the token-less build).
 */
@Composable
fun ConvoyMapAwarenessOverlay(
    mapSurface: MapSurface,
    members: List<ConvoyMemberPosition>,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val camera by mapSurface.cameraSnapshot.collectAsState()
    var viewportSize by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = IntOffset(it.width, it.height) }
                .testTag(CONVOY_AWARENESS_OVERLAY_TAG),
    ) {
        val snapshot = camera ?: return@Box
        if (members.isEmpty() || viewportSize.x <= 0 || viewportSize.y <= 0) return@Box

        val edgeInsetPx = with(LocalDensity.current) { EDGE_INSET.toPx() }

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
            remember(snapshot, members, viewportSize, edgeInsetPx, staleTick) {
                ConvoyArrowPlanner.plan(
                    members = members,
                    cameraLatitude = snapshot.latitude,
                    cameraLongitude = snapshot.longitude,
                    cameraBearing = snapshot.bearing,
                    viewportWidth = viewportSize.x.toFloat(),
                    viewportHeight = viewportSize.y.toFloat(),
                    edgeInsetPx = edgeInsetPx,
                    // The tick IS the clock reading, so the value that decided to
                    // recompute is the same one the staleness test uses.
                    nowMillis = staleTick,
                    project = { member ->
                        mapSurface.screenPositionFor(member.latitude, member.longitude)?.let {
                            ConvoyEdgeGeometry.ProjectedPoint(it.x, it.y)
                        }
                    },
                )
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
                        placement.member.displayName.orEmpty(),
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
 * The spoken description of an off-screen member: who, roughly how far, and —
 * because an arrow means nothing to a screen reader — which way as a clock
 * direction relative to the way the map is facing.
 */
@Composable
private fun directionDescription(placement: ConvoyMemberPlacement.OffScreen): String {
    val name = placement.member.displayName.orEmpty()
    val clock = clockPositionOf(placement.angleDegrees)
    val km = (placement.distanceMeters / 1000.0)
    val distance =
        if (km < 1.0) {
            stringResource(R.string.convoy_awarenessDistanceMeters, placement.distanceMeters.roundToInt())
        } else {
            stringResource(R.string.convoy_awarenessDistanceKm, ((km * 10).roundToInt() / 10.0).toString())
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
 * A QUARTER of [ConvoyArrowPlanner.STALE_AFTER_MS] (so: 30s against the 2-minute
 * staleness window), derived rather than hard-coded so the two cannot drift.
 *
 * The interval sets the worst-case overshoot: a position that crosses the
 * staleness threshold immediately after a tick keeps its arrow until the next
 * one, so a stale member is visible for at most
 * `STALE_AFTER_MS + STALE_TICK_MS` — **2.5 minutes**, against a 2-minute bound.
 * That slack is deliberate. Tightening it buys accuracy nobody can perceive (the
 * arrow is already two minutes wrong at the moment it is due to vanish; the
 * argument for removing it does not get materially stronger in the following 30
 * seconds) and costs a wakeup and a full replan every few seconds for the whole
 * time a convoy is on screen. 30s keeps the guarantee honest — the arrow always
 * goes, and goes soon — at four replans a minute, each O(n log n) over at most
 * MAX_CONVOY_INVITEES members of cheap trigonometry.
 */
internal val STALE_TICK_MS: Long = ConvoyArrowPlanner.STALE_AFTER_MS / 4
