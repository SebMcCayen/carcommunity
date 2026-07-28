package com.kungsbackacarcommunity.app.shell

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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
 * How the map is ORIENTED while it follows the user — toggled by the floating
 * compass control.
 *
 * - [NorthUp] (default): the camera bearing stays at 0 so true north is always
 *   at the top of the screen, exactly as the map has always behaved. Following
 *   the user's POSITION continues; only the rotation is pinned.
 * - [CourseUp]: the camera rotates so the user's travel direction (the puck's
 *   COURSE bearing) points up — a "follow your direction" driving view. Position
 *   following is unchanged; only the bearing now tracks the heading.
 *
 * This is a first-class part of the [MapSurface] seam (like [MapMode]) so the
 * shell can drive it and the real surface can feed the chosen bearing into its
 * SINGLE follow path rather than spinning up a second, competing camera loop.
 */
enum class MapCompassMode {
    /** North stays at the top (default); the camera follows position only. */
    NorthUp,

    /** The map rotates to keep the user's heading pointing up (course-up). */
    CourseUp,
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
 *
 * @property bottomInsetPx device pixels along the BOTTOM edge the camera fit must
 *   leave clear, because the host is covering that much of the map with its own
 *   chrome — in practice the route-preview sheet at its collapsed height. Null
 *   (the default) keeps the surface's own built-in bottom padding, so a caller
 *   that draws nothing over the map does not have to say so. Reported by the
 *   host rather than assumed here: only the host knows how tall its sheet ended
 *   up at the current font scale and navigation-bar inset, and a fixed guess is
 *   what previously framed routes into the top half of the screen.
 */
data class MapRouteOverlay(
    val destination: MapPoint,
    val path: List<MapPoint>,
    val bottomInsetPx: Float? = null,
)

/**
 * A crowd-sourced incident marker to draw on the map (the Waze-style layer,
 * shared by all users). Shell-owned and self-contained so the [MapSurface] seam
 * stays free of the incidents package's types: the host resolves the category
 * into the primitives below, and [id] identifies the marker both for
 * de-duplication and for reporting a tap back ([MapSurface.emitIncidentTap]).
 *
 * The marker is an ICON on a coloured disc, not a bare coloured dot — colour
 * alone could not tell one category from another for a colour-blind user, so
 * the glyph carries the meaning. See
 * `com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle` for the
 * legibility rules these three values come from.
 *
 * @property colorArgb the category disc colour, resolved by the host.
 * @property iconRes drawable resource for the category glyph. A plain resource
 *   id (an `Int`) rather than a category type, which is what keeps this seam
 *   free of the incidents package while still carrying the shape.
 * @property glyphColorArgb the colour to tint [iconRes] with — chosen by the
 *   host per category for contrast against [colorArgb], since a single fixed
 *   glyph colour is unreadable on some discs.
 */
data class MapIncidentMarker(
    val id: String,
    val longitude: Double,
    val latitude: Double,
    val colorArgb: Int,
    @DrawableRes val iconRes: Int,
    val glyphColorArgb: Int,
)

/**
 * A community EVENT pin to draw on the map (visible to every signed-in user).
 * Shell-owned and self-contained so the [MapSurface] seam stays free of the
 * events package's types: the host resolves a published, upcoming event into
 * this, and [id] identifies the pin both for de-duplication and for reporting a
 * tap back ([MapSurface.emitEventTap]) so the host can open the event.
 *
 * Unlike [MapIncidentMarker] there is no per-category colour/glyph — every event
 * is one distinct event badge — so this carries only the id and position; the
 * one event icon/colour is a fixed part of the surface's event layer.
 */
data class MapEventMarker(
    val id: String,
    val longitude: Double,
    val latitude: Double,
)

/**
 * One auto-spawned Kronjakt crown to draw on the map.
 *
 * The exact sibling of [MapIncidentMarker], and separate from it for the same
 * reason that one is separate from the incidents package's types: the surface is
 * handed PRIMITIVES (a colour, a glyph resource, an id) and knows nothing about
 * rarities, point values or collect radii. The host resolves a
 * `com.kungsbackacarcommunity.app.crownhunt.CrownSpawn` into this, and resolves
 * a tapped [id] back again.
 *
 * Why not reuse [MapIncidentMarker] with a nullable glow: the two layers are
 * drawn by different managers, into different style images, and a shared type
 * would make "which layer does this marker belong to?" a runtime question. It is
 * currently a compile-time one, and a tap that opened an incident sheet for a
 * crown would be exactly the bug that costs.
 *
 * @property discColorArgb the rarity disc colour, resolved by the host.
 * @property iconRes drawable for the rarity's crown silhouette. Silhouette is
 *   the primary rarity channel (it survives any colour-vision deficiency and any
 *   basemap); the disc colour reinforces it.
 * @property glyphColorArgb the tint for [iconRes], chosen by the host for
 *   contrast against [discColorArgb].
 * @property glowColorArgb a soft halo drawn OUTSIDE the rings, or null for the
 *   tiers that have none. Only the legendary tier glows — see
 *   `CrownMarkerStyle.glowColorArgb` for why it is not on all four.
 */
data class MapCrownMarker(
    val id: String,
    val longitude: Double,
    val latitude: Double,
    val discColorArgb: Int,
    @DrawableRes val iconRes: Int,
    val glyphColorArgb: Int,
    val glowColorArgb: Int?,
)

/**
 * A point in the map view's own pixel space, as the renderer projected it.
 * Origin is the view's top-left, y grows downward.
 */
data class MapScreenPoint(
    val x: Float,
    val y: Float,
)

/**
 * A settled snapshot of where the camera is.
 *
 * Deliberately ROUNDED (see [MapCameraSnapshot.of]) rather than raw. The map
 * emits a camera change on every animation frame; a snapshot carrying full
 * precision would therefore be a new value ~60 times a second and re-run every
 * consumer with it. Rounding to about a metre, a hundredth of a zoom level and a
 * whole degree makes consecutive frames of a settled camera compare EQUAL, so
 * `StateFlow`'s own de-duplication collapses them and only real camera movement
 * propagates.
 */
data class MapCameraSnapshot(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearing: Double,
    val pitch: Double,
) {
    companion object {
        /** Rounds a raw camera state into a de-duplicable snapshot. */
        fun of(
            latitude: Double,
            longitude: Double,
            zoom: Double,
            bearing: Double,
            pitch: Double,
        ): MapCameraSnapshot =
            MapCameraSnapshot(
                // 5 decimal places of latitude is a bit over a metre — finer than
                // the camera movement anyone can see, coarser than float noise.
                latitude = round(latitude, COORDINATE_DECIMALS),
                longitude = round(longitude, COORDINATE_DECIMALS),
                zoom = round(zoom, ZOOM_DECIMALS),
                bearing = round(bearing, 0),
                pitch = round(pitch, 0),
            )

        private const val COORDINATE_DECIMALS = 5
        private const val ZOOM_DECIMALS = 2

        private fun round(value: Double, decimals: Int): Double {
            if (!value.isFinite()) return 0.0
            var factor = 1.0
            repeat(decimals) { factor *= 10.0 }
            return kotlin.math.round(value * factor) / factor
        }
    }
}

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
     * The community event pins to draw (the shared events layer, visible to every
     * signed-in user). A separate layer from the incidents one: an event is a
     * different thing from a road hazard, with its own icon, its own tap intent
     * ("open this event") and its own [eventTap] flow.
     */
    val eventMarkers: StateFlow<List<MapEventMarker>>

    /**
     * The Kronjakt crowns to draw (the auto-spawn layer).
     *
     * Empty whenever the `crownHuntSpawn` flag is off — the host does not even
     * query in that case, so "off" costs nothing rather than costing a hidden
     * layer's worth of reads.
     */
    val crownMarkers: StateFlow<List<MapCrownMarker>>

    /**
     * Where the camera currently is, or null before the map has one.
     *
     * Exists for the convoy awareness overlay, which needs the camera CENTRE and
     * BEARING to work out which way an off-screen member lies (see
     * `map/ConvoyEdgeGeometry.kt`). Rounded and de-duplicated, so a settled
     * camera does not re-run the overlay every frame. A surface with no real
     * camera (the stub) leaves this null forever.
     */
    val cameraSnapshot: StateFlow<MapCameraSnapshot?>

    /**
     * Project a geographic coordinate into the map view's pixel space, or null
     * when there is no map to project with (not composed, no style, or the stub).
     *
     * This is the renderer's OWN projection, deliberately, because it is the only
     * thing that accounts exactly for zoom, rotation AND pitch. Reimplementing it
     * would mean reimplementing the camera's projection matrix.
     *
     * Two caveats the caller must handle, both documented on
     * `ConvoyEdgeGeometry.isProjectionTrustworthy`: the returned point may be far
     * outside the viewport, and for a coordinate behind a TILTED camera it may be
     * folded back into view rather than being honestly off-screen.
     */
    fun screenPositionFor(latitude: Double, longitude: Double): MapScreenPoint?

    /**
     * The radius in METRES that covers the currently-visible map, for the
     * incident layer to query around the camera centre — or null when there is no
     * live camera to measure (not composed, no style; the stub reports a fixed
     * default instead of null so the token-less build still queries a sane area).
     *
     * The real surface derives this from the camera's visible bounds (see
     * `com.kungsbackacarcommunity.app.incidents.ViewportRadius`), so a street-level
     * view queries a small precise radius and a regional view grows it to fill the
     * screen — clamped to the server's [100 m, 50 km]. Kept on the seam because
     * the only honest read of what is on screen is the renderer's own camera; the
     * geometry that turns bounds into a radius is pure and unit-tested off-device.
     */
    fun visibleRadiusMeters(): Double?

    /**
     * Frame [points] (with padding) instead of following the user, or pass null
     * to go back to normal follow.
     *
     * This is how "keep the whole convoy in view" is expressed, and it is routed
     * through the surface's EXISTING follow path rather than moving the camera
     * itself: the same manual-gesture detach, the same idle-return timer, and the
     * same deference to a route overlay all apply unchanged. Two camera owners
     * fighting each other is the failure mode of this feature, so there is
     * exactly one.
     *
     * Clearing it (null) restores the normal framing, including the zoom, so a
     * convoy that ends cannot leave the camera stuck zoomed out over the group.
     * A no-op beyond storing the value on the stub.
     *
     * [focusEnabled] is the USER'S CHOICE — whether convoy focus is switched on —
     * and is deliberately separate from whether [points] happens to be null.
     * The two differ, and conflating them is a real bug: the planner also returns
     * null when focus IS on but there is nothing fittable yet (nobody sharing a
     * position, or only one point). Inferring "the user toggled focus off" from a
     * null would then fire on a transient data gap and force-resume following,
     * yanking the camera back from a user who had deliberately panned away.
     * Only a change in [focusEnabled] counts as the deliberate act.
     */
    fun setConvoyFit(points: List<MapPoint>?, focusEnabled: Boolean)

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

    /**
     * The id of the incident marker most recently TAPPED, or null when none is
     * pending. The host observes this to open the incident detail sheet, then
     * calls [consumeIncidentTap] to clear it.
     *
     * Deliberately a THIRD, separate flow rather than another producer of
     * [placeRequest]: a tap on an incident marker means "tell me about this
     * incident", which is a different intent from the two gestures that mean
     * "navigate to this place" — routing it through [placeRequest] would open a
     * route preview to the crash you were asking about.
     *
     * Only the id crosses the seam. The surface has no idea what an incident IS
     * (it was handed a colour, a glyph and an id to draw), so the host resolves
     * the id back to the incident it already holds.
     */
    val incidentTap: StateFlow<String?>

    /**
     * The id of the event pin most recently TAPPED, or null when none is pending.
     * The host observes this to open the event info popup, then calls
     * [consumeEventTap] to clear it.
     *
     * A separate flow from [incidentTap] for the same reason that one is separate
     * from [placeRequest]: tapping an event pin means "tell me about this event",
     * a different intent from an incident tap or a "navigate here" gesture. Only
     * the id crosses the seam; the host resolves it back to the event it holds.
     */
    val eventTap: StateFlow<String?>

    /**
     * The id of the crown marker most recently TAPPED, or null when none is
     * pending. The host observes this to open the crown popup, then calls
     * [consumeCrownTap].
     *
     * A SEPARATE flow rather than a second producer of [incidentTap], for the same
     * reason [incidentTap] is not a producer of [placeRequest]: the two ids come
     * from different collections and mean different things, and one shared slot
     * would let a crown id be looked up among the incidents (finding nothing, so
     * the tap would silently do nothing) — a failure with no symptom.
     */
    val crownTap: StateFlow<String?>

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
     * Record a tap on the incident marker with [incidentId]. Called by the real
     * surface's annotation click listener; also drivable by the stub/tests to
     * simulate the tap without a GL surface.
     */
    fun emitIncidentTap(incidentId: String)

    /** Clear the pending [incidentTap] once the host has opened the sheet for it. */
    fun consumeIncidentTap()

    /**
     * Record a tap on the event pin with [eventId]. Called by the real surface's
     * annotation click listener; also drivable by the stub/tests to simulate the
     * tap without a GL surface.
     */
    fun emitEventTap(eventId: String)

    /** Clear the pending [eventTap] once the host has opened the popup for it. */
    fun consumeEventTap()

    /**
     * Record a tap on the crown marker with [spawnId]. Called by the real
     * surface's annotation click listener; also drivable by the stub/tests to
     * simulate the tap without a GL surface.
     */
    fun emitCrownTap(spawnId: String)

    /** Clear the pending [crownTap] once the host has opened the popup for it. */
    fun consumeCrownTap()

    /**
     * Reset the map to north-up: ease the camera bearing back to 0. Moves no
     * camera on the stub, which has no rotatable one — it only records the call
     * in [StubMapSurface.resetNorthCount].
     */
    fun resetNorth()

    /**
     * Re-centre on the user AND reset the bearing to north-up, as ONE camera
     * move — what the floating compass control does.
     *
     * Deliberately a single method rather than a [resetNorth] + [recenter] pair
     * at the call site: those are two independent eased camera animations on the
     * same camera, and the second one issued cancels the first mid-flight, so a
     * caller firing both gets a visible stutter and, depending on ordering, a
     * bearing or a centre that never arrives. Implementations MUST express this
     * as one camera update.
     *
     * Recentring is best-effort in the same way [recenter] is — with no location
     * fix (or no location permission) there is nothing to centre ON, so the
     * implementation falls back to the same default camera [recenter] uses. The
     * north-up reset is NOT best-effort: it applies either way.
     */
    fun recenterNorthUp()

    /**
     * Choose how the map is ORIENTED while following the user (see
     * [MapCompassMode]) — the compass control's two modes.
     *
     * Stores the chosen mode so the surface's EXISTING follow path applies the
     * right bearing on every position/heading update: north-up pins the bearing
     * at 0, course-up rotates the camera to the puck's heading. Deliberately fed
     * into the one follow controller rather than a second camera owner (two
     * camera loops fighting is the documented failure mode here).
     *
     * When the mode actually CHANGES, the surface applies it immediately as ONE
     * user-requested re-centre — resuming follow, cancelling any pending
     * idle-return, and easing to the user with the new orientation (north-up eases
     * the bearing back to 0; course-up rotates to the current heading). Re-setting
     * the SAME mode is a no-op, so the shell can re-push it on a surface swap
     * without forcing a spurious camera move on open. On the stub this only records
     * the mode (and mirrors the re-centre in [StubMapSurface.recenterCount]).
     */
    fun setCompassMode(mode: MapCompassMode)

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
     * Replace the set of community event pins drawn on the map. The host derives
     * these from the published, upcoming events it observes and pushes them here
     * so every signed-in user sees them. A no-op beyond storing the value on the
     * stub.
     */
    fun setEventMarkers(markers: List<MapEventMarker>)

    /**
     * Replace the set of Kronjakt crowns drawn on the map. The host reads these
     * from `crownSpawns` around the viewport and pushes them here. Pushing an
     * empty list is how the layer is taken DOWN — which is what the host does
     * the moment the `crownHuntSpawn` flag reads false. A no-op beyond storing
     * the value on the stub.
     */
    fun setCrownMarkers(markers: List<MapCrownMarker>)

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

    private val eventMarkersFlow = MutableStateFlow<List<MapEventMarker>>(emptyList())
    override val eventMarkers: StateFlow<List<MapEventMarker>> = eventMarkersFlow.asStateFlow()

    private val crownMarkersFlow = MutableStateFlow<List<MapCrownMarker>>(emptyList())
    override val crownMarkers: StateFlow<List<MapCrownMarker>> = crownMarkersFlow.asStateFlow()

    private val placeRequestFlow = MutableStateFlow<MapPlaceRequest?>(null)
    override val placeRequest: StateFlow<MapPlaceRequest?> = placeRequestFlow.asStateFlow()

    // The stub has no camera, so it never has a position to report and never
    // projects anything: the convoy overlay simply draws nothing on it, which is
    // exactly right for CI and the token-less build.
    private val cameraSnapshotFlow = MutableStateFlow<MapCameraSnapshot?>(null)
    override val cameraSnapshot: StateFlow<MapCameraSnapshot?> = cameraSnapshotFlow.asStateFlow()

    private val convoyFitFlow = MutableStateFlow<List<MapPoint>?>(null)
    /** Last value passed to [setConvoyFit] — observable so tests can assert the wiring. */
    val convoyFit: StateFlow<List<MapPoint>?> = convoyFitFlow.asStateFlow()

    private val convoyFocusEnabledFlow = MutableStateFlow(false)

    /** Last [focusEnabled] passed to [setConvoyFit] — observable for tests. */
    val convoyFocusEnabled: StateFlow<Boolean> = convoyFocusEnabledFlow.asStateFlow()

    override fun setConvoyFit(points: List<MapPoint>?, focusEnabled: Boolean) {
        convoyFitFlow.value = points
        convoyFocusEnabledFlow.value = focusEnabled
    }

    // Test hook: the projection the stub should report, or null for "no map".
    private var projectionForTest: ((Double, Double) -> MapScreenPoint?)? = null

    /**
     * No camera on the stub, so nothing can be projected — unless a test has
     * installed a projection via [setProjectionForTest].
     */
    override fun screenPositionFor(latitude: Double, longitude: Double): MapScreenPoint? =
        projectionForTest?.invoke(latitude, longitude)

    // The fixed visible radius the stub reports (metres). The stub has no camera
    // to measure, so it returns a sane constant rather than null, keeping the
    // token-less / CI build's incident query deterministic. Overridable for tests.
    private var visibleRadiusMetersValue: Double = STUB_VISIBLE_RADIUS_METERS

    /**
     * A FIXED sensible radius (no camera to measure), so the incident layer still
     * queries a sane area on the token-less build. Deterministic on purpose.
     */
    override fun visibleRadiusMeters(): Double = visibleRadiusMetersValue

    /** Test hook: pin the visible radius the stub reports. */
    fun setVisibleRadiusForTest(radiusMeters: Double) {
        visibleRadiusMetersValue = radiusMeters
    }

    /** Test hook: pin a camera snapshot so overlay logic can be exercised off-device. */
    fun setCameraSnapshotForTest(snapshot: MapCameraSnapshot?) {
        cameraSnapshotFlow.value = snapshot
    }

    /**
     * Test hook: stand in for the renderer's coordinate→pixel projection, so the
     * convoy awareness overlay can be driven without a real Mapbox surface.
     * Null (the default) keeps the stub's "there is no map" behaviour.
     */
    fun setProjectionForTest(projection: ((Double, Double) -> MapScreenPoint?)?) {
        projectionForTest = projection
    }

    private val incidentTapFlow = MutableStateFlow<String?>(null)
    override val incidentTap: StateFlow<String?> = incidentTapFlow.asStateFlow()

    private val eventTapFlow = MutableStateFlow<String?>(null)
    override val eventTap: StateFlow<String?> = eventTapFlow.asStateFlow()

    private val crownTapFlow = MutableStateFlow<String?>(null)
    override val crownTap: StateFlow<String?> = crownTapFlow.asStateFlow()

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

    /**
     * How many drag gestures have actually been DELIVERED to the map surface as
     * touch events.
     *
     * The real camera gestures are handled inside the Mapbox MapView, which no
     * instrumentation test can reach. What a test CAN pin down is the thing that
     * broke in v0.8.3: whether a drag over the map area reaches the map surface
     * at all, or is swallowed by the chrome composed on top of it. The shell used
     * to draw its pages inside a Material3 [androidx.compose.material3.Scaffold],
     * whose Surface installs an empty `pointerInput {}` purely to block touch
     * propagation to whatever is beneath — so every pan died there and the camera
     * could only ever be moved programmatically ("the map is locked to my
     * location").
     *
     * The stub therefore installs the same kind of pointer-input node the real
     * MapView has and counts the drags that reach it, which makes the delivery
     * path itself assertable off-device.
     */
    var panGestureCount: Int = 0
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

    override fun emitIncidentTap(incidentId: String) {
        incidentTapFlow.value = incidentId
    }

    override fun consumeIncidentTap() {
        incidentTapFlow.value = null
    }

    override fun emitEventTap(eventId: String) {
        eventTapFlow.value = eventId
    }

    override fun consumeEventTap() {
        eventTapFlow.value = null
    }

    override fun emitCrownTap(spawnId: String) {
        crownTapFlow.value = spawnId
    }

    override fun consumeCrownTap() {
        crownTapFlow.value = null
    }

    /**
     * How many times a north-up reset was requested, by either [resetNorth] or
     * [recenterNorthUp]. The stub has no rotatable camera, so the count is the
     * only observable the reset leaves behind.
     */
    var resetNorthCount: Int = 0
        private set

    /** No rotatable camera on the stub, so resetting to north only counts. */
    override fun resetNorth() {
        resetNorthCount += 1
    }

    /**
     * Counts as BOTH a re-centre and a north reset, which is what makes the
     * compass's re-centre assertable: a compass wired to [resetNorth] alone
     * leaves [recenterCount] at 0.
     */
    override fun recenterNorthUp() {
        recenterCount += 1
        resetNorthCount += 1
    }

    /**
     * The compass orientation mode last applied via [setCompassMode]. Starts
     * north-up (the shell's default); observable so tests can assert the compass
     * toggles it.
     */
    var compassMode: MapCompassMode = MapCompassMode.NorthUp
        private set

    /**
     * How many times [setCompassMode] actually CHANGED the mode — observable so a
     * test can assert a tap flipped it exactly once (and that a redundant re-push
     * of the same mode did nothing).
     */
    var compassModeChanges: Int = 0
        private set

    /**
     * No rotatable camera on the stub, so a mode change only records the new mode
     * — and MIRRORS the real surface's user-requested re-centre so the "the
     * compass still re-centres on the user, and returning to north still resets
     * north" guarantees stay assertable off-device: a real change bumps
     * [recenterCount], and switching to north-up also bumps [resetNorthCount].
     * Re-setting the same mode is a no-op (matches the real surface not forcing a
     * camera move when the shell re-pushes an unchanged mode).
     */
    override fun setCompassMode(mode: MapCompassMode) {
        if (mode == compassMode) return
        compassMode = mode
        compassModeChanges += 1
        recenterCount += 1
        if (mode == MapCompassMode.NorthUp) resetNorthCount += 1
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

    override fun setIncidentMarkers(markers: List<MapIncidentMarker>) {
        incidentMarkersFlow.value = markers
    }

    override fun setEventMarkers(markers: List<MapEventMarker>) {
        eventMarkersFlow.value = markers
    }

    override fun setCrownMarkers(markers: List<MapCrownMarker>) {
        crownMarkersFlow.value = markers
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
            modifier =
                modifier
                    // Stands in for the MapView's own camera-gesture handling: the
                    // stub cannot pan a camera, but it CAN record that the drag
                    // reached it, which is what [panGestureCount] exists to make
                    // assertable. Nothing is consumed, so this never changes how
                    // the surrounding chrome behaves.
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { panGestureCount += 1 }) { _, _ -> }
                    }
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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

        // A city-scale default the stub reports for the visible radius: enough to
        // populate the incident layer around the default camera without a real map.
        const val STUB_VISIBLE_RADIUS_METERS = 15_000.0
    }
}

/** Remembers a [StubMapSurface] across recompositions (default shell wiring). */
@Composable
fun rememberStubMapSurface(): StubMapSurface = remember { StubMapSurface() }
