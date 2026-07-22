package com.kungsbackacarcommunity.app.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.drives.RouteDistanceMarkers
import com.kungsbackacarcommunity.app.drives.RoutePoint
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.plugin.annotation.AnnotationConfig
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.scalebar.scalebar

/**
 * A STATIC, non-following Mapbox map that draws the actual driven route of a
 * saved drive for History replay: the decoded [RoutePoint]s as a polyline, a
 * start marker (green) and an end marker (the app's shared destination red from
 * [MapMarkerStyle]), with the camera fitted once to the whole route's bounds.
 *
 * This is NOT the live map-first shell surface ([com.kungsbackacarcommunity.app.shell.MapboxMapSurface]):
 * there is no device puck, no GPS follow, no live-share pulse — the user is
 * reviewing where they drove, so the camera frames the route and stays put
 * (pan/zoom to inspect is still allowed). It reuses the shell's exact annotation
 * patterns (PolylineAnnotationManager + CircleAnnotationManager, cameraForCoordinates
 * fit) and the shared marker styling so a route looks the same everywhere.
 *
 * ## Distinct layer/source ids
 * The annotation managers are created with EXPLICIT layer/source ids
 * ([LINE_LAYER_ID]/[LINE_SOURCE_ID], [ENDPOINT_LAYER_ID]/[ENDPOINT_SOURCE_ID])
 * prefixed `kcc-history-route-…`, so they can never collide with the shell's
 * breadcrumb, incident or traffic layers if this ever shares a style.
 *
 * ## Guards
 * The caller only composes this when a Mapbox token is configured and the route
 * has at least two points; every native call is still wrapped in `runCatching`,
 * so a style that has not finished loading or an unavailable camera-fit degrades
 * to a blank/partial map rather than crashing. On-device verification only: the
 * GL surface, annotation rendering and camera fit run solely on a
 * token-provisioned device, so they are verified on device.
 */
@Composable
fun DriveRouteMap(
    points: List<RoutePoint>,
    modifier: Modifier = Modifier,
) {
    // The route is drawn ONCE, in the factory, from the points captured when the
    // map enters composition. The caller only composes this once the route has
    // resolved to a stable, drawable list (Ready with ≥ 2 points) and disposes it
    // when leaving the drive, so those points never change under a live map —
    // which is why `update` deliberately does nothing: re-running drawRoute would
    // create a SECOND annotation manager on the same fixed layer/source ids.
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Set the global Mapbox access token (mirrors the shell surface) so
            // tiles load even if History is opened before the map-first home.
            val token = context.getString(R.string.mapbox_access_token)
            if (token.isNotBlank()) {
                MapboxOptions.accessToken = token
            }
            MapView(context).apply {
                runCatching { scalebar.updateSettings { enabled = false } }
                runCatching { compass.updateSettings { enabled = false } }
                mapboxMap.loadStyle(Style.STANDARD) { _ ->
                    drawRoute(this, points)
                }
            }
        },
        onRelease = { mapView -> runCatching { mapView.onDestroy() } },
    )
}

/**
 * Draws the polyline + start/end endpoints for [points] on [mapView] and fits
 * the camera to the whole route ONCE. Shared by the small embedded thumbnail
 * ([DriveRouteMap]) and the zoomable full-screen popup
 * ([DriveRouteFullscreenDialog]) so a route looks identical in both. Every
 * native call is wrapped in `runCatching`, so a style that has not finished
 * loading or an unavailable camera-fit degrades to a blank/partial map rather
 * than crashing. Draws nothing for a route of fewer than two points.
 */
internal fun drawRoute(mapView: MapView, points: List<RoutePoint>) {
    if (points.size < 2) return
    runCatching {
        val linePoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }

        val lineManager =
            mapView.annotations.createPolylineAnnotationManager(
                AnnotationConfig(layerId = LINE_LAYER_ID, sourceId = LINE_SOURCE_ID),
            )
        lineManager.create(
            PolylineAnnotationOptions()
                .withPoints(linePoints)
                .withLineColor(ROUTE_LINE_COLOR)
                .withLineWidth(ROUTE_LINE_WIDTH),
        )

        val endpointManager =
            mapView.annotations.createCircleAnnotationManager(
                AnnotationConfig(layerId = ENDPOINT_LAYER_ID, sourceId = ENDPOINT_SOURCE_ID),
            )
        endpointManager.create(startMarker(linePoints.first()))
        endpointManager.create(endMarker(linePoints.last()))

        fitCameraToRoute(mapView, linePoints)
    }
}

/**
 * Draws the per-kilometre markers ("1 km / 2 km / …") from
 * [RouteDistanceMarkers.markers] on [mapView]: a small dot at each crossing plus
 * a text label carrying the whole-kilometre number. Used ONLY by the zoomable
 * full-screen popup (the 240dp thumbnail is too small for readable labels).
 *
 * The marker positions are computed by the pure, unit-tested
 * [RouteDistanceMarkers] (cumulative Haversine + interpolation); this function
 * only renders them. Its annotation managers use DISTINCT `kcc-history-km-…`
 * layer/source ids so they never collide with [drawRoute]'s route/endpoint
 * layers (or the shell surface's). Wrapped in `runCatching`; a route under 1 km
 * yields no markers and draws nothing.
 *
 * [labelFor] maps a kilometre number to its localized label (the caller passes a
 * `stringResource`-backed formatter, since a pure drawing function has no
 * `Context` string access).
 */
internal fun drawKmMarkers(
    mapView: MapView,
    points: List<RoutePoint>,
    labelFor: (Int) -> String,
) {
    val markers = RouteDistanceMarkers.markers(points)
    if (markers.isEmpty()) return
    runCatching {
        val dotManager =
            mapView.annotations.createCircleAnnotationManager(
                AnnotationConfig(layerId = KM_DOT_LAYER_ID, sourceId = KM_DOT_SOURCE_ID),
            )
        val labelManager =
            mapView.annotations.createPointAnnotationManager(
                AnnotationConfig(layerId = KM_LABEL_LAYER_ID, sourceId = KM_LABEL_SOURCE_ID),
            )
        for (marker in markers) {
            val point = Point.fromLngLat(marker.longitude, marker.latitude)
            dotManager.create(
                CircleAnnotationOptions()
                    .withPoint(point)
                    .withCircleRadius(KM_MARKER_RADIUS)
                    .withCircleColor(KM_MARKER_COLOR)
                    .withCircleStrokeWidth(KM_MARKER_STROKE)
                    .withCircleStrokeColor(KM_MARKER_STROKE_COLOR),
            )
            labelManager.create(
                PointAnnotationOptions()
                    .withPoint(point)
                    .withTextField(labelFor(marker.kilometer))
                    .withTextColor(KM_LABEL_COLOR)
                    .withTextHaloColor(KM_LABEL_HALO_COLOR)
                    .withTextHaloWidth(KM_LABEL_HALO_WIDTH)
                    .withTextSize(KM_LABEL_TEXT_SIZE)
                    // Float the number just above its dot rather than over it.
                    .withTextAnchor(TextAnchor.BOTTOM)
                    .withTextOffset(KM_LABEL_TEXT_OFFSET),
            )
        }
    }
}

private fun startMarker(point: Point): CircleAnnotationOptions =
    CircleAnnotationOptions()
        .withPoint(point)
        .withCircleRadius(MapMarkerStyle.DEST_MARKER_RADIUS)
        .withCircleColor(START_MARKER_COLOR)
        .withCircleStrokeWidth(MapMarkerStyle.DEST_MARKER_STROKE)
        .withCircleStrokeColor(MapMarkerStyle.DEST_MARKER_STROKE_COLOR)

private fun endMarker(point: Point): CircleAnnotationOptions =
    CircleAnnotationOptions()
        .withPoint(point)
        .withCircleRadius(MapMarkerStyle.DEST_MARKER_RADIUS)
        .withCircleColor(MapMarkerStyle.DEST_MARKER_COLOR)
        .withCircleStrokeWidth(MapMarkerStyle.DEST_MARKER_STROKE)
        .withCircleStrokeColor(MapMarkerStyle.DEST_MARKER_STROKE_COLOR)

private fun fitCameraToRoute(mapView: MapView, linePoints: List<Point>) {
    val density = mapView.resources.displayMetrics.density
    runCatching {
        val camera =
            mapView.mapboxMap.cameraForCoordinates(
                linePoints,
                cameraOptions {},
                EdgeInsets(
                    FIT_PAD * density,
                    FIT_PAD * density,
                    FIT_PAD * density,
                    FIT_PAD * density,
                ),
                null,
                null,
            )
        mapView.mapboxMap.setCamera(camera)
    }.onFailure {
        // Degenerate/unavailable fit: at least centre on the start point.
        runCatching {
            mapView.mapboxMap.setCamera(
                cameraOptions {
                    center(linePoints.first())
                    zoom(MapMarkers.OWN_MARKER_ZOOM)
                },
            )
        }
    }
}

// Distinct from the shell's route/incident/breadcrumb layers (see class KDoc).
private const val LINE_LAYER_ID = "kcc-history-route-line"
private const val LINE_SOURCE_ID = "kcc-history-route-line-src"
private const val ENDPOINT_LAYER_ID = "kcc-history-route-endpoints"
private const val ENDPOINT_SOURCE_ID = "kcc-history-route-endpoints-src"

// Route line: the same blue the shell's route-preview overlay uses.
private const val ROUTE_LINE_COLOR = 0xFF1A73E8.toInt()
private const val ROUTE_LINE_WIDTH = 6.0

// Start pin green (drive origin); end reuses the shared destination red.
private const val START_MARKER_COLOR = 0xFF2E7D32.toInt()

// Camera-fit padding (dp; scaled to px before use) so the route clears the edges.
private const val FIT_PAD = 48.0

// ---- Per-km marker styling (popup only) ---------------------------------------
// Distinct `kcc-history-km-…` layer/source ids so the km dots + labels never
// collide with drawRoute's route/endpoint layers or the shell surface's layers.
private const val KM_DOT_LAYER_ID = "kcc-history-km-dots"
private const val KM_DOT_SOURCE_ID = "kcc-history-km-dots-src"
private const val KM_LABEL_LAYER_ID = "kcc-history-km-labels"
private const val KM_LABEL_SOURCE_ID = "kcc-history-km-labels-src"

// Marker dot: the route blue, on a small white-outlined dot so it reads against
// both the route line and the basemap. Smaller than the start/end endpoints so a
// km mark never competes with the drive's origin/destination.
private const val KM_MARKER_COLOR = ROUTE_LINE_COLOR
private const val KM_MARKER_RADIUS = 5.0
private const val KM_MARKER_STROKE = 1.5
private const val KM_MARKER_STROKE_COLOR = 0xFFFFFFFF.toInt()

// Label: the km number in white with a dark halo so it stays legible over any
// basemap tile. Anchored above the dot.
private const val KM_LABEL_COLOR = 0xFFFFFFFF.toInt()
private const val KM_LABEL_HALO_COLOR = 0xFF1A73E8.toInt()
private const val KM_LABEL_HALO_WIDTH = 1.5
private const val KM_LABEL_TEXT_SIZE = 12.0
private val KM_LABEL_TEXT_OFFSET = listOf(0.0, -1.0)
