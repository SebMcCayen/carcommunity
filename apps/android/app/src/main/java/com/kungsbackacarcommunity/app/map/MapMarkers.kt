package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.live.LiveMarker

/**
 * Pure map/camera logic for the Map slice (Phase 12 slice 7 + live-markers
 * follow-up).
 *
 * No Android or Mapbox imports so it is JVM-unit-testable and reused by the
 * screen. Holds the default camera used before/without a live fix, the marker
 * model, and the rules for turning raw live positions into that model.
 *
 * Markers are built from per-uid `liveLocation/{uid}/latest` reads only — there
 * is NO collection scan (the RTDB rules grant per-uid reads only). The caller's
 * OWN marker is drawn in the primary colour; other members (e.g. a group-drive
 * roster) in the secondary colour. Members who stopped sharing have a null
 * marker and are simply dropped.
 */

/** A lng/lat position plus zoom — enough to drive a Mapbox camera. */
data class MapCameraPosition(
    val longitude: Double,
    val latitude: Double,
    val zoom: Double,
)

/** Whether a marker is the caller's own position or another member's. */
enum class MapMarkerKind { OWN, OTHER }

/**
 * A single map marker. [kind] selects the colour (own vs other) and [uid]/
 * [displayName] carry identity for future labelling; only lng/lat are needed to
 * draw the circle.
 */
data class MapMarker(
    val longitude: Double,
    val latitude: Double,
    val kind: MapMarkerKind = MapMarkerKind.OWN,
    val uid: String? = null,
    val displayName: String? = null,
)

object MapMarkers {
    /**
     * Default camera when no live fix is available: centered on Kungsbacka
     * (the community's home town) at a neighbourhood-level zoom so the map
     * opens close to the user rather than surveying the whole town. Longitude
     * first to match Mapbox's lng/lat ordering.
     */
    val DEFAULT_CAMERA: MapCameraPosition =
        MapCameraPosition(longitude = 12.0757, latitude = 57.4874, zoom = 15.0)

    /** Zoom used once we have a position to focus on (street/neighbourhood level). */
    const val OWN_MARKER_ZOOM: Double = 16.0

    /**
     * Marker model for the caller's own position, or null when no coordinate
     * is available (nothing to draw). Kept trivial and total so the screen can
     * call it unconditionally.
     */
    fun ownMarker(longitude: Double?, latitude: Double?): MapMarker? {
        if (longitude == null || latitude == null) return null
        return MapMarker(longitude = longitude, latitude = latitude, kind = MapMarkerKind.OWN)
    }

    /** Maps a live [LiveMarker] to a map [MapMarker] of the given [kind]. */
    fun markerOf(marker: LiveMarker?, kind: MapMarkerKind): MapMarker? {
        marker ?: return null
        return MapMarker(
            longitude = marker.longitude,
            latitude = marker.latitude,
            kind = kind,
            uid = marker.uid,
            displayName = marker.displayName,
        )
    }

    /**
     * Builds the ordered marker list to draw: the caller's own marker first
     * (when present), then every other member who is currently sharing. Null
     * entries (members who stopped sharing / never had a fix) are dropped, and
     * the own uid is never duplicated among the others.
     */
    fun markers(own: LiveMarker?, others: List<LiveMarker?>): List<MapMarker> {
        val ownMarker = markerOf(own, MapMarkerKind.OWN)
        val ownUid = own?.uid
        val otherMarkers =
            others
                .asSequence()
                .filterNotNull()
                .filter { it.uid != ownUid }
                .distinctBy { it.uid }
                .mapNotNull { markerOf(it, MapMarkerKind.OTHER) }
                .toList()
        return if (ownMarker != null) listOf(ownMarker) + otherMarkers else otherMarkers
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

    /**
     * Camera for a whole marker list: focus on the caller's own marker when it
     * is present (it is always first), else the first available other marker,
     * else the default town camera. A single focus point (not a bounds fit)
     * keeps this Mapbox-free and deterministic for tests; the own position is
     * the natural centre when the caller is sharing.
     */
    fun cameraForMarkers(markers: List<MapMarker>): MapCameraPosition {
        val focus = markers.firstOrNull { it.kind == MapMarkerKind.OWN } ?: markers.firstOrNull()
        return cameraFor(focus)
    }
}
