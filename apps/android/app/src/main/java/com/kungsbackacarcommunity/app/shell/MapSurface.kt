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
 * The caller's own marker state. [label] is the display name; [isLiveSharing]
 * is true while the user is actively live-sharing their location (drives the
 * green puck pulse), false otherwise.
 */
data class MapUserMarker(
    val label: String,
    val isLiveSharing: Boolean,
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
 */
interface MapSurface {
    /** Current load state; the shell shows "Loading roads…" while [MapLoadState.Loading]. */
    val loadState: StateFlow<MapLoadState>

    /** The user marker the surface should draw, or null when none is set. */
    val userMarker: StateFlow<MapUserMarker?>

    /** Whether the traffic-congestion overlay is currently shown. */
    val trafficEnabled: StateFlow<Boolean>

    /** Recentre the camera on the user's position. */
    fun recenter()

    /** Set (or clear, with null) the caller's own marker. */
    fun setUserMarker(marker: MapUserMarker?)

    /** Show or hide the traffic-congestion overlay (no visible effect on the stub). */
    fun setTrafficEnabled(enabled: Boolean)

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

    /** Number of [recenter] calls — used by tests to assert the wiring. */
    var recenterCount: Int = 0
        private set

    override fun recenter() {
        recenterCount += 1
    }

    override fun setUserMarker(marker: MapUserMarker?) {
        userMarkerFlow.value = marker
    }

    override fun setTrafficEnabled(enabled: Boolean) {
        trafficFlow.value = enabled
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
