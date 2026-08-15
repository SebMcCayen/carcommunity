package com.kungsbackacarcommunity.app.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.diagnostics.rememberClientErrorReporter
import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry
import com.kungsbackacarcommunity.app.map.MapAwarenessDiagnostics
import com.kungsbackacarcommunity.app.map.MapAwarenessReport
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.MapProjection
import com.kungsbackacarcommunity.app.shell.MapScreenPoint
import kotlin.math.roundToInt

/** Test tag on the whole nearby-live-sharer overlay. */
const val NEARBY_LIVE_OVERLAY_TAG = "nearby_live_overlay"

/** Test tag prefix on one nearby sharer's chip (suffixed with the uid). */
const val NEARBY_LIVE_CHIP_TAG = "nearby_live_chip_"

/**
 * Draws STANDALONE ("Single") live sharers who are near the caller — the
 * discovery half of the nearby/public live-session feature — as on-screen
 * markers on top of the map.
 *
 * ## Why a Compose overlay (not map annotations) and why on-screen ONLY
 * Same reason as [com.kungsbackacarcommunity.app.convoy.ConvoyMapAwarenessOverlay]:
 * a sharer's identity is their garage main-car photo (a remote Storage image),
 * which is trivial as an [AsyncImage] and awkward as a Mapbox annotation. It is a
 * SEPARATE, deliberately simpler layer, and it stays OUT of `MapboxMapSurface`
 * (that file is owned by the map PR landing in parallel) — everything here
 * reaches the map only through the [mapSurface] projection seam.
 *
 * Unlike the convoy overlay it draws NO off-screen edge arrows: those are a
 * "keep track of YOUR group" affordance. Pinning arrows to the screen edge for
 * every stranger broadcasting nearby would be noisy and faintly creepy, so a
 * nearby public sharer is shown only while they are actually inside the viewport.
 *
 * Visually distinguished from convoy members by a DIFFERENT ring accent
 * (`tertiary` vs the convoy's `primary`), so the two live-marker kinds read as
 * different things on the same map.
 *
 * Renders nothing when there are no nearby sharers, or on a surface with no
 * camera (the stub, i.e. CI and the token-less build).
 */
@Composable
fun NearbyLiveOverlay(
    mapSurface: MapProjection,
    sharers: List<LiveMarker>,
    modifier: Modifier = Modifier,
) {
    val camera by mapSurface.cameraSnapshot.collectAsState()
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Field telemetry for the "off-screen live user stuck in the top-left corner"
    // bug. A burst of folded/clamped chip projections escalates ONE bucketed,
    // coordinate-free report; a null reporter (config-less build) leaves the
    // evidence in the device-local ring buffer. See MapAwarenessDiagnostics.
    val errorReporter = rememberClientErrorReporter()
    val diagnosticsLog = remember { MapAwarenessDiagnostics.MapAwarenessLog() }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .testTag(NEARBY_LIVE_OVERLAY_TAG),
    ) {
        val snapshot = camera ?: return@Box
        if (sharers.isEmpty() || viewportSize.width <= 0 || viewportSize.height <= 0) return@Box

        val marginPx = with(LocalDensity.current) { (CHIP_SIZE / 2).toPx() }
        val viewportWidth = viewportSize.width.toFloat()
        val viewportHeight = viewportSize.height.toFloat()

        // Recomputed whenever the camera settles somewhere new or the roster
        // changes — the projection reaches into the live map, so it is keyed on
        // the settled snapshot rather than memoised on the sharers alone. Only
        // sharers whose projected point is TRUSTWORTHY and lands inside the
        // viewport (with a half-chip margin, so a marker is not clipped by the
        // edge) are drawn.
        //
        // Two guards, not a plain rectangle check: on the default pitched (45°)
        // map a sharer panned OFF the screen is behind the tilted camera, and
        // `pixelForCoordinate` folds/clamps that point back into view (sometimes
        // to the origin corner). The renderer's own round-trip verdict
        // ([MapScreenPoint.trustworthy]) rejects that deterministically, and
        // [MapAwarenessDiagnostics.classifyChipProjection] keeps the bearing
        // cross-examination ([ConvoyEdgeGeometry.isProjectionTrustworthy]) as a
        // secondary check while naming each verdict for diagnostics.
        val evaluation =
            remember(snapshot, sharers, viewportSize, marginPx) {
                val visible = mutableListOf<Pair<LiveMarker, MapScreenPoint>>()
                val verdicts = mutableListOf<MapAwarenessDiagnostics.ChipProjectionVerdict>()
                for (sharer in sharers) {
                    val point = mapSurface.screenPositionFor(sharer.latitude, sharer.longitude)
                    val projected = point?.let { ConvoyEdgeGeometry.ProjectedPoint(it.x, it.y) }
                    val geographicBearing =
                        ConvoyEdgeGeometry.initialBearingDegrees(
                            fromLatitude = snapshot.latitude,
                            fromLongitude = snapshot.longitude,
                            toLatitude = sharer.latitude,
                            toLongitude = sharer.longitude,
                        )
                    val screenAngle =
                        ConvoyEdgeGeometry.screenAngleDegrees(
                            geographicBearing = geographicBearing,
                            cameraBearing = snapshot.bearing,
                        )
                    val verdict =
                        MapAwarenessDiagnostics.classifyChipProjection(
                            projected = projected,
                            // The renderer's round-trip verdict is authoritative;
                            // a null projection is treated as untrustworthy.
                            roundTripTrustworthy = point?.trustworthy == true,
                            viewportWidth = viewportWidth,
                            viewportHeight = viewportHeight,
                            marginPx = marginPx,
                            expectedScreenAngle = screenAngle,
                        )
                    verdicts += verdict
                    // ON_SCREEN is exactly the draw case (round trip AND bearing
                    // agree, inside the margin-expanded viewport).
                    if (point != null && verdict == MapAwarenessDiagnostics.ChipProjectionVerdict.ON_SCREEN) {
                        visible += sharer to point
                    }
                }
                visible to verdicts
            }

        val onScreen = evaluation.first

        // Feed this settled frame's verdicts to the diagnostics log; escalate one
        // aggregate report the first time folded/clamped projections cross the
        // threshold. Keyed on the verdict list so a settled camera does not
        // re-record until something actually changes.
        LaunchedEffect(evaluation.second) {
            // Record EVERY verdict from this settled frame first, THEN report — so
            // the counts reflect the whole frame rather than a mid-loop partial
            // state, and the report does not depend on sharer iteration order. One
            // snapshot of faultTotal + counts feeds both the message and the dedup
            // code, so they cannot describe different states.
            var escalated = false
            evaluation.second.forEach { verdict ->
                if (diagnosticsLog.recordChip(verdict)) escalated = true
            }
            if (escalated) {
                val faultTotal = diagnosticsLog.faultTotal()
                val counts = diagnosticsLog.verdictCounts()
                errorReporter?.report(
                    feature = MapAwarenessReport.FEATURE_CHIP,
                    message =
                        MapAwarenessReport.chipMessage(
                            faultTotal = faultTotal,
                            counts = counts,
                            viewportWidth = viewportWidth,
                            viewportHeight = viewportHeight,
                        ),
                    code = MapAwarenessReport.chipCode(counts),
                )
            }
        }

        onScreen.forEach { (sharer, point) ->
            NearbySharerChip(
                imagePath = sharer.mainCar?.imagePath,
                centreX = point.x,
                centreY = point.y,
                contentDescription =
                    stringResource(R.string.nearby_liveSharerOnMap, sharer.spokenName()),
                modifier = Modifier.testTag(NEARBY_LIVE_CHIP_TAG + sharer.uid),
            )
        }
    }
}

/**
 * The sharer's name as a screen reader should say it — falling back to a generic
 * "someone nearby" so the description always has a subject even when a live
 * marker arrives without a display name.
 */
@Composable
private fun LiveMarker.spokenName(): String =
    displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.nearby_unknownSharer)

/**
 * One nearby sharer's main-car photo in a coloured ring, centred on their
 * projected screen position. Falls back to a broadcast glyph while the Storage
 * URL resolves, or forever when they have no main-car photo.
 */
@Composable
private fun NearbySharerChip(
    imagePath: String?,
    centreX: Float,
    centreY: Float,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val chipPx = with(density) { CHIP_SIZE.toPx() }
    // tertiary — deliberately NOT the convoy overlay's primary — so a nearby
    // public sharer reads as a different kind of marker from a convoy member.
    val accent = MaterialTheme.colorScheme.tertiary
    val url = rememberStorageImageUrl(LocalContext.current, imagePath)

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
        Box(
            modifier =
                Modifier
                    .size(PHOTO_SIZE)
                    .clip(CircleShape)
                    .background(accent)
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
                    imageVector = Icons.Filled.Podcasts,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(PHOTO_SIZE * 0.5f),
                )
            }
        }
    }
}

// The whole chip footprint.
private val CHIP_SIZE = 44.dp

// The photo (and its ring) inside the chip.
private val PHOTO_SIZE = 38.dp
