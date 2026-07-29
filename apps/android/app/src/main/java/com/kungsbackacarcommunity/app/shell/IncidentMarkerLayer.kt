package com.kungsbackacarcommunity.app.shell

import android.content.Context
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions

/**
 * The ONE incident-badge renderer, shared by every map that draws incidents.
 *
 * It exists because there are now two of those maps and there must not be two
 * renderers. The shell surface ([MapboxMapSurface]) has always drawn the
 * crowd-sourced + Trafikverket incident layer; turn-by-turn navigation owns a
 * SECOND, Navigation-SDK `MapView` and drew nothing, which is the reported bug
 * ("while navigating I don't see Trafikverket's accidents on the map"). Copying
 * the draw into the navigation screen would have given the two maps two chances
 * to disagree about what an accident looks like, which category glyph goes with
 * which colour, and when a marker is "reported gone" — so the body moved here
 * instead and both callers hand it their own manager, style and caches.
 *
 * ## What the caller owns
 * Everything with a lifetime. A `PointAnnotationManager` and the style images
 * registered against a style both die with that style, so each map keeps its
 * own manager, its own [registeredImages] set and its own [idsByAnnotation]
 * lookup, and resets them wherever it recreates the manager. This object holds
 * no state at all, which is what makes it safe to point at two live maps.
 *
 * ## Layer identity
 * Each map's annotation manager creates its OWN GeoJSON source and symbol layer
 * with SDK-generated ids, and the two maps have entirely separate styles, so
 * there is nothing here for the navigation map's route-line, traffic or
 * destination-marker layers to collide with. The one identifier we DO choose is
 * the style-image name, and that is already namespaced — see
 * [IncidentMarkerBitmaps.imageId], which prefixes every image `kcc-incident-`.
 */
internal object IncidentMarkerLayer {
    /**
     * Clears and redraws [markers] on [manager] — one CATEGORY ICON per marker —
     * and rebuilds [idsByAnnotation] so a tap can be resolved back to the
     * incident it landed on. Returns whether the draw was COMPLETE, i.e. whether
     * every image it needed was on the style; an incomplete draw must not be
     * cached by the caller, or the missing icons would persist as blank
     * annotations until the marker set happened to change.
     *
     * These were plain coloured circles, which made colour the only thing
     * distinguishing an accident from roadworks — unreadable for a colour-blind
     * user, and hard for anyone at a glance while driving. Each marker carries
     * its category's glyph (see
     * `com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle`), so the
     * shape carries the meaning and the colour reinforces it.
     *
     * Each distinct marker image is rasterised and registered on the style once
     * ([registeredImages]); the annotations then only reference it by name, so
     * redrawing the layer does not re-upload bitmaps.
     *
     * The lookup is cleared FIRST and repopulated as annotations are created, so
     * it can never outlive the annotations it describes and hand a click
     * listener a stale incident id after a redraw.
     *
     * Every native call is wrapped defensively so a partial/failed draw degrades
     * rather than crashing the map.
     *
     * ACCESSIBILITY, stated here so its absence is not read as an oversight:
     * these markers carry no content description, because there is nowhere to
     * put one. They are Mapbox `PointAnnotation`s — style images inside the GL
     * surface — not Views and not composables, so no node exists in the
     * semantics tree to label, and `PointAnnotationOptions` exposes no
     * accessibility surface of its own (maps-annotation 11.26.0). A screen
     * reader cannot reach an individual badge at all, which is a real gap but an
     * ARCHITECTURAL one: closing it needs a different affordance (an accessible
     * list of nearby incidents), not a string on the annotation. The incident
     * content itself IS accessible once a sheet is open — `IncidentDetailsSheet`
     * announces category, age and source as ordinary text.
     *
     * On-device verification note: annotation rendering and hit-testing run only
     * on a token-provisioned device, so they are verified on device.
     *
     * @param manager the calling map's own incident annotation manager.
     * @param style the style the images must be registered against; null while a
     *   style load is in flight, which simply makes the draw incomplete.
     * @param context application context used to rasterise the glyphs; null in
     *   the same way.
     * @param registeredImages the image names already uploaded to [style], owned
     *   and reset by the caller alongside its manager.
     * @param idsByAnnotation the caller's annotation-id → incident-id lookup.
     */
    fun draw(
        manager: PointAnnotationManager,
        style: Style?,
        context: Context?,
        markers: List<MapIncidentMarker>,
        registeredImages: MutableSet<String>,
        idsByAnnotation: MutableMap<String, String>,
    ): Boolean {
        // Every image this draw needs must be on the style, or the annotations
        // below would reference a name the style does not know and render as
        // nothing. Tracked so the CALLER can decline to cache an incomplete draw
        // and simply try again on the next update.
        var complete = true
        runCatching { manager.deleteAll() }
        idsByAnnotation.clear()
        for (marker in markers) {
            val imageId =
                IncidentMarkerBitmaps.imageId(
                    iconRes = marker.iconRes,
                    discColorArgb = marker.colorArgb,
                    glyphColorArgb = marker.glyphColorArgb,
                    // Part of the KEY, not just the pixels: a normal marker and
                    // its struck-through twin differ only by the slash, so
                    // without this they would collide on one style-image name
                    // and whichever was registered first would be drawn for both.
                    reportedCleared = marker.reportedCleared,
                )
            // Register this category's image on first use against the current
            // style. If the style handle or context is unavailable the image
            // cannot be uploaded, so this draw is incomplete — never a crash.
            if (imageId !in registeredImages) {
                val bitmap =
                    if (style != null && context != null) {
                        IncidentMarkerBitmaps.create(
                            context = context,
                            iconRes = marker.iconRes,
                            discColorArgb = marker.colorArgb,
                            glyphColorArgb = marker.glyphColorArgb,
                            reportedCleared = marker.reportedCleared,
                        )
                    } else {
                        null
                    }
                // Only remember it as registered once the upload actually
                // succeeded, so a transient failure retries on the next redraw
                // rather than permanently marking the icon as present.
                val added =
                    bitmap != null &&
                        style != null &&
                        runCatching { style.addImage(imageId, bitmap) }.isSuccess
                if (added) registeredImages.add(imageId) else complete = false
            }
            runCatching {
                val annotation =
                    manager.create(
                        PointAnnotationOptions()
                            .withPoint(Point.fromLngLat(marker.longitude, marker.latitude))
                            .withIconImage(imageId)
                            // Anchored at the centre: this is a disc badge marking a
                            // point, not a pin whose tip is the location.
                            .withIconAnchor(IconAnchor.CENTER),
                    )
                // Record the drawn annotation so a tap on it resolves back to the
                // incident it represents.
                idsByAnnotation[annotation.id] = marker.id
            }
        }
        return complete
    }

    /**
     * The overlap settings every incident layer must have.
     *
     * Never let the symbol layer's collision detection drop an incident: in a
     * Swedish town centre a dozen imported roadwork markers overlap, and
     * Mapbox's default is to HIDE the ones that collide — which would silently
     * lose incidents from the map. Overlapping badges are the lesser evil; a
     * missing accident is not.
     *
     * Here rather than at each call site for the same reason [draw] is: two maps
     * drawing the same layer must not be able to disagree about whether an
     * accident is allowed to vanish behind a roadwork.
     */
    fun configure(manager: PointAnnotationManager) {
        manager.iconAllowOverlap = true
        manager.iconIgnorePlacement = true
    }
}
