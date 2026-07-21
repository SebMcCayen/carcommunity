package com.kungsbackacarcommunity.app.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kungsbackacarcommunity.app.R
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
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
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

private fun drawRoute(mapView: MapView, points: List<RoutePoint>) {
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
