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
        assertNull(DriveSummary.topSpeedMetersPerSecond(emptyList()))
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
}
