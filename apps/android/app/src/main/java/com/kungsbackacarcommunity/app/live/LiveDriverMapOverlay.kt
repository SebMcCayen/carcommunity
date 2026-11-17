package com.kungsbackacarcommunity.app.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
import com.kungsbackacarcommunity.app.convoy.ConvoyMapAwarenessOverlay
import com.kungsbackacarcommunity.app.convoy.rememberSmoothedMembers
import com.kungsbackacarcommunity.app.map.ConvoyMemberPosition
import com.kungsbackacarcommunity.app.map.DriverPhotoAnchor
import com.kungsbackacarcommunity.app.map.DriverPhotoLayout
import com.kungsbackacarcommunity.app.map.DriverPhotoLayoutResult
import com.kungsbackacarcommunity.app.map.DriverPhotoPoint
import com.kungsbackacarcommunity.app.map.DriverPhotoRect
import com.kungsbackacarcommunity.app.map.toConvoyMemberPosition
import com.kungsbackacarcommunity.app.shell.MapProjection
import kotlin.math.roundToInt

/** Actual chrome bounds in the map overlay's pixel coordinate space. */
val LocalDriverPhotoExclusions = compositionLocalOf<List<DriverPhotoRect>> { emptyList() }

/** One shared layout and presentation roster for BOTH driver layers. */
@Composable
fun LiveDriverMapOverlay(
    mapSurface: MapProjection,
    convoy: List<ConvoyMemberPosition>,
    nearby: List<LiveMarker>,
    onDriverTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    appearances: Map<String, LiveMarkerAppearance> = emptyMap(),
    focusFitActive: Boolean = false,
    onConvoyDrawn: (Set<String>) -> Unit = {},
    onNearbyDrawn: (Set<String>) -> Unit = {},
) {
    val camera by mapSurface.cameraSnapshot.collectAsState()
    val own by mapSurface.renderedOwnPoint.collectAsState()
    val density = LocalDensity.current.density.toDouble()
    val excludedPixels = LocalDriverPhotoExclusions.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var arrowUids by remember { mutableStateOf(emptySet<String>()) }
    val convoyUids = remember(convoy) { convoy.map { it.uid }.toSet() }
    val roster = remember(convoy, nearby) {
        (convoy + nearby.filter { it.uid !in convoyUids }.map { it.toConvoyMemberPosition() }).distinctBy { it.uid }
    }
    val displayed = rememberSmoothedMembers(roster) { System.currentTimeMillis() }
    val originals = remember(roster) { roster.associateBy { it.uid } }
    val layout = remember { DriverPhotoLayout() }
    val result = remember(camera, own, displayed, size, excludedPixels, density) {
        val anchors = if (camera == null) emptyList() else displayed.mapNotNull { member ->
            val point = mapSurface.screenPositionFor(member.latitude, member.longitude)
            if (point == null || !point.trustworthy || point.x !in 0f..size.width.toFloat() || point.y !in 0f..size.height.toFloat()) null
            else DriverPhotoAnchor(member.uid, DriverPhotoPoint(point.x / density, point.y / density))
        }
        val ownScreen = own?.let { mapSurface.screenPositionFor(it.latitude, it.longitude) }?.takeIf { it.trustworthy }
            ?.let { DriverPhotoPoint(it.x / density, it.y / density) }
        val exclusions = excludedPixels.map { DriverPhotoRect(it.left / density, it.top / density, it.right / density, it.bottom / density) }
        layout.layout(anchors, size.width / density, size.height / density, ownScreen, exclusions)
    }
    val placedUids = result.placed.map { it.uid }.toSet()
    val inViewportUids = placedUids + result.overflow.map { it.uid }
    ReportDrawnLiveMarkerUids((placedUids.intersect(convoyUids) + arrowUids).intersect(originals.keys), onConvoyDrawn)
    ReportDrawnLiveMarkerUids(placedUids.minus(convoyUids).intersect(originals.keys), onNearbyDrawn)

    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        // Keep the existing convoy edge-arrow and fit diagnostics behavior.
        ConvoyMapAwarenessOverlay(
            mapSurface = mapSurface,
            members = convoy,
            presentedMembers = displayed.filter { it.uid in convoyUids },
            showOnScreenPhotos = false,
            suppressedArrowUids = inViewportUids,
            focusFitActive = focusFitActive,
            appearances = appearances,
            onDrawnUidsChanged = { arrowUids = it },
        )
        DriverLeaderLines(result)
        Box(Modifier.fillMaxSize().testTag(NEARBY_LIVE_OVERLAY_TAG)) {
            for (placement in result.placed) {
                val member = originals[placement.uid] ?: continue
                val description = if (member.uid in convoyUids) stringResource(R.string.convoy_awarenessMemberOnMap, member.displayName ?: stringResource(R.string.convoy_unknownMember))
                    else stringResource(R.string.nearby_liveSharerOnMap, member.displayName ?: stringResource(R.string.nearby_unknownSharer))
                Box(
                    Modifier.offset { IntOffset(((placement.photo.x - 24) * density).roundToInt(), ((placement.photo.y - 24) * density).roundToInt()) }
                        .size(48.dp)
                        .clickable(role = Role.Button) { onDriverTap(member.uid) }
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .testTag(NEARBY_LIVE_CHIP_TAG + member.uid),
                    contentAlignment = Alignment.Center,
                ) {
                    LiveCarPhotoBadge(member.imagePath, appearances[member.uid] ?: LiveMarkerAppearance(),
                        ringColor = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

@Composable
private fun DriverLeaderLines(result: DriverPhotoLayoutResult) {
    val density = LocalDensity.current.density
    val line = MaterialTheme.colorScheme.onSurface
    val dot = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxSize().testTag("driver_photo_leaders")) {
        for (item in result.placed) {
            for (segment in item.lines) drawLine(line, Offset(segment.start.x.toFloat() * density, segment.start.y.toFloat() * density),
                Offset(segment.end.x.toFloat() * density, segment.end.y.toFloat() * density), 1.5f * density, StrokeCap.Round)
            if (item.showAnchor) {
                val anchor = Offset(item.anchor.x.toFloat() * density, item.anchor.y.toFloat() * density)
                drawCircle(dot, 3f * density, anchor)
                drawCircle(line, 3f * density, anchor, style = Stroke(1f * density))
            }
        }
    }
}
