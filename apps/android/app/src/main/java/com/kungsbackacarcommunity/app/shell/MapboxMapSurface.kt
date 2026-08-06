package com.kungsbackacarcommunity.app.shell

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccPalette
import com.kungsbackacarcommunity.app.diagnostics.FeatureHealthKind
import com.kungsbackacarcommunity.app.diagnostics.FeatureHealthReporter
import com.kungsbackacarcommunity.app.diagnostics.MapRenderWatchdog
import com.kungsbackacarcommunity.app.diagnostics.mapLoadingErrorKindFor
import com.kungsbackacarcommunity.app.diagnostics.rememberFeatureHealthReporter
import com.kungsbackacarcommunity.app.incidents.ViewportRadius
import com.kungsbackacarcommunity.app.map.CameraFollowController
import com.kungsbackacarcommunity.app.map.ConvoyFocusPlanner
import com.kungsbackacarcommunity.app.map.ConvoyLatLng
import com.kungsbackacarcommunity.app.map.MapMarkerStyle
import com.kungsbackacarcommunity.app.map.MapMarkers
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.RotateGestureDetector
import com.mapbox.android.gestures.ShoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.bindgen.Value
import com.mapbox.common.Cancelable
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.ClickInteraction
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.RenderModeType
import com.mapbox.maps.Style
import com.mapbox.maps.interactions.standard.generated.standardPoi
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.observable.eventdata.CameraChangedEventData
import com.mapbox.maps.plugin.animation.MapAnimationOptions.Companion.mapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.getLayerAs
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.layers.properties.generated.Visibility
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.extension.style.sources.generated.vectorSource
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.OnPointAnnotationClickListener
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.delegates.listeners.OnCameraChangeListener
import com.mapbox.maps.plugin.gestures.OnMapLongClickListener
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.OnRotateListener
import com.mapbox.maps.plugin.gestures.OnScaleListener
import com.mapbox.maps.plugin.gestures.OnShoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.scalebar.scalebar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
 * while sharing and blue otherwise. The puck also carries a small DIRECTION
 * ARROW showing which way the user is heading, added once a real heading is
 * known — see [showPuckBearingArrow].
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

    // Day light preset by default (matches the Standard style's own default);
    // the layers popup flips it to Night via the style's `lightPreset` config.
    private val mapModeFlow = MutableStateFlow(MapMode.Day)
    override val mapMode: StateFlow<MapMode> = mapModeFlow.asStateFlow()

    // Camera opens tilted (3D) — see [pitch] below, which defaults to
    // DEFAULT_PITCH — so the flag starts true and the popup flips it to 2D.
    private val is3dFlow = MutableStateFlow(true)
    override val is3d: StateFlow<Boolean> = is3dFlow.asStateFlow()

    // Current camera bearing (degrees, 0 = north-up). Updated from the map's
    // camera-change listener so the floating compass control can rotate its
    // north-arrow to keep pointing at true north as the user rotates the map.
    private val bearingFlow = MutableStateFlow(0f)
    override val bearing: StateFlow<Float> = bearingFlow.asStateFlow()

    private val routeOverlayFlow = MutableStateFlow<MapRouteOverlay?>(null)
    override val routeOverlay: StateFlow<MapRouteOverlay?> = routeOverlayFlow.asStateFlow()

    private val incidentMarkersFlow = MutableStateFlow<List<MapIncidentMarker>>(emptyList())
    override val incidentMarkers: StateFlow<List<MapIncidentMarker>> =
        incidentMarkersFlow.asStateFlow()

    private val crownMarkersFlow = MutableStateFlow<List<MapCrownMarker>>(emptyList())
    override val crownMarkers: StateFlow<List<MapCrownMarker>> = crownMarkersFlow.asStateFlow()

    private val eventMarkersFlow = MutableStateFlow<List<MapEventMarker>>(emptyList())
    override val eventMarkers: StateFlow<List<MapEventMarker>> = eventMarkersFlow.asStateFlow()

    private val billboardMarkersFlow = MutableStateFlow<List<MapBillboardMarker>>(emptyList())
    override val billboardMarkers: StateFlow<List<MapBillboardMarker>> =
        billboardMarkersFlow.asStateFlow()

    private val placeRequestFlow = MutableStateFlow<MapPlaceRequest?>(null)
    override val placeRequest: StateFlow<MapPlaceRequest?> = placeRequestFlow.asStateFlow()

    // Where the camera is, rounded so a settled camera stops re-emitting (see
    // [MapCameraSnapshot]). Fed from the same camera-change listener that drives
    // [bearingFlow]; consumed by the convoy awareness overlay.
    private val cameraSnapshotFlow = MutableStateFlow<MapCameraSnapshot?>(null)
    override val cameraSnapshot: StateFlow<MapCameraSnapshot?> = cameraSnapshotFlow.asStateFlow()

    // "Keep the whole convoy in view": the points the camera should be framing
    // instead of the user's puck, or null for normal follow. Applied ONLY from
    // inside the existing follow path (see [applyConvoyFit]), so it inherits the
    // gesture detach, the idle-return timer and the deference to a route overlay
    // rather than becoming a second camera owner.
    private var convoyFitPoints: List<MapPoint>? = null

    // The user's convoy-focus choice, tracked separately from [convoyFitPoints]
    // so a transient "nothing to fit" is never mistaken for switching focus off.
    private var convoyFocusEnabled: Boolean = false

    // The fit currently applied to the camera, so a stream of live positions that
    // does not actually change the framing does not re-ease the camera every
    // second (see ConvoyFocusPlanner.shouldRefit). Null when not fitting.
    private var appliedConvoyFit: List<ConvoyLatLng>? = null

    private val incidentTapFlow = MutableStateFlow<String?>(null)
    override val incidentTap: StateFlow<String?> = incidentTapFlow.asStateFlow()

    private val crownTapFlow = MutableStateFlow<String?>(null)
    override val crownTap: StateFlow<String?> = crownTapFlow.asStateFlow()

    private val eventTapFlow = MutableStateFlow<String?>(null)
    override val eventTap: StateFlow<String?> = eventTapFlow.asStateFlow()

    private val billboardTapFlow = MutableStateFlow<String?>(null)
    override val billboardTap: StateFlow<String?> = billboardTapFlow.asStateFlow()

    // The map long-click gesture listener ("hold to navigate here"); held so it
    // can be detached in onRelease.
    private var longClickListener: OnMapLongClickListener? = null

    // Cancels the single-tap place interaction that raises a named
    // [placeRequest]. The Interactions API hands back a Cancelable per
    // registration rather than a removable listener, so it is held and cancelled
    // in onRelease alongside the gesture listeners.
    private val placeInteractions = mutableListOf<Cancelable>()

    // "Camera follows me" state. The controller owns the follow/idle DECISION
    // (pure, unit-tested); the 10-second countdown is [idleReturnJob], a coroutine
    // on [followScope] (the composable's scope, cancelled on dispose). Each manual
    // pan/zoom/rotate/tilt gesture stops following and (re)arms the timer; when it
    // elapses (or the user taps my-location) we glide back and resume following.
    private val followController = CameraFollowController()
    private var followScope: CoroutineScope? = null
    private var idleReturnJob: Job? = null

    // Camera-manipulation gesture listeners, held so they can be detached in
    // onRelease. Any of pan/zoom/rotate/tilt counts as the user taking over the
    // camera, so all four drive the same [onUserMapInteraction] hook.
    private var moveListener: OnMoveListener? = null
    private var scaleListener: OnScaleListener? = null
    private var rotateListener: OnRotateListener? = null
    private var shoveListener: OnShoveListener? = null

    // Live references, held only while the map is composed (cleared in
    // onRelease). Touched on the main thread from Compose callbacks.
    private var mapViewRef: MapView? = null
    private var lastPoint: Point? = null

    // How the map is oriented while following (see [MapCompassMode]) — the
    // compass control's two modes. north-up pins the camera bearing at 0;
    // course-up rotates the camera to [lastBearing] (the puck's heading) on every
    // position/heading update. Fed into the ONE follow path (the position/bearing
    // listeners + easeToUser) rather than a second camera loop. A preference, so
    // it survives a MapView recreate on a tab round-trip; the shell also re-pushes
    // it on a surface swap.
    private var compassMode: MapCompassMode = MapCompassMode.NorthUp

    // The puck's most recent COURSE bearing (degrees, direction of travel), fed by
    // the OnIndicatorBearingChangedListener. Read by the follow path to rotate the
    // camera in course-up. Reset on MapView release so a fresh map does not rotate
    // to a stale heading before its first fix.
    private var lastBearing: Double = 0.0

    // Whether the puck is currently drawn WITH its direction arrow.
    //
    // The arrow is not on from the start, and that is the point. A puck created
    // with a bearing image renders the arrow immediately, pointing at whatever
    // the indicator's bearing happens to be — which, before any heading has ever
    // been reported, is 0: an arrow confidently claiming you are facing north.
    // So the puck starts as the plain dot and gains its arrow the first time a
    // real bearing arrives (see the bearing listener), after which the Mapbox
    // location component keeps it pointing at the last reported one.
    //
    // Read by every place that (re)applies the location-component settings — a
    // permission grant, a tab return, a style reload — so the arrow is not lost
    // the moment any of those re-assert the puck. Reset on MapView release so a
    // fresh map goes back to the plain dot until it has its own heading.
    private var puckBearingArrowShown: Boolean = false

    // Re-entrancy latch for the two location-indicator callbacks (position +
    // bearing). Both of them mutate native map state (setCamera / a posted
    // updateSettings), and a native settings re-init can synchronously re-fire an
    // indicator listener while one is already on the stack. This latch makes any
    // such nested indicator callback a no-op, so an indicator body can never
    // re-enter map mutation from inside itself — defence in depth alongside the
    // set-before-apply guard on [showPuckBearingArrow]. Set on entry, cleared in a
    // finally on exit, so a normal (non-nested) fix always runs in full.
    private var handlingIndicator: Boolean = false

    // The private "past ~1 km" breadcrumb tail of the user's OWN travel, drawn
    // only while THIS user is live-sharing (see [BreadcrumbTrail] for the rolling
    // window / jitter / jump rules). Fed from the device puck's position fixes
    // and rendered client-side ONLY — it is never written to RTDB/Firestore nor
    // pushed across the [MapSurface] seam, so no other convoy member ever sees
    // it. Held on the surface (which outlives individual MapViews) so the tail
    // survives a MapView recreate mid-session and can be redrawn against the new
    // style. Touched only on the main-thread position callback.
    private val breadcrumbTrail = BreadcrumbTrail()

    // Test seam: the breadcrumb tail's base ARGB (the brand yellow it is drawn
    // in). Exposed so a pure JVM test can assert the tail is derived from the
    // brand design token — the colour cannot be read off the GL layer off-device.
    // The value itself lives on the private companion ([BREADCRUMB_COLOR]).
    internal val breadcrumbColorArgb: Int
        get() = BREADCRUMB_COLOR

    private var routeLineManager: PolylineAnnotationManager? = null
    private var destMarkerManager: CircleAnnotationManager? = null
    // Incidents are POINT annotations (an icon), not circles: the categories have
    // to be told apart at a glance on a moving map, which a coloured dot cannot
    // do. Point annotations also carry a per-annotation click listener, which is
    // what makes the markers tappable.
    private var incidentMarkerManager: PointAnnotationManager? = null
    // Maps a drawn annotation back to the incident id it represents, so a click
    // can be reported across the seam. Keyed on the annotation's own id rather
    // than a coordinate so two incidents reported at the same spot stay distinct.
    // Rebuilt on every redraw and cleared with the manager.
    //
    // Module-visible rather than private only so unit tests can seed a drawn
    // badge without a GL surface (annotations cannot be created off-device).
    internal val incidentIdsByAnnotation = mutableMapOf<String, String>()

    // The incident annotation click listener, held so it can be detached in
    // onRelease alongside the map's other listeners.
    private var incidentClickListener: OnPointAnnotationClickListener? = null

    // Application context, kept only to rasterise the incident marker images
    // (which needs resources + display density). The APPLICATION context
    // specifically: this surface outlives individual MapViews, and holding an
    // Activity here would leak it.
    private var appContext: Context? = null

    // Style-image names already registered on the CURRENT style, so each
    // category's marker image is rasterised and uploaded once rather than on
    // every redraw. Cleared whenever the style is (re)loaded or the surface is
    // released: style images do NOT survive a style reload, so a stale "already
    // registered" entry would leave an icon-less marker behind.
    private val registeredIncidentImages = mutableSetOf<String>()
    // The incident markers currently drawn, so a recomposition only clears and
    // redraws them when the set ACTUALLY changes (unrelated recompositions must
    // not flicker the layer). Reset to null whenever the manager is (re)created
    // or torn down so a cleared-then-recreated map always redraws.
    private var lastAppliedIncidents: List<MapIncidentMarker>? = null

    // ---- Kronjakt crown layer -----------------------------------------------
    // Its OWN annotation manager, image set and lookup, parallel to the incident
    // ones above and deliberately never shared with them. Two managers means the
    // crown layer can be emptied (the flag going off) without touching a single
    // incident annotation, and a tap resolves against exactly one of the two
    // lookups — so a crown can never open an incident sheet, or vice versa.
    // Style-image names are namespaced `kcc-crown-` by CrownMarkerBitmaps.imageId
    // so they cannot collide with `kcc-incident-` or the event layer's images.
    private var crownMarkerManager: PointAnnotationManager? = null

    // Annotation id → spawn id, rebuilt on every crown redraw and cleared with
    // the manager. Module-visible for the same reason as the incident lookup:
    // annotations cannot be created off-device, so a unit test seeds it directly.
    internal val crownIdsByAnnotation = mutableMapOf<String, String>()

    private var crownClickListener: OnPointAnnotationClickListener? = null
    private val registeredCrownImages = mutableSetOf<String>()
    private var lastAppliedCrowns: List<MapCrownMarker>? = null

    // ---- Community events layer (mirrors the incidents layer above) ----------
    // A SEPARATE PointAnnotationManager so event pins are their own layer with
    // their own tap intent ("open this event"), never entangled with incidents.
    private var eventMarkerManager: PointAnnotationManager? = null
    // Maps a drawn annotation back to the event id it represents, so a click can
    // be reported across the seam. Module-visible so unit tests can seed a drawn
    // pin without a GL surface.
    internal val eventIdsByAnnotation = mutableMapOf<String, String>()
    private var eventClickListener: OnPointAnnotationClickListener? = null
    // The single event-pin image is registered once per style (there is one event
    // icon, unlike the per-category incident images); tracked so a style reload
    // re-uploads it.
    private val registeredEventImages = mutableSetOf<String>()
    private var lastAppliedEvents: List<MapEventMarker>? = null

    // ---- Sponsored billboards layer ------------------------------------------
    // The fourth annotation manager on this surface, and its own for exactly the
    // reasons the three above are: the layer must be emptiable on its own (the
    // digitalBillboards flag going off) without touching an incident, a crown or
    // an event pin, and a tap must resolve against exactly ONE lookup — a
    // billboard tap that opened an incident sheet, or an incident tap that
    // recorded a sponsor impression, are both bugs this seam makes impossible.
    // Style-image names are namespaced `kcc-billboard-` by
    // BillboardMarkerBitmaps.imageId so they cannot collide with
    // `kcc-incident-`, `kcc-crown-` or `kcc-event-marker`.
    private var billboardMarkerManager: PointAnnotationManager? = null

    // Annotation id → billboard id, rebuilt on every redraw and cleared with the
    // manager. Module-visible for the same reason as the other lookups:
    // annotations cannot be created off-device, so a unit test seeds it directly.
    internal val billboardIdsByAnnotation = mutableMapOf<String, String>()

    private var billboardClickListener: OnPointAnnotationClickListener? = null
    private val registeredBillboardImages = mutableSetOf<String>()
    private var lastAppliedBillboards: List<MapBillboardMarker>? = null

    // Camera-change listener that mirrors the live map bearing into [bearingFlow]
    // (so the compass control rotates); held so it can be detached in onRelease.
    private var cameraChangeListener: OnCameraChangeListener? = null

    // ---- Feature-health state (see diagnostics/FeatureHealth.kt) -------------
    // Why any of this exists: release v0.8.1 shipped a map that rendered NOTHING
    // and never threw, so no crash handler and no error-reporting call site ever
    // fired. These facts are what let the watchdog tell "genuinely broken" apart
    // from "offline / covered / not looked at yet", which is the whole difference
    // between a useful auto-reporter and one that gets muted.
    //
    // Deliberately lives ONLY here, on the real surface: [StubMapSurface] (the
    // CI / token-less path) has no GL surface to assert anything about and must
    // stay completely inert.

    // Whether the MapView was ever actually constructed and shown. Nothing about
    // a surface that never appeared is worth asserting.
    @Volatile
    private var surfaceShown: Boolean = false

    // Whether the map is currently visible to the user (see [setActive]). A map
    // sitting behind an opaque tab is not a broken map.
    @Volatile
    private var surfaceActive: Boolean = true

    // Whether the map has ever rendered a FULL frame (MapLoaded, or a render
    // frame the SDK reports as complete). This is the positive health signal —
    // the precise inverse of the v0.8.1 blank rectangle. Once true, the render
    // watchdog is disarmed for good.
    @Volatile
    private var everRendered: Boolean = false

    // Reporter for the SDK's own error callbacks, which fire from native threads
    // outside the composition. Set while composed, cleared in onRelease.
    @Volatile
    private var healthReporter: FeatureHealthReporter? = null

    // Whether the app is in the foreground, sampled from the composable's
    // lifecycle. Read from the native error callbacks, which have no lifecycle
    // of their own.
    @Volatile
    private var appInForeground: Boolean = true

    // Subscriptions to the Maps SDK's own health callbacks; cancelled in
    // onRelease alongside the gesture/camera listeners.
    private var mapLoadingErrorSubscription: Cancelable? = null
    private var mapLoadedSubscription: Cancelable? = null
    private var renderFrameSubscription: Cancelable? = null

    /**
     * Record that the map produced a complete frame — it is demonstrably not
     * blank. Disarms the render watchdog permanently.
     */
    private fun markRendered() {
        everRendered = true
    }

    /**
     * Funnel for the Maps SDK's `subscribeMapLoadingError` callback.
     *
     * The kind mapping (and the decision to ignore the noisy `SOURCE`/`TILE`
     * types entirely) lives in the pure [mapLoadingErrorKindFor] so it is
     * unit-tested rather than trusted. The error's own `message`, `sourceId` and
     * `tileId` are deliberately DISCARDED: a tile id is a coordinate, a source
     * id and a message can carry a URL, and the GitHub issue this feeds is
     * world-readable.
     */
    private fun onMapLoadingError(typeName: String) {
        val kind = mapLoadingErrorKindFor(typeName) ?: return
        healthReporter?.report(
            kind = kind,
            foreground = appInForeground,
            surfaceShown = surfaceShown && surfaceActive,
        )
    }

    // The overlay currently drawn on the map, so the recomposition-driven update
    // block only clears/recreates the annotations and re-fits the camera when the
    // overlay ACTUALLY changes — unrelated recompositions (traffic toggle,
    // live-sharing pulse) no longer cause the route to flicker or the camera to
    // snap back. Reset to null whenever the annotation managers are (re)created or
    // torn down, so the overlay is redrawn against fresh managers (the cache must
    // never claim "already applied" when the annotations were actually cleared).
    private var lastAppliedOverlay: MapRouteOverlay? = null

    // One-shot guard so the camera auto-centres on the FIRST GPS fix only,
    // opening close to the user; afterwards it leaves the camera alone so it
    // never fights the user panning (recenter() is still available on demand).
    private var centeredOnFirstFix: Boolean = false

    // Camera tilt (degrees) applied to every camera we set — the initial default
    // camera, the first-GPS-fix auto-centre, and recenter() — so the map opens
    // and stays in the tilted 3D perspective (the Standard style renders 3D
    // buildings/terrain when the camera is pitched). Held as a single mutable
    // field so the follow-up layers toggle can flip it between DEFAULT_PITCH (3D)
    // and MapMarkers.FLAT_PITCH (2D) and recenter() to re-apply it at runtime.
    private var pitch: Double = MapMarkers.DEFAULT_PITCH

    // The RESTING/browsing zoom — "how far away the focus is" when using the map
    // as usual. Applied to the two sites that frame the user while browsing: the
    // first-GPS-fix auto-centre and easeToUser() (my-location / compass /
    // idle-return). Seeded to the app's original own-marker zoom so an untouched
    // slider reproduces the old framing exactly; the shell pushes the persisted
    // value via setBrowsingZoom(). Deliberately NOT read by the active
    // drive-follow step (later fixes only re-centre, keeping whatever zoom the
    // user is at) nor by the convoy-fit / route-preview cameras.
    private var browsingZoom: Double = MapMarkers.OWN_MARKER_ZOOM

    override fun setUserMarker(marker: MapUserMarker?) {
        userMarkerFlow.value = marker
    }

    override fun setTrafficEnabled(enabled: Boolean) {
        // The Content update lambda observes this flow and applies the layer's
        // visibility on recomposition, but apply eagerly too (mirroring
        // setMapMode) so the overlay flips on the SAME frame the user toggles
        // instead of waiting for a recomposition to propagate — the flow stays
        // the source of truth (re-applied on every style (re)load). A no-op
        // until the style is loaded; wrapped defensively like every native call.
        trafficFlow.value = enabled
        runCatching {
            mapViewRef?.mapboxMap?.style?.let { applyTrafficVisibility(it, enabled) }
        }
    }

    override fun setMapMode(mode: MapMode) {
        mapModeFlow.value = mode
        // The Content update lambda re-applies the light preset on recomposition,
        // but apply eagerly too so the switch is reflected immediately even if no
        // recomposition is pending. A no-op until the style is loaded.
        runCatching {
            mapViewRef?.mapboxMap?.style?.let { applyLightPreset(it, mode) }
        }
    }

    override fun set3dEnabled(enabled: Boolean) {
        is3dFlow.value = enabled
        // Actually SHOW/HIDE the 3D buildings + landmarks: in the Mapbox Standard
        // style these are part of the basemap and render at ANY camera pitch, so
        // flattening the camera alone never removes them (Mapbox issue #2608).
        // The real toggle is the `show3dObjects` config on the Standard import;
        // apply it eagerly here and re-apply it on every style (re)load from the
        // is3dFlow value, so is3dFlow stays the single source of truth. A no-op
        // until the style is loaded; wrapped defensively.
        runCatching {
            mapViewRef?.mapboxMap?.style?.let { apply3dObjects(it, enabled) }
        }
        // Also flip the shared pitch field and re-apply it through recenter(),
        // which re-issues the camera with `pitch(this.pitch)`, so 3D-on opens
        // tilted and 3D-off flattens to top-down. A no-op until the map is
        // composed (recenter guards on mapViewRef).
        pitch = if (enabled) MapMarkers.DEFAULT_PITCH else MapMarkers.FLAT_PITCH
        recenter()
    }

    override fun setBrowsingZoom(zoom: Double) {
        // Store the resting zoom (clamped to the valid range) so the next first-fix
        // / easeToUser frames the user at it. Also ease the CURRENT camera's zoom to
        // it so a slider drag reads live rather than waiting for the next recenter —
        // zoom only, leaving centre/bearing/pitch exactly as they are.
        //
        // Guarded so the live ease never fights a camera that something else owns:
        // - a route preview owns the camera (its fit frames origin->destination), and
        // - a convoy fit owns it (framing the whole group).
        // In both cases we only remember the value for when that owner releases the
        // camera. The active drive-follow step is untouched either way — it re-centres
        // on later fixes without setting a zoom, so it simply keeps this new one.
        // Snap (clamp + round to the 0.5 notch) so the surface holds the same
        // discrete values the store/slider produce — a direct or off-notch call
        // can't leave the field on a fraction, and the `==` guard below then can't
        // ease repeatedly on sub-notch float drift.
        val snapped = com.kungsbackacarcommunity.app.map.MapZoomPreference.snap(zoom)
        val unchanged = snapped == browsingZoom
        browsingZoom = snapped
        if (unchanged) return
        val map = mapViewRef ?: return
        if (routeOverlayFlow.value != null || convoyFitPoints != null) return
        runCatching {
            map.camera.easeTo(
                cameraOptions { zoom(snapped) },
                mapAnimationOptions { duration(RECENTER_ANIMATION_MS) },
            )
        }
    }

    override fun setRouteOverlay(overlay: MapRouteOverlay?) {
        // The Content update lambda observes this flow and (re)draws the line +
        // destination marker, so publishing the value is enough.
        routeOverlayFlow.value = overlay
    }

    override fun setEventMarkers(markers: List<MapEventMarker>) {
        // The Content update lambda observes this flow and (re)draws the event
        // pins; storing it here is enough (a no-op until the manager exists).
        eventMarkersFlow.value = markers
    }

    override fun setIncidentMarkers(markers: List<MapIncidentMarker>) {
        // The Content update lambda observes this flow and (re)draws the incident
        // badges when the set changes, so publishing the value is enough.
        incidentMarkersFlow.value = markers
    }

    override fun setCrownMarkers(markers: List<MapCrownMarker>) {
        // Same contract as the incident markers: the Content update lambda
        // observes this flow and redraws only when the set actually changes, so
        // publishing the value is the whole of it. An empty list takes the layer
        // down, which is what the host pushes when the flag reads false.
        crownMarkersFlow.value = markers
    }

    override fun setBillboardMarkers(markers: List<MapBillboardMarker>) {
        // Same contract again: the Content update lambda observes this flow and
        // redraws only on a real change. An empty list takes the layer down,
        // which is what the host pushes when the digitalBillboards flag reads
        // false — and what it pushes when the server stops calling a billboard
        // map-visible.
        billboardMarkersFlow.value = markers
    }

    override fun screenPositionFor(latitude: Double, longitude: Double): MapScreenPoint? {
        val map = mapViewRef ?: return null
        return runCatching {
            val screen = map.mapboxMap.pixelForCoordinate(Point.fromLngLat(longitude, latitude))
            MapScreenPoint(x = screen.x.toFloat(), y = screen.y.toFloat())
        }.getOrNull()
    }

    override fun visibleRadiusMeters(): Double? =
        mapViewRef?.let { map -> visibleRadiusMeters(map.mapboxMap) }

    override fun setConvoyFit(points: List<MapPoint>?, focusEnabled: Boolean) {
        val previousFocusEnabled = convoyFocusEnabled
        convoyFitPoints = points?.takeIf { it.isNotEmpty() }
        convoyFocusEnabled = focusEnabled

        // Turning the fit ON or OFF is an explicit user act (the convoy bar's
        // focus toggle), so — exactly like the my-location control — it resumes
        // following and cancels any pending idle-return. Everything else that
        // reaches this method is a position/roster tick, which must NOT resume
        // following, or a user who had panned away would be yanked back every
        // second.
        //
        // The two are told apart by the user's FOCUS CHOICE, not by whether
        // `points` went null. Those are different questions: the planner also
        // hands us null while focus is ON but nothing is fittable yet (nobody
        // sharing a position, or only one point). Reading a null as "the user
        // switched focus off" would make a transient data gap — one tick where
        // the roster is momentarily empty — force-resume following and snatch the
        // camera back from someone who had deliberately panned away to look at
        // something.
        val toggled = previousFocusEnabled != focusEnabled
        if (toggled) {
            followController.onRecenterRequested()
            idleReturnJob?.cancel()
            idleReturnJob = null
        }

        if (convoyFitPoints == null) {
            // Nothing to frame. Forget the applied fit either way, so a later
            // refit is not compared against a stale bounding box.
            appliedConvoyFit = null
            // Glide back to the normal framing — which restores the ZOOM as well
            // as the centre — ONLY when the user actually switched focus off
            // (or left / the convoy ended). Without that the camera would be left
            // wherever the last fit put it: technically following the user again,
            // but stuck zoomed out over an area the group no longer occupies.
            //
            // Gated on `toggled` rather than on `previous != null`, because a
            // momentary gap in live positions also arrives here with focus still
            // ON. Easing to the user then would re-zoom the map mid-convoy every
            // time the roster blinked.
            if (toggled && !focusEnabled &&
                followController.shouldTrack(hasRouteOverlay = routeOverlayFlow.value != null)
            ) {
                easeToUser()
            }
            return
        }

        // Applies immediately rather than waiting for the next GPS fix, so the
        // toggle feels like it did something — but still behind the follow gate,
        // so a position tick arriving while the user is mid-pan cannot steal the
        // camera from them. A toggle always passes the gate, because the branch
        // above just resumed following.
        if (followController.shouldTrack(hasRouteOverlay = routeOverlayFlow.value != null)) {
            applyConvoyFit()
        }
    }

    /**
     * Ease the camera to frame the current [convoyFitPoints], if anything has
     * actually changed.
     *
     * Called from the two places the framing can go out of date: the focus
     * choice/roster changing ([setConvoyFit]) and a new position arriving (the
     * indicator-position listener). Both entries are already gated on
     * [CameraFollowController.shouldTrack], so a user who has panned away keeps
     * their view and gets the fit back when the idle timer resumes follow —
     * exactly like plain follow behaves.
     */
    private fun applyConvoyFit() {
        val map = mapViewRef ?: return
        val points = convoyFitPoints ?: return
        val asLatLng = points.map { ConvoyLatLng(latitude = it.latitude, longitude = it.longitude) }
        // Live positions tick every second; re-easing on each one is visibly
        // seasick and pointless when the framing is unchanged.
        if (!ConvoyFocusPlanner.shouldRefit(appliedConvoyFit, asLatLng)) return

        // Keep the user's bearing so course-up framing rotates with travel, but
        // compute the fit on a FLAT view. cameraForCoordinates over-estimates how
        // much a tilted 3D view covers and returns a too-tight zoom, which framed
        // only the middle of the group and dropped the members nearest the screen
        // edges — the "I press focus and still don't see everyone" bug. The real
        // tilt is re-applied to the FINAL camera below; tilting up from a flat fit
        // only reveals more ground, so everyone framed flat stays framed once
        // tilted. See ConvoyFocusPlanner.fitComputationContext; this is also why
        // the route-overlay fit (which has always framed the whole route) computes
        // flat.
        val currentBearing = map.mapboxMap.cameraState.bearing
        val fitContext = ConvoyFocusPlanner.fitComputationContext(currentBearing)
        runCatching {
            // EdgeInsets expects DEVICE PIXELS, so the dp constants are scaled by
            // display density exactly as the route-overlay fit does. Passing the
            // dp numbers raw made the padding shrink with density: on a 3x phone
            // 140 would have been ~47dp of real breathing room, gluing members to
            // the edge under the very controls the padding exists to clear.
            val density = map.resources.displayMetrics.density
            val fitted =
                map.mapboxMap.cameraForCoordinates(
                    coordinates = points.map { Point.fromLngLat(it.longitude, it.latitude) },
                    camera =
                        cameraOptions {
                            bearing(fitContext.bearingDegrees)
                            pitch(fitContext.pitchDegrees)
                        },
                    coordinatesPadding =
                        EdgeInsets(
                            CONVOY_FIT_PAD_TOP * density,
                            CONVOY_FIT_PAD_SIDE * density,
                            CONVOY_FIT_PAD_SIDE * density,
                            CONVOY_FIT_PAD_SIDE * density,
                        ),
                    // Cap the zoom INSIDE the SDK so the centre it returns matches
                    // the capped zoom. Clamping the zoom afterwards while keeping a
                    // centre computed for a DIFFERENT zoom is what let a bunched-up
                    // group frame off-centre. Too far in and the group would fill
                    // the screen at building level; the floor below guards the
                    // other end (too far out and the convoy is dots on a country
                    // map), which the SDK takes no argument for and only bites on a
                    // >100 km spread.
                    maxZoom = MAX_CONVOY_FIT_ZOOM,
                    offset = null,
                )
            val zoom =
                (fitted.zoom ?: MapMarkers.OWN_MARKER_ZOOM)
                    .coerceIn(MIN_CONVOY_FIT_ZOOM, MAX_CONVOY_FIT_ZOOM)
            map.camera.easeTo(
                cameraOptions {
                    center(fitted.center)
                    zoom(zoom)
                    // Fit centre/zoom come from the flat computation; the user's
                    // own bearing and tilt are re-applied here so a convoy fit
                    // reframes WHAT is shown without spinning or flattening the map
                    // out from under a driver using it course-up in 3D.
                    bearing(currentBearing)
                    pitch(this@MapboxMapSurface.pitch)
                },
                mapAnimationOptions { duration(CONVOY_FIT_ANIMATION_MS) },
            )
            appliedConvoyFit = asLatLng
        }
    }

    override fun emitIncidentTap(incidentId: String) {
        incidentTapFlow.value = incidentId
    }

    override fun consumeIncidentTap() {
        incidentTapFlow.value = null
    }

    override fun emitCrownTap(spawnId: String) {
        crownTapFlow.value = spawnId
    }

    override fun consumeCrownTap() {
        crownTapFlow.value = null
    }

    override fun emitEventTap(eventId: String) {
        eventTapFlow.value = eventId
    }

    override fun consumeEventTap() {
        eventTapFlow.value = null
    }

    override fun emitBillboardTap(billboardId: String) {
        billboardTapFlow.value = billboardId
    }

    override fun consumeBillboardTap() {
        billboardTapFlow.value = null
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

    override fun refreshLocationComponent() {
        // Called after the runtime fine-location permission is granted. The puck
        // was enabled at style-load (before the grant), but the Mapbox location
        // provider does not retroactively start once permission arrives, so we
        // re-apply the settings to (re)initialise it and make the puck appear.
        // The position listener added at style-load stays attached, so the
        // first-fix auto-centre still fires off this refresh. A no-op until the
        // map is composed; wrapped defensively like every other native call.
        val map = mapViewRef ?: return
        runCatching {
            map.location.updateSettings {
                enabled = true
                pulsingEnabled = true
                pulsingColor = pulseColorFor(userMarkerFlow.value)
                // Re-assert course-bearing computation so the heading listener keeps
                // firing after a permission grant / reactivate (course-up depends
                // on it). Matches the style-load settings above.
                puckBearingEnabled = true
                puckBearing = PuckBearing.COURSE
                // Re-assert the puck itself too, or a re-applied settings block
                // would drop the direction arrow back to the plain dot on a
                // permission grant / tab return. Still the plain dot until a real
                // heading has been seen — see [puckBearingArrowShown].
                locationPuck = createDefault2DPuck(withBearing = puckBearingArrowShown)
            }
        }
    }

    /**
     * Swap the plain dot for the dot-with-an-arrow, once, the first time this map
     * learns which way the user is facing.
     *
     * ### Why the arrow is the COURSE, not the compass
     * "The direction you are looking at" is, on a driving map, the direction you
     * are driving: the phone is in a cradle or a pocket and its compass says
     * where the HANDSET is pointed, which flips as it is picked up and swings
     * wildly while stationary. The Maps SDK also drives one bearing signal, not
     * two — the same [PuckBearing] feeds the arrow AND the camera's course-up
     * rotation — so switching the puck to compass heading would silently make the
     * whole map spin with the handset. The arrow therefore shows course over
     * ground, matching the map's existing rotation behaviour, and it holds the
     * last reported course when the car stops rather than swinging on the spot.
     *
     * Idempotent and cheap: the guard means a fix arriving every second does not
     * re-apply the location-component settings.
     *
     * ### Set the flag BEFORE the swap, not after (re-entrancy)
     * The flag is raised BEFORE [updateSettings][com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin]
     * runs, and only cleared again if the swap FAILS. This ordering is load-bearing,
     * not cosmetic. `updateSettings { locationPuck = ... }` re-initialises the
     * location component SYNCHRONOUSLY, and that re-init synchronously RE-FIRES the
     * position and bearing indicator listeners — including the bearing listener that
     * called this method. If the flag were only raised AFTER the swap returned (the
     * old `.onSuccess` ordering), that re-fired bearing listener would find the flag
     * still `false`, call `showPuckBearingArrow()` again, apply `updateSettings`
     * again, re-fire again… recursing without bound. That unbounded recursion is the
     * "Input dispatching timed out" ANR, and the re-entrant `setCamera` the position
     * listener runs during the storm corrupts native camera state into the
     * `libmapbox-maps.so` SIGSEGV/SIGABRT family. Raising the flag first makes the
     * synchronous re-entry a no-op, so the swap runs exactly once. Clearing it only
     * on failure keeps the original retry behaviour: a swap that genuinely throws
     * leaves the next heading to try again rather than stranding the plain dot.
     *
     * ### Apply OFF the indicator callback
     * The swap is [posted][android.view.View.post] onto the map view rather than run
     * inline, so the settings write (and any listener re-fire it triggers) happens
     * after the current indicator callback has unwound instead of nested inside it —
     * keeping native re-entrancy off the call stack entirely. The posted runnable
     * applies to the CAPTURED map and only while it is still the live surface
     * (`mapViewRef === map`): a post can outlive its MapView across a tab round-trip
     * recreate, so it must never mutate a freshly composed replacement map. The
     * set-before-apply guard above still guarantees the swap runs exactly once even
     * if several fixes post before the first post executes.
     */
    private fun showPuckBearingArrow() {
        if (puckBearingArrowShown) return
        val map = mapViewRef ?: return
        map.post {
            // Apply to the CAPTURED map, and only while it is STILL the live
            // surface. A View.post runnable sits on the main looper and can run
            // even after its MapView is detached, so a fix posted just before a
            // tab round-trip recreated the surface must not mutate the NEW map.
            // If the ref has moved on, the captured map is gone; the freshly
            // composed one gets its own arrow through its own first-heading
            // callback (onRelease clears puckBearingArrowShown, so the flag never
            // carries across the swap and never sets this map's flag either).
            if (mapViewRef !== map) return@post
            applyPuckBearingArrowOnce(
                isShown = { puckBearingArrowShown },
                markShown = { puckBearingArrowShown = true },
                markNotShown = { puckBearingArrowShown = false },
                applySwap = {
                    map.location.updateSettings {
                        locationPuck = createDefault2DPuck(withBearing = true)
                    }
                },
            )
        }
    }

    /**
     * Run an indicator-listener body under the [handlingIndicator] latch: if a
     * callback is already on the stack this is a no-op, otherwise the flag is held
     * for the duration of [block] and always released afterwards (finally). Keeps
     * any native re-init that re-fires an indicator listener from re-entering map
     * mutation from inside another indicator callback. Inline so the existing
     * `return@OnIndicator…ChangedListener` early-returns inside [block] still work
     * (and still run the finally).
     */
    private inline fun guardIndicator(block: () -> Unit) {
        if (handlingIndicator) return
        handlingIndicator = true
        try {
            block()
        } finally {
            handlingIndicator = false
        }
    }

    override fun setActive(active: Boolean) {
        // Called when the map home is covered by another bottom-nav tab, and
        // again when it comes back. The map deliberately stays composed across
        // that trip (see MapSurface.setActive), so this is what stops a map
        // nobody can see from pulsing its puck every frame and burning GPS fixes.
        //
        // Only the location component is touched: the MapView, its loaded style
        // and the camera all stay exactly as they were, which is what makes the
        // return instant. Reactivating goes through the same updateSettings call
        // refreshLocationComponent already uses after a permission grant, so the
        // puck comes back the same way it does there. A no-op until the map is
        // composed; wrapped defensively like every other native call.
        //
        // Also the feature-health "is the user actually looking at this?" signal:
        // while inactive the map is covered by an opaque page, so a map that is
        // not rendering is not a defect and the render watchdog must not accrue
        // time (see the watchdog loop in [Content]).
        surfaceActive = active
        val map = mapViewRef ?: return
        if (active) {
            refreshLocationComponent()
        } else {
            // Cancel any pending idle-return so a timer armed just before the
            // user left the tab can't ease the camera while the map is covered.
            idleReturnJob?.cancel()
            idleReturnJob = null
            runCatching {
                map.location.updateSettings {
                    enabled = false
                    pulsingEnabled = false
                }
            }
        }
    }

    override fun recenter() = userRequestedRecenter(resetBearingToNorth = false)

    /**
     * The compass: exactly [recenter], plus north-up folded into the SAME camera
     * update. Calling `recenter()` and `resetNorth()` back-to-back would issue
     * two easeTo animations on one camera a frame apart — the second cancels the
     * first, so the user sees a stutter and loses whichever property the
     * cancelled animation carried. One cameraOptions carrying centre + zoom +
     * pitch + bearing(0) cannot fight itself.
     */
    override fun recenterNorthUp() = userRequestedRecenter(resetBearingToNorth = true)

    /**
     * The compass toggle's two orientation modes (see [MapCompassMode]).
     *
     * Storing the mode is what makes the SINGLE follow path apply the right
     * bearing: the position/heading listeners and [easeToUser] all read
     * [compassMode], so north-up keeps the camera at 0 and course-up rotates it to
     * the puck's heading — no second camera owner.
     *
     * A real CHANGE is applied at once as one user-requested re-centre (resume
     * follow, cancel any idle-return, ease to the user): north-up folds bearing(0)
     * into that move; course-up rotates to the current heading, since
     * [compassMode] is updated first so [easeToUser] picks it up. Re-setting the
     * SAME mode is a no-op, so the shell can safely re-push the saved mode on a
     * surface swap without a spurious camera move on open.
     */
    override fun setCompassMode(mode: MapCompassMode) {
        if (mode == compassMode) return
        compassMode = mode
        userRequestedRecenter(resetBearingToNorth = mode == MapCompassMode.NorthUp)
    }

    /**
     * A re-centre the USER asked for, by the my-location control or the compass:
     * resume follow, cancel any pending idle-return timer, and glide to them.
     * The idle-return path reuses [easeToUser] so the three never diverge.
     *
     * Both entry points share this body rather than repeating it, so the
     * follow/timer handling cannot drift between the two controls — only the
     * bearing differs, and that difference is the single parameter.
     */
    private fun userRequestedRecenter(resetBearingToNorth: Boolean) {
        followController.onRecenterRequested()
        idleReturnJob?.cancel()
        idleReturnJob = null
        easeToUser(resetBearingToNorth = resetBearingToNorth)
    }

    /**
     * Smoothly glides the camera to the user's current position (or the default
     * town camera when there is no fix yet), keeping the current 3D tilt, and —
     * when [resetBearingToNorth] — rotating back to north-up in the same move.
     * Shared by the my-location control ([recenter]), the compass
     * ([recenterNorthUp]) and the 10-second idle-return timer: same target, same
     * zoom, same pitch, same easing and duration for all three, so a re-centre
     * never looks like a different gesture depending on what triggered it. The
     * ONE thing that varies is [resetBearingToNorth] — the compass alone folds a
     * rotation back to north into this same camera update. A no-op until the map
     * is composed; wrapped defensively so a missing fix/permission never crashes.
     *
     * With no fix ([lastPoint] null — no location yet, or permission denied) the
     * camera still moves, to the default town camera, which is the established
     * behaviour of the my-location control. The bearing reset does not depend on
     * having a fix: it rides on the same camera update either way, so the compass
     * always at least delivers north-up.
     */
    private fun easeToUser(resetBearingToNorth: Boolean = false) {
        val map = mapViewRef ?: return
        val target = lastPoint
        runCatching {
            val destination =
                cameraOptions {
                    if (target != null) {
                        center(target)
                        // The RESTING/browsing zoom (the user's "focus distance"
                        // preference), defaulting to the original own-marker zoom
                        // when unset — this is the re-centre-on-the-user framing,
                        // not the active drive-follow step.
                        zoom(browsingZoom)
                    } else {
                        center(
                            Point.fromLngLat(
                                MapMarkers.DEFAULT_CAMERA.longitude,
                                MapMarkers.DEFAULT_CAMERA.latitude,
                            ),
                        )
                        zoom(MapMarkers.DEFAULT_CAMERA.zoom)
                    }
                    // Keep the 3D tilt when the camera re-centres.
                    pitch(this@MapboxMapSurface.pitch)
                    // Bearing, folded into the SAME options — see the KDoc: this
                    // is why the compass does not issue its own second easeTo.
                    // - [resetBearingToNorth] (the compass switching to north-up,
                    //   or recenterNorthUp) always wins: bearing(0).
                    // - otherwise, in course-up, re-centring keeps the map facing
                    //   the current heading (so my-location / idle-return don't
                    //   silently drop course-up orientation).
                    // - in north-up (not an explicit reset) the bearing is left
                    //   untouched, preserving any manual rotation, exactly as
                    //   the my-location control always has.
                    when {
                        resetBearingToNorth -> bearing(0.0)
                        compassMode == MapCompassMode.CourseUp ->
                            bearing(CompassCamera.followBearing(compassMode, lastBearing))
                    }
                }
            // Smoothly glide to the target rather than jumping instantly, via the
            // camera-animations plugin. A pleasant ~1s ease reads as intentional
            // and avoids the disorienting snap of setCamera().
            map.camera.easeTo(
                destination,
                mapAnimationOptions { duration(RECENTER_ANIMATION_MS) },
            )
        }
    }

    /**
     * One-shot centre on [point] (the convoy "Go to location"): eased like a
     * my-location recentre, but to an ARBITRARY point rather than the user's puck.
     *
     * It is framed as a synthetic manual pan on purpose — [onUserGestureBegin]
     * detaches follow and cancels any idle-return so the position/convoy-fit ticks
     * cannot immediately override the centre, then [onUserGestureEnd] arms the
     * ordinary idle-return timer so the camera drifts back to following the user
     * after the same quiet window a real pan uses. No new camera owner, no change
     * to the convoy-fit invariant: it borrows the existing follow path for one ease.
     * A no-op until the map is composed; wrapped defensively.
     */
    override fun centerOn(point: MapPoint) {
        val map = mapViewRef ?: return
        // Detach follow, then GUARANTEE it is re-armed: begin and end must be
        // paired even if the ease throws, or a failed centre would leave the
        // follow controller stuck in gesture-begun state with the idle-return job
        // cancelled — follow permanently detached. The end therefore runs in a
        // finally, and only the ease itself is defended with runCatching.
        onUserGestureBegin()
        try {
            runCatching {
                map.camera.easeTo(
                    cameraOptions {
                        center(Point.fromLngLat(point.longitude, point.latitude))
                        // The resting/browsing zoom, the same framing a my-location
                        // recentre lands at, and keep the current tilt.
                        zoom(browsingZoom)
                        pitch(this@MapboxMapSurface.pitch)
                        if (compassMode == MapCompassMode.CourseUp) {
                            bearing(CompassCamera.followBearing(compassMode, lastBearing))
                        }
                    },
                    mapAnimationOptions { duration(RECENTER_ANIMATION_MS) },
                )
            }
        } finally {
            // Arm the idle-return timer so follow resumes after the quiet window,
            // exactly as a real pan-to-look-at-something does — success or throw.
            onUserGestureEnd()
        }
    }

    /**
     * A manual map-camera gesture STARTED (pan / pinch-zoom / rotate / tilt):
     * stop following and stop any pending idle-return — no 10-second countdown
     * runs while the user is actively interacting, so a continuous gesture lasting
     * longer than the window never snaps the camera mid-drag. The timer is armed
     * only when the gesture ends ([onUserGestureEnd]). Runs on the main thread
     * (gesture callbacks).
     */
    private fun onUserGestureBegin() {
        followController.onGestureBegin()
        idleReturnJob?.cancel()
        idleReturnJob = null
    }

    /**
     * A manual map-camera gesture ENDED. Only once ALL gestures have ended (a
     * pan can overlap a pinch) do we arm the [CameraFollowController.IDLE_RETURN_MS]
     * timer, so it counts from when interaction STOPS. On elapse we resume the
     * follow STATE, but only glide back to the user when no route overlay owns the
     * camera — mirroring the position-listener follow gate so the idle-return
     * never fights an active route fit. Follow resumes moving the camera once the
     * overlay is cleared. A no-op until the composable scope exists.
     */
    private fun onUserGestureEnd() {
        if (!followController.onGestureEnd()) return
        val scope = followScope ?: return
        // Cancel any previous timer first so exactly ONE idle job is ever alive.
        idleReturnJob?.cancel()
        val job =
            scope.launch {
                delay(CameraFollowController.IDLE_RETURN_MS)
                // A gesture that began during the delay cancels this job. delay()
                // throws on cancellation while suspended, but if cancel() lands in
                // the instant AFTER delay() returns and BEFORE the lines below run,
                // there is no further suspension point to observe it — so check
                // explicitly here and bail before touching any camera state, so a
                // cancelled timer can never resume follow or fire the recenter.
                ensureActive()
                // Quiet window elapsed with no further gesture: resume follow.
                followController.onIdleElapsed()
                // Glide back only when nothing else owns the camera (a route
                // preview fits and holds it); otherwise leave the framing be.
                if (followController.shouldTrack(hasRouteOverlay = routeOverlayFlow.value != null)) {
                    easeToUser()
                }
            }
        idleReturnJob = job
        // Clear the shared reference only when THIS job is still the current one,
        // so a job that completes or is cancelled after a newer timer has already
        // replaced it never nulls out the newer job. invokeOnCompletion runs on
        // the coroutine's Main dispatcher (same thread that mutates idleReturnJob),
        // so the identity check needs no extra synchronisation.
        job.invokeOnCompletion {
            if (idleReturnJob === job) idleReturnJob = null
        }
    }

    override fun resetNorth() {
        val map = mapViewRef ?: return
        // Ease the camera bearing back to north-up; other camera props are left
        // untouched. Uses the camera-animations plugin with the same explicit
        // duration as recenter(), so the reset is predictable and consistent
        // across Mapbox default changes. A no-op until the map is composed;
        // wrapped defensively.
        runCatching {
            map.camera.easeTo(
                cameraOptions { bearing(0.0) },
                mapAnimationOptions { duration(RECENTER_ANIMATION_MS) },
            )
        }
    }

    @Composable
    override fun Content(modifier: Modifier) {
        // ---- Feature health: the v0.8.1 blank-map detector -------------------
        // A real access token is structurally guaranteed here (rememberMapSurface
        // only builds this class when the token is non-blank), but it is read
        // rather than assumed so the reported flag states a fact instead of a
        // belief. The BOOLEAN is all that travels; the token value never does.
        val accessTokenPresent = stringResource(R.string.mapbox_access_token).isNotBlank()
        val health = rememberFeatureHealthReporter(accessTokenPresent = accessTokenPresent)
        // The watchdog below is deliberately keyed on `health` alone, so a change
        // of lifecycle owner (a different NavBackStackEntry / host) does NOT
        // restart the blank-map clock and lose the time already accrued. That
        // makes the coroutine outlive the owner it launched with, so it must read
        // the CURRENT owner on every tick rather than the one it captured —
        // otherwise it would gate reporting on a stale, permanently-DESTROYED
        // lifecycle and either suppress every report or mislabel `foreground`.
        val healthLifecycle by rememberUpdatedState(LocalLifecycleOwner.current)
        DisposableEffect(health) {
            healthReporter = health
            onDispose { healthReporter = null }
        }

        // The render watchdog. This — not the SDK error listeners — is the check
        // that would actually have caught v0.8.1, where the map simply never
        // appeared and the SDK reported nothing at all.
        //
        // Every gate below exists to keep a benign condition from filing a public
        // issue. Time accrues ONLY while the map is shown, uncovered, in the
        // foreground, and the device has validated internet — so a tunnel, a
        // plane, a dead SIM, a backgrounded app, or the user sitting on another
        // tab all simply pause the clock instead of tripping it.
        LaunchedEffect(health) {
            val watchdog = MapRenderWatchdog()
            while (!watchdog.isDisarmed) {
                delay(HEALTH_TICK_MILLIS)
                appInForeground =
                    healthLifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                val eligible =
                    surfaceShown && surfaceActive && appInForeground && health.isOnline()
                val fired = watchdog.onTick(HEALTH_TICK_MILLIS, eligible, everRendered)
                // Re-read `everRendered` instead of reusing the snapshot above.
                // It is @Volatile because the Maps SDK sets it from its NATIVE
                // callback threads, so it can flip to true between the tick
                // decision and this report — precisely in the marginal case this
                // watchdog targets, a map that finally renders around the timeout
                // boundary. The report files a WORLD-READABLE GitHub issue, so a
                // "map never rendered" claim about a map that just rendered is
                // exactly the false positive worth one extra volatile read.
                if (fired && !everRendered) {
                    health.report(
                        kind = FeatureHealthKind.MapRenderTimeout,
                        foreground = appInForeground,
                        surfaceShown = surfaceShown && surfaceActive,
                    )
                }
            }
        }

        val trafficOn by trafficFlow.collectAsState()
        val mapMode by mapModeFlow.collectAsState()
        val overlay by routeOverlayFlow.collectAsState()
        val incidents by incidentMarkersFlow.collectAsState()
        val crowns by crownMarkersFlow.collectAsState()
        val events by eventMarkersFlow.collectAsState()
        val billboards by billboardMarkersFlow.collectAsState()
        // The caller's marker only carries live-sharing state now (its position
        // is the device puck): a green pulse signals sharing, blue otherwise.
        val marker by userMarkerFlow.collectAsState()
        // Recreate the position listener once; it just records the last fix so
        // recenter() can jump the camera to it.
        val positionListener =
            remember {
                OnIndicatorPositionChangedListener { point ->
                    // Under the shared re-entrancy latch: a native re-init that
                    // re-fires this listener while a callback is already on the
                    // stack is dropped, so the follow setCamera below can never be
                    // re-entered from inside itself (see [guardIndicator]).
                    guardIndicator {
                        lastPoint = point
                        // Private breadcrumb tail: record the user's OWN path while —
                        // and only while — they are live-sharing. Done BEFORE the
                        // camera-follow gate below so the tail keeps growing even when
                        // the user has panned away and follow is suppressed. The buffer
                        // drops jitter/duplicate fixes itself and only reports a change
                        // when the tail actually moved, so a stationary puck emitting a
                        // fix every frame triggers no redraw. Local-only: nothing here
                        // crosses the seam or is written anywhere shared.
                        if (userMarkerFlow.value?.isLiveSharing == true) {
                            val changed =
                                breadcrumbTrail.add(
                                    MapPoint(longitude = point.longitude(), latitude = point.latitude()),
                                )
                            if (changed) runCatching { redrawBreadcrumb() }
                        }
                        // "Camera follows me": while following (and no route overlay
                        // owns the camera), keep the camera centred on the puck as the
                        // user moves. Suppressed once the user pans/zooms/rotates —
                        // the 10s idle timer resumes it — and while a route preview is
                        // shown, so follow never fights an explicit camera move.
                        if (!followController.shouldTrack(hasRouteOverlay = routeOverlayFlow.value != null)) {
                            return@OnIndicatorPositionChangedListener
                        }
                        val map = mapViewRef ?: return@OnIndicatorPositionChangedListener
                        // "Keep the whole convoy in view" replaces the follow TARGET,
                        // not the follow MACHINERY: same gate above, same listener,
                        // just a different thing to frame. Own movement still drives
                        // it, because the user is one of the points being framed.
                        if (convoyFitPoints != null) {
                            centeredOnFirstFix = true
                            applyConvoyFit()
                            return@OnIndicatorPositionChangedListener
                        }
                        runCatching {
                            if (!centeredOnFirstFix) {
                                // Open the FIRST fix close to the user, at the
                                // RESTING/browsing zoom (the user's "focus distance"
                                // preference, defaulting to the original own-marker
                                // zoom when unset) and 3D tilt (snap, so the map opens
                                // already framed). This is the browsing open, not the
                                // active drive-follow step below, which never re-sets
                                // the zoom.
                                centeredOnFirstFix = true
                                map.mapboxMap.setCamera(
                                    cameraOptions {
                                        center(point)
                                        zoom(browsingZoom)
                                        pitch(this@MapboxMapSurface.pitch)
                                        // Open already facing the direction of travel
                                        // when the user chose course-up; north-up
                                        // leaves the bearing at 0.
                                        if (compassMode == MapCompassMode.CourseUp) {
                                            bearing(CompassCamera.followBearing(compassMode, lastBearing))
                                        }
                                    },
                                )
                            } else {
                                // Later fixes: snap the CENTRE straight onto the puck
                                // (leave zoom/pitch/bearing untouched) so the screen
                                // actually keeps up with the user while driving.
                                //
                                // This deliberately does NOT ease. This callback fires
                                // for every INTERPOLATED frame of the location
                                // component's own puck animation (many per second, not
                                // once per GPS fix), and those frames are already
                                // smooth. The previous code started a ~700ms easeTo on
                                // each of them; because a fresh ease supersedes the
                                // in-flight one every frame, the camera only ever
                                // travelled a sliver of each 700ms glide before being
                                // restarted — so at driving speed it fell ever further
                                // behind and the map appeared frozen while the puck
                                // drove off the edge. Matching the camera centre to the
                                // (already-smoothed) puck position every frame keeps the
                                // puck pinned with no queue of competing animations and
                                // no animator cost — the pattern Mapbox documents for
                                // simple location tracking. A programmatic setCamera
                                // does not trigger the gesture listeners, so this never
                                // disables follow; a manual pan still breaks follow via
                                // the gesture hooks and the idle-return timer restores it.
                                //
                                // In course-up the bearing rides along with the centre
                                // so the map stays rotated to the direction of travel as
                                // the user moves (the bearing listener also rotates it in
                                // place while turning). north-up sets only the centre,
                                // leaving the bearing untouched (0, or a manual rotation).
                                map.mapboxMap.setCamera(
                                    cameraOptions {
                                        center(point)
                                        if (compassMode == MapCompassMode.CourseUp) {
                                            bearing(CompassCamera.followBearing(compassMode, lastBearing))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

        // Puck-heading listener for course-up. It records the latest COURSE
        // bearing (used by the position listener + easeToUser) and, while in
        // course-up and following, rotates the camera IN PLACE so the map keeps
        // turning to the direction of travel even when the user is barely moving
        // (e.g. rounding a bend). Position-follow is untouched — this only ever
        // sets the bearing.
        val bearingListener =
            remember {
                OnIndicatorBearingChangedListener { heading ->
                    // Same shared re-entrancy latch as the position listener: the
                    // arrow swap and the rotate-in-place setCamera below both mutate
                    // native state, so a re-fired bearing callback stacked on top of
                    // one already running is dropped (see [guardIndicator]).
                    guardIndicator {
                        lastBearing = heading
                        // First real heading of this map: give the dot its arrow.
                        //
                        // This listener only fires for a fix that actually CARRIES a
                        // bearing — the location provider maps the platform fix's
                        // bearing and drops the sample when there is none — so
                        // reaching here is the proof that "which way am I pointing?"
                        // has an answer. Guarded, so it is one settings update per
                        // map, not one per fix.
                        showPuckBearingArrow()
                        // north-up ignores the heading entirely (the map stays at 0).
                        if (compassMode != MapCompassMode.CourseUp) {
                            return@OnIndicatorBearingChangedListener
                        }
                        // Same follow gate as the position listener: a manual gesture
                        // (or a route preview) owns the camera, so a heading update
                        // must not fight it — the idle-return timer resumes course-up
                        // rotation once the user stops interacting.
                        if (!followController.shouldTrack(hasRouteOverlay = routeOverlayFlow.value != null)) {
                            return@OnIndicatorBearingChangedListener
                        }
                        // A convoy fit deliberately keeps the user's current bearing
                        // while framing the group (see applyConvoyFit), so don't spin
                        // the map out from under it here.
                        if (convoyFitPoints != null) return@OnIndicatorBearingChangedListener
                        val map = mapViewRef ?: return@OnIndicatorBearingChangedListener
                        // Rotate in place: only the bearing is set, so centre/zoom/
                        // pitch stay exactly as the position listener maintains them.
                        // A programmatic setCamera does not fire the gesture listeners,
                        // so this never disables follow.
                        runCatching {
                            map.mapboxMap.setCamera(
                                cameraOptions {
                                    bearing(CompassCamera.followBearing(compassMode, heading))
                                },
                            )
                        }
                    }
                }
            }

        // Scope for the 10-second idle-return timer, tied to this composable's
        // lifecycle: cancelled (with any in-flight timer) when the map leaves the
        // composition, so no follow work outlives the screen.
        val followScope = rememberCoroutineScope()
        DisposableEffect(followScope) {
            this@MapboxMapSurface.followScope = followScope
            onDispose {
                idleReturnJob?.cancel()
                idleReturnJob = null
                this@MapboxMapSurface.followScope = null
            }
        }

        AndroidView(
            modifier = modifier,
            factory = { context ->
                val token = context.getString(R.string.mapbox_access_token)
                if (token.isNotBlank()) {
                    MapboxOptions.accessToken = token
                }
                appContext = context.applicationContext
                MapView(context).apply {
                    mapViewRef = this
                    // The surface now genuinely exists and is on screen — from
                    // here on, "the map is not rendering" is a statement worth
                    // making. Feature-health only; see the class KDoc.
                    surfaceShown = true
                    everRendered = false
                    // The Maps SDK's own health callbacks. Listener-based
                    // detection is precise but only fires when the SDK NOTICES a
                    // failure; the v0.8.1 blank map may have produced no callback
                    // at all, which is why the watchdog in [Content] exists
                    // alongside these rather than instead of them.
                    runCatching {
                        mapLoadingErrorSubscription =
                            mapboxMap.subscribeMapLoadingError { error ->
                                // Pass the TYPE NAME only. The error's message,
                                // sourceId and tileId are dropped on the floor:
                                // a tile id is a coordinate and a message can
                                // carry a URL, and this feeds a PUBLIC issue.
                                onMapLoadingError(error.type.name)
                            }
                    }
                    runCatching {
                        // "The map finished loading everything it needed" — the
                        // strongest possible proof it is not blank.
                        mapLoadedSubscription = mapboxMap.subscribeMapLoaded { markRendered() }
                    }
                    runCatching {
                        // Backstop for the above: on a slow link MapLoaded can lag
                        // well behind the first usable frame, and a FULL render
                        // frame already proves the map drew its whole content.
                        // Accepting either is what keeps the watchdog from firing
                        // at a map the user can plainly see.
                        renderFrameSubscription =
                            mapboxMap.subscribeRenderFrameFinished { frame ->
                                if (frame.renderMode == RenderModeType.FULL) markRendered()
                            }
                    }
                    // Drop the scale bar (distance/km ruler, upper-left); the
                    // map-first shell has no room for it.
                    runCatching { scalebar.updateSettings { enabled = false } }
                    // Disable the built-in Mapbox compass (which appears top-right
                    // when the map is rotated); the shell draws its own compass
                    // control in the right-side floating stack instead.
                    runCatching { compass.updateSettings { enabled = false } }
                    // Mirror the live camera bearing into the flow so the shell's
                    // compass control rotates to keep pointing at true north.
                    val camListener =
                        object : OnCameraChangeListener {
                            @Suppress("UNUSED_PARAMETER")
                            override fun onCameraChanged(eventData: CameraChangedEventData) {
                                // Round to the nearest whole degree before emitting:
                                // the raw camera bearing changes by tiny fractions on
                                // every frame while rotating, which would spam the
                                // flow and re-compose the compass needlessly. 1° steps
                                // are visually smooth for a compass needle, and the
                                // MutableStateFlow dedupes consecutive equal values so
                                // only real 1° changes propagate downstream.
                                val camera = mapboxMap.cameraState
                                bearingFlow.value =
                                    camera.bearing.toFloat().roundToInt().toFloat()
                                // Same de-duplication argument as the bearing
                                // above, applied to the whole camera: the convoy
                                // awareness overlay reprojects every member when
                                // this changes, so it is rounded to about a metre
                                // / a hundredth of a zoom / a whole degree and
                                // StateFlow collapses the settled frames.
                                cameraSnapshotFlow.value =
                                    MapCameraSnapshot.of(
                                        latitude = camera.center.latitude(),
                                        longitude = camera.center.longitude(),
                                        zoom = camera.zoom,
                                        bearing = camera.bearing,
                                        pitch = camera.pitch,
                                    )
                            }
                        }
                    cameraChangeListener = camListener
                    runCatching { mapboxMap.addOnCameraChangeListener(camListener) }
                    // Long-press (hold) anywhere on the map to navigate there:
                    // publish the pressed lng/lat so the host opens the route
                    // preview for it. This is the hold gesture only — pan/zoom/
                    // rotate (drag/pinch/two-finger) are untouched, so it never
                    // conflicts with normal map manipulation. Returning true marks
                    // the long-press handled.
                    val longPressListener =
                        OnMapLongClickListener { point ->
                            // Publish through the shared hook (not placeRequestFlow
                            // directly) so all publishing goes through one place and
                            // stays consistent with the stub surface.
                            emitLongPress(MapPoint(point.longitude(), point.latitude()))
                            true
                        }
                    longClickListener = longPressListener
                    runCatching { gestures.addOnMapLongClickListener(longPressListener) }
                    // Single tap on a place the basemap already draws (a shop, a
                    // petrol station, a workshop) → the same "navigate here?"
                    // preview the hold gesture raises, but carrying the place's own
                    // name so the preview can say where the user is going.
                    //
                    // Uses the Standard style's typed `poi` FEATURESET via the
                    // Interactions API rather than queryRenderedFeatures: Standard
                    // ships as a style IMPORT whose internal layer ids are private
                    // and unstable, so there is no layer id to query against that
                    // would keep working across basemap updates. The featureset is
                    // the style's public, versioned contract for exactly this, and
                    // hands back a typed name + geometry instead of raw JSON.
                    // ClickInteraction.standardPoi defaults its importId to
                    // "basemap" — the same import [STANDARD_IMPORT_ID] configures
                    // for lightPreset/show3dObjects, i.e. the one Style.STANDARD
                    // actually loads.
                    //
                    // Returning true marks the tap handled so it stops here and
                    // does not fall through to the rest of the map.
                    runCatching {
                        placeInteractions +=
                            mapboxMap.addInteraction(
                                ClickInteraction.standardPoi { poi, _ ->
                                    // A poi feature's geometry is a non-null Point.
                                    val point = poi.geometry
                                    emitPlaceTap(
                                        point = MapPoint(point.longitude(), point.latitude()),
                                        // Blank/absent names fall back to the host's
                                        // dropped-pin label rather than previewing an
                                        // unnamed destination.
                                        name = poi.name?.trim()?.takeIf { it.isNotEmpty() },
                                    )
                                    true
                                },
                            )
                    }
                    // Camera-manipulation gestures (pan/zoom/rotate/tilt): any of
                    // them means the user is taking over the camera. On BEGIN they
                    // stop "follow me" and halt any pending idle-return; the 10s
                    // idle-return timer is armed only on END (see onUserGestureEnd),
                    // so it counts from when interaction STOPS and never snaps the
                    // camera mid-gesture. These are touch-gesture callbacks only —
                    // the programmatic follow/recenter eases never fire them, so
                    // follow doesn't cancel itself. Returning false from onMove
                    // leaves the pan itself untouched.
                    val moveL =
                        object : OnMoveListener {
                            override fun onMoveBegin(detector: MoveGestureDetector) = onUserGestureBegin()
                            override fun onMove(detector: MoveGestureDetector): Boolean = false
                            override fun onMoveEnd(detector: MoveGestureDetector) = onUserGestureEnd()
                        }
                    val scaleL =
                        object : OnScaleListener {
                            override fun onScaleBegin(detector: StandardScaleGestureDetector) = onUserGestureBegin()
                            override fun onScale(detector: StandardScaleGestureDetector) = Unit
                            override fun onScaleEnd(detector: StandardScaleGestureDetector) = onUserGestureEnd()
                        }
                    val rotateL =
                        object : OnRotateListener {
                            override fun onRotateBegin(detector: RotateGestureDetector) = onUserGestureBegin()
                            override fun onRotate(detector: RotateGestureDetector) = Unit
                            override fun onRotateEnd(detector: RotateGestureDetector) = onUserGestureEnd()
                        }
                    val shoveL =
                        object : OnShoveListener {
                            override fun onShoveBegin(detector: ShoveGestureDetector) = onUserGestureBegin()
                            override fun onShove(detector: ShoveGestureDetector) = Unit
                            override fun onShoveEnd(detector: ShoveGestureDetector) = onUserGestureEnd()
                        }
                    moveListener = moveL
                    scaleListener = scaleL
                    rotateListener = rotateL
                    shoveListener = shoveL
                    runCatching { gestures.addOnMoveListener(moveL) }
                    runCatching { gestures.addOnScaleListener(scaleL) }
                    runCatching { gestures.addOnRotateListener(rotateL) }
                    runCatching { gestures.addOnShoveListener(shoveL) }
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
                            // Tilt the default camera so the map opens in 3D.
                            pitch(this@MapboxMapSurface.pitch)
                        },
                    )
                    mapboxMap.loadStyle(Style.STANDARD) { style ->
                        loadStateFlow.value = MapLoadState.Loaded
                        // Apply the current day/night light preset (read the flow
                        // directly — this runs async at style-load, not at
                        // composition, so the captured value can be stale).
                        runCatching { applyLightPreset(style, mapModeFlow.value) }
                        // Apply the current 3D-objects toggle (read the flow
                        // directly — this runs async at style-load, so a captured
                        // value could be stale) so a map (re)created while 3D is
                        // OFF opens with the buildings hidden, not re-shown.
                        runCatching { apply3dObjects(style, is3dFlow.value) }
                        // Built for the CURRENT preset (flow read directly — this
                        // runs async at style-load, so a captured value could be
                        // stale), so a map that loads straight into night mode gets
                        // the night congestion colours without waiting for a toggle.
                        runCatching { addTrafficLayer(style, mapModeFlow.value) }
                        runCatching { applyTrafficVisibility(style, trafficFlow.value) }
                        // Route line + destination marker managers, created once
                        // the style is ready. Drawn from the current flow value
                        // so a route picked while the style was still loading is
                        // rendered (not lost).
                        runCatching {
                            routeLineManager = annotations.createPolylineAnnotationManager()
                            destMarkerManager = annotations.createCircleAnnotationManager()
                            // Fresh managers ⇒ any previously-drawn annotations are
                            // gone, so drop the cache to force a redraw of the
                            // current overlay against the new managers.
                            lastAppliedOverlay = null
                            applyRouteOverlayIfChanged(this, routeOverlayFlow.value)
                        }
                        // Incident markers manager (the shared incidents layer),
                        // created once the style is ready. Drawn from the current
                        // flow value so markers fetched while the style was still
                        // loading are rendered (not lost).
                        runCatching {
                            val incidentManager = annotations.createPointAnnotationManager()
                            // The overlap rules EVERY incident layer must have,
                            // shared with the navigation map's copy of this layer
                            // so an accident can never be allowed to vanish behind
                            // a roadwork on one map but not the other. See
                            // IncidentMarkerLayer.configure for why.
                            IncidentMarkerLayer.configure(incidentManager)
                            // Tap an incident badge → publish its id so the host can
                            // open the detail sheet.
                            //
                            // This is an ANNOTATION click, not a map click: the
                            // annotation plugin hit-tests its own symbols first and
                            // we return true to consume the event, so the tap never
                            // falls through to the basemap-POI interaction (which
                            // would open a "navigate here?" preview for the crash the
                            // user was asking about). Pan/pinch/rotate and the
                            // long-press "navigate here" gesture are all untouched —
                            // none of them is a tap.
                            val incidentClick =
                                OnPointAnnotationClickListener { annotation ->
                                    onIncidentAnnotationClicked(annotation.id)
                                }
                            incidentClickListener = incidentClick
                            incidentManager.addClickListener(incidentClick)
                            incidentMarkerManager = incidentManager
                            lastAppliedIncidents = null
                            // Style images are owned by the style that was just
                            // (re)loaded, so anything registered against the
                            // PREVIOUS one is gone. Forgetting them here is what
                            // makes the icons survive a style reload: the next
                            // draw re-uploads them instead of assuming they are
                            // still present.
                            registeredIncidentImages.clear()
                            applyIncidentMarkersIfChanged(incidentMarkersFlow.value)
                        }
                        // Kronjakt crown manager — its OWN PointAnnotationManager,
                        // so the crown layer and the incidents layer can be drawn,
                        // emptied and hit-tested independently of each other.
                        //
                        // Created unconditionally, even with the crownHuntSpawn
                        // flag off: an empty manager draws nothing, costs nothing
                        // and issues no query (the host is what queries, and it is
                        // gated). Gating the MANAGER on the flag instead would mean
                        // a flag flipped on mid-session had no manager to draw
                        // into until the next style load.
                        runCatching {
                            val crownManager = annotations.createPointAnnotationManager()
                            // Same reasoning as the incidents layer: never let
                            // symbol collision silently drop a crown. Two crowns
                            // are at least 150 m apart by construction, so overlap
                            // only happens when zoomed well out — where a hidden
                            // crown would read as "there is nothing here".
                            crownManager.iconAllowOverlap = true
                            crownManager.iconIgnorePlacement = true
                            val crownClick =
                                OnPointAnnotationClickListener { annotation ->
                                    onCrownAnnotationClicked(annotation.id)
                                }
                            crownClickListener = crownClick
                            crownManager.addClickListener(crownClick)
                            crownMarkerManager = crownManager
                            lastAppliedCrowns = null
                            // Style images die with the style that owned them.
                            registeredCrownImages.clear()
                            applyCrownMarkersIfChanged(crownMarkersFlow.value)
                        }
                        // Community events manager (the shared events layer),
                        // created once the style is ready — a SEPARATE manager from
                        // the incidents one so event pins never collide with, or
                        // get torn down alongside, incident badges. Drawn from the
                        // current flow value so events fetched while the style was
                        // still loading are rendered (not lost).
                        runCatching {
                            val eventManager = annotations.createPointAnnotationManager()
                            // Overlapping event pins are shown, never dropped — a
                            // town centre with several meetups must not silently
                            // hide one (same rationale as the incidents layer).
                            eventManager.iconAllowOverlap = true
                            eventManager.iconIgnorePlacement = true
                            val eventClick =
                                OnPointAnnotationClickListener { annotation ->
                                    onEventAnnotationClicked(annotation.id)
                                }
                            eventClickListener = eventClick
                            eventManager.addClickListener(eventClick)
                            eventMarkerManager = eventManager
                            lastAppliedEvents = null
                            registeredEventImages.clear()
                            applyEventMarkersIfChanged(eventMarkersFlow.value)
                        }
                        // Sponsored billboards manager — again its OWN manager,
                        // for the same reasons as the three above: its own tap
                        // intent ("show me this advert"), its own lookup, and
                        // the ability to be emptied (the digitalBillboards flag
                        // going off) without disturbing anything else on the
                        // map. Drawn from the current flow value so billboards
                        // that arrived while the style was still loading are
                        // rendered rather than lost.
                        runCatching {
                            val billboardManager = annotations.createPointAnnotationManager()
                            BillboardMarkerLayer.configure(billboardManager)
                            val billboardClick =
                                OnPointAnnotationClickListener { annotation ->
                                    onBillboardAnnotationClicked(annotation.id)
                                }
                            billboardClickListener = billboardClick
                            billboardManager.addClickListener(billboardClick)
                            billboardMarkerManager = billboardManager
                            lastAppliedBillboards = null
                            // Style images die with the style that owned them.
                            registeredBillboardImages.clear()
                            applyBillboardMarkersIfChanged(billboardMarkersFlow.value)
                        }
                        // Private breadcrumb tail: a GeoJSON line source + a
                        // line-gradient layer (both hidden until there is a tail to
                        // draw), created once the style is ready. Idempotent. If a
                        // live-sharing session is already running (e.g. the MapView
                        // was recreated on a tab round-trip mid-session), redraw the
                        // retained tail against the fresh style so it is not lost.
                        runCatching {
                            addBreadcrumbLayer(style)
                            if (userMarkerFlow.value?.isLiveSharing == true) {
                                redrawBreadcrumb()
                            } else {
                                clearBreadcrumbRender()
                            }
                        }
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
                                // Compute the puck's COURSE bearing (direction of
                                // travel) so the OnIndicatorBearingChangedListener
                                // below actually emits — the source of the heading
                                // course-up rotates the camera to. COURSE (GPS
                                // travel direction), not HEADING (device compass):
                                // this is a driving map, so the direction the car is
                                // moving is what should point up.
                                puckBearingEnabled = true
                                puckBearing = PuckBearing.COURSE
                                // The dot's DIRECTION ARROW. The Maps SDK's
                                // default puck for a programmatically created
                                // MapView has no bearing image at all (the
                                // attribute parser builds
                                // `createDefault2DPuck(withBearing = false)` when
                                // there are no XML attrs), so enabling the bearing
                                // computation above only fed the camera — nothing
                                // was ever drawn. This is what actually puts the
                                // arrow on the dot, and it starts OFF: see
                                // [puckBearingArrowShown] for why the arrow waits
                                // for a real heading.
                                locationPuck =
                                    createDefault2DPuck(withBearing = puckBearingArrowShown)
                            }
                            location.addOnIndicatorPositionChangedListener(positionListener)
                            location.addOnIndicatorBearingChangedListener(bearingListener)
                        }
                    }
                }
            },
            update = { mapView ->
                // Apply the current traffic toggle once the style is present.
                runCatching {
                    mapView.mapboxMap.style?.let { applyTrafficVisibility(it, trafficOn) }
                }
                // Apply the current day/night light preset once the style is present.
                runCatching {
                    mapView.mapboxMap.style?.let { applyLightPreset(it, mapMode) }
                }
                // Re-colour congestion for the current preset. The light preset
                // only re-lights the BASEMAP — our traffic layer is our own layer,
                // so its colours do not follow it and have to be switched here, or
                // the day palette would stay on a night map (which is exactly what
                // made heavy/severe traffic unreadable in the dark).
                runCatching {
                    mapView.mapboxMap.style?.let { applyTrafficColors(it, mapMode) }
                }
                // (Re)draw the route line + destination marker only when the
                // overlay actually changes; unrelated recompositions (traffic
                // toggle, live-sharing pulse) must not re-clear/redraw the route
                // or re-fit the camera. A no-op until the managers exist (style
                // loaded).
                runCatching { applyRouteOverlayIfChanged(mapView, overlay) }
                // (Re)draw the incident markers only when the set actually
                // changes; a no-op until the manager exists (style loaded).
                runCatching { applyIncidentMarkersIfChanged(incidents) }
                // Same for the crown layer — a no-op until its manager exists,
                // and a no-op whenever the set is unchanged (which, with the flag
                // off, is "empty" forever).
                runCatching { applyCrownMarkersIfChanged(crowns) }
                // (Re)draw the community event pins only when the set actually
                // changes; a no-op until the manager exists (style loaded).
                runCatching { applyEventMarkersIfChanged(events) }
                // Same again for the sponsored billboards — a no-op until its
                // manager exists, and a no-op whenever the set is unchanged
                // (which, with the digitalBillboards flag off, is "empty"
                // forever).
                runCatching { applyBillboardMarkersIfChanged(billboards) }
                // Reflect live-sharing on the puck: green pulse while sharing.
                runCatching {
                    mapView.location.updateSettings { pulsingColor = pulseColorFor(marker) }
                }
                // Breadcrumb tail lifecycle: it starts when a live session starts
                // (built up by the position listener) and CLEARS when the session
                // ends. When not sharing, drop the retained tail and wipe it off
                // the map so a finished drive leaves no trail behind. Cheap and
                // idempotent, so running it on unrelated recompositions is fine.
                runCatching {
                    if (marker?.isLiveSharing != true) {
                        breadcrumbTrail.clear()
                        clearBreadcrumbRender()
                    }
                }
            },
            onRelease = { mapView ->
                runCatching {
                    mapView.location.removeOnIndicatorPositionChangedListener(positionListener)
                }
                runCatching {
                    mapView.location.removeOnIndicatorBearingChangedListener(bearingListener)
                }
                // Drop the stale heading so a recreated map (tab round-trip) does
                // not rotate to an old bearing before its first fresh fix. The
                // compass MODE itself is a preference and deliberately kept across
                // the recreate — the fresh map's listeners re-apply it.
                lastBearing = 0.0
                // …and with the heading goes the arrow that visualised it: a
                // recreated map shows the plain dot again until its own first
                // heading arrives, rather than an arrow left pointing north.
                puckBearingArrowShown = false
                cameraChangeListener?.let { l ->
                    runCatching { mapView.mapboxMap.removeOnCameraChangeListener(l) }
                }
                cameraChangeListener = null
                // Feature-health subscriptions come down with everything else.
                // The surface is gone, so nothing about it can be asserted until
                // a new one is built (which resets surfaceShown/everRendered).
                runCatching { mapLoadingErrorSubscription?.cancel() }
                runCatching { mapLoadedSubscription?.cancel() }
                runCatching { renderFrameSubscription?.cancel() }
                mapLoadingErrorSubscription = null
                mapLoadedSubscription = null
                renderFrameSubscription = null
                surfaceShown = false
                longClickListener?.let { l ->
                    runCatching { mapView.gestures.removeOnMapLongClickListener(l) }
                }
                longClickListener = null
                // Interactions are cancelled rather than removed (the Interactions
                // API hands back a Cancelable per registration, not a listener).
                placeInteractions.forEach { interaction -> runCatching { interaction.cancel() } }
                placeInteractions.clear()
                // Detach the camera-gesture (follow) listeners and stop the timer.
                moveListener?.let { l -> runCatching { mapView.gestures.removeOnMoveListener(l) } }
                scaleListener?.let { l -> runCatching { mapView.gestures.removeOnScaleListener(l) } }
                rotateListener?.let { l -> runCatching { mapView.gestures.removeOnRotateListener(l) } }
                shoveListener?.let { l -> runCatching { mapView.gestures.removeOnShoveListener(l) } }
                moveListener = null
                scaleListener = null
                rotateListener = null
                shoveListener = null
                idleReturnJob?.cancel()
                idleReturnJob = null
                // Reset the mirrored bearing now that the camera-change listener is
                // detached. This surface outlives the MapView (it's remembered across
                // tab switches while the MapView is destroyed/recreated), so without
                // this the compass would briefly render the stale bearing from the
                // old map while the recreated map's camera starts at north (0). The
                // fresh map's camera-change listener re-populates this once it emits.
                bearingFlow.value = 0f
                cameraSnapshotFlow.value = null
                routeLineManager = null
                // Detach the incident click listener before dropping the manager,
                // so a torn-down map cannot keep publishing taps.
                incidentClickListener?.let { l ->
                    runCatching { incidentMarkerManager?.removeClickListener(l) }
                }
                incidentClickListener = null
                // Same teardown for the crown layer's click listener, so a torn-down
                // map cannot keep publishing crown taps into a popup that is gone.
                crownClickListener?.let { l ->
                    runCatching { crownMarkerManager?.removeClickListener(l) }
                }
                crownClickListener = null
                // Same teardown for the events layer: detach the click listener
                // before dropping the manager so a torn-down map cannot keep
                // publishing event taps.
                eventClickListener?.let { l ->
                    runCatching { eventMarkerManager?.removeClickListener(l) }
                }
                eventClickListener = null
                // Same teardown for the billboards layer, so a torn-down map
                // cannot keep publishing billboard taps into a popup that is
                // gone — or, worse, record a sponsor-facing `open` interaction
                // for a marker nobody is looking at.
                billboardClickListener?.let { l ->
                    runCatching { billboardMarkerManager?.removeClickListener(l) }
                }
                billboardClickListener = null
                destMarkerManager = null
                incidentMarkerManager = null
                crownMarkerManager = null
                eventMarkerManager = null
                billboardMarkerManager = null
                // The annotations are gone with their manager, so the lookup that
                // described them must go too — otherwise a stale annotation id
                // could resolve to an incident/event on the NEXT map.
                incidentIdsByAnnotation.clear()
                crownIdsByAnnotation.clear()
                eventIdsByAnnotation.clear()
                billboardIdsByAnnotation.clear()
                // Managers are gone, so a later re-init must redraw the overlay,
                // the incident markers, the crowns, the event pins and the
                // billboards.
                lastAppliedOverlay = null
                lastAppliedIncidents = null
                lastAppliedCrowns = null
                lastAppliedEvents = null
                lastAppliedBillboards = null
                // The style (and every image registered on it) dies with this
                // MapView, so a later map must re-upload the marker images
                // rather than believe these are still present.
                registeredIncidentImages.clear()
                registeredCrownImages.clear()
                registeredEventImages.clear()
                registeredBillboardImages.clear()
                appContext = null
                mapViewRef = null
                lastPoint = null
                centeredOnFirstFix = false
                // A later recreated map (e.g. after a tab switch) should open
                // following the user again, even if the user had panned away.
                followController.reset()
                // A fresh map opens on the normal follow framing. The convoy
                // focus CHOICE lives in the shell (see ConvoyFocusStore) and
                // is re-pushed on the next composition if it is still Convoy,
                // so clearing the applied fit here cannot strand the camera.
                convoyFitPoints = null
                appliedConvoyFit = null
                convoyFocusEnabled = false
                mapView.onDestroy()
            },
        )
    }

    /**
     * Draws [overlay] only when it differs from the last one actually applied,
     * caching the new value so unrelated recompositions don't re-clear/redraw the
     * route or re-fit the camera (which would flicker the line and snap the camera
     * back while the user is looking at the route). [MapRouteOverlay] is a data
     * class, so `==` compares the destination + path by value. The cache is reset
     * to null wherever the annotation managers are (re)created or torn down, so a
     * cleared-then-recreated map always redraws rather than trusting a stale hit.
     */
    private fun applyRouteOverlayIfChanged(mapView: MapView, overlay: MapRouteOverlay?) {
        if (overlay == lastAppliedOverlay) return
        applyRouteOverlay(mapView, overlay)
        lastAppliedOverlay = overlay
    }

    /**
     * Handles a tap on one of THIS surface's incident badges, returning whether
     * the tap was consumed.
     *
     * **Always returns true.** The annotation plugin resolves the tapped symbol
     * against its own live annotation map BEFORE invoking this listener and
     * bails out itself when it finds nothing (`AnnotationManagerImpl`'s click
     * interaction returns false without calling any listener). So by the time we
     * are called the tap has definitionally landed on a badge belonging to the
     * incidents manager — which makes consuming it always the correct answer,
     * whether or not we can still name the incident.
     *
     * Returning false instead would hand the tap onward to the next registered
     * interaction, which is the basemap-POI click that raises a "navigate here?"
     * preview. Offering to route the user to a petrol station because they
     * tapped an accident badge is worse than any no-op.
     *
     * An id we cannot resolve means [incidentIdsByAnnotation] has drifted out of
     * step with the annotations actually drawn — the two are written together in
     * [applyIncidentMarkers], so they can only diverge if a native call there
     * failed and was swallowed. Rather than eat the gesture silently we treat it
     * as the cache-invalidation signal it is and force a full redraw, which
     * rebuilds the lookup from the current markers so the next tap resolves.
     * Nothing is shown to the user: the condition is an internal desync with no
     * meaning to them, and it repairs itself before they can tap again.
     */
    internal fun onIncidentAnnotationClicked(annotationId: String): Boolean {
        val incidentId = incidentIdsByAnnotation[annotationId]
        if (incidentId != null) {
            emitIncidentTap(incidentId)
            return true
        }
        // Reset-then-reapply: the same idiom used wherever the manager is
        // (re)created or torn down. It can only cause an extra redraw, never
        // skip one, so it cannot strand the layer in a half-drawn state.
        lastAppliedIncidents = null
        applyIncidentMarkersIfChanged(incidentMarkersFlow.value)
        return true
    }

    /**
     * Resolves a tapped event annotation back to its event id and publishes it so
     * the host can open the event info popup. Mirrors
     * [onIncidentAnnotationClicked]: returns true to consume the tap (so it never
     * falls through to the basemap "navigate here?" interaction), and repairs an
     * internal lookup desync with a forced redraw rather than eating the gesture.
     */
    internal fun onEventAnnotationClicked(annotationId: String): Boolean {
        val eventId = eventIdsByAnnotation[annotationId]
        if (eventId != null) {
            emitEventTap(eventId)
            return true
        }
        lastAppliedEvents = null
        applyEventMarkersIfChanged(eventMarkersFlow.value)
        return true
    }

    /**
     * Redraws the event pins only when the set differs from the last one applied,
     * so unrelated recompositions don't clear/redraw the whole events layer. The
     * cache is reset to null wherever the manager is (re)created or torn down.
     * Only a complete draw (the single event image uploaded) is cached, so a draw
     * attempted before the style handle is ready repairs itself on the next update.
     */
    private fun applyEventMarkersIfChanged(markers: List<MapEventMarker>) {
        if (markers == lastAppliedEvents) return
        if (applyEventMarkers(markers)) {
            lastAppliedEvents = markers
        }
    }

    /**
     * Clears and redraws the event pins — one event badge per marker — and
     * rebuilds the annotation-id → event-id lookup the click listener resolves
     * taps through. A no-op until the manager exists (style loaded). Every native
     * call is wrapped defensively so a partial/failed draw degrades rather than
     * crashing. Returns true only when every pin was drawn against an uploaded
     * image (so the caller can decline to cache an incomplete draw).
     *
     * The single event image (a calendar glyph on the event disc) is rasterised
     * and registered on the style once via the shared [IncidentMarkerBitmaps]
     * builder (a generic disc+glyph rasteriser), then referenced by name.
     *
     * ACCESSIBILITY: like the incident badges these Mapbox annotations carry no
     * semantics node, so a screen reader cannot reach an individual pin — the
     * event content becomes accessible once the info popup opens (ordinary text).
     */
    private fun applyEventMarkers(markers: List<MapEventMarker>): Boolean {
        val manager = eventMarkerManager ?: return false
        val style = mapViewRef?.mapboxMap?.style
        val context = appContext
        var complete = true
        val imageId = EVENT_MARKER_IMAGE_ID
        // Upload the one event image on first use against the current style.
        if (imageId !in registeredEventImages) {
            val bitmap =
                if (style != null && context != null) {
                    IncidentMarkerBitmaps.create(
                        context = context,
                        iconRes = R.drawable.ic_event_marker,
                        discColorArgb = EVENT_MARKER_DISC_COLOR,
                        glyphColorArgb = EVENT_MARKER_GLYPH_COLOR,
                    )
                } else {
                    null
                }
            val added =
                bitmap != null &&
                    style != null &&
                    runCatching { style.addImage(imageId, bitmap) }.isSuccess
            if (added) registeredEventImages.add(imageId) else complete = false
        }
        runCatching { manager.deleteAll() }
        eventIdsByAnnotation.clear()
        // Nothing more to draw if the image never made it onto the style: the
        // annotations would reference a name the style does not know.
        if (imageId !in registeredEventImages) return false
        for (marker in markers) {
            runCatching {
                val annotation =
                    manager.create(
                        PointAnnotationOptions()
                            .withPoint(Point.fromLngLat(marker.longitude, marker.latitude))
                            .withIconImage(imageId)
                            .withIconAnchor(IconAnchor.CENTER),
                    )
                eventIdsByAnnotation[annotation.id] = marker.id
            }
        }
        return complete
    }

    /**
     * Redraws the incident markers only when the set differs from the last one
     * applied, so unrelated recompositions (traffic toggle, route redraw,
     * live-sharing pulse) don't clear/redraw the whole incidents layer. The
     * cache is reset to null wherever the manager is (re)created or torn down.
     */
    private fun applyIncidentMarkersIfChanged(markers: List<MapIncidentMarker>) {
        if (markers == lastAppliedIncidents) return
        // Cache ONLY a complete draw. An incomplete one (the style handle not
        // available yet, so a marker image could not be uploaded) would
        // otherwise be remembered as applied, and every later update carrying
        // the same markers would short-circuit here — leaving those incidents
        // drawn as blank, icon-less annotations for as long as the set did not
        // change. Declining to cache costs one redundant redraw and makes the
        // next update repair it.
        if (applyIncidentMarkers(markers)) {
            lastAppliedIncidents = markers
        }
    }

    /**
     * Clears and redraws the incident markers on THIS surface. A no-op until the
     * manager exists (style loaded).
     *
     * The draw itself is [IncidentMarkerLayer.draw] — the one renderer shared
     * with the turn-by-turn navigation map, so the two maps cannot end up
     * drawing the same incident differently. Everything with a lifetime (the
     * manager, the registered style images, the annotation → incident lookup) is
     * this surface's own and is passed in; see that object's KDoc for the draw
     * order, the incomplete-draw contract and the accessibility note.
     */
    private fun applyIncidentMarkers(markers: List<MapIncidentMarker>): Boolean {
        val manager = incidentMarkerManager ?: return false
        return IncidentMarkerLayer.draw(
            manager = manager,
            style = mapViewRef?.mapboxMap?.style,
            context = appContext,
            markers = markers,
            registeredImages = registeredIncidentImages,
            idsByAnnotation = incidentIdsByAnnotation,
        )
    }

    /**
     * Handles a tap on one of THIS surface's billboard markers, returning whether
     * the tap was consumed.
     *
     * **Always returns true**, for the reasons on [onIncidentAnnotationClicked]:
     * the annotation plugin has already hit-tested against the BILLBOARD
     * manager's own annotations, so the tap definitionally landed on a
     * billboard, and returning false would pass it to the basemap-POI
     * interaction — offering to navigate the user to some unrelated shop because
     * they tapped an advert.
     *
     * An unresolvable id means [billboardIdsByAnnotation] has drifted from what
     * is drawn; the same reset-then-redraw repair is used, and nothing is shown
     * to the user because the condition means nothing to them.
     */
    internal fun onBillboardAnnotationClicked(annotationId: String): Boolean {
        val billboardId = billboardIdsByAnnotation[annotationId]
        if (billboardId != null) {
            emitBillboardTap(billboardId)
            return true
        }
        lastAppliedBillboards = null
        applyBillboardMarkersIfChanged(billboardMarkersFlow.value)
        return true
    }

    /**
     * Redraws the billboard markers only when the set differs from the last one
     * applied. Same incomplete-draw rule as every other layer here: a draw that
     * could not upload its image is NOT cached, so the next update repairs it
     * rather than leaving permanently blank annotations.
     */
    private fun applyBillboardMarkersIfChanged(markers: List<MapBillboardMarker>) {
        if (markers == lastAppliedBillboards) return
        if (applyBillboardMarkers(markers)) {
            lastAppliedBillboards = markers
        }
    }

    /**
     * Clears and redraws the sponsored billboards on THIS surface. A no-op until
     * the manager exists (style loaded).
     *
     * The draw itself is [BillboardMarkerLayer.draw]; everything with a lifetime
     * (the manager, the registered style image, the annotation → billboard
     * lookup) is this surface's own and is passed in.
     */
    private fun applyBillboardMarkers(markers: List<MapBillboardMarker>): Boolean {
        val manager = billboardMarkerManager ?: return false
        return BillboardMarkerLayer.draw(
            manager = manager,
            style = mapViewRef?.mapboxMap?.style,
            context = appContext,
            markers = markers,
            registeredImages = registeredBillboardImages,
            idsByAnnotation = billboardIdsByAnnotation,
        )
    }

    /**
     * Handles a tap on one of THIS surface's crown markers, returning whether the
     * tap was consumed.
     *
     * **Always returns true**, for exactly the reasons spelled out on
     * [onIncidentAnnotationClicked]: the annotation plugin has already hit-tested
     * the tap against the CROWN manager's own annotations before calling us, so
     * the tap definitionally landed on a crown, and returning false would hand it
     * to the basemap-POI interaction — offering to route the user to a nearby
     * shop because they tapped a crown.
     *
     * An unresolvable id means [crownIdsByAnnotation] has drifted from what is
     * drawn; the same reset-then-redraw repair is used, and nothing is shown to
     * the user because the condition means nothing to them.
     */
    internal fun onCrownAnnotationClicked(annotationId: String): Boolean {
        val spawnId = crownIdsByAnnotation[annotationId]
        if (spawnId != null) {
            emitCrownTap(spawnId)
            return true
        }
        lastAppliedCrowns = null
        applyCrownMarkersIfChanged(crownMarkersFlow.value)
        return true
    }

    /**
     * Redraws the crown markers only when the set differs from the last one
     * applied. Same incomplete-draw rule as the incidents layer: a draw that
     * could not upload one of its images is NOT cached, so the next update
     * repairs it rather than leaving a permanently blank annotation.
     */
    private fun applyCrownMarkersIfChanged(markers: List<MapCrownMarker>) {
        if (markers == lastAppliedCrowns) return
        if (applyCrownMarkers(markers)) {
            lastAppliedCrowns = markers
        }
    }

    /**
     * Clears and redraws the Kronjakt crowns — one rarity marker per crown — and
     * rebuilds the annotation-id → spawn-id lookup taps resolve through. A no-op
     * until the manager exists (style loaded), and every native call is wrapped
     * defensively so a partial draw degrades rather than crashing.
     *
     * Deliberately a near-copy of [applyIncidentMarkers] rather than a shared
     * generic helper. The two differ in the manager, the bitmap builder (crowns
     * carry an optional halo) and the lookup they populate — so a shared version
     * would take all three as parameters and be a worse description of either
     * layer, while coupling a change to one into a change to both.
     *
     * ACCESSIBILITY: as with the incident badges, these annotations carry no
     * content description because there is no semantics node to put one on (they
     * are style images inside the GL surface). The crown POPUP that a tap opens
     * is ordinary Compose and is fully readable — rarity, value and distance are
     * all text.
     *
     * On-device verification note: annotation rendering and hit-testing run only
     * on a token-provisioned device, so they are verified on device.
     */
    private fun applyCrownMarkers(markers: List<MapCrownMarker>): Boolean {
        val manager = crownMarkerManager ?: return false
        val style = mapViewRef?.mapboxMap?.style
        val context = appContext
        var complete = true
        runCatching { manager.deleteAll() }
        crownIdsByAnnotation.clear()
        for (marker in markers) {
            val imageId =
                CrownMarkerBitmaps.imageId(
                    iconRes = marker.iconRes,
                    discColorArgb = marker.discColorArgb,
                    glyphColorArgb = marker.glyphColorArgb,
                    glowColorArgb = marker.glowColorArgb,
                )
            if (imageId !in registeredCrownImages) {
                val bitmap =
                    if (style != null && context != null) {
                        CrownMarkerBitmaps.create(
                            context = context,
                            iconRes = marker.iconRes,
                            discColorArgb = marker.discColorArgb,
                            glyphColorArgb = marker.glyphColorArgb,
                            glowColorArgb = marker.glowColorArgb,
                        )
                    } else {
                        null
                    }
                val added =
                    bitmap != null &&
                        style != null &&
                        runCatching { style.addImage(imageId, bitmap) }.isSuccess
                if (added) registeredCrownImages.add(imageId) else complete = false
            }
            runCatching {
                val annotation =
                    manager.create(
                        PointAnnotationOptions()
                            .withPoint(Point.fromLngLat(marker.longitude, marker.latitude))
                            .withIconImage(imageId)
                            // Centre-anchored: the disc marks the point, it is not
                            // a pin whose tip is the location.
                            .withIconAnchor(IconAnchor.CENTER),
                    )
                crownIdsByAnnotation[annotation.id] = marker.id
            }
        }
        return complete
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
        // Bottom inset: whatever the HOST reports it is covering (the route-preview
        // sheet, at its measured COLLAPSED height), falling back to the built-in dp
        // constant for callers that draw nothing over the map. Without this the fit
        // always reserved room for a fully EXPANDED sheet, so the route was framed
        // into the top of the screen — half of the "I can't see the whole route"
        // problem, the other half being that the sheet opened expanded at all.
        val bottomPad =
            overlay.bottomInsetPx?.toDouble() ?: (ROUTE_PAD_BOTTOM * density)
        runCatching {
            val camera =
                mapView.mapboxMap.cameraForCoordinates(
                    fitPoints,
                    cameraOptions {},
                    EdgeInsets(
                        ROUTE_PAD_TOP * density,
                        ROUTE_PAD_SIDE * density,
                        bottomPad,
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

    /**
     * Redraw the private breadcrumb tail from the current [breadcrumbTrail]. Sets
     * the GeoJSON line geometry (oldest→newest) and shows the layer when there
     * are at least two points to draw; otherwise clears it. A no-op until the
     * style + source exist (style loaded). Native calls are wrapped so a partial
     * draw degrades to no tail rather than crashing.
     *
     * On-device only: the annotation/GL render runs on a token-provisioned map,
     * so the actual line + gradient are verified on device, not in CI.
     */
    private fun redrawBreadcrumb() {
        val style = mapViewRef?.mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(BREADCRUMB_SOURCE_ID) ?: return
        val pts = breadcrumbTrail.points()
        if (pts.size < 2) {
            runCatching { source.featureCollection(FeatureCollection.fromFeatures(emptyList())) }
            runCatching { setBreadcrumbVisible(style, false) }
            return
        }
        runCatching {
            source.geometry(
                LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) }),
            )
        }
        runCatching { setBreadcrumbVisible(style, true) }
    }

    /** Wipe the breadcrumb line off the map (empty geometry + hidden layer). */
    private fun clearBreadcrumbRender() {
        val style = mapViewRef?.mapboxMap?.style ?: return
        runCatching {
            style.getSourceAs<GeoJsonSource>(BREADCRUMB_SOURCE_ID)
                ?.featureCollection(FeatureCollection.fromFeatures(emptyList()))
        }
        runCatching { setBreadcrumbVisible(style, false) }
    }

    /**
     * `internal`, not private: the turn-by-turn screen's own Navigation-SDK map
     * reuses the traffic helpers here ([addTrafficLayer], [applyTrafficColors],
     * [applyTrafficVisibility]) so the congestion overlay the layers popup
     * toggles is the SAME source, the same tileset and the same day/night ramp
     * on both maps, rather than a second implementation that can drift.
     */
    internal companion object {
        /**
         * The query radius covering what [map] can currently see, or null when
         * the camera cannot be read.
         *
         * Shared rather than a method on this class because turn-by-turn
         * navigation drives the SAME `incidents.listNearby` poll from ITS map
         * (a second, Navigation-SDK `MapView`): while navigating the shell
         * surface is stood down and its camera is frozen at wherever the trip
         * started, so a poll anchored to it would go on asking about the origin
         * for the whole drive. One implementation, so the two maps compute the
         * radius the same way — the alternative is two subtly different notions
         * of "what is on screen" feeding one rate-limited callable.
         *
         * Reads the camera's own visible bounds — the only honest account of
         * what is on screen at this zoom/rotation/pitch — then hands the corners
         * to the pure geometry that turns them into a clamped radius. Built from
         * the live `cameraState` via the DSL so no `toCameraOptions()` extension
         * is assumed across SDK versions. Every native call is wrapped, so an
         * unreadable camera degrades to null (and the caller's fallback) rather
         * than throwing.
         */
        fun visibleRadiusMeters(map: MapboxMap): Double? =
            runCatching {
                val camera = map.cameraState
                val bounds =
                    map.coordinateBoundsForCamera(
                        cameraOptions {
                            center(camera.center)
                            zoom(camera.zoom)
                            bearing(camera.bearing)
                            pitch(camera.pitch)
                            padding(camera.padding)
                        },
                    )
                ViewportRadius.radiusMetersForBounds(
                    centerLat = camera.center.latitude(),
                    centerLon = camera.center.longitude(),
                    swLat = bounds.southwest.latitude(),
                    swLon = bounds.southwest.longitude(),
                    neLat = bounds.northeast.latitude(),
                    neLon = bounds.northeast.longitude(),
                )
            }.getOrNull()

        /**
         * How often the render watchdog samples its eligibility conditions.
         *
         * 1s: fine-grained enough that a brief window of connectivity or
         * visibility is attributed correctly, coarse enough to be free (one
         * boolean check per second, and the loop exits the moment the map
         * renders — which on a healthy map is within the first few ticks).
         */
        const val HEALTH_TICK_MILLIS = 1_000L

        const val ROUTE_LINE_COLOR = 0xFF1A73E8.toInt()
        const val ROUTE_LINE_WIDTH = 6.0

        // Community event pin styling. One fixed disc colour + glyph tint for the
        // single event image (there is no per-category variation like incidents).
        // A distinct teal disc so an event pin is never mistaken for an incident
        // badge (which are red/orange/amber/blue/purple), with a white calendar
        // glyph on top. The image name is a fixed `kcc-event-*` id — collision-free
        // against the traffic/breadcrumb layer ids and the annotation-managed
        // incident images.
        const val EVENT_MARKER_IMAGE_ID = "kcc-event-marker"
        const val EVENT_MARKER_DISC_COLOR = 0xFF00897B.toInt()
        const val EVENT_MARKER_GLYPH_COLOR = 0xFFFFFFFF.toInt()
        // Destination-pin styling is SHARED with the turn-by-turn navigation
        // map's end-of-route marker (see [MapMarkerStyle]): the same destination
        // must not change appearance the moment the user presses "Start".
        const val DEST_MARKER_COLOR = MapMarkerStyle.DEST_MARKER_COLOR
        const val DEST_MARKER_RADIUS = MapMarkerStyle.DEST_MARKER_RADIUS
        const val DEST_MARKER_STROKE = MapMarkerStyle.DEST_MARKER_STROKE
        const val DEST_MARKER_STROKE_COLOR = MapMarkerStyle.DEST_MARKER_STROKE_COLOR

        // (Incident markers are icon images, not circles — their geometry and
        // colours live in `incidents/IncidentMarkerStyle.kt`, which is pure and
        // unit-tested for light/dark legibility.)

        // Camera-fit padding (dp; multiplied by display density → px before use):
        // extra room at the bottom for the summary sheet. ROUTE_PAD_BOTTOM is only
        // the FALLBACK now — a host that draws a sheet over the map reports its
        // real height as MapRouteOverlay.bottomInsetPx and that wins.
        const val ROUTE_PAD_TOP = 140.0
        const val ROUTE_PAD_SIDE = 80.0
        const val ROUTE_PAD_BOTTOM = 320.0

        /**
         * Duration (ms) of the smooth camera glide used by [recenter] when the
         * user taps the recenter button. Short enough to feel responsive, long
         * enough to read as an intentional animation rather than a jarring snap.
         */
        const val RECENTER_ANIMATION_MS = 1000L

        /**
         * Duration (ms) of the camera glide when the convoy fit is (re)applied.
         * Longer than a follow step: a fit can change the zoom as well as the
         * centre, and a slow reframe reads as the map thinking rather than
         * lurching.
         */
        const val CONVOY_FIT_ANIMATION_MS = 900L

        /**
         * Padding kept between the framed convoy members and the viewport edge,
         * so nobody ends up glued to the side of the screen where the floating
         * controls are. The top gets more because the convoy bar and the search
         * row live there.
         *
         * In **dp**, like [ROUTE_PAD_TOP] and friends above — multiplied by
         * display density at the call site, because `EdgeInsets` takes device
         * pixels. Density-independent is the whole point: the controls these
         * clear are themselves laid out in dp.
         */
        const val CONVOY_FIT_PAD_SIDE = 140.0
        const val CONVOY_FIT_PAD_TOP = 260.0

        /**
         * How far the convoy fit is allowed to zoom OUT. Beyond this the basemap
         * has no road detail worth showing and the members are dots on a map of
         * the country — the fit stops being useful long before it stops being
         * possible, so a member who has driven to another city pins the zoom here
         * and simply falls off the edge (where the direction arrows pick them up).
         */
        const val MIN_CONVOY_FIT_ZOOM = 8.0

        /**
         * How far the convoy fit is allowed to zoom IN, for a group bunched at
         * one traffic light: without this, a near-zero bounding box asks for a
         * street-furniture zoom level.
         */
        const val MAX_CONVOY_FIT_ZOOM = 16.5

        /**
         * Import id of the Mapbox Standard style's basemap, used to set config
         * properties such as `lightPreset`. When [Style.STANDARD] is loaded, its
         * single style import is exposed under this id.
         */
        const val STANDARD_IMPORT_ID = "basemap"

        /** Config key on the Standard import controlling the day/night lighting. */
        const val LIGHT_PRESET_CONFIG = "lightPreset"

        /**
         * Config key on the Standard import that shows/hides all 3D objects
         * (buildings, landmarks, trees). This — NOT the camera pitch — is what
         * actually removes the 3D buildings when the layers popup's "3D buildings"
         * toggle is turned off; the Standard basemap renders them at any pitch.
         */
        const val SHOW_3D_OBJECTS_CONFIG = "show3dObjects"

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
         * The `match` expression colouring congestion lines for [mode], built
         * from the pure [TrafficPalette] table (which owns the day/night colour
         * decision and is unit-tested off-device).
         */
        fun congestionColorExpression(mode: MapMode): Expression {
            val colors = TrafficPalette.colors(mode)
            return Expression.match {
                get("congestion")
                literal("low")
                color(colors.low)
                literal("moderate")
                color(colors.moderate)
                literal("heavy")
                color(colors.heavy)
                literal("severe")
                color(colors.severe)
                // Default (e.g. "unknown"): neutral grey.
                color(colors.unknown)
            }
        }

        /**
         * Adds the Mapbox traffic vector source + a congestion-coloured line
         * layer (green → yellow → red, see [TrafficPalette] for the day/night
         * ramps), initially hidden. Idempotent:
         * a no-op if the source/layer already exist (e.g. after a style
         * reload). Placed in the Standard style's "middle" slot so it sits
         * under labels.
         *
         * Colours/width are applied for [mode] here and re-applied by
         * [applyTrafficColors] whenever the day/night preset flips, so a layer
         * added while the map is already in night mode starts legible rather
         * than waiting for a toggle.
         */
        fun addTrafficLayer(
            style: Style,
            mode: MapMode,
            slotName: String? = "middle",
            belowLayerId: String? = null,
        ) {
            if (style.styleSourceExists(TRAFFIC_SOURCE_ID)) return
            style.addSource(
                vectorSource(TRAFFIC_SOURCE_ID) { url(TRAFFIC_TILESET) },
            )
            val layer =
                lineLayer(TRAFFIC_LAYER_ID, TRAFFIC_SOURCE_ID) {
                    sourceLayer(TRAFFIC_SOURCE_LAYER)
                    // Slots are a Standard-style concept. The turn-by-turn map
                    // runs a CLASSIC navigation style, which defines none, so it
                    // passes null and positions the layer by id instead (below),
                    // rather than naming a slot that style has never heard of.
                    if (slotName != null) slot(slotName)
                    lineWidth(TrafficPalette.lineWidth(mode))
                    visibility(Visibility.NONE)
                    lineColor(congestionColorExpression(mode))
                }
            // Explicit placement for styles without slots: turn-by-turn anchors
            // the congestion lines below the same label layer its ROUTE line is
            // anchored below, and adds them FIRST, so the route (inserted
            // directly below that label layer afterwards) ends up on top of the
            // traffic rather than buried under it. Falls back to a plain top-of-
            // stack add if the named layer is not in this style, so a style
            // without it still gets traffic rather than none.
            val placed =
                belowLayerId != null &&
                    runCatching { style.addLayerBelow(layer, belowLayerId) }.isSuccess
            if (!placed) style.addLayer(layer)
        }

        /**
         * Re-colours the congestion layer for the current day/night preset.
         * A no-op until the layer is added.
         *
         * Deliberately separate from [applyTrafficVisibility]: the two are
         * orthogonal (a hidden layer still gets re-coloured, so switching the
         * layer on at night shows night colours immediately), and visibility is
         * applied eagerly on every update.
         */
        fun applyTrafficColors(style: Style, mode: MapMode) {
            val layer = style.getLayerAs<LineLayer>(TRAFFIC_LAYER_ID) ?: return
            layer.lineColor(congestionColorExpression(mode))
            layer.lineWidth(TrafficPalette.lineWidth(mode))
        }

        /**
         * Switches the Standard style's `lightPreset` import config between the
         * "day" and "night" presets, changing the whole basemap's lighting. Only
         * has an effect on the Standard style (which owns the [STANDARD_IMPORT_ID]
         * import); callers wrap it in runCatching so a non-Standard style or an
         * unloaded import degrades to a no-op rather than crashing.
         */
        fun applyLightPreset(style: Style, mode: MapMode) {
            val preset = if (mode == MapMode.Night) "night" else "day"
            style.setStyleImportConfigProperty(STANDARD_IMPORT_ID, LIGHT_PRESET_CONFIG, Value(preset))
        }

        /**
         * Shows or hides the Standard style's 3D objects (buildings/landmarks)
         * via the `show3dObjects` import config. Only affects the Standard style
         * (which owns the [STANDARD_IMPORT_ID] import); callers wrap it in
         * runCatching so a non-Standard/unloaded import degrades to a no-op.
         */
        fun apply3dObjects(style: Style, enabled: Boolean) {
            style.setStyleImportConfigProperty(STANDARD_IMPORT_ID, SHOW_3D_OBJECTS_CONFIG, Value(enabled))
        }

        /** Toggles the traffic layer's visibility; a no-op until it is added. */
        fun applyTrafficVisibility(style: Style, visible: Boolean) {
            val layer = style.getLayerAs<LineLayer>(TRAFFIC_LAYER_ID) ?: return
            layer.visibility(if (visible) Visibility.VISIBLE else Visibility.NONE)
        }

        // ---- Private breadcrumb tail ------------------------------------
        // Own ids (never shared with the route line, traffic, or incident
        // layers) so the tail can never fight them for a source/layer.
        const val BREADCRUMB_SOURCE_ID = "kcc-breadcrumb-source"
        const val BREADCRUMB_LAYER_ID = "kcc-breadcrumb-layer"
        const val BREADCRUMB_LINE_WIDTH = 5.0

        /**
         * The breadcrumb tail's base colour: the brand yellow (the crownGold
         * design token / semantic `brandPrimary`, 0xFFEAB54B). It was previously
         * the live-share GREEN, which — tested only in daylight — melted into the
         * green "low congestion" band of the traffic overlay (TrafficPalette.DAY
         * low is 0xFF4CAF50). The brand yellow reads clearly against both the day
         * and night basemaps and sits in a different hue family from the traffic
         * greens/reds, so the user's own path is no longer lost in the road
         * colouring.
         *
         * NOTE this is intentionally NOT [LIVE_SHARE_PULSE_COLOR]: the sharing
         * puck's pulse stays green (that colour is unchanged), only the tail moves
         * to the brand yellow.
         */
        private val BREADCRUMB_COLOR: Int = KccPalette.crownGold.toArgb()

        // Derived by splitting the single-source [BREADCRUMB_COLOR] ARGB into its
        // R/G/B components, so the tail tracks the brand token if it ever changes;
        // the fade gradient then varies only the alpha.
        private val BREADCRUMB_R: Double = ((BREADCRUMB_COLOR shr 16) and 0xFF).toDouble()
        private val BREADCRUMB_G: Double = ((BREADCRUMB_COLOR shr 8) and 0xFF).toDouble()
        private val BREADCRUMB_B: Double = (BREADCRUMB_COLOR and 0xFF).toDouble()
        // Alpha at the NEWEST end; the oldest end fades to fully transparent.
        private const val BREADCRUMB_HEAD_ALPHA = 0.85

        /**
         * A line-progress gradient fading the tail from fully transparent at the
         * OLDEST end (line-progress 0) to [BREADCRUMB_HEAD_ALPHA] at the NEWEST
         * end (line-progress 1) — "older = more transparent". Requires the source
         * to carry line metrics (see [addBreadcrumbLayer]).
         */
        fun breadcrumbGradientExpression(): Expression =
            Expression.interpolate {
                linear()
                lineProgress()
                stop(0.0) { rgba(BREADCRUMB_R, BREADCRUMB_G, BREADCRUMB_B, 0.0) }
                stop(1.0) { rgba(BREADCRUMB_R, BREADCRUMB_G, BREADCRUMB_B, BREADCRUMB_HEAD_ALPHA) }
            }

        /**
         * Adds the breadcrumb GeoJSON source (line metrics ON, so the gradient
         * can be computed) + a hidden, round-capped line layer, in the "middle"
         * slot so the tail sits under labels and under the route line.
         *
         * Idempotent on the source AND the layer INDEPENDENTLY: the source is
         * added only if missing, the layer only if missing. This matters because
         * the caller wraps this in `runCatching`, so a failure after
         * [addSource] but before/while [addLayer] would otherwise leave the
         * source present and the layer missing — and a source-only early return
         * would then never create the layer, so the tail could never render
         * until a full style reload. Guarding each half separately lets the next
         * call finish the job.
         *
         * The per-vertex fade is a best-effort enhancement layered on a SOLID
         * brand-yellow fallback: the layer is created with a solid [lineColor] first,
         * then [lineGradient] is applied separately. If the gradient is rejected
         * on the pinned SDK the line still draws solid — which the feature
         * explicitly permits — rather than the whole layer being dropped.
         */
        fun addBreadcrumbLayer(style: Style) {
            if (!style.styleSourceExists(BREADCRUMB_SOURCE_ID)) {
                style.addSource(
                    geoJsonSource(BREADCRUMB_SOURCE_ID) {
                        lineMetrics(true)
                        featureCollection(FeatureCollection.fromFeatures(emptyList()))
                    },
                )
            }
            if (!style.styleLayerExists(BREADCRUMB_LAYER_ID)) {
                style.addLayer(
                    lineLayer(BREADCRUMB_LAYER_ID, BREADCRUMB_SOURCE_ID) {
                        slot("middle")
                        lineCap(LineCap.ROUND)
                        lineJoin(LineJoin.ROUND)
                        lineWidth(BREADCRUMB_LINE_WIDTH)
                        lineColor(Expression.rgba(BREADCRUMB_R, BREADCRUMB_G, BREADCRUMB_B, BREADCRUMB_HEAD_ALPHA))
                        visibility(Visibility.NONE)
                    },
                )
            }
            runCatching {
                style.getLayerAs<LineLayer>(BREADCRUMB_LAYER_ID)
                    ?.lineGradient(breadcrumbGradientExpression())
            }
        }

        /** Show/hide the breadcrumb layer; a no-op until it is added. */
        fun setBreadcrumbVisible(style: Style, visible: Boolean) {
            val layer = style.getLayerAs<LineLayer>(BREADCRUMB_LAYER_ID) ?: return
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

/**
 * The set-before-apply guard ordering behind [MapboxMapSurface.showPuckBearingArrow].
 *
 * Extracted as a pure, Mapbox-free function so the ordering that fixes the puck
 * re-entrancy ANR/native-crash can be unit-tested without a `MapView`: the guard
 * flag is passed as [isShown]/[markShown]/[markNotShown] and the native settings
 * write as [applySwap].
 *
 * Contract:
 * 1. If the flag is already set, do nothing (idempotent — one swap per map).
 * 2. Otherwise set the flag BEFORE running [applySwap]. [applySwap] may re-enter
 *    this function synchronously (a real `updateSettings` re-fires the indicator
 *    listeners while it runs); because the flag is already set that re-entry hits
 *    rule 1 and is a no-op, so [applySwap] runs EXACTLY ONCE instead of recursing.
 * 3. If [applySwap] throws, clear the flag again so the next call retries.
 */
internal fun applyPuckBearingArrowOnce(
    isShown: () -> Boolean,
    markShown: () -> Unit,
    markNotShown: () -> Unit,
    applySwap: () -> Unit,
) {
    if (isShown()) return
    markShown()
    runCatching { applySwap() }.onFailure { markNotShown() }
}
