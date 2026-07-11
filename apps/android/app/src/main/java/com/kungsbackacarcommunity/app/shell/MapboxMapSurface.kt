package com.kungsbackacarcommunity.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccPalette
import com.kungsbackacarcommunity.app.map.MapMarkers
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
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
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.scalebar.scalebar
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
 * ## How the user's own position is drawn
 * The user is shown by the Mapbox location-component puck at the real GPS
 * position, so it stays anchored to the ground when the map pans (there is no
 * centre-locked Compose overlay). The [MapUserMarker] pushed via
 * [setUserMarker] now only carries live-sharing state: the puck pulses green
 * while sharing and blue otherwise.
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

    // Live references, held only while the map is composed (cleared in
    // onRelease). Touched on the main thread from Compose callbacks.
    private var mapViewRef: MapView? = null
    private var lastPoint: Point? = null

    // One-shot guard so the camera auto-centres on the FIRST GPS fix only,
    // opening close to the user; afterwards it leaves the camera alone so it
    // never fights the user panning (recenter() is still available on demand).
    private var centeredOnFirstFix: Boolean = false

    override fun setUserMarker(marker: MapUserMarker?) {
        userMarkerFlow.value = marker
    }

    override fun setTrafficEnabled(enabled: Boolean) {
        // The Content update lambda observes this flow and applies the layer's
        // visibility, so flipping the flow is enough to toggle the overlay.
        trafficFlow.value = enabled
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
        // The caller's marker only carries live-sharing state now (its position
        // is the device puck): a green pulse signals sharing, blue otherwise.
        val marker by userMarkerFlow.collectAsState()
        // Recreate the position listener once; it just records the last fix so
        // recenter() can jump the camera to it.
        val positionListener =
            remember {
                OnIndicatorPositionChangedListener { point ->
                    lastPoint = point
                    // Auto-centre on the first valid fix so the map opens close
                    // to the user rather than on the default town camera. Once
                    // only, so later fixes don't yank the camera while panning.
                    if (!centeredOnFirstFix) {
                        centeredOnFirstFix = true
                        runCatching {
                            mapViewRef?.mapboxMap?.setCamera(
                                cameraOptions {
                                    center(point)
                                    zoom(MapMarkers.OWN_MARKER_ZOOM)
                                },
                            )
                        }
                    }
                }
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
                    // Drop the scale bar (distance/km ruler, upper-left); the
                    // map-first shell has no room for it.
                    runCatching { scalebar.updateSettings { enabled = false } }
                    // Default camera until the first GPS fix arrives.
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
                        // Device-location puck (blue dot). Shows only when the
                        // location permission is granted; otherwise it stays
                        // hidden without error.
                        runCatching {
                            location.updateSettings {
                                enabled = true
                                pulsingEnabled = true
                                // This runs asynchronously when the style finishes
                                // loading — not at composition — and loadStateFlow
                                // changes don't recompose Content, so the captured
                                // `marker` can be stale here. Read the backing
                                // flow's current value to apply the latest
                                // live-sharing state when the puck is enabled.
                                pulsingColor = pulseColorFor(userMarkerFlow.value)
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
                // Reflect live-sharing on the puck: green pulse while sharing.
                runCatching {
                    mapView.location.updateSettings { pulsingColor = pulseColorFor(marker) }
                }
            },
            onRelease = { mapView ->
                runCatching {
                    mapView.location.removeOnIndicatorPositionChangedListener(positionListener)
                }
                mapViewRef = null
                lastPoint = null
                centeredOnFirstFix = false
                mapView.onDestroy()
            },
        )
    }

    private companion object {
        const val TRAFFIC_SOURCE_ID = "kcc-traffic-source"
        const val TRAFFIC_LAYER_ID = "kcc-traffic-layer"
        const val TRAFFIC_SOURCE_LAYER = "traffic"
        const val TRAFFIC_TILESET = "mapbox://mapbox.mapbox-traffic-v1"

        /**
         * Puck pulse ARGB while live-sharing. Sourced from the shell's success
         * green design token ([KccPalette.successGreen], 0xFF1E8E3E) so it stays
         * the single source of truth for the status/success green.
         */
        val LIVE_SHARE_PULSE_COLOR: Int = KccPalette.successGreen.toArgb()

        /** Puck pulse ARGB when not sharing (neutral blue). */
        val DEFAULT_PULSE_COLOR: Int = 0xFF1A73E8.toInt()

        /** Green pulse when the caller is live-sharing, blue otherwise. */
        fun pulseColorFor(marker: MapUserMarker?): Int =
            if (marker?.isLiveSharing == true) LIVE_SHARE_PULSE_COLOR else DEFAULT_PULSE_COLOR

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
