package com.kungsbackacarcommunity.app.profile

import com.kungsbackacarcommunity.app.drives.DriveStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers only the profile-summary assembly + empty-state predicate. The drive
 * fold itself is exercised by DriveStatsTest (the drive-stats page) and is not
 * re-tested here.
 */
class ProfileStatsTest {

    private fun driveStats(
        totalDrives: Int = 3,
        totalDistanceMeters: Double = 15_000.0,
        totalDurationSeconds: Long = 3_600,
        highestMaxSpeedMps: Double? = 30.0,
    ) = DriveStats(
        totalDrives = totalDrives,
        totalDistanceMeters = totalDistanceMeters,
        totalDurationSeconds = totalDurationSeconds,
        longestDriveMeters = 8_000.0,
        averageDriveMeters = totalDistanceMeters / totalDrives,
        fastestAverageSpeedMps = 20.0,
        highestMaxSpeedMps = highestMaxSpeedMps,
        thisMonthDrives = 1,
        thisMonthDistanceMeters = 5_000.0,
    )

    @Test
    fun `maps drive totals, badge count, points and member-since through`() {
        val summary =
            ProfileStatsSummary.from(
                driveStats = driveStats(),
                badgeCount = 2,
                pointsBalance = 150L,
                memberSinceMillis = 1_700_000_000_000L,
            )

        assertEquals(3, summary.totalDrives)
        assertEquals(15_000.0, summary.totalDistanceMeters, 0.0)
        assertEquals(3_600L, summary.totalDurationSeconds)
        assertEquals(30.0, summary.highestMaxSpeedMps!!, 0.0001)
        assertEquals(2, summary.badgeCount)
        assertEquals(150L, summary.pointsBalance)
        assertEquals(1_700_000_000_000L, summary.memberSinceMillis)
        assertTrue(summary.hasActivity)
    }

    @Test
    fun `null drive stats reads as zero drives and no highest speed`() {
        val summary =
            ProfileStatsSummary.from(
                driveStats = null,
                badgeCount = 1,
                pointsBalance = 0L,
                memberSinceMillis = null,
            )

        assertEquals(0, summary.totalDrives)
        assertEquals(0.0, summary.totalDistanceMeters, 0.0)
        assertEquals(0L, summary.totalDurationSeconds)
        assertNull(summary.highestMaxSpeedMps)
        assertNull(summary.memberSinceMillis)
    }

    @Test
    fun `no drives and no badges has no activity even with points and a join date`() {
        val summary =
            ProfileStatsSummary.from(
                driveStats = null,
                badgeCount = 0,
                pointsBalance = 500L,
                memberSinceMillis = 1_700_000_000_000L,
            )

        assertFalse(summary.hasActivity)
    }

    @Test
    fun `a single badge alone counts as activity`() {
        val summary =
            ProfileStatsSummary.from(
                driveStats = null,
                badgeCount = 1,
                pointsBalance = null,
                memberSinceMillis = null,
            )

        assertTrue(summary.hasActivity)
    }

    @Test
    fun `a single drive alone counts as activity`() {
        val summary =
            ProfileStatsSummary.from(
                driveStats = driveStats(totalDrives = 1),
                badgeCount = 0,
                pointsBalance = null,
                memberSinceMillis = null,
            )

        assertTrue(summary.hasActivity)
    }

    @Test
    fun `negative badge counts are clamped to zero`() {
        val summary =
            ProfileStatsSummary.from(
                driveStats = null,
                badgeCount = -5,
                pointsBalance = null,
                memberSinceMillis = null,
            )

        assertEquals(0, summary.badgeCount)
        assertFalse(summary.hasActivity)
    }
}
