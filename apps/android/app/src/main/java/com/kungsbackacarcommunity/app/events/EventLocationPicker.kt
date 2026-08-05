package com.kungsbackacarcommunity.app.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kungsbackacarcommunity.app.R
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.RotateGestureDetector
import com.mapbox.android.gestures.ShoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.observable.eventdata.CameraChangedEventData
import com.mapbox.maps.plugin.delegates.listeners.OnCameraChangeListener
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.OnRotateListener
import com.mapbox.maps.plugin.gestures.OnScaleListener
import com.mapbox.maps.plugin.gestures.OnShoveListener
import com.mapbox.maps.plugin.gestures.gestures

/**
 * Pure helpers for the event location picker, kept Android-free so the "where
 * does the pin start / is the captured coordinate valid" logic is JVM-unit
 * testable without a device or a Mapbox surface.
 *
 * UX CHOICE — a FIXED centre pin over a pannable map (not a draggable
 * annotation). Justification: the user pans/zooms the map under a pin locked to
 * the screen centre and confirms, which is the familiar "position the map"
 * pattern (Uber/Google). It needs no annotation drag hit-testing, works
 * identically at any zoom, and the confirmed coordinate is simply the camera
 * centre — always a single, valid point, with no "did I actually grab the pin?"
 * failure mode. The captured coordinate is validated with
 * [Events.isValidCoordinatePair] before it leaves the picker.
 */
object EventLocationPicker {
    /**
     * Last-resort map centre used only when neither the event's own pin nor the
     * user's current location is available: Kungsbacka, the town the app serves
     * (same coordinates the main map's default camera uses —
     * map/MapMarkers.DEFAULT_CAMERA). This is a FALLBACK only; the primary
     * default is the user's own location (see [startCenter]).
     *
     * These are two independent named constants, not an ordered pair —
     * [startCenter] returns (latitude, longitude), while the constants are fed to
     * Mapbox's `Point.fromLngLat` in (longitude, latitude) order at the call
     * site; always pass each one by name so the order can't be transposed.
     */
    const val DEFAULT_LONGITUDE = 12.0757
    const val DEFAULT_LATITUDE = 57.4874

    /** A sensible town-scale start zoom so the user can position the pin. */
    const val START_ZOOM = 13.0

    /**
     * Where the pin should start, in priority order:
     *  1. the event's existing pin, when it already has a valid, complete one —
     *     a user-placed point (editing / re-opening the picker) always wins;
     *  2. otherwise the user's OWN current location, when available and valid —
     *     the primary default, so a member creating an event starts where they
     *     are rather than at a fixed town;
     *  3. otherwise the Kungsbacka fallback, for the permission-denied /
     *     location-unavailable / token-less-CI cases.
     *
     * A half-set or out-of-range pair (for either the pin or the user fix) is
     * treated as absent and skipped rather than starting the camera at a bogus
     * point. Returns (latitude, longitude).
     */
    fun startCenter(
        latitude: Double?,
        longitude: Double?,
        userLatitude: Double? = null,
        userLongitude: Double? = null,
    ): Pair<Double, Double> {
        if (latitude != null &&
            longitude != null &&
            Events.isValidCoordinatePair(latitude, longitude)
        ) {
            return latitude to longitude
        }
        if (userLatitude != null &&
            userLongitude != null &&
            Events.isValidCoordinatePair(userLatitude, userLongitude)
        ) {
            return userLatitude to userLongitude
        }
        return DEFAULT_LATITUDE to DEFAULT_LONGITUDE
    }
}

/**
 * Full-screen "choose the event location on the map" picker: a pannable Mapbox
 * map with a pin locked to the screen centre, plus Confirm / Cancel. The user
 * moves the map so the pin sits on the spot; Confirm captures the camera centre
 * as the event's coordinate ([onConfirm] with latitude, longitude), Cancel
 * discards ([onCancel]).
 *
 * The place NAME is captured separately by the create form's own text field —
 * this picker deliberately captures only the coordinate, so the events flow
 * stays free of the navigation/geocoding stack (a manual name field, retained on
 * purpose; see the slice notes).
 *
 * The camera OPENS on the user's own location ([userLatitude]/[userLongitude],
 * a last-known fix the caller reads up front) when the event has no pin yet, so
 * a member starts where they are and pins from there; it falls back to the
 * Kungsbacka default only when no pin and no location are available. If the fix
 * lands after the map is already showing, the camera slides to it once — unless
 * the user has already started panning (see the recentre effect).
 *
 * On-device only: the Mapbox [MapView] renders and pans only on a
 * token-provisioned device, so the map itself is verified on device. Without a
 * token ([hasToken] false — CI / no-Firebase) the map area shows a neutral
 * notice and Confirm captures the current (default or existing) centre so the
 * screen still compiles and UI-tests without a GL surface.
 */
@Composable
fun EventLocationPickerScreen(
    initialLatitude: Double?,
    initialLongitude: Double?,
    userLatitude: Double?,
    userLongitude: Double?,
    hasToken: Boolean,
    onConfirm: (latitude: Double, longitude: Double) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (startLat, startLng) =
        remember(initialLatitude, initialLongitude, userLatitude, userLongitude) {
            EventLocationPicker.startCenter(
                latitude = initialLatitude,
                longitude = initialLongitude,
                userLatitude = userLatitude,
                userLongitude = userLongitude,
            )
        }

    // The live camera centre, updated as the user pans; seeded to the start
    // centre so Confirm is valid even before the first camera-change fires (and
    // on the token-less build, which never pans).
    var centerLat by remember { mutableStateOf(startLat) }
    var centerLng by remember { mutableStateOf(startLng) }

    // The camera-change listener, held so it can be detached in onRelease
    // alongside destroying the MapView. The picker is opened and closed
    // repeatedly from the create form, and a MapView left undestroyed leaks its
    // GL/rendering resources each time — same teardown MapScreen and
    // MapboxMapSurface do.
    val cameraListenerHolder = remember { arrayOfNulls<OnCameraChangeListener>(1) }
    // Detaches the user-gesture listeners (pan/zoom/rotate/tilt), held for the
    // same teardown as the camera listener. They flip [userMovedCamera] so a late
    // location fix never yanks the camera once the user has taken over.
    val gestureDetachHolder = remember { arrayOfNulls<() -> Unit>(1) }
    // The live MapView, published from the factory so a late-arriving location
    // fix can recentre its camera (the factory runs once and cannot see later
    // state changes on its own).
    var mapView by remember { mutableStateOf<MapView?>(null) }
    // Set the moment the user pans/zooms/rotates/tilts the map; guards the
    // recentre effect.
    var userMovedCamera by remember { mutableStateOf(false) }

    // A last-known fix that lands AFTER the map is already showing (the user
    // opened the picker faster than the one-shot location read resolved): slide
    // the camera to the freshly resolved centre and re-seed the captured centre.
    // Keyed on the resolved centre, so it fires when the user's location first
    // arrives (the pair changes) and NOT on a plain pan (which leaves startLat/
    // startLng unchanged). Skipped once the user has moved the map themselves —
    // [userMovedCamera] is a key too, so the moment it flips true the effect
    // restarts and the guard below returns, making the skip deterministic even if
    // the user starts panning in the same frame the fix lands.
    LaunchedEffect(mapView, startLat, startLng, userMovedCamera) {
        val view = mapView ?: return@LaunchedEffect
        if (userMovedCamera) return@LaunchedEffect
        // Only adopt the new centre as the captured coordinate if the camera
        // actually moved there; a thrown setCamera (teardown/race) leaves the
        // captured pair on the last real camera position rather than desyncing it.
        runCatching {
            view.mapboxMap.setCamera(
                cameraOptions {
                    center(Point.fromLngLat(startLng, startLat))
                    zoom(EventLocationPicker.START_ZOOM)
                },
            )
        }.onSuccess {
            centerLat = startLat
            centerLng = startLng
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (hasToken) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val token = context.getString(R.string.mapbox_access_token)
                    if (token.isNotBlank()) {
                        MapboxOptions.accessToken = token
                    }
                    MapView(context).apply {
                        mapboxMap.setCamera(
                            cameraOptions {
                                center(Point.fromLngLat(startLng, startLat))
                                zoom(EventLocationPicker.START_ZOOM)
                            },
                        )
                        mapboxMap.loadStyle(Style.STANDARD)
                        // Track the camera centre so Confirm captures exactly what
                        // is under the fixed pin. cameraState is the honest read of
                        // where the camera settled after a pan.
                        val listener =
                            object : OnCameraChangeListener {
                                @Suppress("UNUSED_PARAMETER")
                                override fun onCameraChanged(eventData: CameraChangedEventData) {
                                    val center = mapboxMap.cameraState.center
                                    centerLat = center.latitude()
                                    centerLng = center.longitude()
                                }
                            }
                        cameraListenerHolder[0] = listener
                        mapboxMap.addOnCameraChangeListener(listener)
                        // ANY user camera gesture — pan, pinch-zoom, rotate or
                        // tilt — takes ownership of the camera: after the first one
                        // a late location fix must not recentre (or reset the zoom).
                        // Same set of gestures MapboxMapSurface treats as "the user
                        // took over"; the *Begin callbacks are the earliest signal.
                        val markMoved = { userMovedCamera = true }
                        val moveListener =
                            object : OnMoveListener {
                                override fun onMoveBegin(detector: MoveGestureDetector) {
                                    markMoved()
                                }

                                override fun onMove(detector: MoveGestureDetector): Boolean = false

                                override fun onMoveEnd(detector: MoveGestureDetector) = Unit
                            }
                        val scaleListener =
                            object : OnScaleListener {
                                override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                                    markMoved()
                                }

                                override fun onScale(detector: StandardScaleGestureDetector) = Unit

                                override fun onScaleEnd(detector: StandardScaleGestureDetector) = Unit
                            }
                        val rotateListener =
                            object : OnRotateListener {
                                override fun onRotateBegin(detector: RotateGestureDetector) {
                                    markMoved()
                                }

                                override fun onRotate(detector: RotateGestureDetector) = Unit

                                override fun onRotateEnd(detector: RotateGestureDetector) = Unit
                            }
                        val shoveListener =
                            object : OnShoveListener {
                                override fun onShoveBegin(detector: ShoveGestureDetector) {
                                    markMoved()
                                }

                                override fun onShove(detector: ShoveGestureDetector) = Unit

                                override fun onShoveEnd(detector: ShoveGestureDetector) = Unit
                            }
                        gestures.addOnMoveListener(moveListener)
                        gestures.addOnScaleListener(scaleListener)
                        gestures.addOnRotateListener(rotateListener)
                        gestures.addOnShoveListener(shoveListener)
                        gestureDetachHolder[0] = {
                            runCatching { gestures.removeOnMoveListener(moveListener) }
                            runCatching { gestures.removeOnScaleListener(scaleListener) }
                            runCatching { gestures.removeOnRotateListener(rotateListener) }
                            runCatching { gestures.removeOnShoveListener(shoveListener) }
                        }
                    }.also { view -> mapView = view }
                },
                onRelease = { view ->
                    // Detach before destroying so a torn-down map can never write
                    // back into the (gone) composition state.
                    cameraListenerHolder[0]?.let { listener ->
                        runCatching { view.mapboxMap.removeOnCameraChangeListener(listener) }
                    }
                    cameraListenerHolder[0] = null
                    gestureDetachHolder[0]?.invoke()
                    gestureDetachHolder[0] = null
                    mapView = null
                    runCatching { view.onDestroy() }
                },
            )
        } else {
            // Token-less build: no GL surface. The screen still works (Confirm
            // captures the default/existing centre) but there is no map to pan.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.events_locationPickerUnavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }

        // The fixed centre pin: locked to the screen centre, drawn OVER the map,
        // never intercepting map gestures. Its tip marks the point Confirm reads.
        Icon(
            imageVector = Icons.Filled.Place,
            contentDescription = stringResource(R.string.events_locationPickerPin),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.align(Alignment.Center).size(48.dp),
        )

        // Confirm / Cancel, over the map at the bottom.
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.events_locationPickerHint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        // Guard the captured pair before it leaves the picker; the
                        // camera centre is always in range, so this only ever fails
                        // on a corrupt state, in which case Confirm is a no-op.
                        if (Events.isValidCoordinatePair(centerLat, centerLng)) {
                            onConfirm(centerLat, centerLng)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag(EVENT_LOCATION_PICKER_CONFIRM_TAG),
                ) {
                    Text(text = stringResource(R.string.events_locationPickerConfirm))
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.events_locationPickerCancel))
                }
            }
        }
    }
}

/** Test tag on the picker's Confirm button. */
const val EVENT_LOCATION_PICKER_CONFIRM_TAG = "event_location_picker_confirm"
