package com.kungsbackacarcommunity.app.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager

/**
 * Map surface (Phase 12 slice 7) hosting a Mapbox [MapView] via [AndroidView].
 *
 * Renders a standard-style map centered either on the caller's own live
 * position (when [ownMarker] is non-null) or on a default town-level camera,
 * and draws a single circle annotation for the caller's own marker. There is
 * no multi-member feed here — the live RTDB rules grant only a per-uid
 * `liveLocation/{uid}/latest` read, so viewing other members' markers is a
 * follow-up (see [MapMarkers]).
 *
 * Tile rendering requires a real Mapbox access token, which is a secret NOT
 * present in CI and is provisioned at cutover (see [MapRoute] /
 * `MapboxOptions.accessToken`). Without a token the MapView still renders (an
 * empty style) rather than crashing, keeping the config-less build green.
 *
 * @param ownMarker the caller's own position, or null when unavailable.
 * @param onBack optional back navigation, mirroring the other slices.
 */
@Composable
fun MapScreen(
    ownMarker: MapMarker?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val camera = MapMarkers.cameraFor(ownMarker)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.map_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                MapboxMapView(
                    camera = camera,
                    ownMarker = ownMarker,
                    markerColor = MaterialTheme.colorScheme.primary.toArgb(),
                )
            }

            if (onBack != null) {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.profile_back))
                }
            }
        }
    }
}

/**
 * The Mapbox [MapView] itself, bridged into Compose. Loads the standard style,
 * applies the camera, and (best-effort) draws the own marker once the style is
 * ready.
 *
 * MapView v11 self-observes the host lifecycle via its lifecycle plugin, so no
 * [androidx.compose.runtime.DisposableEffect] is needed to start/stop it; the
 * [AndroidView] `onRelease` callback explicitly calls `onDestroy()` to tear the
 * native map down when the composition leaves, so nothing leaks if the plugin
 * is not attached.
 *
 * @param markerColor ARGB color for the own-marker circle, sourced from the
 * theme so it stays consistent with the app's palette.
 */
@Composable
private fun MapboxMapView(
    camera: MapCameraPosition,
    ownMarker: MapMarker?,
    markerColor: Int,
) {
    // Holds the annotation manager created after the style loads, so the update
    // lambda can clear/recreate the own marker when [ownMarker] changes.
    val managerHolder = remember { arrayOfNulls<CircleAnnotationManager>(1) }
    // Latest marker + color, kept current by the update lambda. loadStyle runs
    // asynchronously, so its callback must NOT capture the factory-time values —
    // it draws from these holders instead, so a marker that changed before the
    // style finished loading is rendered correctly (not the stale initial value).
    val markerHolder = remember { arrayOfNulls<MapMarker>(1) }
    val colorHolder = remember { intArrayOf(0) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            markerHolder[0] = ownMarker
            colorHolder[0] = markerColor
            MapView(context).apply {
                mapboxMap.setCamera(
                    cameraOptions {
                        center(Point.fromLngLat(camera.longitude, camera.latitude))
                        zoom(camera.zoom)
                    },
                )
                // Create the circle-annotation manager once a style is present.
                // No image asset needed (a circle annotation), so this never
                // fails on a missing drawable and stays crash-free even when
                // tiles cannot load (no token). Drawn from the holders so it
                // reflects the current [ownMarker], even if it changed while the
                // style was still loading.
                mapboxMap.loadStyle(Style.STANDARD) {
                    managerHolder[0] = annotations.createCircleAnnotationManager()
                    drawOwnMarker(managerHolder[0], markerHolder[0], colorHolder[0])
                }
            }
        },
        update = { mapView ->
            markerHolder[0] = ownMarker
            colorHolder[0] = markerColor
            mapView.mapboxMap.setCamera(
                cameraOptions {
                    center(Point.fromLngLat(camera.longitude, camera.latitude))
                    zoom(camera.zoom)
                },
            )
            // Redraw when [ownMarker] (or its coordinate) changes; a no-op until
            // the style has loaded and the manager exists.
            drawOwnMarker(managerHolder[0], ownMarker, markerColor)
        },
        onRelease = { mapView ->
            managerHolder[0] = null
            mapView.onDestroy()
        },
    )
}

/**
 * Clears any existing own-marker annotation and, when [ownMarker] is non-null,
 * draws a fresh circle for it. No-op until [manager] is available (i.e. the
 * style has finished loading).
 */
private fun drawOwnMarker(
    manager: CircleAnnotationManager?,
    ownMarker: MapMarker?,
    markerColor: Int,
) {
    manager ?: return
    manager.deleteAll()
    ownMarker?.let { marker ->
        manager.create(
            CircleAnnotationOptions()
                .withPoint(Point.fromLngLat(marker.longitude, marker.latitude))
                .withCircleRadius(8.0)
                .withCircleColor(markerColor),
        )
    }
}

@Preview(name = "Map – no fix", showBackground = true)
@Composable
private fun MapScreenPreview() {
    KccTheme {
        MapScreen(ownMarker = null, onBack = {})
    }
}
