package com.kungsbackacarcommunity.app.shell

import android.content.Context
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
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
     * Each approved logo version is registered once per Mapbox style. Markers
     * without a loaded logo share the original generic billboard fallback.
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
    ): Boolean {
        var complete = true
        val fallbackId = BillboardMarkerBitmaps.imageId()
        fun imageId(marker: MapBillboardMarker): String =
            if (marker.logoBitmap == null || marker.logoPath == null) fallbackId
            else "kcc-billboard-logo-" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(marker.logoPath.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val requested = markers.associateBy { imageId(it) }
        for ((id, marker) in requested) {
            if (id in registeredImages) continue
            val bitmap = if (style != null && context != null) BillboardMarkerBitmaps.create(
                density = context.resources.displayMetrics.density,
                logo = marker.logoBitmap,
            ) else null
            val added = bitmap != null && style != null && runCatching { style.addImage(id, bitmap) }.isSuccess
            if (added) registeredImages.add(id) else complete = false
        }
        runCatching { manager.deleteAll() }
        idsByAnnotation.clear()
        for (marker in markers) {
            val imageId = imageId(marker)
            if (imageId !in registeredImages) continue
            runCatching {
                val annotation =
                    manager.create(
                        PointAnnotationOptions()
                            .withPoint(Point.fromLngLat(marker.longitude, marker.latitude))
                            .withIconImage(imageId)
                            // BOTTOM, unlike every other layer on this map: this
                            // marker is a plaque standing on a post, so the
                            // post's foot is the coordinate. Centring it would
                            // bury the location under the plaque and throw away
                            // half of what makes the shape distinguishable from
                            // the three disc layers (see BillboardMarkerStyle).
                            .withIconAnchor(IconAnchor.BOTTOM),
                    )
                idsByAnnotation[annotation.id] = marker.id
            }
        }
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
