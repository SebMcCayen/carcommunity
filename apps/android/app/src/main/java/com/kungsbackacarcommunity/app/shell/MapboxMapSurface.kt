package com.kungsbackacarcommunity.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccPalette
import com.kungsbackacarcommunity.app.map.CameraFollowController
import com.kungsbackacarcommunity.app.map.MapMarkers
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.RotateGestureDetector
import com.mapbox.android.gestures.ShoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.bindgen.Value
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.observable.eventdata.CameraChangedEventData
import com.mapbox.maps.plugin.animation.MapAnimationOptions.Companion.mapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
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
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.delegates.listeners.OnCameraChangeListener
import com.mapbox.maps.plugin.gestures.OnMapLongClickListener
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.OnRotateListener
import com.mapbox.maps.plugin.gestures.OnScaleListener
import com.mapbox.maps.plugin.gestures.OnShoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
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

    private val longPressFlow = MutableStateFlow<MapPoint?>(null)
    override val longPress: StateFlow<MapPoint?> = longPressFlow.asStateFlow()

    // The map long-click gesture listener (Google-Maps "hold to navigate here");
    // held so it can be detached in onRelease.
    private var longClickListener: OnMapLongClickListener? = null

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
    private var routeLineManager: PolylineAnnotationManager? = null
    private var destMarkerManager: CircleAnnotationManager? = null
    private var incidentMarkerManager: CircleAnnotationManager? = null
    // The incident markers currently drawn, so a recomposition only clears and
    // redraws them when the set ACTUALLY changes (unrelated recompositions must
    // not flicker the layer). Reset to null whenever the manager is (re)created
    // or torn down so a cleared-then-recreated map always redraws.
    private var lastAppliedIncidents: List<MapIncidentMarker>? = null

    // Camera-change listener that mirrors the live map bearing into [bearingFlow]
    // (so the compass control rotates); held so it can be detached in onRelease.
    private var cameraChangeListener: OnCameraChangeListener? = null

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

    override fun setRouteOverlay(overlay: MapRouteOverlay?) {
        // The Content update lambda observes this flow and (re)draws the line +
        // destination marker, so publishing the value is enough.
        routeOverlayFlow.value = overlay
    }

    override fun setIncidentMarkers(markers: List<MapIncidentMarker>) {
        // The Content update lambda observes this flow and (re)draws the incident
        // circles when the set changes, so publishing the value is enough.
        incidentMarkersFlow.value = markers
    }

    override fun emitLongPress(point: MapPoint) {
        longPressFlow.value = point
    }

    override fun consumeLongPress() {
        longPressFlow.value = null
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
            }
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

    override fun recenter() {
        // Tapping my-location is the shared re-centre affordance: resume follow,
        // cancel any pending idle-return timer, and glide to the user. The
        // idle-return path reuses [easeToUser] so the two never diverge.
        followController.onRecenterRequested()
        idleReturnJob?.cancel()
        idleReturnJob = null
        easeToUser()
    }

    /**
     * Smoothly glides the camera to the user's current position (or the default
     * town camera when there is no fix yet), keeping the current 3D tilt. Shared
     * by the my-location control ([recenter]) and the 10-second idle-return timer
     * so the re-centre animation is identical for both. A no-op until the map is
     * composed; wrapped defensively so a missing fix/permission never crashes.
     */
    private fun easeToUser() {
        val map = mapViewRef ?: return
        val target = lastPoint
        runCatching {
            val destination =
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
                    // Keep the 3D tilt when the camera re-centres.
                    pitch(this@MapboxMapSurface.pitch)
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
        val trafficOn by trafficFlow.collectAsState()
        val mapMode by mapModeFlow.collectAsState()
        val overlay by routeOverlayFlow.collectAsState()
        val incidents by incidentMarkersFlow.collectAsState()
        // The caller's marker only carries live-sharing state now (its position
        // is the device puck): a green pulse signals sharing, blue otherwise.
        val marker by userMarkerFlow.collectAsState()
        // Recreate the position listener once; it just records the last fix so
        // recenter() can jump the camera to it.
        val positionListener =
            remember {
                OnIndicatorPositionChangedListener { point ->
                    lastPoint = point
                    // "Camera follows me": while following (and no route overlay
                    // owns the camera), keep the camera centred on the puck as the
                    // user moves. Suppressed once the user pans/zooms/rotates —
                    // the 10s idle timer resumes it — and while a route preview is
                    // shown, so follow never fights an explicit camera move.
                    if (!followController.shouldTrack(hasRouteOverlay = routeOverlayFlow.value != null)) {
                        return@OnIndicatorPositionChangedListener
                    }
                    val map = mapViewRef ?: return@OnIndicatorPositionChangedListener
                    runCatching {
                        if (!centeredOnFirstFix) {
                            // Open the FIRST fix close to the user, at the own-marker
                            // zoom and 3D tilt (snap, so the map opens already framed).
                            centeredOnFirstFix = true
                            map.mapboxMap.setCamera(
                                cameraOptions {
                                    center(point)
                                    zoom(MapMarkers.OWN_MARKER_ZOOM)
                                    pitch(this@MapboxMapSurface.pitch)
                                },
                            )
                        } else {
                            // Later fixes: glide the CENTRE only (leave zoom/pitch/
                            // bearing as they are) with a short ease so following
                            // reads smoothly and doesn't fight its own zoom. A
                            // programmatic ease does not trigger the gesture
                            // listeners, so this never disables follow.
                            map.camera.easeTo(
                                cameraOptions { center(point) },
                                mapAnimationOptions { duration(FOLLOW_ANIMATION_MS) },
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
                MapView(context).apply {
                    mapViewRef = this
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
                                bearingFlow.value =
                                    mapboxMap.cameraState.bearing.toFloat().roundToInt().toFloat()
                            }
                        }
                    cameraChangeListener = camListener
                    runCatching { mapboxMap.addOnCameraChangeListener(camListener) }
                    // Long-press (hold) anywhere on the map to navigate there,
                    // Google-Maps style: publish the pressed lng/lat so the host
                    // opens the route preview for it. This is the hold gesture only
                    // — pan/zoom/rotate (drag/pinch/two-finger) are untouched, so it
                    // never conflicts with normal map manipulation. Returning true
                    // marks the long-press handled.
                    val longPressListener =
                        OnMapLongClickListener { point ->
                            // Publish through the shared hook (not longPressFlow
                            // directly) so all long-press publishing goes through
                            // one place and stays consistent with the stub surface.
                            emitLongPress(MapPoint(point.longitude(), point.latitude()))
                            true
                        }
                    longClickListener = longPressListener
                    runCatching { gestures.addOnMapLongClickListener(longPressListener) }
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
                        runCatching { addTrafficLayer(style) }
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
                            incidentMarkerManager = annotations.createCircleAnnotationManager()
                            lastAppliedIncidents = null
                            applyIncidentMarkersIfChanged(incidentMarkersFlow.value)
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
                // Apply the current day/night light preset once the style is present.
                runCatching {
                    mapView.mapboxMap.style?.let { applyLightPreset(it, mapMode) }
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
                // Reflect live-sharing on the puck: green pulse while sharing.
                runCatching {
                    mapView.location.updateSettings { pulsingColor = pulseColorFor(marker) }
                }
            },
            onRelease = { mapView ->
                runCatching {
                    mapView.location.removeOnIndicatorPositionChangedListener(positionListener)
                }
                cameraChangeListener?.let { l ->
                    runCatching { mapView.mapboxMap.removeOnCameraChangeListener(l) }
                }
                cameraChangeListener = null
                longClickListener?.let { l ->
                    runCatching { mapView.gestures.removeOnMapLongClickListener(l) }
                }
                longClickListener = null
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
                routeLineManager = null
                destMarkerManager = null
                incidentMarkerManager = null
                // Managers are gone, so a later re-init must redraw the overlay
                // and the incident markers.
                lastAppliedOverlay = null
                lastAppliedIncidents = null
                mapViewRef = null
                lastPoint = null
                centeredOnFirstFix = false
                // A later recreated map (e.g. after a tab switch) should open
                // following the user again, even if the user had panned away.
                followController.reset()
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
     * Redraws the incident markers only when the set differs from the last one
     * applied, so unrelated recompositions (traffic toggle, route redraw,
     * live-sharing pulse) don't clear/redraw the whole incidents layer. The
     * cache is reset to null wherever the manager is (re)created or torn down.
     */
    private fun applyIncidentMarkersIfChanged(markers: List<MapIncidentMarker>) {
        if (markers == lastAppliedIncidents) return
        applyIncidentMarkers(markers)
        lastAppliedIncidents = markers
    }

    /**
     * Clears and redraws the incident circles (one coloured circle per marker).
     * A no-op until the manager exists (style loaded). Every native call is
     * wrapped defensively so a partial/failed draw degrades rather than crashing.
     *
     * On-device verification note: annotation rendering runs only on a
     * token-provisioned device, so it is verified on device.
     */
    private fun applyIncidentMarkers(markers: List<MapIncidentMarker>) {
        val manager = incidentMarkerManager ?: return
        runCatching { manager.deleteAll() }
        for (marker in markers) {
            runCatching {
                manager.create(
                    CircleAnnotationOptions()
                        .withPoint(Point.fromLngLat(marker.longitude, marker.latitude))
                        .withCircleRadius(INCIDENT_MARKER_RADIUS)
                        .withCircleColor(marker.colorArgb)
                        .withCircleStrokeWidth(INCIDENT_MARKER_STROKE)
                        .withCircleStrokeColor(INCIDENT_MARKER_STROKE_COLOR),
                )
            }
        }
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

        // Incident circles: the per-marker fill colour is supplied by the host
        // (category colour); a white stroke keeps them legible on any basemap.
        const val INCIDENT_MARKER_RADIUS = 8.0
        const val INCIDENT_MARKER_STROKE = 2.0
        const val INCIDENT_MARKER_STROKE_COLOR = 0xFFFFFFFF.toInt()

        // Camera-fit padding (dp; multiplied by display density → px before use):
        // extra room at the bottom for the summary sheet.
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
         * Duration (ms) of the per-fix camera glide while "follow me" is active.
         * Short — a fix arrives roughly every second — so the camera keeps pace
         * with the user smoothly without a queue of long animations building up
         * (each new ease supersedes the previous one on the camera-animations
         * plugin).
         */
        const val FOLLOW_ANIMATION_MS = 700L

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
