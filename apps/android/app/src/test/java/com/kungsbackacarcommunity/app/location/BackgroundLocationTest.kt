package com.kungsbackacarcommunity.app.location

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- Publish throttle (battery / callable traffic) -----------------------

    private val kungsbacka = 57.4874 to 12.0761

    @Test
    fun `the first fix of a session always publishes`() {
        assertTrue(
            BackgroundLocation.shouldPublish(
                lastSubmittedAtMillis = null,
                lastSubmittedLatitude = null,
                lastSubmittedLongitude = null,
                latitude = kungsbacka.first,
                longitude = kungsbacka.second,
                nowMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `GPS jitter while parked does not publish`() {
        // ~2 m north of the last published fix, 1 s later: below the movement
        // threshold and well inside the heartbeat.
        assertFalse(
            BackgroundLocation.shouldPublish(
                lastSubmittedAtMillis = 1_000L,
                lastSubmittedLatitude = kungsbacka.first,
                lastSubmittedLongitude = kungsbacka.second,
                latitude = kungsbacka.first + 0.000018,
                longitude = kungsbacka.second,
                nowMillis = 2_000L,
            ),
        )
    }

    @Test
    fun `a stationary car still publishes a heartbeat`() {
        assertTrue(
            BackgroundLocation.shouldPublish(
                lastSubmittedAtMillis = 1_000L,
                lastSubmittedLatitude = kungsbacka.first,
                lastSubmittedLongitude = kungsbacka.second,
                latitude = kungsbacka.first,
                longitude = kungsbacka.second,
                nowMillis = 1_000L + BackgroundLocation.STATIONARY_HEARTBEAT_MS,
            ),
        )
    }

    @Test
    fun `the stationary heartbeat is throttled to 3 minutes`() {
        // The main data saver: a parked phone writes once every 3 min, not 30 s.
        assertEquals(3 * 60 * 1000L, BackgroundLocation.STATIONARY_HEARTBEAT_MS)
    }

    @Test
    fun `a stationary car does NOT publish before the 3-minute heartbeat`() {
        // 30 s after the last submitted fix, no movement: the old cadence would
        // have published here; the throttled one waits for the 3-min heartbeat.
        assertFalse(
            BackgroundLocation.shouldPublish(
                lastSubmittedAtMillis = 1_000L,
                lastSubmittedLatitude = kungsbacka.first,
                lastSubmittedLongitude = kungsbacka.second,
                latitude = kungsbacka.first,
                longitude = kungsbacka.second,
                nowMillis = 1_000L + 30_000L,
            ),
        )
    }

    @Test
    fun `a moving car publishes every fix`() {
        // 50 km/h for the 5 s nominal interval is ~69 m — far past the threshold,
        // so convoy arrows and focus mode keep getting fresh positions.
        assertTrue(
            BackgroundLocation.shouldPublish(
                lastSubmittedAtMillis = 1_000L,
                lastSubmittedLatitude = kungsbacka.first,
                lastSubmittedLongitude = kungsbacka.second,
                latitude = kungsbacka.first + 0.000625,
                longitude = kungsbacka.second,
                nowMillis = 1_000L + BackgroundLocation.UPDATE_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `distanceMeters measures a known north-south offset`() {
        // 0.001 deg of latitude is ~111 m anywhere on Earth.
        val metres =
            BackgroundLocation.distanceMeters(
                kungsbacka.first,
                kungsbacka.second,
                kungsbacka.first + 0.001,
                kungsbacka.second,
            )
        assertTrue("expected ~111 m, was $metres", metres in 110.0..113.0)
    }
}
