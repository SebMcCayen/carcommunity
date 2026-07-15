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
}
