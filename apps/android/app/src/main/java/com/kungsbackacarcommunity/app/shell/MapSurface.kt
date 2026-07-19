package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
 * A crowd-sourced incident marker to draw on the map (the Waze-style layer,
 * shared by all users). Shell-owned and self-contained so the [MapSurface] seam
 * stays free of the incidents package's types: [colorArgb] is the category
 * colour resolved by the host, [id] identifies the marker for de-duplication.
 */
data class MapIncidentMarker(
    val id: String,
    val longitude: Double,
    val latitude: Double,
    val colorArgb: Int,
)

/**
 * A user gesture on the map asking "navigate to this place?".
 *
 * Raised by BOTH map gestures that mean the same thing, so they resolve to the
 * SAME confirmation instead of two parallel ones:
 * - a long-press on open map — [name] is null (nothing is there to name, so the
 *   host reverse-geocodes / falls back to a "dropped pin" label);
 * - a single tap on a place the basemap already draws (a shop, a petrol
 *   station, a restaurant) — [name] is that place's own label, so the
 *   confirmation can say where the user is actually going.
 *
 * The distinction is deliberately only the [name]: everything downstream (the
 * preview, the route, the confirmation) treats the two identically.
 */
data class MapPlaceRequest(
    val point: MapPoint,
    val name: String?,
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

    /**
     * The current map bearing in degrees (0 = north-up, clockwise). Observed by
     * the floating compass control, which rotates its north-arrow by the negative
     * of this value so the arrow keeps pointing to true north as the map rotates.
     * A surface that cannot rotate (the stub) exposes a constant 0.
     */
    val bearing: StateFlow<Float>

    /** The destination + route line to draw, or null when none is set. */
    val routeOverlay: StateFlow<MapRouteOverlay?>

    /** The crowd-sourced incident markers to draw (the shared incidents layer). */
    val incidentMarkers: StateFlow<List<MapIncidentMarker>>

    /**
     * The most recent "navigate to this place?" gesture, or null when none is
     * pending. The host observes this to open the navigation preview for the
     * requested place, then calls [consumePlaceRequest] to clear it.
     *
     * ONE flow for both gestures ([emitLongPress] and [emitPlaceTap]) so a
     * long-press and a place tap can never drift into two different
     * confirmations — see [MapPlaceRequest].
     */
    val placeRequest: StateFlow<MapPlaceRequest?>

    /** Recentre the camera on the user's position. */
    fun recenter()

    /**
     * Record a long-press on open map at [point] (no place name available).
     * Called by the real surface's long-click gesture listener; also drivable by
     * the stub/tests to simulate the gesture.
     */
    fun emitLongPress(point: MapPoint)

    /**
     * Record a single tap on a basemap place at [point], named [name]. Called by
     * the real surface's place-tap interaction; also drivable by the stub/tests.
     */
    fun emitPlaceTap(point: MapPoint, name: String?)

    /** Clear the pending [placeRequest] once the host has opened the preview for it. */
    fun consumePlaceRequest()

    /**
     * Reset the map to north-up: ease the camera bearing back to 0. A no-op on
     * the stub, which has no rotatable camera.
     */
    fun resetNorth()

    /**
     * Re-apply the device-location component after the runtime fine-location
     * permission is granted, so the blue puck appears without recreating the
     * map (the component is enabled at style-load, before the grant, and the
     * Mapbox location provider does not retroactively start once permission is
     * granted). A no-op on the stub, which has no device/GPS.
     */
    fun refreshLocationComponent()

    /**
     * Whether the map is actually visible to the user.
     *
     * The map stays COMPOSED for the whole signed-in shell — every bottom-nav
     * tab, every full-screen route, the address search and turn-by-turn — so its
     * GL surface and loaded style survive every navigation. Disposing it made
     * coming back rebuild the whole `MapView` and blank the screen for a beat.
     * The price of keeping it alive is that a map nobody can see would otherwise
     * keep pulsing its puck (continuous GL redraw) and keep consuming location
     * fixes, so the shell calls `setActive(false)` while something OPAQUE covers
     * it and `setActive(true)` when it is visible again.
     *
     * Note "visible", not "the active page": the address-search overlay draws its
     * chrome over a map the user is still looking at (it shows the route and the
     * puck), so the map stays ACTIVE underneath it. Only a page that actually
     * hides the map stands it down.
     *
     * Deactivating only stands the location component down; the surface, the
     * style and the camera are all left intact, which is the whole point.
     * Reactivating re-applies it exactly like [refreshLocationComponent] does
     * after a permission grant. A no-op on the stub, which has no device/GPS.
     */
    fun setActive(active: Boolean)

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

    /**
     * Replace the set of incident markers drawn on the map. The host fetches
     * these near the viewport via `incidents.listNearby` and pushes them here so
     * every user sees them. A no-op beyond storing the value on the stub.
     */
    fun setIncidentMarkers(markers: List<MapIncidentMarker>)

    /**
     * The map view itself, filling [modifier].
     *
     * Composed at exactly ONE place in the whole signed-in shell (see
     * `AuthenticatedApp`), underneath every page, and never left behind by a
     * navigation. Each entry into the composition builds a fresh `MapView` and
     * re-runs the style load on the real surface, and the window has nothing to
     * show until that first GL frame lands — so a second call site (or a call
     * site inside a page that can be navigated away from) is a blank-flash bug,
     * not a style choice.
     */
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

    // The stub has no rotatable camera: bearing is a constant north-up 0.
    private val bearingFlow = MutableStateFlow(0f)
    override val bearing: StateFlow<Float> = bearingFlow.asStateFlow()

    private val routeOverlayFlow = MutableStateFlow<MapRouteOverlay?>(null)
    override val routeOverlay: StateFlow<MapRouteOverlay?> = routeOverlayFlow.asStateFlow()

    private val incidentMarkersFlow = MutableStateFlow<List<MapIncidentMarker>>(emptyList())
    override val incidentMarkers: StateFlow<List<MapIncidentMarker>> =
        incidentMarkersFlow.asStateFlow()

    private val placeRequestFlow = MutableStateFlow<MapPlaceRequest?>(null)
    override val placeRequest: StateFlow<MapPlaceRequest?> = placeRequestFlow.asStateFlow()

    /** Number of [recenter] calls — used by tests to assert the wiring. */
    var recenterCount: Int = 0
        private set

    /**
     * Last value passed to [setActive] — the stub has no GL surface or GPS to
     * stand down, so it just records the call for tests asserting that the shell
     * deactivates the map while another tab covers it. Starts active: the shell
     * opens on the Map tab.
     */
    var isActive: Boolean = true
        private set

    /**
     * How many times [Content] has ENTERED the composition. The real surface
     * rebuilds its whole MapView whenever this happens (see MapboxMapSurface's
     * AndroidView factory/onRelease), which is what used to blank the screen on
     * the way back to the Map tab — so tests assert this stays at 1 across a tab
     * round-trip, i.e. the map was never disposed.
     */
    var contentCompositions: Int = 0
        private set

    override fun setActive(active: Boolean) {
        isActive = active
    }

    override fun recenter() {
        recenterCount += 1
    }

    override fun emitLongPress(point: MapPoint) {
        placeRequestFlow.value = MapPlaceRequest(point = point, name = null)
    }

    override fun emitPlaceTap(point: MapPoint, name: String?) {
        placeRequestFlow.value = MapPlaceRequest(point = point, name = name)
    }

    override fun consumePlaceRequest() {
        placeRequestFlow.value = null
    }

    /** No rotatable camera on the stub, so resetting to north is a no-op. */
    override fun resetNorth() = Unit

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

    override fun setIncidentMarkers(markers: List<MapIncidentMarker>) {
        incidentMarkersFlow.value = markers
    }

    /** Test/impl hook to force the load state (e.g. after tiles finish). */
    fun markLoaded() {
        loadStateFlow.value = MapLoadState.Loaded
    }

    @Composable
    override fun Content(modifier: Modifier) {
        // Count entries into the composition (not recompositions) so tests can
        // assert the shell keeps ONE map alive across a tab round-trip. On the
        // real surface each entry is a fresh MapView + style load.
        DisposableEffect(Unit) {
            contentCompositions += 1
            onDispose {}
        }
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
