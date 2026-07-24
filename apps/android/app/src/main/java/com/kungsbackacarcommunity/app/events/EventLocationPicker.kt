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
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions

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
     * The default map centre when the event has no pin yet: Kungsbacka, the town
     * the app serves (same coordinates the main map's default camera uses —
     * map/MapMarkers.DEFAULT_CAMERA). Longitude then latitude.
     */
    const val DEFAULT_LONGITUDE = 12.0757
    const val DEFAULT_LATITUDE = 57.4874

    /** A sensible town-scale start zoom so the user can position the pin. */
    const val START_ZOOM = 13.0

    /**
     * Where the pin should start: the event's existing coordinates when it
     * already has a (valid, complete) pin, otherwise the Kungsbacka default. A
     * half-set or out-of-range pair is treated as "no pin" and falls back to the
     * default rather than starting the camera at a bogus point. Returns
     * (latitude, longitude).
     */
    fun startCenter(latitude: Double?, longitude: Double?): Pair<Double, Double> =
        if (latitude != null &&
            longitude != null &&
            Events.isValidCoordinatePair(latitude, longitude)
        ) {
            latitude to longitude
        } else {
            DEFAULT_LATITUDE to DEFAULT_LONGITUDE
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
    hasToken: Boolean,
    onConfirm: (latitude: Double, longitude: Double) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (startLat, startLng) =
        remember(initialLatitude, initialLongitude) {
            EventLocationPicker.startCenter(initialLatitude, initialLongitude)
        }

    // The live camera centre, updated as the user pans; seeded to the start
    // centre so Confirm is valid even before the first camera-change fires (and
    // on the token-less build, which never pans).
    var centerLat by remember { mutableStateOf(startLat) }
    var centerLng by remember { mutableStateOf(startLng) }

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
                        mapboxMap.addOnCameraChangeListener {
                            val center = mapboxMap.cameraState.center
                            centerLat = center.latitude()
                            centerLng = center.longitude()
                        }
                    }
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
