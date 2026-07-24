package com.kungsbackacarcommunity.app.incidents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.map.MapMarkers
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.scalebar.scalebar

/** Test tag on the "place here" confirm control of the location picker. */
const val INCIDENT_LOCATION_PICKER_CONFIRM_TAG = "incident_location_picker_confirm"

/**
 * A full-screen map popup for placing a NEW incident report by hand, the "pick
 * on map" half of the report flow's location choice (the quick default stays
 * "use my current location").
 *
 * The user moves the MAP under a fixed, centered pin — the standard "drag the map,
 * not the marker" pattern that avoids the fat-finger imprecision of dragging a
 * small annotation. Confirming reads the camera CENTRE (the point under the pin)
 * and hands it back through [onConfirm]; the coordinate is validated
 * ([isValidReportCoordinate]) before it is accepted, so a not-yet-loaded map
 * (a NaN centre) cannot submit an impossible point — the instruction line swaps
 * to a "pick a spot first" prompt instead.
 *
 * Reuses the app's existing embedded-Mapbox pattern (see
 * [com.kungsbackacarcommunity.app.map.DriveRouteFullscreenDialog]): the PUBLIC
 * Maps SDK, gestures left on, scale bar / built-in compass off, every native call
 * wrapped in `runCatching` so a token-less / not-yet-styled surface degrades to a
 * blank map rather than a crash. The GL surface and camera are on-device-only
 * concerns (CI has an empty token); the pure part — validating the picked
 * coordinate — lives in [isValidReportCoordinate] and is unit-tested off-device.
 *
 * @param initialCenter where to open the camera (typically where the user is
 *   already looking on the home map); falls back to the app's default Kungsbacka
 *   camera when null.
 * @param onConfirm invoked with the chosen, validated coordinate.
 * @param onDismiss close the popup without choosing (back press, scrim, Cancel).
 */
@Composable
fun IncidentLocationPickerDialog(
    initialCenter: LatLng?,
    onConfirm: (LatLng) -> Unit,
    onDismiss: () -> Unit,
) {
    val instruction = stringResource(R.string.incidents_pickLocationInstruction)
    val invalidInstruction = stringResource(R.string.incidents_pickLocationInvalid)
    val title = stringResource(R.string.incidents_pickLocationTitle)
    val mapDesc = stringResource(R.string.incidents_pickLocationMap)
    val confirmLabel = stringResource(R.string.incidents_pickLocationConfirm)
    val cancelLabel = stringResource(R.string.incidents_pickLocationCancel)

    // Held so the confirm button can read the live camera centre. Touched only on
    // the main thread (Compose callbacks + the AndroidView factory).
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    // Flips to true only after a confirm attempt found no usable centre (map not
    // ready) — swaps the instruction line to the "pick a spot first" prompt.
    var showInvalid by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = mapDesc },
                factory = { context ->
                    val token = context.getString(R.string.mapbox_access_token)
                    if (token.isNotBlank()) {
                        MapboxOptions.accessToken = token
                    }
                    MapView(context).apply {
                        mapViewRef = this
                        runCatching { scalebar.updateSettings { enabled = false } }
                        runCatching { compass.updateSettings { enabled = false } }
                        mapboxMap.loadStyle(Style.STANDARD) { _ ->
                            runCatching {
                                val center = initialCenter ?: DEFAULT_CENTER
                                mapboxMap.setCamera(
                                    cameraOptions {
                                        center(Point.fromLngLat(center.longitude, center.latitude))
                                        zoom(MapMarkers.OWN_MARKER_ZOOM)
                                    },
                                )
                            }
                        }
                    }
                },
                onRelease = { mapView ->
                    mapViewRef = null
                    runCatching { mapView.onDestroy() }
                },
            )

            // The fixed centre pin the user moves the map UNDER. Offset up by half
            // its height so the pin's TIP (not its centre) marks the camera centre.
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = null,
                tint = Color(IncidentPalette.colorArgb(IncidentType.HAZARD)),
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(PIN_SIZE)
                        .offset(y = -(PIN_SIZE / 2)),
            )

            // Title + instruction card, top.
            Surface(
                shape = RoundedCornerShape(KccSpacing.s3),
                tonalElevation = KccSpacing.s1,
                shadowElevation = KccSpacing.s1,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(KccSpacing.s4)
                        .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(KccSpacing.s4),
                    verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
                ) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (showInvalid) invalidInstruction else instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (showInvalid) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }

            // Cancel / confirm controls, bottom.
            Surface(
                shape = RoundedCornerShape(KccSpacing.s3),
                tonalElevation = KccSpacing.s1,
                shadowElevation = KccSpacing.s1,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(KccSpacing.s4)
                        .fillMaxWidth(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(KccSpacing.s3),
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(cancelLabel)
                    }
                    Button(
                        onClick = {
                            val picked = mapViewRef?.let { readCenter(it) }
                            if (picked != null && isValidReportCoordinate(picked)) {
                                onConfirm(picked)
                            } else {
                                showInvalid = true
                            }
                        },
                        contentPadding = PaddingValues(horizontal = KccSpacing.s4),
                        modifier = Modifier.testTag(INCIDENT_LOCATION_PICKER_CONFIRM_TAG),
                    ) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}

/**
 * Reads the live camera CENTRE (the point under the fixed pin) as a [LatLng], or
 * null when the map has no usable camera yet. Wrapped defensively so a read
 * before the style/camera is ready is a null rather than a crash.
 */
private fun readCenter(mapView: MapView): LatLng? =
    runCatching {
        val center = mapView.mapboxMap.cameraState.center
        LatLng(longitude = center.longitude(), latitude = center.latitude())
    }.getOrNull()

// The app's default Kungsbacka camera, reused when the caller has no better
// starting point (no live camera snapshot yet).
private val DEFAULT_CENTER =
    LatLng(
        longitude = MapMarkers.DEFAULT_CAMERA.longitude,
        latitude = MapMarkers.DEFAULT_CAMERA.latitude,
    )

private val PIN_SIZE = 48.dp
