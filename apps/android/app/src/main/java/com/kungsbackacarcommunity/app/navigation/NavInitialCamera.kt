package com.kungsbackacarcommunity.app.navigation

import com.kungsbackacarcommunity.app.location.LastKnownLocation
import com.kungsbackacarcommunity.app.map.MapCameraPosition
import com.kungsbackacarcommunity.app.map.MapMarkers

/**
 * Decides where a map's camera should OPEN before the first GPS fix arrives.
 *
 * The turn-by-turn map (and the map home) used to start at Mapbox's default
 * world camera (lng/lat 0,0, zoom 0) and only ease to the puck once the first
 * fix landed — so navigation opened on a whole-globe view and then flew in,
 * which read as a slow, disorienting animation. This resolves the INITIAL
 * camera synchronously instead:
 *
 *  - a cached last-known location → open there at street level, so the map is
 *    already framed on the user; the first real fix then only nudges the centre;
 *  - no cache (fresh install / permission never granted) → fall back to
 *    Kungsbacka, the community's home town, at the shared default zoom.
 *
 * Pure and JVM-unit-testable: no Android or Mapbox types.
 */
object NavInitialCamera {

    /**
     * Street-level zoom used when we DO have a cached position, so a cached fix
     * opens close to the user rather than surveying the region. Matches the map
     * home's own-marker focus zoom.
     */
    const val CACHED_ZOOM: Double = MapMarkers.OWN_MARKER_ZOOM

    /**
     * Resolve the opening camera: the cached fix at [CACHED_ZOOM] when present,
     * otherwise the Kungsbacka default ([MapMarkers.DEFAULT_CAMERA], lng 12.0757,
     * lat 57.4874, zoom 15).
     */
    fun resolve(cached: LastKnownLocation?): MapCameraPosition =
        if (cached != null) {
            MapCameraPosition(
                longitude = cached.longitude,
                latitude = cached.latitude,
                zoom = CACHED_ZOOM,
            )
        } else {
            MapMarkers.DEFAULT_CAMERA
        }
}
