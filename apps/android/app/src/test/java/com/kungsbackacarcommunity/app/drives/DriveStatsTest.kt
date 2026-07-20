package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveStatsTest {

    private val monthStart = 1_000_000L

    private fun drive(
        id: String = "r",
        distanceMeters: Double? = 1000.0,
        durationSeconds: Long = 60,
        averageSpeedMetersPerSecond: Double? = 10.0,
        startedAtMillis: Long? = null,
        createdAtMillis: Long? = null,
    ) = SavedDrive(
        rideId = id,
        title = null,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        averageSpeedMetersPerSecond = averageSpeedMetersPerSecond,
        startedAtMillis = startedAtMillis,
        endedAtMillis = null,
        createdAtMillis = createdAtMillis,
    )

    @Test
    fun `empty list yields null so the UI shows an empty state`() {
        assertNull(DriveStatsCalculator.compute(emptyList(), monthStart))
    }

    @Test
    fun `single drive reports itself as every all-time figure`() {
        val stats =
            DriveStatsCalculator.compute(
                listOf(drive(distanceMeters = 5000.0, durationSeconds = 300, averageSpeedMetersPerSecond = 16.0)),
                monthStart,
            )!!
        assertEquals(1, stats.totalDrives)
        assertEquals(5000.0, stats.totalDistanceMeters, 0.0)
        assertEquals(300L, stats.totalDurationSeconds)
        assertEquals(5000.0, stats.longestDriveMeters, 0.0)
        assertEquals(5000.0, stats.averageDriveMeters, 0.0)
        assertEquals(16.0, stats.fastestAverageSpeedMps!!, 0.0001)
    }

    @Test
    fun `many drives sum totals and pick the correct max and average`() {
        val drives =
            listOf(
                drive(id = "a", distanceMeters = 1000.0, durationSeconds = 100, averageSpeedMetersPerSecond = 10.0),
                drive(id = "b", distanceMeters = 4000.0, durationSeconds = 200, averageSpeedMetersPerSecond = 20.0),
                drive(id = "c", distanceMeters = 1000.0, durationSeconds = 300, averageSpeedMetersPerSecond = 5.0),
            )
        val stats = DriveStatsCalculator.compute(drives, monthStart)!!
        assertEquals(3, stats.totalDrives)
        assertEquals(6000.0, stats.totalDistanceMeters, 0.0)
        assertEquals(600L, stats.totalDurationSeconds)
        assertEquals(4000.0, stats.longestDriveMeters, 0.0) // max, not last
        assertEquals(2000.0, stats.averageDriveMeters, 0.0) // 6000 / 3
        assertEquals(20.0, stats.fastestAverageSpeedMps!!, 0.0001) // max avg speed
    }

    @Test
    fun `unusable distances contribute zero to totals longest and average`() {
        val drives =
            listOf(
                drive(id = "a", distanceMeters = 2000.0),
                drive(id = "b", distanceMeters = null),
                drive(id = "c", distanceMeters = -50.0),
                drive(id = "d", distanceMeters = Double.NaN),
                drive(id = "e", distanceMeters = Double.POSITIVE_INFINITY),
            )
        val stats = DriveStatsCalculator.compute(drives, monthStart)!!
        assertEquals(5, stats.totalDrives)
        assertEquals(2000.0, stats.totalDistanceMeters, 0.0)
        assertEquals(2000.0, stats.longestDriveMeters, 0.0)
        // average divides by ALL drives (5), so a missing distance drags the mean down
        assertEquals(400.0, stats.averageDriveMeters, 0.0)
    }

    @Test
    fun `negative durations are clamped so total time never goes backwards`() {
        val drives =
            listOf(
                drive(id = "a", durationSeconds = 100),
                drive(id = "b", durationSeconds = -500),
                drive(id = "c", durationSeconds = 50),
            )
        val stats = DriveStatsCalculator.compute(drives, monthStart)!!
        assertEquals(150L, stats.totalDurationSeconds)
    }

    @Test
    fun `fastest average speed is null when no drive yields a usable speed`() {
        val drives =
            listOf(
                drive(id = "a", averageSpeedMetersPerSecond = null, distanceMeters = null, durationSeconds = 0),
                drive(id = "b", averageSpeedMetersPerSecond = Double.NaN, distanceMeters = null, durationSeconds = 0),
            )
        val stats = DriveStatsCalculator.compute(drives, monthStart)!!
        assertNull(stats.fastestAverageSpeedMps)
    }

    @Test
    fun `fastest average speed falls back to distance over duration when server value is missing`() {
        // No persisted average speed, but 1000 m over 100 s = 10 m/s is derivable.
        val stats =
            DriveStatsCalculator.compute(
                listOf(drive(averageSpeedMetersPerSecond = null, distanceMeters = 1000.0, durationSeconds = 100)),
                monthStart,
            )!!
        assertEquals(10.0, stats.fastestAverageSpeedMps!!, 0.0001)
    }

    @Test
    fun `this month counts only drives at or after the month boundary`() {
        val drives =
            listOf(
                drive(id = "old", distanceMeters = 1000.0, startedAtMillis = monthStart - 1),
                drive(id = "boundary", distanceMeters = 2000.0, startedAtMillis = monthStart),
                drive(id = "recent", distanceMeters = 3000.0, startedAtMillis = monthStart + 5000),
                drive(id = "undated", distanceMeters = 9000.0, startedAtMillis = null, createdAtMillis = null),
            )
        val stats = DriveStatsCalculator.compute(drives, monthStart)!!
        // all-time still counts everything
        assertEquals(4, stats.totalDrives)
        assertEquals(15000.0, stats.totalDistanceMeters, 0.0)
        // this month: boundary + recent only (old is before; undated excluded)
        assertEquals(2, stats.thisMonthDrives)
        assertEquals(5000.0, stats.thisMonthDistanceMeters, 0.0)
    }

    @Test
    fun `this month falls back to createdAt when startedAt is absent`() {
        val drives =
            listOf(
                drive(id = "a", distanceMeters = 1000.0, startedAtMillis = null, createdAtMillis = monthStart + 10),
                drive(id = "b", distanceMeters = 2000.0, startedAtMillis = null, createdAtMillis = monthStart - 10),
            )
        val stats = DriveStatsCalculator.compute(drives, monthStart)!!
        assertEquals(1, stats.thisMonthDrives)
        assertEquals(1000.0, stats.thisMonthDistanceMeters, 0.0)
    }
}
