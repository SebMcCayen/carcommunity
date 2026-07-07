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
 * Map surface (Phase 12 slice 7 + live-markers follow-up) hosting a Mapbox
 * [MapView] via [AndroidView].
 *
 * Renders a standard-style map centered on the caller's own live position (when
 * present) or on a default town-level camera, and draws a circle annotation for
 * every marker in [markers]: the caller's OWN marker in the primary colour and
 * other members' markers (e.g. a group-drive roster) in the secondary colour.
 * The marker feed is built from per-uid `liveLocation/{uid}/latest` reads — no
 * collection scan (see [MapMarkers] / [MapRoute]).
 *
 * Tile rendering requires a real Mapbox access token, which is a secret NOT
 * present in CI and is provisioned at cutover (see [MapRoute] /
 * `MapboxOptions.accessToken`). Without a token the MapView still renders (an
 * empty style) rather than crashing, keeping the config-less build green.
 *
 * @param markers the markers to draw; own first, then other members. Empty when
 * no one is sharing.
 * @param onBack optional back navigation, mirroring the other slices.
 */
@Composable
fun MapScreen(
    markers: List<MapMarker>,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val camera = MapMarkers.cameraForMarkers(markers)

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
                    markers = markers,
                    ownColor = MaterialTheme.colorScheme.primary.toArgb(),
                    otherColor = MaterialTheme.colorScheme.secondary.toArgb(),
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
 * applies the camera, and (best-effort) draws all markers once the style is
 * ready.
 *
 * MapView v11 self-observes the host lifecycle via its lifecycle plugin, so no
 * [androidx.compose.runtime.DisposableEffect] is needed to start/stop it; the
 * [AndroidView] `onRelease` callback explicitly calls `onDestroy()` to tear the
 * native map down when the composition leaves, so nothing leaks if the plugin
 * is not attached.
 *
 * @param ownColor ARGB colour for the caller's own marker.
 * @param otherColor ARGB colour for other members' markers. Both are sourced
 * from the theme so they stay consistent with the app's palette.
 */
@Composable
private fun MapboxMapView(
    camera: MapCameraPosition,
    markers: List<MapMarker>,
    ownColor: Int,
    otherColor: Int,
) {
    // Holds the annotation manager created after the style loads, so the update
    // lambda can clear/recreate the markers when [markers] change.
    val managerHolder = remember { arrayOfNulls<CircleAnnotationManager>(1) }
    // Latest markers + colours, kept current by the update lambda. loadStyle runs
    // asynchronously, so its callback must NOT capture the factory-time values —
    // it draws from these holders instead, so markers that changed before the
    // style finished loading are rendered correctly (not the stale initial value).
    @Suppress("UNCHECKED_CAST")
    val markersHolder = remember { arrayOfNulls<List<MapMarker>>(1) }
    val ownColorHolder = remember { intArrayOf(0) }
    val otherColorHolder = remember { intArrayOf(0) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            markersHolder[0] = markers
            ownColorHolder[0] = ownColor
            otherColorHolder[0] = otherColor
            MapView(context).apply {
                mapboxMap.setCamera(
                    cameraOptions {
                        center(Point.fromLngLat(camera.longitude, camera.latitude))
                        zoom(camera.zoom)
                    },
                )
                // Create the circle-annotation manager once a style is present.
                // No image asset needed (circle annotations), so this never
                // fails on a missing drawable and stays crash-free even when
                // tiles cannot load (no token). Drawn from the holders so it
                // reflects the current [markers], even if they changed while the
                // style was still loading.
                mapboxMap.loadStyle(Style.STANDARD) {
                    managerHolder[0] = annotations.createCircleAnnotationManager()
                    drawMarkers(
                        managerHolder[0],
                        markersHolder[0].orEmpty(),
                        ownColorHolder[0],
                        otherColorHolder[0],
                    )
                }
            }
        },
        update = { mapView ->
            markersHolder[0] = markers
            ownColorHolder[0] = ownColor
            otherColorHolder[0] = otherColor
            mapView.mapboxMap.setCamera(
                cameraOptions {
                    center(Point.fromLngLat(camera.longitude, camera.latitude))
                    zoom(camera.zoom)
                },
            )
            // Redraw when [markers] change; a no-op until the style has loaded
            // and the manager exists.
            drawMarkers(managerHolder[0], markers, ownColor, otherColor)
        },
        onRelease = { mapView ->
            managerHolder[0] = null
            mapView.onDestroy()
        },
    )
}

/**
 * Clears any existing annotations and draws a fresh circle for every marker,
 * colouring own vs other by [MapMarker.kind]. No-op until [manager] is
 * available (i.e. the style has finished loading).
 */
private fun drawMarkers(
    manager: CircleAnnotationManager?,
    markers: List<MapMarker>,
    ownColor: Int,
    otherColor: Int,
) {
    manager ?: return
    manager.deleteAll()
    markers.forEach { marker ->
        val color = if (marker.kind == MapMarkerKind.OWN) ownColor else otherColor
        manager.create(
            CircleAnnotationOptions()
                .withPoint(Point.fromLngLat(marker.longitude, marker.latitude))
                .withCircleRadius(8.0)
                .withCircleColor(color),
        )
    }
}

@Preview(name = "Map – no fix", showBackground = true)
@Composable
private fun MapScreenPreview() {
    KccTheme {
        MapScreen(markers = emptyList(), onBack = {})
    }
}
