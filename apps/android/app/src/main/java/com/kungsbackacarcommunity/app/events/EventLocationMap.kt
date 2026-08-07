package com.kungsbackacarcommunity.app.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.map.MapMarkerStyle
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.plugin.animation.MapAnimationOptions.Companion.mapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.AnnotationConfig
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.scalebar.scalebar

/**
 * Pure presentation logic for the event-detail location map — Android-free so the
 * "is there a point to show at all" decision is JVM-unit-testable without a device
 * or a Mapbox surface.
 */
object EventMapPresentation {
    /**
     * The single marker point for [event], or null when it carries no valid,
     * complete pin. Drives BOTH the map and the Navigate button: when this is null
     * the detail screen hides the whole location section gracefully (task
     * requirement — "if the event has no coordinates, hide the map + navigate
     * button"). Uses the SAME both-or-neither + WGS-84 gate the create form and the
     * map pin layer use ([Events.isValidCoordinatePair]), so a half-set or
     * out-of-range pair is treated as "no location" everywhere.
     */
    fun markerPoint(event: EventSummary): LatLng? {
        val lat = event.latitude
        val lng = event.longitude
        if (lat == null || lng == null) return null
        if (!Events.isValidCoordinatePair(lat, lng)) return null
        return LatLng(longitude = lng, latitude = lat)
    }

    /** A town-scale zoom for a single pin — close enough to read the street. */
    const val MARKER_ZOOM = 14.0
}

/**
 * A small, STATIC (non-following, non-interactive) Mapbox map showing a single
 * marker at the event's [point], embedded on the event detail screen. Tapping it
 * MAXIMIZES to [EventLocationFullscreenDialog] — so the whole thumbnail is a
 * button that raises the zoomable full-screen map.
 *
 * Mirrors [com.kungsbackacarcommunity.app.map.DriveRouteMap]: the map's own
 * pan/zoom gestures are OFF so the parent `clickable` reliably receives the
 * tap-to-expand, the token is read from resources and every native call is wrapped
 * in `runCatching`, so a not-yet-loaded style degrades to a blank tile rather than
 * a crash. The Mapbox attribution plugin is left ENABLED (only the scalebar/compass
 * clutter is removed), keeping the "© Mapbox" attribution the SDK renders — the
 * same convention the History route maps use. On-device verification only.
 *
 * The caller only composes this when a token is configured and [point] is non-null
 * ([EventMapPresentation.markerPoint]).
 */
@Composable
fun EventLocationMap(
    point: LatLng,
    onMaximize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapDesc = stringResource(R.string.events_mapEmbeddedLabel)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(KccSpacing.s3))
                .clickable(role = Role.Button, onClick = onMaximize)
                .semantics { contentDescription = mapDesc }
                .testTag(EVENT_MAP_EMBEDDED_TAG),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val token = context.getString(R.string.mapbox_access_token)
                if (token.isNotBlank()) {
                    MapboxOptions.accessToken = token
                }
                MapView(context).apply {
                    runCatching { scalebar.updateSettings { enabled = false } }
                    runCatching { compass.updateSettings { enabled = false } }
                    // Static thumbnail: turn OFF the MapView's own touch handling so
                    // the parent `clickable` reliably receives the tap-to-expand.
                    runCatching {
                        gestures.updateSettings {
                            scrollEnabled = false
                            pinchToZoomEnabled = false
                            rotateEnabled = false
                            pitchEnabled = false
                            doubleTapToZoomInEnabled = false
                            doubleTouchToZoomOutEnabled = false
                            quickZoomEnabled = false
                        }
                    }
                    runCatching {
                        mapboxMap.setCamera(
                            cameraOptions {
                                center(Point.fromLngLat(point.longitude, point.latitude))
                                zoom(EventMapPresentation.MARKER_ZOOM)
                            },
                        )
                    }
                    mapboxMap.loadStyle(Style.STANDARD) { _ -> drawEventMarker(this, point) }
                }
            },
            onRelease = { mapView -> runCatching { mapView.onDestroy() } },
        )
    }
}

/**
 * The full-screen, zoomable version of the event location map, opened by tapping
 * the embedded [EventLocationMap] thumbnail. Draws the SAME single marker (reusing
 * [drawEventMarker]) but ADDS pan + pinch-to-zoom (the Maps SDK gestures are on by
 * default and left on here) and explicit zoom-in / zoom-out buttons for precise
 * control and accessibility. Dismisses on back press, the close button, or a scrim
 * tap.
 *
 * Mirrors [com.kungsbackacarcommunity.app.map.DriveRouteFullscreenDialog]; every
 * native call is wrapped defensively and the Mapbox attribution stays enabled.
 */
@Composable
fun EventLocationFullscreenDialog(
    point: LatLng,
    onDismiss: () -> Unit,
) {
    val zoomInDesc = stringResource(R.string.events_mapZoomIn)
    val zoomOutDesc = stringResource(R.string.events_mapZoomOut)
    val closeDesc = stringResource(R.string.events_mapClose)
    val mapDesc = stringResource(R.string.events_mapFullscreenLabel)

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize().testTag(EVENT_MAP_FULLSCREEN_TAG)) {
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
                        runCatching { scalebar.updateSettings { enabled = false } }
                        runCatching { compass.updateSettings { enabled = false } }
                        runCatching {
                            mapboxMap.setCamera(
                                cameraOptions {
                                    center(Point.fromLngLat(point.longitude, point.latitude))
                                    zoom(EventMapPresentation.MARKER_ZOOM)
                                },
                            )
                        }
                        mapboxMap.loadStyle(Style.STANDARD) { _ -> drawEventMarker(this, point) }
                    }
                },
                onRelease = { mapView ->
                    mapViewRef = null
                    runCatching { mapView.onDestroy() }
                },
            )

            FilledIconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(KccSpacing.s4)
                        .testTag(EVENT_MAP_CLOSE_TAG)
                        .semantics { contentDescription = closeDesc },
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }

            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(KccSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
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
 * Draws the single destination marker at [point] on [mapView], reusing the app's
 * shared destination-marker styling ([MapMarkerStyle]) so an event pin looks like
 * every other destination pin. Its own `kcc-event-detail-marker` layer/source ids
 * keep it clear of the shell surface's layers if a style is ever shared. Wrapped in
 * `runCatching`, so a not-yet-loaded style degrades to no marker rather than a
 * crash. On-device verification only.
 */
internal fun drawEventMarker(mapView: MapView, point: LatLng) {
    runCatching {
        val manager =
            mapView.annotations.createCircleAnnotationManager(
                AnnotationConfig(layerId = MARKER_LAYER_ID, sourceId = MARKER_SOURCE_ID),
            )
        manager.create(
            CircleAnnotationOptions()
                .withPoint(Point.fromLngLat(point.longitude, point.latitude))
                .withCircleRadius(MapMarkerStyle.DEST_MARKER_RADIUS)
                .withCircleColor(MapMarkerStyle.DEST_MARKER_COLOR)
                .withCircleStrokeWidth(MapMarkerStyle.DEST_MARKER_STROKE)
                .withCircleStrokeColor(MapMarkerStyle.DEST_MARKER_STROKE_COLOR),
        )
    }
}

/**
 * Eases the map's zoom by [delta] levels (clamped to the Mapbox valid range),
 * keeping the current centre. Wrapped defensively so a call before the style /
 * camera is ready is a no-op rather than a crash. Mirrors DriveRouteFullscreenDialog.
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

/** Test tags so UI tests can address the embedded/full-screen maps and controls. */
const val EVENT_MAP_EMBEDDED_TAG = "events_map_embedded"
const val EVENT_MAP_FULLSCREEN_TAG = "events_map_fullscreen"
const val EVENT_MAP_CLOSE_TAG = "events_map_close"

private const val MARKER_LAYER_ID = "kcc-event-detail-marker"
private const val MARKER_SOURCE_ID = "kcc-event-detail-marker-src"

private const val ZOOM_STEP = 1.0
private const val ZOOM_ANIMATION_MS = 200L
private const val MIN_ZOOM = 0.0
private const val MAX_ZOOM = 22.0
