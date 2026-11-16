package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingCourseBearingTest {
    private val course = RecordingCourseBearing()

    private fun fix(
        at: Long = 10_000L,
        east: Double = 0.0,
        north: Double = 0.0,
        bearing: Double? = null,
        speed: Double? = 10.0,
        accuracy: Double = 5.0,
        bearingAccuracy: Double? = null,
    ) = MapRecordingPosition(
        point = MapPoint(longitude = 12.08 + east, latitude = 57.48 + north),
        elapsedRealtimeMs = at,
        accuracyMeters = accuracy,
        bearingDegrees = bearing,
        speedMps = speed,
        bearingAccuracyDegrees = bearingAccuracy,
    )

    @Test
    fun `moving GPS course wins over noisy position deltas and is normalized`() {
        assertEquals(42.0, course.update(fix(bearing = 42.0))!!, 0.0)
        assertEquals(0.0, course.update(fix(at = 12_000L, east = 0.0005, bearing = 360.0))!!, 0.0)
    }

    @Test
    fun `missing GPS course recovers east then north without phone orientation`() {
        assertEquals(0.0, course.update(fix(bearing = 0.0))!!, 0.0)
        assertEquals(90.0, course.update(fix(at = 12_000L, east = 0.0005))!!, 0.01)
        assertEquals(0.0, course.update(fix(at = 14_000L, east = 0.0005, north = 0.0003))!!, 0.01)
    }

    @Test
    fun `unknown speed can resolve direction from clear displacement`() {
        assertNull(course.update(fix(speed = null, bearing = 180.0)))
        assertEquals(90.0, course.update(fix(at = 12_000L, east = 0.0005, speed = null))!!, 0.01)
    }

    @Test
    fun `uncertain or invalid GPS bearing falls back to displacement`() {
        for (bad in listOf(Double.NaN, -1.0, 361.0, null, 0.0)) {
            val resolver = RecordingCourseBearing()
            resolver.update(fix())
            assertEquals(90.0, resolver.update(fix(
                at = 12_000L, east = 0.0005, bearing = bad, bearingAccuracy = 90.0,
            ))!!, 0.01)
        }
    }

    @Test
    fun `stationary GPS courses and drifting positions hold the last moving heading`() {
        course.update(fix(bearing = 75.0))
        assertEquals(75.0, course.update(fix(at = 12_000L, east = 0.0005, speed = 0.0, bearing = 240.0))!!, 0.0)
        assertEquals(75.0, course.update(fix(at = 14_000L, east = 0.0005, speed = 1.0, bearing = 0.0))!!, 0.0)
    }

    @Test
    fun `short noisy segments accumulate until beyond both accuracy radii`() {
        assertNull(course.update(fix()))
        assertNull(course.update(fix(at = 12_000L, east = 0.0001)))
        assertEquals(90.0, course.update(fix(at = 14_000L, east = 0.0005))!!, 0.01)
    }

    @Test
    fun `inaccurate positions and slow drift do not invent a bearing`() {
        assertNull(course.update(fix(accuracy = 50.0)))
        assertNull(course.update(fix(at = 12_000L, east = 0.0005, accuracy = 50.0)))
        val slow = RecordingCourseBearing()
        slow.update(fix(speed = null))
        assertNull(slow.update(fix(at = 24_000L, north = 0.0001, speed = null)))
    }

    @Test
    fun `long GPS gap and duplicate or out of order samples cannot turn the map`() {
        course.update(fix(bearing = 75.0))
        assertEquals(75.0, course.update(fix(at = 10_000L, bearing = 200.0))!!, 0.0)
        assertEquals(75.0, course.update(fix(at = 9_000L, bearing = 200.0))!!, 0.0)
        assertEquals(75.0, course.update(fix(at = 30_000L, east = 0.01))!!, 0.0)
        assertEquals(90.0, course.update(fix(at = 32_000L, east = 0.0105))!!, 0.01)
    }
}
