package com.kungsbackacarcommunity.app.shell

import android.content.Context
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
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
 * own manager, its own [registeredImages] set, [annotations] reconciler and
 * [idsByAnnotation] lookup, and resets them whenever it recreates the manager.
 * This object holds no state, so it can safely serve two live maps.
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
     * Reconciles [markers] on [manager], retaining unchanged annotations by id,
     * and rebuilds [idsByAnnotation] so a tap can be resolved back to the
     * incident it landed on. Returns whether the draw was COMPLETE, i.e. whether
     * every image and annotation operation succeeded. An incomplete draw must
     * not be cached by the caller, or the missing icons would persist as blank
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
     * The lookup is rebuilt from retained annotations after reconciliation, so
     * unchanged badges keep both their native identity and their tap target.
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
     * @param annotations retained annotations, cleared with the manager.
     */
    fun draw(
        manager: PointAnnotationManager,
        style: Style?,
        context: Context?,
        markers: List<MapIncidentMarker>,
        registeredImages: MutableSet<String>,
        idsByAnnotation: MutableMap<String, String>,
        annotations: IncidentMarkerReconciler<PointAnnotation>,
    ): Boolean {
        val complete = annotations.reconcile(
            markers = markers,
            prepare = { marker ->
                registerImage(marker, style, context, registeredImages)
            },
            create = { marker ->
                manager.create(
                    PointAnnotationOptions()
                        .withPoint(Point.fromLngLat(marker.longitude, marker.latitude))
                        .withIconImage(imageId(marker))
                        .withIconAnchor(IconAnchor.CENTER),
                )
            },
            update = { annotation, marker ->
                annotation.point = Point.fromLngLat(marker.longitude, marker.latitude)
                annotation.iconImage = imageId(marker)
                manager.update(annotation)
            },
            delete = { annotation -> manager.delete(annotation) },
        )
        idsByAnnotation.clear()
        annotations.forEachAnnotation { id, annotation -> idsByAnnotation[annotation.id] = id }
        return complete
    }

    private fun imageId(marker: MapIncidentMarker): String =
        IncidentMarkerBitmaps.imageId(
            iconRes = marker.iconRes,
            discColorArgb = marker.colorArgb,
            glyphColorArgb = marker.glyphColorArgb,
            reportedCleared = marker.reportedCleared,
        )

    private fun registerImage(
        marker: MapIncidentMarker,
        style: Style?,
        context: Context?,
        registeredImages: MutableSet<String>,
    ): Boolean {
        val imageId = imageId(marker)
        if (imageId in registeredImages) return true
        if (style == null || context == null) return false
        val added = runCatching {
            val bitmap = IncidentMarkerBitmaps.create(
                context = context,
                iconRes = marker.iconRes,
                discColorArgb = marker.colorArgb,
                glyphColorArgb = marker.glyphColorArgb,
                reportedCleared = marker.reportedCleared,
            ) ?: return@runCatching false
            style.addImage(imageId, bitmap)
            true
        }.getOrDefault(false)
        if (added) registeredImages.add(imageId)
        return added
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
