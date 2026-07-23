package com.kungsbackacarcommunity.app.incidents

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry behind the incident layer's VISIBLE-VIEWPORT query radius.
 *
 * The `incidents.listNearby` callable takes a centre + a radius. Historically the
 * client sent a FIXED 15 km radius regardless of zoom, so a street-level view
 * over-queried and a regional view left the screen's edges empty (anything past
 * 15 km from the centre was never fetched). This turns the radius into a function
 * of what is actually on screen.
 *
 * ## Why the CORNER distance (≈ half the visible diagonal), not the nearest edge
 * The task is to cover the WHOLE visible area with one circular query. The circle
 * is centred on the camera centre, so the farthest on-screen points are the
 * viewport CORNERS. Using the distance to the nearest EDGE would leave the four
 * corners of the screen outside the queried circle — reintroducing the exact
 * "empty edges" bug at every zoom level, not just past the 50 km cap. Using the
 * greatest centre-to-corner distance (about half the visible diagonal) guarantees
 * every visible point falls inside the queried circle. It over-queries the small
 * lune outside the rectangle's corners, which is cheap and bounded, in exchange
 * for never showing a hole on screen.
 *
 * The result is CLAMPED to the server's own bounds ([MIN_RADIUS_METERS],
 * [MAX_RADIUS_METERS], mirrored from `functions/src/incidents/incidents-core.ts`)
 * so the client never asks for a radius the backend would reject or silently
 * clamp: zoomed all the way in floors at 100 m, zoomed out past ~50 km caps at
 * 50 km (the far edges beyond 50 km simply do not populate — the server's hard
 * limit, accepted deliberately).
 */
object ViewportRadius {
    /**
     * Server floor, mirrored from `functions/src/incidents/incidents-core.ts`
     * (`MIN_RADIUS_METERS`). A radius below this is clamped up.
     */
    const val MIN_RADIUS_METERS: Double = 100.0

    /**
     * Server ceiling, mirrored from `functions/src/incidents/incidents-core.ts`
     * (`MAX_RADIUS_METERS`). A radius above this is clamped down; the map area
     * past 50 km from the centre is not fetched (the backend's hard limit).
     */
    const val MAX_RADIUS_METERS: Double = 50_000.0

    // IUGG mean Earth radius — the same figure Turf/Mapbox use, so client-side
    // distances line up with anything computed against the Mapbox geometry.
    private const val EARTH_RADIUS_METERS: Double = 6_371_008.8

    /**
     * Great-circle distance in metres between two lat/lng points (Haversine).
     * Public so the requery decision can reuse the one distance function rather
     * than growing a second, subtly-different one.
     */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val sinDPhi = sin(dPhi / 2.0)
        val sinDLambda = sin(dLambda / 2.0)
        val a = sinDPhi * sinDPhi + cos(phi1) * cos(phi2) * sinDLambda * sinDLambda
        // clamp for float noise so asin's domain is never exceeded near a==1.
        val c = 2.0 * asin(min(1.0, sqrt(a)))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * The query radius that covers the visible viewport whose axis-aligned bounds
     * are [swLat]/[swLon]..[neLat]/[neLon], as seen from camera centre
     * [centerLat]/[centerLon]: the greatest centre-to-corner distance (≈ half the
     * visible diagonal), CLAMPED to [MIN_RADIUS_METERS]..[MAX_RADIUS_METERS].
     *
     * All four corners of the axis-aligned bounds are measured (not just the two
     * bounds corners) and the max taken, so a centre that is not the exact
     * midpoint — a rotated/pitched camera hands back a bounding box wider than the
     * on-screen quad — still yields a radius that reaches the farthest visible
     * point.
     */
    fun radiusMetersForBounds(
        centerLat: Double,
        centerLon: Double,
        swLat: Double,
        swLon: Double,
        neLat: Double,
        neLon: Double,
    ): Double {
        val maxCornerMeters =
            maxOf(
                haversineMeters(centerLat, centerLon, swLat, swLon), // SW
                haversineMeters(centerLat, centerLon, neLat, neLon), // NE
                haversineMeters(centerLat, centerLon, neLat, swLon), // NW
                haversineMeters(centerLat, centerLon, swLat, neLon), // SE
            )
        return maxCornerMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
    }
}
