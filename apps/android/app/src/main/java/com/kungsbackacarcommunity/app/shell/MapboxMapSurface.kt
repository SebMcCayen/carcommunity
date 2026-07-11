package com.kungsbackacarcommunity.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.map.MapMarkers
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.getLayerAs
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.Visibility
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.vectorSource
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real [MapSurface]: a Mapbox v11 [MapView] bridged into Compose, drawing
 * the standard style centred on the user (device location puck), with an
 * optional traffic-congestion overlay toggled by the shell's layers control.
 *
 * This is the on-device implementation that drops in behind the [MapSurface]
 * seam built for the map-first shell. It is selected by [rememberMapSurface]
 * only when a Mapbox access token is configured; otherwise the config-less /
 * CI build falls back to [StubMapSurface] (no token, no device, no GPS), which
 * is why the shell and its tests stay green without Mapbox secrets.
 *
 * ## Access-token guard
 * The runtime token (a public `pk.` token) is read from the
 * `mapbox_access_token` string resource, which defaults to empty in CI. We set
 * [MapboxOptions.accessToken] only when it is non-blank — mirroring the
 * existing `map/MapRoute` guard — so tile loads work on device without ever
 * committing the token.
 *
 * ## Why the "You / Online" pin is not drawn here
 * The shell overlays a centred Compose [MapUserMarker] pin over the surface
 * (Waze-style: the camera follows the user, who stays screen-centred), so this
 * surface only needs to render the map + the location puck and recentre on
 * demand — it deliberately does not draw the label bubble itself.
 *
 * Every native-map mutation is wrapped defensively: a missing token, a style
 * that has not finished loading, or an absent location permission degrades to a
 * blank/partial map rather than crashing.
 */
class MapboxMapSurface : MapSurface {
    private val loadStateFlow = MutableStateFlow(MapLoadState.Loading)
    override val loadState: StateFlow<MapLoadState> = loadStateFlow.asStateFlow()

    private val userMarkerFlow = MutableStateFlow<MapUserMarker?>(null)
    override val userMarker: StateFlow<MapUserMarker?> = userMarkerFlow.asStateFlow()

    private val trafficFlow = MutableStateFlow(false)
    override val trafficEnabled: StateFlow<Boolean> = trafficFlow.asStateFlow()

    private val routeOverlayFlow = MutableStateFlow<MapRouteOverlay?>(null)
    override val routeOverlay: StateFlow<MapRouteOverlay?> = routeOverlayFlow.asStateFlow()

    // Live references, held only while the map is composed (cleared in
    // onRelease). Touched on the main thread from Compose callbacks.
    private var mapViewRef: MapView? = null
    private var lastPoint: Point? = null
    private var routeLineManager: PolylineAnnotationManager? = null
    private var destMarkerManager: CircleAnnotationManager? = null

    override fun setUserMarker(marker: MapUserMarker?) {
        userMarkerFlow.value = marker
    }

    override fun setTrafficEnabled(enabled: Boolean) {
        // The Content update lambda observes this flow and applies the layer's
        // visibility, so flipping the flow is enough to toggle the overlay.
        trafficFlow.value = enabled
    }

    override fun setRouteOverlay(overlay: MapRouteOverlay?) {
        // The Content update lambda observes this flow and (re)draws the line +
        // destination marker, so publishing the value is enough.
        routeOverlayFlow.value = overlay
    }

    override fun recenter() {
        val map = mapViewRef ?: return
        val target = lastPoint
        runCatching {
            map.mapboxMap.setCamera(
                cameraOptions {
                    if (target != null) {
                        center(target)
                        zoom(MapMarkers.OWN_MARKER_ZOOM)
                    } else {
                        center(
                            Point.fromLngLat(
                                MapMarkers.DEFAULT_CAMERA.longitude,
                                MapMarkers.DEFAULT_CAMERA.latitude,
                            ),
                        )
                        zoom(MapMarkers.DEFAULT_CAMERA.zoom)
                    }
                },
            )
        }
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val trafficOn by trafficFlow.collectAsState()
        val overlay by routeOverlayFlow.collectAsState()
        // Recreate the position listener once; it just records the last fix so
        // recenter() can jump the camera to it.
        val positionListener =
            remember {
                OnIndicatorPositionChangedListener { point -> lastPoint = point }
            }

        AndroidView(
            modifier = modifier,
            factory = { context ->
                val token = context.getString(R.string.mapbox_access_token)
                if (token.isNotBlank()) {
                    MapboxOptions.accessToken = token
                }
                MapView(context).apply {
                    mapViewRef = this
                    // Default town camera until the first GPS fix arrives.
                    mapboxMap.setCamera(
                        cameraOptions {
                            center(
                                Point.fromLngLat(
                                    MapMarkers.DEFAULT_CAMERA.longitude,
                                    MapMarkers.DEFAULT_CAMERA.latitude,
                                ),
                            )
                            zoom(MapMarkers.DEFAULT_CAMERA.zoom)
                        },
                    )
                    mapboxMap.loadStyle(Style.STANDARD) { style ->
                        loadStateFlow.value = MapLoadState.Loaded
                        runCatching { addTrafficLayer(style) }
                        runCatching { applyTrafficVisibility(style, trafficFlow.value) }
                        // Route line + destination marker managers, created once
                        // the style is ready. Drawn from the current flow value
                        // so a route picked while the style was still loading is
                        // rendered (not lost).
                        runCatching {
                            routeLineManager = annotations.createPolylineAnnotationManager()
                            destMarkerManager = annotations.createCircleAnnotationManager()
                            applyRouteOverlay(this, routeOverlayFlow.value)
                        }
                        // Device-location puck (blue dot). Shows only when the
                        // location permission is granted; otherwise it stays
                        // hidden without error.
                        runCatching {
                            location.updateSettings {
                                enabled = true
                                pulsingEnabled = true
                            }
                            location.addOnIndicatorPositionChangedListener(positionListener)
                        }
                    }
                }
            },
            update = { mapView ->
                // Apply the current traffic toggle once the style is present.
                runCatching {
                    mapView.mapboxMap.style?.let { applyTrafficVisibility(it, trafficOn) }
                }
                // (Re)draw the route line + destination marker when the overlay
                // changes; a no-op until the managers exist (style loaded).
                runCatching { applyRouteOverlay(mapView, overlay) }
            },
            onRelease = { mapView ->
                runCatching {
                    mapView.location.removeOnIndicatorPositionChangedListener(positionListener)
                }
                routeLineManager = null
                destMarkerManager = null
                mapViewRef = null
                lastPoint = null
                mapView.onDestroy()
            },
        )
    }

    /**
     * Redraws the destination marker + route line for [overlay] (clearing both
     * when null) and fits the camera to the route. A no-op until the annotation
     * managers exist (style loaded). Every native call is wrapped defensively so
     * a partial/failed draw degrades rather than crashing.
     *
     * On-device verification note: the camera-fit (`cameraForCoordinates`) and
     * annotation rendering run only on a token-provisioned device, so they are
     * verified on device; the fit falls back to centring on the destination if
     * the SDK call throws.
     */
    private fun applyRouteOverlay(mapView: MapView, overlay: MapRouteOverlay?) {
        val lineManager = routeLineManager
        val markerManager = destMarkerManager
        runCatching { lineManager?.deleteAll() }
        runCatching { markerManager?.deleteAll() }
        if (overlay == null || lineManager == null || markerManager == null) return

        val dest = Point.fromLngLat(overlay.destination.longitude, overlay.destination.latitude)
        val linePoints = overlay.path.map { Point.fromLngLat(it.longitude, it.latitude) }

        if (linePoints.size >= 2) {
            runCatching {
                lineManager.create(
                    PolylineAnnotationOptions()
                        .withPoints(linePoints)
                        .withLineColor(ROUTE_LINE_COLOR)
                        .withLineWidth(ROUTE_LINE_WIDTH),
                )
            }
        }
        runCatching {
            markerManager.create(
                CircleAnnotationOptions()
                    .withPoint(dest)
                    .withCircleRadius(DEST_MARKER_RADIUS)
                    .withCircleColor(DEST_MARKER_COLOR)
                    .withCircleStrokeWidth(DEST_MARKER_STROKE)
                    .withCircleStrokeColor(DEST_MARKER_STROKE_COLOR),
            )
        }

        // Fit the camera to the whole route (origin→destination), falling back
        // to centring on the destination if the fit call is unavailable.
        val fitPoints = if (linePoints.size >= 2) linePoints else listOf(dest)
        // Convert the dp padding to px so the camera fit is consistent across
        // screen densities (EdgeInsets expects device pixels).
        val density = mapView.resources.displayMetrics.density
        runCatching {
            val camera =
                mapView.mapboxMap.cameraForCoordinates(
                    fitPoints,
                    cameraOptions {},
                    EdgeInsets(
                        ROUTE_PAD_TOP * density,
                        ROUTE_PAD_SIDE * density,
                        ROUTE_PAD_BOTTOM * density,
                        ROUTE_PAD_SIDE * density,
                    ),
                    null,
                    null,
                )
            mapView.mapboxMap.setCamera(camera)
        }.onFailure {
            runCatching {
                mapView.mapboxMap.setCamera(
                    cameraOptions {
                        center(dest)
                        zoom(MapMarkers.OWN_MARKER_ZOOM)
                    },
                )
            }
        }
    }

    private companion object {
        const val ROUTE_LINE_COLOR = 0xFF1A73E8.toInt()
        const val ROUTE_LINE_WIDTH = 6.0
        const val DEST_MARKER_COLOR = 0xFFD32F2F.toInt()
        const val DEST_MARKER_RADIUS = 9.0
        const val DEST_MARKER_STROKE = 2.0
        const val DEST_MARKER_STROKE_COLOR = 0xFFFFFFFF.toInt()

        // Camera-fit padding (dp; multiplied by display density → px before use):
        // extra room at the bottom for the summary sheet.
        const val ROUTE_PAD_TOP = 140.0
        const val ROUTE_PAD_SIDE = 80.0
        const val ROUTE_PAD_BOTTOM = 320.0

        const val TRAFFIC_SOURCE_ID = "kcc-traffic-source"
        const val TRAFFIC_LAYER_ID = "kcc-traffic-layer"
        const val TRAFFIC_SOURCE_LAYER = "traffic"
        const val TRAFFIC_TILESET = "mapbox://mapbox.mapbox-traffic-v1"

        /**
         * Adds the Mapbox traffic vector source + a congestion-coloured line
         * layer (green → yellow → orange → red), initially hidden. Idempotent:
         * a no-op if the source/layer already exist (e.g. after a style
         * reload). Placed in the Standard style's "middle" slot so it sits
         * under labels.
         */
        fun addTrafficLayer(style: Style) {
            if (style.styleSourceExists(TRAFFIC_SOURCE_ID)) return
            style.addSource(
                vectorSource(TRAFFIC_SOURCE_ID) { url(TRAFFIC_TILESET) },
            )
            style.addLayer(
                lineLayer(TRAFFIC_LAYER_ID, TRAFFIC_SOURCE_ID) {
                    sourceLayer(TRAFFIC_SOURCE_LAYER)
                    slot("middle")
                    lineWidth(2.5)
                    visibility(Visibility.NONE)
                    lineColor(
                        Expression.match {
                            get("congestion")
                            literal("low")
                            rgb(76.0, 175.0, 80.0)
                            literal("moderate")
                            rgb(255.0, 193.0, 7.0)
                            literal("heavy")
                            rgb(255.0, 111.0, 0.0)
                            literal("severe")
                            rgb(211.0, 47.0, 47.0)
                            // Default (e.g. "unknown"): neutral grey.
                            rgb(158.0, 158.0, 158.0)
                        },
                    )
                },
            )
        }

        /** Toggles the traffic layer's visibility; a no-op until it is added. */
        fun applyTrafficVisibility(style: Style, visible: Boolean) {
            val layer = style.getLayerAs<LineLayer>(TRAFFIC_LAYER_ID) ?: return
            layer.visibility(if (visible) Visibility.VISIBLE else Visibility.NONE)
        }
    }
}

/**
 * Remembers the [MapSurface] the shell should use: the real [MapboxMapSurface]
 * when a Mapbox access token is configured, otherwise the neutral
 * [StubMapSurface]. Keeping the choice here means the config-less / CI build
 * (no token) always gets the stub — so the shell renders and its UI tests pass
 * without Mapbox, GPS, or a device — while a provisioned device build gets the
 * real map behind the same seam.
 */
@Composable
fun rememberMapSurface(): MapSurface {
    val token = stringResource(R.string.mapbox_access_token)
    return remember(token) {
        if (token.isNotBlank()) MapboxMapSurface() else StubMapSurface()
    }
}
