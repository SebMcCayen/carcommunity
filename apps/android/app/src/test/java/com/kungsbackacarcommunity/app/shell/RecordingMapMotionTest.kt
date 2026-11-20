package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMapMotionTest {
    private val motion = RecordingMapMotion()
    private val a = MapPoint(12.0, 57.0)
    private val b = MapPoint(12.0, 57.0002)
    private val c = MapPoint(12.0002, 57.0002)

    private fun fix(point: MapPoint, count: Int, elapsed: Long) = MapRecordingPosition(
        point = point,
        elapsedRealtimeMs = elapsed,
        accuracyMeters = 5.0,
        speedMps = 10.0,
        pointCount = count,
    )

    private fun frame(at: Long): RecordingMapFrame = requireNotNull(motion.frame(at)).also {
        assertEquals("line tip must be the exact puck/camera input", it.point, it.trail.last())
    }

    private fun assertPoint(expected: MapPoint, actual: MapPoint) {
        assertEquals(expected.longitude, actual.longitude, 1e-9)
        assertEquals(expected.latitude, actual.latitude, 1e-9)
    }

    @Test
    fun `first current fix snaps and normal samples have continuous intermediate frames`() {
        motion.update(fix(a, 1, 1_000L), listOf(a), 0.0, 0L)
        assertEquals(a, frame(0L).point)
        assertFalse(frame(0L).animating)
        motion.update(fix(b, 2, 3_000L), listOf(a, b), 0.0, 2_000L)

        for (elapsed in listOf(0L, 50L, 125L, 200L, 250L)) {
            val rendered = frame(2_000L + elapsed)
            assertPoint(MapPoint(a.longitude, a.latitude + (b.latitude - a.latitude) * elapsed / 250.0), rendered.point)
            assertEquals(elapsed < 250L, rendered.animating)
            assertTrue(rendered.trail.size <= 2)
        }
        assertEquals(b, frame(50_000L).point) // No dead reckoning after GPS stops.
        assertFalse(frame(50_000L).animating)
    }

    @Test
    fun `coalesced accepted fixes retain their corner and never draw future vertices`() {
        motion.update(fix(a, 1, 1_000L), listOf(a), 0.0, 0L)
        val accepted = listOf(a, b, c)
        motion.update(fix(c, 3, 5_000L), accepted, 90.0, 4_000L)

        val approach = frame(4_062L)
        assertEquals(a.longitude, approach.point.longitude, 1e-9)
        assertTrue(approach.point.latitude > a.latitude && approach.point.latitude < b.latitude)
        assertEquals(listOf(a, approach.point), approach.trail)
        val exit = frame(4_188L)
        assertEquals(b.latitude, exit.point.latitude, 1e-9)
        assertTrue(exit.point.longitude > b.longitude && exit.point.longitude < c.longitude)
        assertEquals(listOf(a, b, exit.point), exit.trail)
        assertEquals(accepted, frame(4_250L).trail)
        assertEquals(listOf(a, b, c), accepted)
    }

    @Test
    fun `retargeting in flight starts at displayed position and still visits old target`() {
        motion.update(fix(a, 1, 1_000L), listOf(a), 0.0, 0L)
        motion.update(fix(b, 2, 3_000L), listOf(a, b), 0.0, 2_000L)
        val before = frame(2_125L)
        motion.update(fix(c, 3, 5_000L), listOf(a, b, c), 90.0, 2_125L)
        assertEquals(before.point, frame(2_125L).point)
        assertEquals(before.trail, frame(2_125L).trail)

        for (at in 2_150L..2_375L step 25L) {
            val rendered = frame(at)
            assertTrue("interpolated point must stay on one of the accepted legs",
                kotlin.math.abs(rendered.point.longitude - a.longitude) < 1e-9 ||
                    kotlin.math.abs(rendered.point.latitude - b.latitude) < 1e-9)
            if (rendered.point.longitude > b.longitude + 1e-9) assertTrue(b in rendered.trail)
        }
        assertEquals(c, frame(2_375L).point)
    }

    @Test
    fun `bearing takes shortest turn across north using the same display fraction`() {
        motion.update(fix(a, 1, 1_000L), listOf(a), 359.0, 0L)
        motion.update(fix(b, 2, 3_000L), listOf(a, b), 1.0, 2_000L)
        assertEquals(359.0, frame(2_000L).bearingDegrees!!, 1e-9)
        assertEquals(0.0, frame(2_125L).bearingDegrees!!, 1e-9)
        assertEquals(1.0, frame(2_250L).bearingDegrees!!, 1e-9)
    }

    @Test
    fun `timer duplicates and out of order timestamps cannot restart animation`() {
        motion.update(fix(a, 1, 1_000L), listOf(a), 0.0, 0L)
        val second = fix(b, 2, 3_000L)
        motion.update(second, listOf(a, b), 0.0, 2_000L)
        frame(2_125L)
        motion.update(second, listOf(a, b), 0.0, 2_125L)
        motion.update(second.copy(elapsedRealtimeMs = 2_999L), listOf(a, b), 240.0, 2_170L)
        assertPoint(MapPoint(a.longitude, (a.latitude + b.latitude) / 2), frame(2_125L).point)
        assertEquals(0.0, frame(2_125L).bearingDegrees!!, 0.0)
        assertEquals(b, frame(2_250L).point)
        assertFalse(frame(2_250L).animating)
    }

    @Test
    fun `identical stationary points and unchanged course require no frame loop`() {
        motion.update(fix(a, 1, 1_000L), listOf(a), 45.0, 0L)
        motion.update(fix(a, 2, 3_000L), listOf(a, a), 45.0, 2_000L)
        assertEquals(a, frame(2_000L).point)
        assertFalse(frame(2_000L).animating)
    }

    @Test
    fun `raw or display clock gaps snap to current accepted position`() {
        motion.update(fix(a, 1, 1_000L), listOf(a), 0.0, 0L)
        motion.update(fix(b, 2, 7_000L), listOf(a, b), 90.0, 6_000L)
        assertEquals(b, frame(6_000L).point)
        assertFalse(frame(6_000L).animating)
        motion.update(fix(c, 3, 9_000L), listOf(a, b, c), 90.0, 12_000L)
        assertEquals(c, frame(12_000L).point)
        assertFalse(frame(12_000L).animating)
    }

    @Test
    fun `distance discontinuity does not animate or join an unknown road`() {
        val far = MapPoint(12.0, 57.01)
        motion.update(fix(a, 1, 1_000L), listOf(a), 0.0, 0L)
        motion.update(fix(far, 2, 3_000L), listOf(a, far), 0.0, 2_000L)
        assertEquals(far, frame(2_000L).point)
        assertEquals(listOf(far), frame(2_000L).trail)
        assertFalse(frame(2_000L).animating)
    }

    @Test
    fun `reset and recording point count restart cannot replay old motion`() {
        motion.update(fix(a, 1, 1_000L), listOf(a), 0.0, 0L)
        motion.update(fix(b, 2, 3_000L), listOf(a, b), 90.0, 2_000L)
        motion.reset()
        assertNull(motion.frame(2_500L))
        motion.update(fix(c, 20, 5_000L), listOf(b, c), 180.0, 3_000L)
        assertEquals(c, frame(3_000L).point)
        assertFalse(frame(3_000L).animating)
        motion.update(fix(a, 1, 6_000L), listOf(a), null, 4_000L)
        assertEquals(a, frame(4_000L).point)
        assertNull(frame(4_000L).bearingDegrees)
        assertEquals(listOf(a), frame(4_000L).trail)
    }

    @Test
    fun `missing or inconsistent route metadata falls back to an immediate current fix`() {
        motion.update(fix(a, 0, 1_000L), listOf(a), 0.0, 0L)
        motion.update(fix(b, 0, 3_000L), listOf(a, b), 0.0, 2_000L)
        assertEquals(b, frame(2_000L).point)
        assertFalse(frame(2_000L).animating)
        motion.update(fix(c, 3, 5_000L), listOf(a, b), 0.0, 4_000L)
        assertEquals(c, frame(4_000L).point)
        assertEquals(listOf(c), frame(4_000L).trail)
        assertFalse(frame(4_000L).animating)
    }

    @Test
    fun `rolling recent buffer retains the global in flight cursor`() {
        val d = MapPoint(12.0004, 57.0002)
        val e = MapPoint(12.0006, 57.0002)
        motion.update(fix(c, 3, 5_000L), listOf(a, b, c), 90.0, 0L)
        motion.update(fix(d, 4, 7_000L), listOf(b, c, d), 90.0, 2_000L)
        val before = frame(2_125L)
        motion.update(fix(e, 5, 9_000L), listOf(c, d, e), 90.0, 2_125L)
        assertEquals(before.point, frame(2_125L).point)
        assertEquals(listOf(c, before.point), frame(2_125L).trail)
        assertEquals(listOf(c, d, e), frame(2_375L).trail)
    }

    @Test
    fun `repeated loop coordinates cannot rewind the accepted route prefix`() {
        motion.update(fix(a, 1, 1_000L), listOf(a), 0.0, 0L)
        motion.update(fix(b, 2, 3_000L), listOf(a, b), 0.0, 2_000L)
        frame(4_000L)
        motion.update(fix(a, 3, 5_000L), listOf(a, b, a), 180.0, 4_000L)
        frame(6_000L)
        motion.update(fix(c, 4, 7_000L), listOf(a, b, a, c), 45.0, 6_000L)
        val midway = frame(7_000L)
        assertEquals(listOf(a, b, a, midway.point), midway.trail)
        assertEquals(listOf(a, b, a, c), frame(8_000L).trail)
    }

    @Test
    fun `historical prefix is bounded without mutating accepted input lists`() {
        val accepted = (0 until 3_000).map { MapPoint(12.0, 57.0 + it * 0.00001) }.toMutableList()
        val original = accepted.toList()
        motion.update(fix(accepted.last(), 3_000, 1_000L), accepted, 0.0, 0L)
        val next = MapPoint(12.0, 57.03)
        val updated = (accepted.drop(1) + next).toMutableList()
        motion.update(fix(next, 3_001, 3_000L), updated, 0.0, 2_000L)
        val midway = frame(3_000L)
        assertTrue(midway.trail.size < 1_000)
        assertTrue(midway.trail.size > 800)
        assertEquals(original, accepted)
        updated.clear() // The cached render route owns its copy.
        assertEquals(next, frame(4_000L).point)
        assertTrue(frame(4_000L).trail.isNotEmpty())
    }
}
