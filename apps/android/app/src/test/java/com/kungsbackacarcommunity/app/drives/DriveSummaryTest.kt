package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the client-side end-of-session summary preview
 * ([DriveSummary]). Mirrors the backend Haversine + average-speed logic, so the
 * dialog can show distance/speed/duration before the authoritative server save.
 */
class DriveSummaryTest {

    private fun point(lat: Double, lon: Double, ts: Long) = RecordedPoint(lat, lon, ts)

    @Test
    fun `fewer than two points yields duration-only summary`() {
        val preview = DriveSummary.preview(listOf(point(57.0, 12.0, 0L)), elapsedMillis = 30_000L)
        assertNull(preview.distanceMeters)
        assertNull(preview.averageSpeedMetersPerSecond)
        assertEquals(30L, preview.durationSeconds)
    }

    @Test
    fun `empty points yields duration-only summary`() {
        val preview = DriveSummary.preview(emptyList(), elapsedMillis = 5_000L)
        assertNull(preview.distanceMeters)
        assertNull(preview.averageSpeedMetersPerSecond)
        assertEquals(5L, preview.durationSeconds)
    }

    @Test
    fun `duration seconds are rounded to match the backend, not floored`() {
        // Backend driveDurationSeconds uses Math.round(ms / 1000): 1500ms -> 2s
        // (a floor would give 1s), and 1499ms -> 1s.
        assertEquals(2L, DriveSummary.preview(emptyList(), elapsedMillis = 1_500L).durationSeconds)
        assertEquals(1L, DriveSummary.preview(emptyList(), elapsedMillis = 1_499L).durationSeconds)
        // Exact multiples are unaffected by rounding.
        assertEquals(30L, DriveSummary.preview(emptyList(), elapsedMillis = 30_000L).durationSeconds)
    }

    @Test
    fun `haversine is zero for identical points`() {
        assertEquals(0.0, DriveSummary.haversineMetres(57.0, 12.0, 57.0, 12.0), 1e-6)
    }

    @Test
    fun `known short distance is roughly correct`() {
        // ~0.001 deg latitude ≈ 111 m near these coords.
        val d = DriveSummary.haversineMetres(57.0000, 12.0, 57.0010, 12.0)
        assertTrue("expected ~111 m, got $d", d in 105.0..118.0)
    }

    @Test
    fun `accumulates distance across ordered points and derives average speed`() {
        val points =
            listOf(
                point(57.0000, 12.0, 0L),
                point(57.0010, 12.0, 10_000L),
                point(57.0020, 12.0, 20_000L),
            )
        val preview = DriveSummary.preview(points, elapsedMillis = 20_000L)
        val distance = preview.distanceMeters
        assertNotNull(distance)
        // Two ~111 m legs.
        assertTrue("expected ~222 m, got $distance", distance!! in 210.0..236.0)
        assertEquals(20L, preview.durationSeconds)
        // distance / duration.
        assertEquals(distance / 20.0, preview.averageSpeedMetersPerSecond!!, 1e-6)
    }

    @Test
    fun `implausible jump segment is excluded from distance`() {
        // Second point teleports ~1 deg (~111 km) in 1 s → far above the plausible
        // speed cap, so that segment is dropped and total distance stays 0.
        val points =
            listOf(
                point(57.0, 12.0, 0L),
                point(58.0, 12.0, 1_000L),
            )
        assertEquals(0.0, DriveSummary.totalDistanceMetres(points), 1e-6)
    }

    @Test
    fun `non-positive time delta segment is skipped`() {
        val points =
            listOf(
                point(57.0000, 12.0, 5_000L),
                point(57.0010, 12.0, 5_000L),
            )
        assertEquals(0.0, DriveSummary.totalDistanceMetres(points), 1e-6)
    }

    // --- topSpeedMetersPerSecond (client-side share-text figure) ---

    /** Latitude delta (degrees) that spans [metres] north at these latitudes. */
    private fun latOffsetForMetres(metres: Double): Double =
        metres / (6_371_000.0 * Math.PI / 180.0)

    @Test
    fun `top speed is null for fewer than two points`() {
        assertNull(DriveSummary.topSpeedMetersPerSecond(emptyList<RecordedPoint>()))
        assertNull(DriveSummary.topSpeedMetersPerSecond(listOf(point(57.0, 12.0, 0L))))
    }

    @Test
    fun `top speed returns the fastest plausible segment`() {
        // Leg 1: 200 m over 10 s = 20 m/s. Leg 2: 300 m over 10 s = 30 m/s.
        val lat0 = 57.0
        val lat1 = lat0 + latOffsetForMetres(200.0)
        val lat2 = lat1 + latOffsetForMetres(300.0)
        val points =
            listOf(
                point(lat0, 12.0, 0L),
                point(lat1, 12.0, 10_000L),
                point(lat2, 12.0, 20_000L),
            )
        val top = DriveSummary.topSpeedMetersPerSecond(points)
        assertNotNull(top)
        assertEquals(30.0, top!!, 0.2)
    }

    @Test
    fun `top speed excludes an implausible over-200kmh spike`() {
        // Leg 1 is a plausible 20 m/s; leg 2 implies ~300 m/s (well over the
        // 55.6 m/s ~200 km/h cap) — a GPS glitch that must NOT become top speed.
        val lat0 = 57.0
        val lat1 = lat0 + latOffsetForMetres(200.0)
        val lat2 = lat1 + latOffsetForMetres(300.0)
        val points =
            listOf(
                point(lat0, 12.0, 0L),
                point(lat1, 12.0, 10_000L),
                point(lat2, 12.0, 11_000L),
            )
        val top = DriveSummary.topSpeedMetersPerSecond(points)
        assertNotNull(top)
        // The spike is dropped, so the plausible 20 m/s leg wins.
        assertEquals(20.0, top!!, 0.2)
    }

    @Test
    fun `top speed is null when every segment is an implausible spike`() {
        // A single ~300 m/s leg between two points: filtered out, nothing left.
        val points =
            listOf(
                point(57.0, 12.0, 0L),
                point(57.0 + latOffsetForMetres(300.0), 12.0, 1_000L),
            )
        assertNull(DriveSummary.topSpeedMetersPerSecond(points))
    }

    @Test
    fun `top speed skips non-positive time deltas`() {
        // Both endpoints share a timestamp → no measurable segment.
        val points =
            listOf(
                point(57.0, 12.0, 5_000L),
                point(57.0 + latOffsetForMetres(100.0), 12.0, 5_000L),
            )
        assertNull(DriveSummary.topSpeedMetersPerSecond(points))
    }

    // --- topSpeedMetersPerSecond(List<RoutePoint>) overload ---
    // The History share text folds top speed straight over the DECODED route
    // points, so it no longer allocates a parallel List<RecordedPoint> (~20k
    // objects) on the UI thread as the route loads. The overload MUST return the
    // identical figure the old `.map { it.toRecordedPoint() }` path produced, or
    // the displayed top speed would silently change.

    private fun routePoint(lat: Double, lon: Double, ts: Long) = RoutePoint(lat, lon, ts)

    @Test
    fun `RoutePoint overload yields the identical value as the RecordedPoint path`() {
        val lat0 = 57.0
        val lat1 = lat0 + latOffsetForMetres(200.0)
        val lat2 = lat1 + latOffsetForMetres(300.0)
        val route =
            listOf(
                routePoint(lat0, 12.0, 0L),
                routePoint(lat1, 12.0, 10_000L),
                routePoint(lat2, 12.0, 20_000L),
            )
        // Old path: map every RoutePoint to a RecordedPoint, then fold.
        val viaRecordedList = DriveSummary.topSpeedMetersPerSecond(route.map { it.toRecordedPoint() })
        // New path: fold straight over the RoutePoints, no intermediate list.
        val viaRouteOverload = DriveSummary.topSpeedMetersPerSecond(route)
        assertEquals(viaRecordedList, viaRouteOverload)
        assertEquals(30.0, viaRouteOverload!!, 0.2)
    }

    @Test
    fun `RoutePoint overload is null for fewer than two points`() {
        assertNull(DriveSummary.topSpeedMetersPerSecond(emptyList<RoutePoint>()))
        assertNull(DriveSummary.topSpeedMetersPerSecond(listOf(routePoint(57.0, 12.0, 0L))))
    }

    @Test
    fun `RoutePoint overload applies the same spike filter as the RecordedPoint path`() {
        val lat0 = 57.0
        val lat1 = lat0 + latOffsetForMetres(200.0)
        val lat2 = lat1 + latOffsetForMetres(300.0)
        val route =
            listOf(
                routePoint(lat0, 12.0, 0L),
                routePoint(lat1, 12.0, 10_000L),
                routePoint(lat2, 12.0, 11_000L), // ~300 m/s glitch, must be dropped
            )
        val viaRecordedList = DriveSummary.topSpeedMetersPerSecond(route.map { it.toRecordedPoint() })
        val viaRouteOverload = DriveSummary.topSpeedMetersPerSecond(route)
        assertEquals(viaRecordedList, viaRouteOverload)
        // The spike is dropped either way, so the plausible 20 m/s leg wins.
        assertEquals(20.0, viaRouteOverload!!, 0.2)
    }

    // --- topSpeedPoint (top speed AND where it occurred, for the summary map) ---
    // Returns both the figure the stat row shows and the vertex the map marks, so
    // the two can never disagree. Same GPS-jump filter as topSpeedMetersPerSecond.

    @Test
    fun `top speed point is null for fewer than two points`() {
        assertNull(DriveSummary.topSpeedPoint(emptyList()))
        assertNull(DriveSummary.topSpeedPoint(listOf(routePoint(57.0, 12.0, 0L))))
    }

    @Test
    fun `top speed point is the fix ending the fastest segment`() {
        // Leg 1: 200 m over 10 s = 20 m/s. Leg 2: 300 m over 10 s = 30 m/s (top).
        val lat0 = 57.0
        val lat1 = lat0 + latOffsetForMetres(200.0)
        val lat2 = lat1 + latOffsetForMetres(300.0)
        val route =
            listOf(
                routePoint(lat0, 12.0, 0L),
                routePoint(lat1, 12.0, 10_000L),
                routePoint(lat2, 12.0, 20_000L),
            )
        val top = DriveSummary.topSpeedPoint(route)
        assertNotNull(top)
        assertEquals(30.0, top!!.metersPerSecond, 0.2)
        // Fastest segment is 1→2, so the marker is at index 2 (its end point).
        assertEquals(2, top.index)
        assertEquals(lat2, top.latitude, 1e-9)
        assertEquals(12.0, top.longitude, 1e-9)
        // The marked point is exactly the drawn route's vertex at that index.
        assertEquals(route[top.index].latitude, top.latitude, 0.0)
        assertEquals(route[top.index].longitude, top.longitude, 0.0)
        // The figure the stat row shows matches the scalar top-speed scan.
        assertEquals(DriveSummary.topSpeedMetersPerSecond(route)!!, top.metersPerSecond, 0.0)
    }

    @Test
    fun `top speed point excludes an implausible spike`() {
        // Leg 1 is a plausible 20 m/s; leg 2 implies ~300 m/s (a GPS jump). The
        // jump must NOT be chosen as the top-speed point.
        val lat0 = 57.0
        val lat1 = lat0 + latOffsetForMetres(200.0)
        val lat2 = lat1 + latOffsetForMetres(300.0)
        val route =
            listOf(
                routePoint(lat0, 12.0, 0L),
                routePoint(lat1, 12.0, 10_000L),
                routePoint(lat2, 12.0, 11_000L), // ~300 m/s glitch, must be dropped
            )
        val top = DriveSummary.topSpeedPoint(route)
        assertNotNull(top)
        // The plausible 20 m/s leg (1) wins, marked at its end point (index 1).
        assertEquals(20.0, top!!.metersPerSecond, 0.2)
        assertEquals(1, top.index)
    }

    @Test
    fun `top speed point is null when every segment is filtered out`() {
        // A single ~300 m/s leg between two points: filtered, nothing left.
        val route =
            listOf(
                routePoint(57.0, 12.0, 0L),
                routePoint(57.0 + latOffsetForMetres(300.0), 12.0, 1_000L),
            )
        assertNull(DriveSummary.topSpeedPoint(route))
    }

    // --- GPS spike rejection UNDER the absolute 200 km/h cap (the reported bug) ---
    // A position glitch on a real ~80 km/h drive can imply ~150 km/h for one
    // segment — comfortably under MAX_PLAUSIBLE_SPEED_MPS, so the absolute cap
    // waves it through and the max is inflated ~70 km/h. The acceleration guard
    // rejects it without clipping genuinely high, SUSTAINED speeds.

    /** Longitude delta (degrees) spanning [metres] east at latitude [atLat]. */
    private fun lonOffsetForMetres(metres: Double, atLat: Double): Double =
        metres / (6_371_000.0 * Math.PI / 180.0 * Math.cos(Math.toRadians(atLat)))

    @Test
    fun `a lone sub-200kmh spike is rejected from the top speed`() {
        // Real 20 m/s (72 km/h) cruise at a 5 s fix cadence, with ONE segment
        // implying 43 m/s (~155 km/h) — a GPS glitch well under the 55.6 m/s
        // absolute cap. Old behaviour: 43 m/s becomes the top speed (the bug).
        val lat0 = 57.0
        val lat1 = lat0 + latOffsetForMetres(100.0) // 100 m / 5 s = 20 m/s
        val lat2 = lat1 + latOffsetForMetres(215.0) // 215 m / 5 s = 43 m/s spike
        val lat3 = lat2 + latOffsetForMetres(100.0) // 100 m / 5 s = 20 m/s
        val points =
            listOf(
                point(lat0, 12.0, 0L),
                point(lat1, 12.0, 5_000L),
                point(lat2, 12.0, 10_000L),
                point(lat3, 12.0, 15_000L),
            )
        val top = DriveSummary.topSpeedMetersPerSecond(points)
        assertNotNull(top)
        // The 43 m/s spike is dropped; the real 20 m/s cruise stands.
        assertEquals(20.0, top!!, 0.3)
        // The marker agrees: it is NOT placed at the spike (index 2).
        val marker = DriveSummary.topSpeedPoint(points.map { RoutePoint(it.latitude, it.longitude, it.timestampMs) })
        assertNotNull(marker)
        assertEquals(top, marker!!.metersPerSecond, 0.0)
        assertTrue("marker must not sit on the spike segment", marker.index != 2)
    }

    @Test
    fun `a genuinely sustained high speed is kept`() {
        // Accelerate 20 -> 30 -> 40 m/s over 5 s steps, then hold 40 m/s
        // (144 km/h). Each step is plausible acceleration, so the true top is
        // kept — the guard must not clip a real fast drive.
        var lat = 57.0
        val ts = mutableListOf(0L)
        val lats = mutableListOf(lat)
        val speeds = listOf(20.0, 30.0, 40.0, 40.0) // per 5 s segment
        speeds.forEachIndexed { i, s ->
            lat += latOffsetForMetres(s * 5.0)
            lats += lat
            ts += (i + 1) * 5_000L
        }
        val points = lats.indices.map { point(lats[it], 12.0, ts[it]) }
        val top = DriveSummary.topSpeedMetersPerSecond(points)
        assertNotNull(top)
        assertEquals(40.0, top!!, 0.4)
    }

    @Test
    fun `a single-point sideways glitch is rejected on both of its segments`() {
        // A lone fix that jumps ~215 m EAST and back corrupts the TWO segments
        // touching it (out, then back) — both imply > 40 m/s while the real
        // drive is 20 m/s. Because a rejected segment does not advance the trust
        // anchor, both halves are measured against the real speed and rejected.
        val lat0 = 57.0
        val lat1 = lat0 + latOffsetForMetres(100.0)
        val lat3 = lat0 + latOffsetForMetres(200.0)
        val lat4 = lat0 + latOffsetForMetres(300.0)
        val points =
            listOf(
                point(lat0, 12.0, 0L),
                point(lat1, 12.0, 5_000L), // seg1: 20 m/s
                // Glitch: same latitude as p1 but flung ~215 m east.
                point(lat1, 12.0 + lonOffsetForMetres(215.0, 57.0), 10_000L),
                point(lat3, 12.0, 15_000L), // back on the real line
                point(lat4, 12.0, 20_000L), // seg4: 20 m/s
            )
        val top = DriveSummary.topSpeedMetersPerSecond(points)
        assertNotNull(top)
        // Neither inflated glitch segment (~43 and ~47 m/s) survives.
        assertEquals(20.0, top!!, 0.5)
    }

    @Test
    fun `top speed point keeps the first of two equally fast segments`() {
        // Two identical 20 m/s legs; a tie must resolve to the EARLIER segment,
        // whose end point is index 1 (not the later index 3).
        val lat0 = 57.0
        val lat1 = lat0 + latOffsetForMetres(200.0)
        val lat2 = lat1 + latOffsetForMetres(50.0) // slow filler leg (5 m/s)
        val lat3 = lat2 + latOffsetForMetres(200.0)
        val route =
            listOf(
                routePoint(lat0, 12.0, 0L),
                routePoint(lat1, 12.0, 10_000L), // 20 m/s
                routePoint(lat2, 12.0, 20_000L), // 5 m/s
                routePoint(lat3, 12.0, 30_000L), // 20 m/s (tie)
            )
        val top = DriveSummary.topSpeedPoint(route)
        assertNotNull(top)
        assertEquals(20.0, top!!.metersPerSecond, 0.2)
        assertEquals(1, top.index)
    }
}
