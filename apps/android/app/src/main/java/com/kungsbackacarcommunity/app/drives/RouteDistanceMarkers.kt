package com.kungsbackacarcommunity.app.drives

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A per-kilometre marker along a driven route: the interpolated position where
 * the cumulative driven distance first crossed [kilometer] × 1 km, tagged with
 * that whole-kilometre number (1, 2, 3, …). Pure data (no Android / Mapbox
 * types) so [RouteDistanceMarkers] is fully JVM-unit-testable.
 */
data class KmMarker(
    val latitude: Double,
    val longitude: Double,
    val kilometer: Int,
)

/**
 * Computes the "1 km / 2 km / …" markers Seb asked for on the History
 * full-screen route map ("markings for each km that was driven").
 *
 * The saved route stores only ordered [RoutePoint]s and a single total
 * [SavedDrive.distanceMeters] — there is NO per-point running distance — so the
 * marker positions are derived here client-side: walk consecutive points
 * accumulating great-circle (Haversine) distance, and every time the running
 * total first reaches an integer kilometre, interpolate the exact position along
 * the segment where that crossing happens.
 *
 * ## Why this is a pure object
 * The GL map (annotations, camera) can only be verified on a token-provisioned
 * device, so the ACTUAL logic — distance accumulation, boundary detection and
 * interpolation — lives here where a JVM unit test can assert marker count and
 * position against a hand-computed synthetic route. The popup map just draws
 * whatever this returns.
 *
 * ## Edge cases (never throws)
 * - Fewer than two points, or a total under 1 km: no markers (empty list).
 * - A zero-length or non-finite segment is skipped so it can neither divide by
 *   zero nor stall the walk.
 * - A single long segment that spans several kilometres emits every crossing it
 *   contains (e.g. a 3.4 km straight leg yields the 1/2/3 km markers).
 * - Long drives simply yield many markers; the count is bounded by the total
 *   distance, and [MAX_MARKERS] caps a pathological input so drawing can never
 *   be handed an unbounded list.
 */
object RouteDistanceMarkers {
    /** Mean Earth radius in metres (same value the backend distance calc uses). */
    private const val EARTH_RADIUS_M = 6_371_000.0

    private const val METERS_PER_KM = 1_000.0

    /**
     * Absolute cap on emitted markers, so a corrupt/absurd route (e.g. points
     * that claim to span the globe many times) can never produce an unbounded
     * annotation list. Comfortably above any real drive: 10 000 km of markers.
     */
    private const val MAX_MARKERS = 10_000

    /**
     * Returns the ordered per-kilometre markers for [points], or an empty list
     * when there is nothing to mark (see the class KDoc for every edge case).
     */
    fun markers(points: List<RoutePoint>): List<KmMarker> {
        if (points.size < 2) return emptyList()

        val result = ArrayList<KmMarker>()
        var cumulative = 0.0
        var nextKm = 1
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val segLen =
                haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            // Skip a degenerate segment (duplicate fix, or a non-finite coord):
            // it advances no distance and must not divide the interpolation by 0.
            if (!segLen.isFinite() || segLen <= 0.0) continue

            val segStart = cumulative
            val segEnd = cumulative + segLen
            // Emit one marker per integer-km boundary that falls inside this
            // segment. nextKm strictly increases, so the loop always terminates.
            while (nextKm * METERS_PER_KM <= segEnd) {
                val target = nextKm * METERS_PER_KM
                // Fraction of the way along this segment where the crossing lands.
                val t = ((target - segStart) / segLen).coerceIn(0.0, 1.0)
                result.add(
                    KmMarker(
                        latitude = a.latitude + (b.latitude - a.latitude) * t,
                        longitude = a.longitude + (b.longitude - a.longitude) * t,
                        kilometer = nextKm,
                    ),
                )
                nextKm++
                if (result.size >= MAX_MARKERS) return result
            }
            cumulative = segEnd
        }
        return result
    }

    /**
     * Great-circle distance in metres between two lat/lng points (Haversine).
     * Module-visible so a unit test can pin the distance model the marker
     * spacing is built on.
     */
    internal fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val sinDPhi = sin(dPhi / 2)
        val sinDLambda = sin(dLambda / 2)
        val h = sinDPhi * sinDPhi + cos(phi1) * cos(phi2) * sinDLambda * sinDLambda
        // clamp for numeric safety before asin (h can nudge just past 1.0).
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }
}
