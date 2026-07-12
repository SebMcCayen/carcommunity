package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Load state of the map render, driving the transient "Loading roads…" status
 * line in the shell. Mirrors a typical tile/style load lifecycle.
 */
enum class MapLoadState {
    /** Style/tiles are being fetched — the shell shows "Loading roads…". */
    Loading,

    /** The map is interactive — the status line is hidden. */
    Loaded,
}

/**
 * Which light preset the Mapbox Standard style renders: [Day] (the default,
 * bright basemap) or [Night] (the dark/dusk preset). Toggled by the map-layers
 * popup's day/night switch. A surface that cannot re-style (the stub) still
 * exposes the flag so the shell wiring stays uniform; only the real Mapbox
 * surface actually swaps the style's `lightPreset`.
 */
enum class MapMode {
    /** Bright/day light preset (default). */
    Day,

    /** Dark/night light preset. */
    Night,
}

/**
 * The caller's own marker state. [label] is the display name; [isLiveSharing]
 * is true while the user is actively live-sharing their location (drives the
 * green puck pulse), false otherwise.
 */
data class MapUserMarker(
    val label: String,
    val isLiveSharing: Boolean,
)

/** A single lng/lat vertex of a drawn route line. */
data class MapPoint(
    val longitude: Double,
    val latitude: Double,
)

/**
 * A destination + the route line to draw for it. Owned by the shell (kept free
 * of the navigation package's types) so the [MapSurface] seam stays
 * self-contained; the host maps a resolved route onto this. An empty [path]
 * still marks the destination (line simply not drawn).
 */
data class MapRouteOverlay(
    val destination: MapPoint,
    val path: List<MapPoint>,
)

/**
 * Seam between the map-first shell and the actual map renderer.
 *
 * The entire shell (search bar, "Loading roads…" state, floating controls,
 * "Create route" CTA, bottom nav) is written against this abstraction so it
 * compiles, unit/UI-tests, and passes CI **without** a device, GPS, or a
 * Mapbox access token. The current implementation is [StubMapSurface], which
 * renders a neutral themed placeholder. The real Mapbox render + live GPS drop
 * in later behind this same interface (the existing `map/` Mapbox code is left
 * in place for that follow-up).
 *
 * Hooks:
 * - [recenter] — recentre the camera on the user (stub records the request).
 * - [setUserMarker] — supply/clear the caller's live-sharing marker state
 *   ([MapUserMarker]: a label plus [MapUserMarker.isLiveSharing], which drives
 *   the puck's green/blue pulse).
 * - [loadState] — drives the shell's "Loading roads…" indicator.
 * - [trafficEnabled] / [setTrafficEnabled] — the optional traffic-congestion
 *   overlay the layers control toggles. A surface that cannot draw traffic
 *   (the stub) still exposes the flag so the shell wiring stays uniform; only
 *   the real Mapbox surface renders coloured congestion lines.
 * - [mapMode] / [setMapMode] — day vs night light preset of the Standard
 *   style, toggled by the layers popup (stub exposes the flag only).
 * - [is3d] / [set3dEnabled] — tilted 3D vs flat top-down 2D camera, toggled by
 *   the layers popup; the real surface flips the camera pitch and re-centres
 *   (stub exposes the flag only).
 */
interface MapSurface {
    /** Current load state; the shell shows "Loading roads…" while [MapLoadState.Loading]. */
    val loadState: StateFlow<MapLoadState>

    /** The user marker the surface should draw, or null when none is set. */
    val userMarker: StateFlow<MapUserMarker?>

    /** Whether the traffic-congestion overlay is currently shown. */
    val trafficEnabled: StateFlow<Boolean>

    /** The current light preset (day/night) of the Standard style. */
    val mapMode: StateFlow<MapMode>

    /** Whether the camera is in tilted 3D mode (true) or flat 2D (false). */
    val is3d: StateFlow<Boolean>

    /** The destination + route line to draw, or null when none is set. */
    val routeOverlay: StateFlow<MapRouteOverlay?>

    /** Recentre the camera on the user's position. */
    fun recenter()

    /**
     * Re-apply the device-location component after the runtime fine-location
     * permission is granted, so the blue puck appears without recreating the
     * map (the component is enabled at style-load, before the grant, and the
     * Mapbox location provider does not retroactively start once permission is
     * granted). A no-op on the stub, which has no device/GPS.
     */
    fun refreshLocationComponent()

    /** Set (or clear, with null) the caller's own marker. */
    fun setUserMarker(marker: MapUserMarker?)

    /** Show or hide the traffic-congestion overlay (no visible effect on the stub). */
    fun setTrafficEnabled(enabled: Boolean)

    /** Switch the day/night light preset (no visible effect on the stub). */
    fun setMapMode(mode: MapMode)

    /** Switch between tilted 3D ([true]) and flat 2D ([false]) (no visible effect on the stub). */
    fun set3dEnabled(enabled: Boolean)

    /**
     * Draw (or clear, with null) a destination marker + route line and fit the
     * camera to it. A no-op beyond storing the value on the stub.
     */
    fun setRouteOverlay(overlay: MapRouteOverlay?)

    /** The map view itself, filling [modifier]. */
    @Composable
    fun Content(modifier: Modifier)
}

/**
 * Placeholder [MapSurface] with no device/SDK dependency: it renders a neutral,
 * themed box labeled "Map" and simulates a brief tile load so the shell's
 * "Loading roads…" state is exercised. Deterministic for tests — construct with
 * an explicit [initialState] (and [autoLoad] = false) to pin the load state.
 *
 * [recenterCount] and the last [userMarker] are observable so UI/unit tests can
 * assert the shell wires the floating controls to the right hooks.
 */
class StubMapSurface(
    initialState: MapLoadState = MapLoadState.Loading,
    private val autoLoad: Boolean = true,
) : MapSurface {
    private val loadStateFlow = MutableStateFlow(initialState)
    override val loadState: StateFlow<MapLoadState> = loadStateFlow.asStateFlow()

    private val userMarkerFlow = MutableStateFlow<MapUserMarker?>(null)
    override val userMarker: StateFlow<MapUserMarker?> = userMarkerFlow.asStateFlow()

    private val trafficFlow = MutableStateFlow(false)
    override val trafficEnabled: StateFlow<Boolean> = trafficFlow.asStateFlow()

    // Mirrors the real surface's defaults: day light preset, 3D camera on.
    private val mapModeFlow = MutableStateFlow(MapMode.Day)
    override val mapMode: StateFlow<MapMode> = mapModeFlow.asStateFlow()

    private val is3dFlow = MutableStateFlow(true)
    override val is3d: StateFlow<Boolean> = is3dFlow.asStateFlow()

    private val routeOverlayFlow = MutableStateFlow<MapRouteOverlay?>(null)
    override val routeOverlay: StateFlow<MapRouteOverlay?> = routeOverlayFlow.asStateFlow()

    /** Number of [recenter] calls — used by tests to assert the wiring. */
    var recenterCount: Int = 0
        private set

    override fun recenter() {
        recenterCount += 1
    }

    /** No device/GPS on the stub, so there is no location component to refresh. */
    override fun refreshLocationComponent() = Unit

    override fun setUserMarker(marker: MapUserMarker?) {
        userMarkerFlow.value = marker
    }

    override fun setTrafficEnabled(enabled: Boolean) {
        trafficFlow.value = enabled
    }

    override fun setMapMode(mode: MapMode) {
        mapModeFlow.value = mode
    }

    override fun set3dEnabled(enabled: Boolean) {
        is3dFlow.value = enabled
    }

    override fun setRouteOverlay(overlay: MapRouteOverlay?) {
        routeOverlayFlow.value = overlay
    }

    /** Test/impl hook to force the load state (e.g. after tiles finish). */
    fun markLoaded() {
        loadStateFlow.value = MapLoadState.Loaded
    }

    @Composable
    override fun Content(modifier: Modifier) {
        // Simulate a short tile/style load once, so the "Loading roads…" line
        // appears briefly then clears. Disabled when autoLoad is false so tests
        // can pin the state deterministically.
        if (autoLoad) {
            LaunchedEffect(Unit) {
                if (loadStateFlow.value == MapLoadState.Loading) {
                    delay(STUB_LOAD_MILLIS)
                    loadStateFlow.value = MapLoadState.Loaded
                }
            }
        }
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.shell_mapPlaceholder),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    private companion object {
        const val STUB_LOAD_MILLIS = 700L
    }
}

/** Remembers a [StubMapSurface] across recompositions (default shell wiring). */
@Composable
fun rememberStubMapSurface(): StubMapSurface = remember { StubMapSurface() }
