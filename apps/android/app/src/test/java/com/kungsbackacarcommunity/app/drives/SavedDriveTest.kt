package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedDriveTest {

    private fun drive(id: String, createdAt: Long?) =
        SavedDrive(
            rideId = id,
            title = null,
            distanceMeters = 1000.0,
            durationSeconds = 60,
            averageSpeedMetersPerSecond = 10.0,
            startedAtMillis = null,
            endedAtMillis = null,
            createdAtMillis = createdAt,
        )

    @Test
    fun `sortedForList is newest first with undated last`() {
        val drives = listOf(drive("a", 100L), drive("b", null), drive("c", 300L), drive("d", 200L))
        assertEquals(
            listOf("c", "d", "a", "b"),
            SavedDrives.sortedForList(drives).map { it.rideId },
        )
    }

    @Test
    fun `formatDistance uses metres under a kilometre and one decimal km above`() {
        assertEquals("820 m", DriveFormatters.formatDistance(820.0))
        assertEquals("12.3 km", DriveFormatters.formatDistance(12345.0))
        assertEquals("—", DriveFormatters.formatDistance(null))
    }

    @Test
    fun `formatDuration drops zero leading units`() {
        assertEquals("1 h 5 min", DriveFormatters.formatDuration(3900))
        assertEquals("5 min", DriveFormatters.formatDuration(300))
        assertEquals("45 s", DriveFormatters.formatDuration(45))
        assertEquals("0 min", DriveFormatters.formatDuration(0))
    }

    @Test
    fun `formatSpeed converts to whole km per hour`() {
        assertEquals("36 km/h", DriveFormatters.formatSpeed(10.0))
        assertEquals("—", DriveFormatters.formatSpeed(null))
    }

    @Test
    fun `a drive with no stored max speed renders the dash, never zero`() {
        // Every drive saved before maxSpeedMetersPerSecond existed reads as
        // null (there is no backfill). "0 km/h" would be a claim the car never
        // moved, on a drive that covered kilometres.
        val legacy = drive("legacy", createdAt = 1L)
        assertEquals(null, legacy.maxSpeedMetersPerSecond)
        assertEquals(null, legacy.routeThumbnail)
        assertEquals("—", DriveFormatters.formatSpeed(legacy.maxSpeedMetersPerSecond))
        // A genuinely stationary drive DID store 0, and 0 is a fact, so it
        // renders as a number — the dash means "unknown", not "slow".
        assertEquals("0 km/h", DriveFormatters.formatSpeed(0.0))
        // Whole km/h, no decimals: 25 m/s = 90 km/h.
        assertEquals("90 km/h", DriveFormatters.formatSpeed(25.0))
    }

    @Test
    fun `formatSpeedKmh appends the unit to a whole km per hour and dashes null`() {
        // The live-session bar formats an already-deadbanded Int km/h through
        // this overload, so it must carry the same "km/h" label formatSpeed does.
        assertEquals("54 km/h", DriveFormatters.formatSpeedKmh(54))
        // Zero is a fact (stationary), rendered as a number, not the "unknown" dash.
        assertEquals("0 km/h", DriveFormatters.formatSpeedKmh(0))
        // No sample yet → the missing-value dash, never a bogus "0 km/h".
        assertEquals("—", DriveFormatters.formatSpeedKmh(null))
        // A negative km/h is not a speed; render the dash, mirroring formatSpeed's
        // negative handling, rather than drawing "-5 km/h".
        assertEquals("—", DriveFormatters.formatSpeedKmh(-5))
    }

    @Test
    fun `formatSpeed renders em dash for non-finite values`() {
        assertEquals("—", DriveFormatters.formatSpeed(Double.POSITIVE_INFINITY))
        assertEquals("—", DriveFormatters.formatSpeed(Double.NEGATIVE_INFINITY))
        assertEquals("—", DriveFormatters.formatSpeed(Double.NaN))
    }

    @Test
    fun `effectiveAverageSpeed rejects a non-finite stored value and falls back`() {
        // Corrupted stored value must not pass through; distance/duration is used.
        assertEquals(
            10.0,
            DriveFormatters.effectiveAverageSpeed(
                Double.POSITIVE_INFINITY,
                distanceMeters = 1000.0,
                durationSeconds = 100,
            ),
        )
        assertEquals(
            10.0,
            DriveFormatters.effectiveAverageSpeed(Double.NaN, distanceMeters = 1000.0, durationSeconds = 100),
        )
    }

    @Test
    fun `effectiveAverageSpeed is null when both sources are non-finite`() {
        assertEquals(
            null,
            DriveFormatters.effectiveAverageSpeed(
                Double.NaN,
                distanceMeters = Double.POSITIVE_INFINITY,
                durationSeconds = 100,
            ),
        )
    }

    @Test
    fun `effectiveAverageSpeed prefers the stored value`() {
        assertEquals(
            12.0,
            DriveFormatters.effectiveAverageSpeed(12.0, distanceMeters = 100.0, durationSeconds = 5),
        )
    }

    @Test
    fun `effectiveAverageSpeed derives from distance over duration when unstored`() {
        assertEquals(
            10.0,
            DriveFormatters.effectiveAverageSpeed(null, distanceMeters = 1000.0, durationSeconds = 100),
        )
    }

    @Test
    fun `effectiveAverageSpeed is null when neither source is usable`() {
        assertEquals(null, DriveFormatters.effectiveAverageSpeed(null, distanceMeters = null, durationSeconds = 0))
        assertEquals(null, DriveFormatters.effectiveAverageSpeed(null, distanceMeters = 1000.0, durationSeconds = 0))
    }

    @Test
    fun `formatDriveTimeRange is null when an endpoint is missing`() {
        assertEquals(null, formatDriveTimeRange(null, 1000L))
        assertEquals(null, formatDriveTimeRange(1000L, null))
    }

    @Test
    fun `formatDriveTimeRange is null for a reversed range`() {
        // end before start (backend-provided, nullable) must never render backwards.
        assertEquals(null, formatDriveTimeRange(2000L, 1000L))
    }

    @Test
    fun `formatDriveTimeRange renders a single time for equal endpoints`() {
        val result = formatDriveTimeRange(1000L, 1000L)
        assertNotNull(result)
        assertFalse(result!!.contains("–"))
    }

    @Test
    fun `formatDriveTimeRange renders a range for a forward interval`() {
        val result = formatDriveTimeRange(1000L, 2000L)
        assertNotNull(result)
        assertTrue(result!!.contains("–"))
    }

    @Test
    fun `coordinator marks deleted on success`() = runTest {
        val coordinator = DrivesCoordinator(FakeDrivesRepository(shouldFail = false))
        coordinator.delete("r1")
        assertEquals(DriveDeleteStatus.Deleted, coordinator.deleteStatus.value)
    }

    @Test
    fun `coordinator marks failed when the callable throws`() = runTest {
        val coordinator = DrivesCoordinator(FakeDrivesRepository(shouldFail = true))
        coordinator.delete("r1")
        assertEquals(DriveDeleteStatus.Failed, coordinator.deleteStatus.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `coordinator ignores a second delete while one is in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var deleteCalls = 0
        val repo =
            object : DrivesRepository {
                override fun observeDrives(uid: String) = throw UnsupportedOperationException()

                override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult =
                    DriveSaveResult(rideId = "ride", routePath = null, alreadySaved = false)

                override suspend fun deleteDrive(rideId: String) {
                    deleteCalls++
                    gate.await()
                }
            }
        val coordinator = DrivesCoordinator(repo)

        val job = launch { coordinator.delete("r1") }
        // Run the launched delete up to its suspension point (the gated callable).
        runCurrent()
        assertEquals(DriveDeleteStatus.Deleting, coordinator.deleteStatus.value)

        // A second delete while one is in flight must be a no-op, not a new call.
        coordinator.delete("r1")
        assertEquals(1, deleteCalls)

        gate.complete(Unit)
        job.join()
        assertEquals(DriveDeleteStatus.Deleted, coordinator.deleteStatus.value)
    }

    @Test
    fun `reset returns the coordinator to idle after a failure`() = runTest {
        val coordinator = DrivesCoordinator(FakeDrivesRepository(shouldFail = true))
        coordinator.delete("r1")
        assertEquals(DriveDeleteStatus.Failed, coordinator.deleteStatus.value)
        coordinator.reset()
        assertEquals(DriveDeleteStatus.Idle, coordinator.deleteStatus.value)
    }
}

private class FakeDrivesRepository(private val shouldFail: Boolean) : DrivesRepository {
    override fun observeDrives(uid: String) = throw UnsupportedOperationException()

    override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult {
        if (shouldFail) throw IllegalStateException("save failed")
        return DriveSaveResult(rideId = "ride", routePath = null, alreadySaved = false)
    }

    override suspend fun deleteDrive(rideId: String) {
        if (shouldFail) throw IllegalStateException("delete failed")
    }
}
