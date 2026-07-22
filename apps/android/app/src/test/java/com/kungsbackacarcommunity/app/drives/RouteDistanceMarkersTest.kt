package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-km marker positions are what the History full-screen map draws, and
 * the GL layer can only be checked on-device, so the accumulation +
 * interpolation logic is pinned here against hand-built synthetic routes.
 *
 * Routes are built along the equator, where great-circle distance is linear in
 * longitude, so a km boundary's expected longitude is simply
 * `metersToDegrees(km × 1000)` — derived from the util's own Haversine so the
 * fixtures never drift from the distance model under test.
 */
class RouteDistanceMarkersTest {

    // Metres per one degree of longitude at the equator (≈ 111 195 m), from the
    // util's own Haversine model so the fixtures track the code under test.
    private val metersPerDegree = RouteDistanceMarkers.haversineMeters(0.0, 0.0, 0.0, 1.0)

    private fun lngForMeters(meters: Double): Double = meters / metersPerDegree

    /** An equator route: one point every [stepMeters] out to [totalMeters]. */
    private fun equatorRoute(totalMeters: Double, stepMeters: Double): List<RoutePoint> {
        val points = ArrayList<RoutePoint>()
        var d = 0.0
        var t = 0L
        while (d <= totalMeters + 1e-6) {
            points.add(RoutePoint(latitude = 0.0, longitude = lngForMeters(d), timestampMs = t))
            d += stepMeters
            t += 1000
        }
        return points
    }

    @Test
    fun emptyRoute_hasNoMarkers() {
        assertEquals(emptyList<KmMarker>(), RouteDistanceMarkers.markers(emptyList()))
    }

    @Test
    fun singlePoint_hasNoMarkers() {
        val one = listOf(RoutePoint(0.0, 0.0, 0))
        assertEquals(emptyList<KmMarker>(), RouteDistanceMarkers.markers(one))
    }

    @Test
    fun driveUnderOneKm_hasNoMarkers() {
        // ~700 m total: below the first km boundary, so nothing to mark.
        val route = equatorRoute(totalMeters = 700.0, stepMeters = 100.0)
        assertTrue(RouteDistanceMarkers.markers(route).isEmpty())
    }

    @Test
    fun exactlyUnderTwoKm_marksOnlyFirstKm() {
        // ~1.9 km: crosses 1 km but not 2 km.
        val route = equatorRoute(totalMeters = 1_900.0, stepMeters = 100.0)
        val markers = RouteDistanceMarkers.markers(route)
        assertEquals(1, markers.size)
        assertEquals(1, markers[0].kilometer)
    }

    @Test
    fun multiKmRoute_marksEachKilometreInOrderAtExpectedPositions() {
        // 3.5 km, 350 m spacing so no boundary lands exactly on a sample point.
        val route = equatorRoute(totalMeters = 3_500.0, stepMeters = 350.0)
        val markers = RouteDistanceMarkers.markers(route)

        assertEquals(listOf(1, 2, 3), markers.map { it.kilometer })
        for (marker in markers) {
            // On the equator the latitude stays 0 and the longitude is the
            // linear position of that km boundary.
            assertEquals(0.0, marker.latitude, 1e-9)
            assertEquals(lngForMeters(marker.kilometer * 1_000.0), marker.longitude, 1e-4)
        }
    }

    @Test
    fun singleLongSegment_emitsEveryBoundaryItSpans() {
        // Just two points 3.4 km apart: all three km marks fall inside the one
        // segment and must still be interpolated onto it.
        val route =
            listOf(
                RoutePoint(0.0, 0.0, 0),
                RoutePoint(0.0, lngForMeters(3_400.0), 1000),
            )
        val markers = RouteDistanceMarkers.markers(route)
        assertEquals(listOf(1, 2, 3), markers.map { it.kilometer })
        assertEquals(lngForMeters(2_000.0), markers[1].longitude, 1e-4)
    }

    @Test
    fun duplicateAndDegeneratePoints_doNotCrashOrDoubleCount() {
        // Interleave duplicate (zero-length) fixes; they advance no distance and
        // must be skipped without stalling or emitting spurious markers.
        val route =
            listOf(
                RoutePoint(0.0, 0.0, 0),
                RoutePoint(0.0, 0.0, 1000), // duplicate
                RoutePoint(0.0, lngForMeters(1_200.0), 2000),
                RoutePoint(0.0, lngForMeters(1_200.0), 3000), // duplicate
                RoutePoint(0.0, lngForMeters(2_100.0), 4000),
            )
        val markers = RouteDistanceMarkers.markers(route)
        assertEquals(listOf(1, 2), markers.map { it.kilometer })
    }

    /** A single equator segment [meters] long (two points), for the cap tests. */
    private fun longEquatorSegment(meters: Double): List<RoutePoint> =
        listOf(
            RoutePoint(0.0, 0.0, 0),
            RoutePoint(0.0, lngForMeters(meters), 1000),
        )

    @Test
    fun routeJustUnderCap_keepsOneKmInterval() {
        // 450 km: 450 per-km markers is under the 500 cap, so the interval stays
        // at 1 km (markers at 1, 2, 3, …).
        val markers = RouteDistanceMarkers.markers(longEquatorSegment(450_000.0))
        assertEquals(1, markers.first().kilometer)
        assertEquals(2, markers[1].kilometer)
        assertTrue("expected ~450 markers, got ${markers.size}", markers.size in 449..450)
    }

    @Test
    fun routeOverCap_coarsensIntervalButStaysEvenlySpaced() {
        // 600 km: 600 per-km markers would exceed the 500 cap, so the interval
        // steps up to 2 km — markers at 2, 4, 6, … km, ~300 of them.
        val markers = RouteDistanceMarkers.markers(longEquatorSegment(600_000.0))
        assertTrue("count must stay within the cap", markers.size <= 500)
        val kms = markers.map { it.kilometer }
        // Evenly spaced on round kilometres, coarser than 1 km.
        val interval = kms.first()
        assertTrue("interval should have coarsened past 1 km", interval > 1)
        assertEquals(interval, 2)
        for (i in 1 until kms.size) {
            assertEquals(interval, kms[i] - kms[i - 1])
        }
    }

    @Test
    fun globeSpanningCorruptRoute_staysUnderCapAndEvenlySpaced() {
        // ~19 900 km (near half the equator): a pathological/corrupt length. The
        // interval coarsens hard so the drawable count never explodes, yet the
        // markers stay evenly spaced rather than abruptly stopping.
        val markers = RouteDistanceMarkers.markers(longEquatorSegment(19_900_000.0))
        assertTrue("must never exceed the drawable cap", markers.size <= 500)
        assertTrue("a long route should still produce markers", markers.isNotEmpty())
        val kms = markers.map { it.kilometer }
        val interval = kms.first()
        assertTrue("interval must coarsen well past 1 km", interval > 1)
        for (i in 1 until kms.size) {
            assertEquals(interval, kms[i] - kms[i - 1])
        }
    }
}
