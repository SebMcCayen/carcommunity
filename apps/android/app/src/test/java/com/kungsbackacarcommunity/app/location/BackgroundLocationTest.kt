package com.kungsbackacarcommunity.app.location

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundLocationTest {

    @Test
    fun `buildCoordinate formats recordedAt as ISO-8601 instant`() {
        val timeMillis = 1_700_000_000_000L
        val coordinate =
            BackgroundLocation.buildCoordinate(
                latitude = 57.487,
                longitude = 12.076,
                timeMillis = timeMillis,
            )

        assertEquals(57.487, coordinate.latitude, 0.0)
        assertEquals(12.076, coordinate.longitude, 0.0)
        assertEquals(
            Instant.ofEpochMilli(timeMillis).toString(),
            coordinate.recordedAtIso,
        )
        // Sanity: the string round-trips back to the same instant.
        assertEquals(timeMillis, Instant.parse(coordinate.recordedAtIso).toEpochMilli())
    }

    @Test
    fun `buildCoordinate omits optional fields by default`() {
        val coordinate =
            BackgroundLocation.buildCoordinate(
                latitude = 0.0,
                longitude = 0.0,
                timeMillis = 0L,
            )

        assertNull(coordinate.accuracyMeters)
        assertNull(coordinate.headingDegrees)
        assertNull(coordinate.speedMetersPerSecond)
    }

    @Test
    fun `buildCoordinate passes through optional fields`() {
        val coordinate =
            BackgroundLocation.buildCoordinate(
                latitude = 1.0,
                longitude = 2.0,
                timeMillis = 10L,
                accuracyMeters = 3.5,
                bearingDegrees = 90.0,
                speedMps = 12.25,
            )

        assertEquals(3.5, coordinate.accuracyMeters)
        assertEquals(90.0, coordinate.headingDegrees)
        assertEquals(12.25, coordinate.speedMetersPerSecond)
    }

    @Test
    fun `interval constants are sane`() {
        assertEquals(5_000L, BackgroundLocation.UPDATE_INTERVAL_MS)
        assertEquals(2_000L, BackgroundLocation.MIN_UPDATE_INTERVAL_MS)
        // Fastest cadence must not exceed the nominal interval.
        assertTrue(
            BackgroundLocation.MIN_UPDATE_INTERVAL_MS <= BackgroundLocation.UPDATE_INTERVAL_MS,
        )
    }
}
