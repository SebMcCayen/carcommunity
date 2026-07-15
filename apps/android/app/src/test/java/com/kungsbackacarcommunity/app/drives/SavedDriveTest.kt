package com.kungsbackacarcommunity.app.drives

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
}

private class FakeDrivesRepository(private val shouldFail: Boolean) : DrivesRepository {
    override fun observeDrives(uid: String) = throw UnsupportedOperationException()

    override suspend fun saveDrive(request: Map<String, Any?>) {
        if (shouldFail) throw IllegalStateException("save failed")
    }

    override suspend fun deleteDrive(rideId: String) {
        if (shouldFail) throw IllegalStateException("delete failed")
    }
}
