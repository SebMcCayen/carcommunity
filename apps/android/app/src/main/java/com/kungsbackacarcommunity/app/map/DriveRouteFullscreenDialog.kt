package com.kungsbackacarcommunity.app.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.drives.RoutePoint
import com.mapbox.common.MapboxOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.plugin.animation.MapAnimationOptions.Companion.mapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.scalebar.scalebar

/**
 * The full-screen, zoomable version of the History route map, opened by tapping
 * the embedded [DriveRouteMap] thumbnail on the drive-detail screen.
 *
 * It draws the SAME route as the thumbnail — reusing [drawRoute] so the polyline,
 * start/end endpoints and camera fit are identical — but ADDS:
 *  - pan + pinch-to-zoom (the Maps SDK gestures are on by default and left on
 *    here, unlike the small thumbnail),
 *  - explicit zoom-in / zoom-out buttons for precise control (and accessibility,
 *    since pinch is hard to discover), and
 *  - the per-km markers ([drawKmMarkers]) Seb asked for, which are too small to
 *    read on the 240dp thumbnail but legible on a full-screen map.
 *
 * Like [DriveRouteMap] this is a STATIC replay map (no device puck, no follow);
 * the caller only opens it once a drawable route (≥ 2 points) has resolved. Every
 * native call is wrapped defensively so a not-yet-loaded style degrades to a
 * blank map rather than crashing. The GL surface, annotations and camera are
 * on-device-only concerns (CI has an empty token), so the actual marker logic
 * lives in the pure, unit-tested [com.kungsbackacarcommunity.app.drives.RouteDistanceMarkers].
 *
 * @param points the decoded route (≥ 2 points) to draw.
 * @param onDismiss close the popup (back press, close button, or scrim tap).
 */
@Composable
fun DriveRouteFullscreenDialog(
    points: List<RoutePoint>,
    onDismiss: () -> Unit,
) {
    val kmLabelTemplate = stringResource(R.string.savedDrives_routeKmMarkerLabel)
    val zoomInDesc = stringResource(R.string.savedDrives_routeZoomIn)
    val zoomOutDesc = stringResource(R.string.savedDrives_routeZoomOut)
    val closeDesc = stringResource(R.string.savedDrives_routeMapClose)
    val mapDesc = stringResource(R.string.savedDrives_routeMapFullscreenLabel)

    // Held so the floating zoom buttons can drive the live map's camera. Touched
    // only on the main thread (Compose callbacks + the AndroidView factory).
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        // Full-bleed map: opt out of the platform's default (narrow) dialog width
        // so the map fills the screen. Back press still dismisses.
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = mapDesc },
                factory = { context ->
                    val token = context.getString(R.string.mapbox_access_token)
                    if (token.isNotBlank()) {
                        MapboxOptions.accessToken = token
                    }
                    MapView(context).apply {
                        mapViewRef = this
                        // No scale bar / built-in compass clutter — this is a
                        // review map, and the app supplies its own close/zoom.
                        runCatching { scalebar.updateSettings { enabled = false } }
                        runCatching { compass.updateSettings { enabled = false } }
                        mapboxMap.loadStyle(Style.STANDARD) { _ ->
                            // Same route as the thumbnail (shared drawRoute), plus
                            // the per-km markers the full-screen size can show.
                            drawRoute(this, points)
                            drawKmMarkers(this, points) { km ->
                                kmLabelTemplate.format(km)
                            }
                        }
                    }
                },
                onRelease = { mapView ->
                    mapViewRef = null
                    runCatching { mapView.onDestroy() }
                },
            )

            // Close control, top-start.
            FilledIconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .semantics { contentDescription = closeDesc },
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }

            // Zoom controls, bottom-end (thumb-reachable). Each nudges the camera
            // one zoom level with a short ease; a no-op until the style is loaded.
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledIconButton(
                    onClick = { mapViewRef?.let { nudgeZoom(it, ZOOM_STEP) } },
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .semantics { contentDescription = zoomInDesc },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                }
                FilledIconButton(
                    onClick = { mapViewRef?.let { nudgeZoom(it, -ZOOM_STEP) } },
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .semantics { contentDescription = zoomOutDesc },
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = null)
                }
            }
        }
    }
}

/**
 * Eases the map's zoom by [delta] levels (clamped to the Mapbox valid range),
 * keeping the current centre. Wrapped defensively so a call before the style /
 * camera is ready is a no-op rather than a crash.
 */
private fun nudgeZoom(mapView: MapView, delta: Double) {
    runCatching {
        val current = mapView.mapboxMap.cameraState.zoom
        val target = (current + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        mapView.camera.easeTo(
            cameraOptions { zoom(target) },
            mapAnimationOptions { duration(ZOOM_ANIMATION_MS) },
        )
    }
}

// One tap of a zoom button moves a whole zoom level, matching the +/- buttons
// users expect from web maps.
private const val ZOOM_STEP = 1.0
private const val ZOOM_ANIMATION_MS = 200L

// Mapbox's supported zoom range; clamp so a repeated tap can't drive past it.
private const val MIN_ZOOM = 0.0
private const val MAX_ZOOM = 22.0
