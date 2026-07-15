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
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotation
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager

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
                    // Callout text colour: high-contrast onSurface so the sharer
                    // name + car reads on any basemap (a white halo is added at
                    // draw time for legibility over dark tiles).
                    calloutColor = MaterialTheme.colorScheme.onSurface.toArgb(),
                    // Fallback "who" line when a marker carries a car but no
                    // display name, so the car is never shown without a sharer.
                    calloutFallbackName = stringResource(R.string.map_liveMarkerFallbackName),
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
 * On top of each circle a [PointAnnotation] text callout is drawn — the
 * sharer's display name and, when their main car is denormalized onto the
 * marker, its "make model" — so a live-share viewer SEES who each pin is and
 * which car they are driving, not just an anonymous dot. The car PHOTO
 * ([MapMarker.mainCar] imagePath) is a documented follow-up: rendering it needs
 * an async Storage-URL resolve + bitmap decode added to the style per marker,
 * which is out of scope for this text-first callout slice.
 *
 * @param ownColor ARGB colour for the caller's own marker.
 * @param otherColor ARGB colour for other members' markers. Both are sourced
 * from the theme so they stay consistent with the app's palette.
 * @param calloutColor ARGB colour for the callout text (a white halo is added
 * at draw time for legibility over any basemap).
 * @param calloutFallbackName the "who" line used when a marker carries a car
 * but no display name (see [MapMarkers.calloutLabel]).
 */
@Composable
private fun MapboxMapView(
    camera: MapCameraPosition,
    markers: List<MapMarker>,
    ownColor: Int,
    otherColor: Int,
    calloutColor: Int,
    calloutFallbackName: String,
) {
    // Holds the annotation manager created after the style loads, so the update
    // lambda can diff the markers when [markers] change.
    val managerHolder = remember { arrayOfNulls<CircleAnnotationManager>(1) }
    // The point (text) annotation manager for the marker callouts, created next
    // to the circle manager once the style is ready.
    val calloutManagerHolder = remember { arrayOfNulls<PointAnnotationManager>(1) }
    // Live annotations keyed by marker identity, so [drawMarkers] can reuse and
    // move existing circles instead of deleting/recreating every one on each
    // live-location update (avoids churn/flicker with many participants).
    val annotationsHolder = remember { mutableMapOf<String, CircleAnnotation>() }
    // Live text callouts keyed the same way, reconciled alongside the circles so
    // frequent live-location updates reuse them instead of recreating each one.
    val calloutsHolder = remember { mutableMapOf<String, PointAnnotation>() }
    // Latest markers + colours, kept current by the update lambda. loadStyle runs
    // asynchronously, so its callback must NOT capture the factory-time values —
    // it draws from these holders instead, so markers that changed before the
    // style finished loading are rendered correctly (not the stale initial value).
    val markersHolder = remember { arrayOfNulls<List<MapMarker>>(1) }
    val ownColorHolder = remember { intArrayOf(0) }
    val otherColorHolder = remember { intArrayOf(0) }
    val calloutColorHolder = remember { intArrayOf(0) }
    val calloutFallbackHolder = remember { arrayOfNulls<String>(1) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            markersHolder[0] = markers
            ownColorHolder[0] = ownColor
            otherColorHolder[0] = otherColor
            calloutColorHolder[0] = calloutColor
            calloutFallbackHolder[0] = calloutFallbackName
            MapView(context).apply {
                mapboxMap.setCamera(
                    cameraOptions {
                        center(Point.fromLngLat(camera.longitude, camera.latitude))
                        zoom(camera.zoom)
                    },
                )
                // Create the annotation managers once a style is present. No image
                // asset is needed (circle + text annotations only), so this never
                // fails on a missing drawable and stays crash-free even when tiles
                // cannot load (no token). Drawn from the holders so it reflects the
                // current [markers], even if they changed while the style was still
                // loading.
                mapboxMap.loadStyle(Style.STANDARD) {
                    managerHolder[0] = annotations.createCircleAnnotationManager()
                    calloutManagerHolder[0] = annotations.createPointAnnotationManager()
                    drawMarkers(
                        managerHolder[0],
                        annotationsHolder,
                        markersHolder[0].orEmpty(),
                        ownColorHolder[0],
                        otherColorHolder[0],
                    )
                    drawCallouts(
                        calloutManagerHolder[0],
                        calloutsHolder,
                        markersHolder[0].orEmpty(),
                        calloutColorHolder[0],
                        calloutFallbackHolder[0].orEmpty(),
                    )
                }
            }
        },
        update = { mapView ->
            markersHolder[0] = markers
            ownColorHolder[0] = ownColor
            otherColorHolder[0] = otherColor
            calloutColorHolder[0] = calloutColor
            calloutFallbackHolder[0] = calloutFallbackName
            mapView.mapboxMap.setCamera(
                cameraOptions {
                    center(Point.fromLngLat(camera.longitude, camera.latitude))
                    zoom(camera.zoom)
                },
            )
            // Redraw when [markers] change; a no-op until the style has loaded
            // and the managers exist.
            drawMarkers(managerHolder[0], annotationsHolder, markers, ownColor, otherColor)
            drawCallouts(
                calloutManagerHolder[0],
                calloutsHolder,
                markers,
                calloutColor,
                calloutFallbackName,
            )
        },
        onRelease = { mapView ->
            managerHolder[0] = null
            calloutManagerHolder[0] = null
            annotationsHolder.clear()
            calloutsHolder.clear()
            mapView.onDestroy()
        },
    )
}

/**
 * Reconciles the on-map circles with [markers], keyed by marker identity so
 * frequent live-location updates reuse existing annotations instead of clearing
 * and recreating them all (which caused churn/flicker with many participants).
 *
 * For each marker: an existing circle is moved/recoloured in place via
 * [CircleAnnotationManager.update]; a new one is created; circles whose marker
 * has gone (stopped sharing) are deleted. [live] is the caller-owned map of the
 * currently drawn annotations, mutated in place to match [markers]. No-op until
 * [manager] is available (i.e. the style has finished loading).
 *
 * The key is the marker uid, falling back to the marker kind for the (single)
 * own marker that may lack a uid — there is at most one own marker and every
 * other member carries a uid, so keys never collide.
 */
private fun drawMarkers(
    manager: CircleAnnotationManager?,
    live: MutableMap<String, CircleAnnotation>,
    markers: List<MapMarker>,
    ownColor: Int,
    otherColor: Int,
) {
    manager ?: return
    val seen = HashSet<String>(markers.size)
    markers.forEach { marker ->
        val key = marker.uid ?: marker.kind.name
        seen += key
        val color = if (marker.kind == MapMarkerKind.OWN) ownColor else otherColor
        val point = Point.fromLngLat(marker.longitude, marker.latitude)
        val existing = live[key]
        if (existing != null) {
            existing.point = point
            existing.circleColorInt = color
            manager.update(existing)
        } else {
            live[key] =
                manager.create(
                    CircleAnnotationOptions()
                        .withPoint(point)
                        .withCircleRadius(8.0)
                        .withCircleColor(color),
                )
        }
    }
    // Remove circles for markers that are no longer present.
    val iterator = live.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (entry.key !in seen) {
            manager.delete(entry.value)
            iterator.remove()
        }
    }
}

/**
 * Reconciles the on-map text callouts with [markers], keyed by marker identity
 * (same key scheme as [drawMarkers]) so frequent live-location updates reuse
 * existing text annotations instead of recreating them all. Each callout shows
 * [MapMarkers.calloutLabel] — the sharer's display name and, when present, their
 * main car's "make model" — anchored just above the marker circle.
 *
 * A marker with nothing to label (no name and no car; see [MapMarkers.calloutLabel]
 * returning null) gets no text annotation, and an existing one is removed if its
 * marker loses its label. [live] is the caller-owned map of currently drawn text
 * annotations, mutated in place to match [markers]. No-op until [manager] is
 * available (i.e. the style has finished loading).
 *
 * The text is drawn in [textColor] with a white halo for legibility over any
 * basemap. On-device verification note: annotation text rendering requires a
 * token-provisioned device, so it is verified on device.
 */
private fun drawCallouts(
    manager: PointAnnotationManager?,
    live: MutableMap<String, PointAnnotation>,
    markers: List<MapMarker>,
    textColor: Int,
    fallbackName: String,
) {
    manager ?: return
    val seen = HashSet<String>(markers.size)
    markers.forEach { marker ->
        val label = MapMarkers.calloutLabel(marker, fallbackName) ?: return@forEach
        val key = marker.uid ?: marker.kind.name
        seen += key
        val point = Point.fromLngLat(marker.longitude, marker.latitude)
        val existing = live[key]
        if (existing != null) {
            existing.point = point
            existing.textField = label
            existing.textColorInt = textColor
            manager.update(existing)
        } else {
            live[key] =
                manager.create(
                    PointAnnotationOptions()
                        .withPoint(point)
                        .withTextField(label)
                        .withTextColor(textColor)
                        .withTextHaloColor(CALLOUT_HALO_COLOR)
                        .withTextHaloWidth(CALLOUT_HALO_WIDTH)
                        .withTextSize(CALLOUT_TEXT_SIZE)
                        // Anchor the text's bottom to the point and nudge it up so
                        // the label floats just above the circle rather than over it.
                        .withTextAnchor(TextAnchor.BOTTOM)
                        .withTextOffset(CALLOUT_TEXT_OFFSET),
                )
        }
    }
    // Remove callouts for markers that are gone (stopped sharing) or lost their label.
    val iterator = live.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (entry.key !in seen) {
            manager.delete(entry.value)
            iterator.remove()
        }
    }
}

/** White halo ARGB behind the callout text so it stays legible on dark tiles. */
private const val CALLOUT_HALO_COLOR: Int = 0xFFFFFFFF.toInt()

/** Halo width (px) around the callout text. */
private const val CALLOUT_HALO_WIDTH: Double = 1.4

/** Callout text size (sp-equivalent). */
private const val CALLOUT_TEXT_SIZE: Double = 13.0

/**
 * Text offset in ems: nudge the label up (negative y) so it clears the 8px
 * marker circle. Paired with [TextAnchor.BOTTOM] so the text sits above the pin.
 */
private val CALLOUT_TEXT_OFFSET: List<Double> = listOf(0.0, -1.2)

@Preview(name = "Map – no fix", showBackground = true)
@Composable
private fun MapScreenPreview() {
    KccTheme {
        MapScreen(markers = emptyList(), onBack = {})
    }
}
