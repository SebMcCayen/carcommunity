package com.kungsbackacarcommunity.app.map

import org.junit.Assert.*
import org.junit.Test

class DriverMotionTimelineTest {
    private val point = DriverMotionPoint(57.0, 12.0, 0.0)

    @Test fun `normal three second updates move continuously with a short presentation budget`() {
        val motion = DriverMotionTimeline()
        motion.update(point, 10_000L, 0L, 0L, 10.0, 0.0)
        assertEquals(point, motion.frame(250L))
        var previous = requireNotNull(motion.frame(250L))
        for (time in 300L..3_000L step 50L) {
            val frame = requireNotNull(motion.frame(time))
            assertTrue("moving between publications at $time", frame.latitude > previous.latitude)
            previous = frame
        }
        val next = DriverMotionPoint(point.latitude + Math.toDegrees(30.0 / 6_371_000.0), point.longitude, 0.0)
        val before = motion.frame(3_000L)
        motion.update(next, 13_000L, 0L, 3_000L, 10.0, 0.0)
        assertEquals("retarget cannot teleport", before, motion.frame(3_000L))
        assertEquals(next.latitude, requireNotNull(motion.frame(3_250L)).latitude, 1e-9)
        assertTrue(requireNotNull(motion.frame(3_300L)).latitude > next.latitude)
    }

    @Test fun `self nearby and convoy use identical timeline results`() {
        val tracks = List(3) { DriverMotionTimeline() }
        tracks.forEach { it.update(point, 1_000L, 100L, 100L, 20.0, 90.0) }
        for (time in 100L..4_000L step 16L) {
            val frames = tracks.map { it.frame(time) }
            assertEquals(frames[0], frames[1])
            assertEquals(frames[0], frames[2])
        }
        assertEquals(250L, DriverMotionPolicy.PRESENTATION_DELAY_MS)
    }

    @Test fun `old packets cannot start a fresh prediction window`() {
        val old = DriverMotionTimeline()
        old.update(point, 1_000L, 20_000L, 30_000L, 20.0, 0.0)
        val first = old.frame(30_000L)
        assertEquals(point, first)
        assertEquals(first, old.frame(60_000L))
        assertFalse(old.isAnimating(30_000L))
        val bounded = DriverMotionTimeline()
        bounded.update(point, 1_000L, 0L, 0L, 20.0, 0.0)
        assertEquals(bounded.frame(3_750L), bounded.frame(60_000L))
        assertFalse(bounded.isAnimating(3_750L))
    }

    @Test fun `a fresh explicit stop ends prediction after the correction window`() {
        val motion = DriverMotionTimeline()
        motion.update(point, 1_000L, 0L, 0L, 20.0, 0.0)
        val stop = DriverMotionPoint(57.0001, 12.0, 0.0)
        motion.frame(2_000L)
        motion.update(stop, 3_000L, 0L, 2_000L, 0.0, 0.0)
        assertEquals(stop, motion.frame(2_250L))
        assertEquals(stop, motion.frame(30_000L))
        assertFalse(motion.isAnimating(2_250L))
    }

    @Test fun `out of order duplicate and invalid samples cannot rewind the marker`() {
        val motion = DriverMotionTimeline()
        motion.update(point, 10L, 0L, 0L)
        assertFalse(motion.update(point.copy(latitude = 58.0), 10L, 0L, 10L))
        assertFalse(motion.update(point.copy(latitude = 58.0), 9L, 0L, 10L))
        assertFalse(motion.update(point.copy(latitude = Double.NaN), 11L, 0L, 10L))
        assertEquals(point, motion.frame(1_000L))
        assertEquals(motion.frame(1_000L), motion.frame(500L))
    }

    @Test fun `north and antimeridian interpolation take the shortest arc`() {
        val a = DriverMotionPoint(0.0, 179.0, 359.0)
        val b = DriverMotionPoint(0.0, -179.0, 1.0)
        val halfway = DriverMotionTimeline.interpolate(a, b, .5)
        assertEquals(-180.0, halfway.longitude, 1e-9)
        assertEquals(0.0, halfway.bearing!!, 1e-9)
    }

    @Test fun `turn corrections do not teleport and then follow the new course`() {
        val motion = DriverMotionTimeline()
        motion.update(point, 1_000L, 0L, 0L, 10.0, 0.0)
        val before = motion.frame(3_000L)
        val corner = DriverMotionPoint(57.00027, 12.0, 90.0)
        motion.update(corner, 4_000L, 0L, 3_000L, 10.0, 90.0)
        assertEquals(before, motion.frame(3_000L))
        val east = requireNotNull(motion.frame(3_500L))
        assertTrue(east.longitude > corner.longitude)
        assertEquals(90.0, east.bearing!!, 1e-9)
    }

    @Test fun `undated future dated and implausible velocities do not fabricate movement`() {
        for ((recordedAt, age, speed) in listOf(Triple(null, 0L, 10.0), Triple(1L, -1_000L, 10.0), Triple(1L, 0L, 500.0))) {
            val motion = DriverMotionTimeline()
            motion.update(point, recordedAt, age, 0L, speed, 0.0)
            assertEquals(point, motion.frame(5_000L))
            assertFalse(motion.isAnimating(5_000L))
        }
    }
}
