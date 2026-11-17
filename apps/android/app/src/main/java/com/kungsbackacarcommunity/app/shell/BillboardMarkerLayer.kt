package com.kungsbackacarcommunity.app.shell

import android.content.Context
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions

/**
 * The sponsored-billboard renderer — the fourth marker layer on the shell map,
 * after incidents, Kronjakt crowns and community event pins.
 *
 * Deliberately shaped like [IncidentMarkerLayer]: a stateless object that draws
 * into a manager, a style and caches the CALLER owns, so nothing here has a
 * lifetime that could outlive the style it drew against. See that object's KDoc
 * for the reasoning behind that split — it applies unchanged.
 *
 * Kept as its own object rather than folded into the incident renderer because
 * the two draw different things in different ways: incidents rasterise a vector
 * glyph per category and anchor the badge on its centre, billboards draw one
 * piece of canvas geometry and anchor it on the foot of a post. Merging them
 * would produce a function whose body is a shape switch, and would put the two
 * layers one edit away from restyling each other.
 */
internal object BillboardMarkerLayer {
    /**
     * Clears and redraws [markers] on [manager] and rebuilds [idsByAnnotation]
     * so a tap resolves back to the billboard it landed on. Returns whether the
     * draw was COMPLETE — i.e. whether the marker image made it onto the style.
     * An incomplete draw must not be cached by the caller, or the missing icon
     * would persist as blank annotations until the marker set happened to
     * change.
     *
     * There is exactly ONE image for this layer (a billboard has no per-item
     * variants), so it is rasterised and registered against the style once and
     * every annotation then references it by name.
     *
     * The lookup is cleared FIRST and repopulated as annotations are created, so
     * it can never outlive the annotations it describes and hand a click
     * listener a stale billboard id after a redraw.
     *
     * Every native call is wrapped defensively so a partial/failed draw degrades
     * rather than crashing the map.
     *
     * ACCESSIBILITY, stated here so its absence is not read as an oversight:
     * these markers carry no content description, for exactly the reason set out
     * on [IncidentMarkerLayer.draw] — they are Mapbox `PointAnnotation`s inside
     * the GL surface, not composables, so no semantics node exists to label. The
     * billboard's own content becomes accessible the moment its popup opens
     * (ordinary text, including the "Sponsrad placering" label).
     *
     * On-device verification note: annotation rendering and hit-testing run only
     * on a token-provisioned device, so they are verified on device.
     */
    fun draw(
        manager: PointAnnotationManager,
        style: Style?,
        context: Context?,
        markers: List<MapBillboardMarker>,
        registeredImages: MutableSet<String>,
        idsByAnnotation: MutableMap<String, String>,
        annotations: MapAnnotationReconciler<MapBillboardMarker, PointAnnotation>,
        opacityById: Map<String, Double> = emptyMap(),
    ): Boolean {
        val imageId = BillboardMarkerBitmaps.imageId()
        var complete = true
        if (imageId !in registeredImages) {
            val bitmap =
                if (style != null && context != null) {
                    BillboardMarkerBitmaps.create(
                        density = context.resources.displayMetrics.density,
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
        complete = annotations.reconcile(markers,
            prepare = { imageId in registeredImages },
            create = { marker -> manager.create(PointAnnotationOptions()
                .withPoint(Point.fromLngLat(marker.longitude, marker.latitude))
                .withIconImage(imageId).withIconOpacity(opacityById[marker.id] ?: 1.0)
                .withIconAnchor(IconAnchor.BOTTOM)) },
            update = { annotation, marker ->
                annotation.point = Point.fromLngLat(marker.longitude, marker.latitude)
                annotation.iconImage = imageId
                manager.update(annotation)
            }, delete = { manager.delete(it) }) && complete
        idsByAnnotation.clear()
        annotations.forEachAnnotation { id, annotation -> idsByAnnotation[annotation.id] = id }
        return complete
    }

    /**
     * The overlap settings this layer must have.
     *
     * Matches [IncidentMarkerLayer.configure] and for a related reason, though
     * the stakes differ: a hidden incident is a safety problem, whereas a hidden
     * billboard is a sponsor asking why their placement is not on the map. Both
     * end in "the marker set we drew is not the marker set on screen", which is
     * the outcome collision detection must not be allowed to produce silently.
     *
     * It also keeps the layers HONEST about each other: with placement enabled,
     * whether a billboard or the incident beside it survived would depend on
     * layer creation order, which is not a decision anyone made.
     */
    fun configure(manager: PointAnnotationManager) {
        manager.iconAllowOverlap = true
        manager.iconIgnorePlacement = true
    }
}
