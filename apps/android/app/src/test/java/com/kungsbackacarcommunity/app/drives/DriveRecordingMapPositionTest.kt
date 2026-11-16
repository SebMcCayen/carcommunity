package com.kungsbackacarcommunity.app.drives

import com.kungsbackacarcommunity.app.live.LiveLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRecordingMapPositionTest {
    private val coordinator = DriveRecordingCoordinator(Repository(), "live-map")

    private fun sample(elapsed: Long = 1_000L, longitude: Double = 12.0) = DriveLocationSample(
        latitude = 57.0,
        longitude = longitude,
        timestampMs = 1_800_000_000_000L + elapsed,
        elapsedRealtimeMs = elapsed,
        receivedElapsedRealtimeMs = elapsed,
        receivedAtMillis = 1_800_000_000_000L + elapsed,
        accuracyMeters = 5.0,
        speedMps = 10.0,
        bearingDegrees = 42.0,
        bearingAccuracyDegrees = 5.0,
    )

    @Test
    fun `puck input advances only when the same point is kept in the route`() {
        coordinator.start()
        val first = sample()
        coordinator.addSample(first.copy(accuracyMeters = 500.0))
        assertNull(coordinator.latestAcceptedSample)
        coordinator.addSample(first)
        assertEquals(first, coordinator.latestAcceptedSample)

        // Poor accuracy, a lateral teleport, stale data, and duplicate time must
        // not move the puck away from the accepted route or poison recovery.
        coordinator.addSample(sample(6_000L).copy(accuracyMeters = 500.0))
        coordinator.addSample(sample(6_000L, longitude = 13.0))
        coordinator.addSample(sample(6_000L).copy(receivedElapsedRealtimeMs = 30_000L))
        coordinator.addSample(first)
        assertEquals(first, coordinator.latestAcceptedSample)
        assertEquals(1, coordinator.recordedPoints().size)

        val next = sample(11_000L, longitude = 12.001)
        coordinator.addSample(next)
        assertEquals(next, coordinator.latestAcceptedSample)
        val head = coordinator.recentRecordedPoints().last()
        assertEquals(next.latitude, head.latitude, 0.0)
        assertEquals(next.longitude, head.longitude, 0.0)
        assertEquals(42.0, coordinator.latestAcceptedSample?.bearingDegrees)
    }

    @Test
    fun `late samples after stopping cannot move the last accepted position`() {
        coordinator.start()
        val fix = sample()
        coordinator.addSample(fix)
        coordinator.stop()
        coordinator.addSample(sample(6_000L, longitude = 12.001))
        assertEquals(fix, coordinator.latestAcceptedSample)
        assertEquals(1, coordinator.recordedPoints().size)
    }

    @Test
    fun `live map cadence fits the full session within recorder capacity`() {
        val maxPoints = LiveLocation.LIVE_SESSION_MAX_MS / DriveLocationController.UPDATE_INTERVAL_MS + 1
        assertTrue(maxPoints <= DriveRecorder.MAX_ROUTE_POINTS)
        assertTrue(DriveLocationController.UPDATE_INTERVAL_MS <= 2_000L)
    }

    private class Repository : DrivesRepository {
        override fun observeDrives(uid: String) = throw UnsupportedOperationException()
        override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult = throw UnsupportedOperationException()
        override suspend fun deleteDrive(rideId: String) = throw UnsupportedOperationException()
    }
}
