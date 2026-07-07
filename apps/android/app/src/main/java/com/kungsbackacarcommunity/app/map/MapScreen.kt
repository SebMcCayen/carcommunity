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
import androidx.compose.ui.Modifier
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
                MapboxMapView(camera = camera, ownMarker = ownMarker)
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
 * ready. The view's start/stop/destroy lifecycle is driven from a
 * [DisposableEffect] so the native map is torn down with the composition.
 */
@Composable
private fun MapboxMapView(camera: MapCameraPosition, ownMarker: MapMarker?) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).apply {
                mapboxMap.setCamera(
                    cameraOptions {
                        center(Point.fromLngLat(camera.longitude, camera.latitude))
                        zoom(camera.zoom)
                    },
                )
                mapboxMap.loadStyle(Style.STANDARD) {
                    // Draw the caller's own marker once a style is present. No
                    // image asset needed (a circle annotation), so this never
                    // fails on a missing drawable and stays crash-free even
                    // when tiles cannot load (no token).
                    ownMarker?.let { marker ->
                        val manager = annotations.createCircleAnnotationManager()
                        manager.create(
                            CircleAnnotationOptions()
                                .withPoint(Point.fromLngLat(marker.longitude, marker.latitude))
                                .withCircleRadius(8.0)
                                .withCircleColor("#1E88E5"),
                        )
                    }
                }
            }
        },
        update = { mapView ->
            mapView.mapboxMap.setCamera(
                cameraOptions {
                    center(Point.fromLngLat(camera.longitude, camera.latitude))
                    zoom(camera.zoom)
                },
            )
        },
        // MapView v11 self-observes the host lifecycle via its lifecycle plugin;
        // onRelease still explicitly tears the native map down when the
        // composition leaves, so nothing leaks if the plugin is not attached.
        onRelease = { mapView -> mapView.onDestroy() },
    )
}

@Preview(name = "Map – no fix", showBackground = true)
@Composable
private fun MapScreenPreview() {
    KccTheme {
        MapScreen(ownMarker = null, onBack = {})
    }
}
