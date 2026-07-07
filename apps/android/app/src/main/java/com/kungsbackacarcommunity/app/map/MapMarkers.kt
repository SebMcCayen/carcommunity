package com.kungsbackacarcommunity.app.map

/**
 * Pure map/camera logic for the Map slice (Phase 12 slice 7).
 *
 * No Android or Mapbox imports so it is JVM-unit-testable and reused by the
 * screen. Holds the default camera used before/without a live fix, the marker
 * model, and the rule for turning a raw position into that model.
 *
 * The multi-member marker feed is deliberately OUT OF SCOPE here: the live
 * RTDB rules grant only a per-uid `liveLocation/{uid}/latest` read (no
 * collection scan), so this slice renders the caller's OWN marker only. A
 * follow-up will add per-uid reads for other members.
 */

/** A lng/lat position plus zoom — enough to drive a Mapbox camera. */
data class MapCameraPosition(
    val longitude: Double,
    val latitude: Double,
    val zoom: Double,
)

/** A single map marker (currently only the caller's own position). */
data class MapMarker(
    val longitude: Double,
    val latitude: Double,
)

object MapMarkers {
    /**
     * Default camera when no live fix is available: centered on Kungsbacka
     * (the community's home town) at a town-level zoom. Longitude first to
     * match Mapbox's lng/lat ordering.
     */
    val DEFAULT_CAMERA: MapCameraPosition =
        MapCameraPosition(longitude = 12.0757, latitude = 57.4874, zoom = 11.0)

    /** Zoom used once we have the caller's own position to focus on. */
    const val OWN_MARKER_ZOOM: Double = 14.0

    /**
     * Marker model for the caller's own position, or null when no coordinate
     * is available (nothing to draw). Kept trivial and total so the screen can
     * call it unconditionally.
     */
    fun ownMarker(longitude: Double?, latitude: Double?): MapMarker? {
        if (longitude == null || latitude == null) return null
        return MapMarker(longitude = longitude, latitude = latitude)
    }

    /**
     * Camera to show for the given own-position marker: focus on it when
     * present, otherwise fall back to [DEFAULT_CAMERA]. Longitude/latitude are
     * carried through unchanged; only the zoom tightens when we have a fix.
     */
    fun cameraFor(marker: MapMarker?): MapCameraPosition =
        if (marker == null) {
            DEFAULT_CAMERA
        } else {
            MapCameraPosition(
                longitude = marker.longitude,
                latitude = marker.latitude,
                zoom = OWN_MARKER_ZOOM,
            )
        }
}
