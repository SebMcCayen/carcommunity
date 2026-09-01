package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure wire→domain mapping for the `drives-listHistory` / `drives-stats` callable
 * responses. The callables return plain JSON (numbers, not Firestore Timestamps),
 * and the Firebase SDK hands numbers back as boxed [Number]s of varying concrete
 * type, so the mapper must coerce via [Number] and drop malformed rows.
 */
class DriveHistoryMapperTest {

    @Test
    fun `tier maps case-insensitively and defaults unknown to UNKNOWN`() {
        assertEquals(DriveSubscriptionTier.COMMUNITY, DriveHistoryMapper.tierFromWire("community"))
        assertEquals(DriveSubscriptionTier.PLUS, DriveHistoryMapper.tierFromWire("Plus"))
        assertEquals(DriveSubscriptionTier.SUPPORTER, DriveHistoryMapper.tierFromWire("SUPPORTER"))
        assertEquals(DriveSubscriptionTier.UNKNOWN, DriveHistoryMapper.tierFromWire("premium"))
        assertEquals(DriveSubscriptionTier.UNKNOWN, DriveHistoryMapper.tierFromWire(null))
    }

    @Test
    fun `driveFromWire maps every field and coerces mixed number types`() {
        val drive =
            DriveHistoryMapper.driveFromWire(
                mapOf(
                    "rideId" to "ride-1",
                    "title" to "Morning loop",
                    "distanceMeters" to 12345.0,
                    "durationSeconds" to 600, // Int
                    "averageSpeedMetersPerSecond" to 10.5,
                    "maxSpeedMetersPerSecond" to 25,
                    "startedAtMillis" to 1_000_000L,
                    "endedAtMillis" to 1_600_000L,
                    "createdAtMillis" to 1_000_000L,
                    "routeThumbnail" to "abc",
                    "carImagePath" to "cars/x.jpg",
                    "convoyMembers" to listOf(mapOf("uid" to "u1", "displayName" to "Anna")),
                ),
            )!!
        assertEquals("ride-1", drive.rideId)
        assertEquals("Morning loop", drive.title)
        assertEquals(12345.0, drive.distanceMeters!!, 0.0)
        assertEquals(600L, drive.durationSeconds)
        assertEquals(10.5, drive.averageSpeedMetersPerSecond!!, 0.0)
        assertEquals(25.0, drive.maxSpeedMetersPerSecond!!, 0.0)
        assertEquals(1_000_000L, drive.startedAtMillis)
        assertEquals(1_600_000L, drive.endedAtMillis)
        assertEquals(1_000_000L, drive.createdAtMillis)
        assertEquals("abc", drive.routeThumbnail)
        assertEquals("cars/x.jpg", drive.carImagePath)
        assertEquals(listOf(ConvoyDriveMember("u1", "Anna", null)), drive.convoyMembers)
    }

    @Test
    fun `driveFromWire drops a row without a usable rideId or duration`() {
        assertNull(DriveHistoryMapper.driveFromWire(mapOf("durationSeconds" to 10)))
        assertNull(DriveHistoryMapper.driveFromWire(mapOf("rideId" to "  ", "durationSeconds" to 10)))
        assertNull(DriveHistoryMapper.driveFromWire(mapOf("rideId" to "r")))
        assertNull(DriveHistoryMapper.driveFromWire(mapOf("rideId" to "r", "durationSeconds" to -5)))
        assertNull(DriveHistoryMapper.driveFromWire("not a map"))
    }

    @Test
    fun `driveFromWire degrades malformed optional stats to null, never a fake value`() {
        val drive =
            DriveHistoryMapper.driveFromWire(
                mapOf(
                    "rideId" to "r",
                    "durationSeconds" to 60,
                    "distanceMeters" to -1.0,
                    "averageSpeedMetersPerSecond" to Double.NaN,
                    "maxSpeedMetersPerSecond" to Double.POSITIVE_INFINITY,
                    "title" to "  ",
                ),
            )!!
        assertNull(drive.distanceMeters)
        assertNull(drive.averageSpeedMetersPerSecond)
        assertNull(drive.maxSpeedMetersPerSecond)
        assertNull(drive.title)
        assertTrue(drive.convoyMembers.isEmpty())
    }

    @Test
    fun `pageFromWire carries paging and the first-page hidden count`() {
        val page =
            DriveHistoryMapper.pageFromWire(
                mapOf(
                    "tier" to "plus",
                    "drives" to
                        listOf(
                            mapOf("rideId" to "a", "durationSeconds" to 60, "createdAtMillis" to 3L),
                            "garbage", // dropped
                            mapOf("rideId" to "b", "durationSeconds" to 90, "createdAtMillis" to 2L),
                        ),
                    "hasMore" to true,
                    "nextCursorRideId" to "b",
                    "hiddenDriveCount" to 7,
                    "hasTierRestrictedHistory" to true,
                ),
            )
        assertEquals(DriveSubscriptionTier.PLUS, page.tier)
        assertEquals(listOf("a", "b"), page.drives.map { it.rideId })
        assertTrue(page.hasMore)
        assertEquals("b", page.nextCursorRideId)
        assertEquals(7, page.hiddenDriveCount)
        assertEquals(true, page.hasTierRestrictedHistory)
    }

    @Test
    fun `pageFromWire tolerates an absent later-page hidden count and cursor`() {
        val page =
            DriveHistoryMapper.pageFromWire(
                mapOf(
                    "tier" to "supporter",
                    "drives" to emptyList<Any?>(),
                    "hasMore" to false,
                    "nextCursorRideId" to null,
                    "hiddenDriveCount" to null,
                    "hasTierRestrictedHistory" to null,
                ),
            )
        assertFalse(page.hasMore)
        assertNull(page.nextCursorRideId)
        assertNull(page.hiddenDriveCount)
        assertNull(page.hasTierRestrictedHistory)
    }

    @Test
    fun `statsFromWire normalises the server's zero speed to null so the dash shows`() {
        val stats =
            DriveHistoryMapper.statsFromWire(
                mapOf(
                    "tier" to "community",
                    "totalDrives" to 3,
                    "totalDistanceMeters" to 6000.0,
                    "totalDurationSeconds" to 600,
                    "longestDriveMeters" to 4000.0,
                    "averageDriveMeters" to 2000.0,
                    "fastestAverageSpeedMps" to 0.0, // "none" per server contract
                    "highestMaxSpeedMps" to 30.0,
                    "thisMonthDrives" to 1,
                    "thisMonthDistanceMeters" to 1000.0,
                ),
            )
        assertEquals(DriveSubscriptionTier.COMMUNITY, stats.tier)
        assertEquals(3, stats.totalDrives)
        assertEquals(6000.0, stats.totalDistanceMeters, 0.0)
        assertEquals(600L, stats.totalDurationSeconds)
        assertEquals(4000.0, stats.longestDriveMeters, 0.0)
        assertEquals(2000.0, stats.averageDriveMeters, 0.0)
        assertNull(stats.fastestAverageSpeedMps) // 0 → null
        assertEquals(30.0, stats.highestMaxSpeedMps!!, 0.0)
        assertEquals(1, stats.thisMonthDrives)
        assertEquals(1000.0, stats.thisMonthDistanceMeters, 0.0)
    }

    @Test
    fun `statsFromWire defaults a missing or malformed payload to safe zeroes`() {
        val stats = DriveHistoryMapper.statsFromWire(null)
        assertEquals(0, stats.totalDrives)
        assertEquals(0.0, stats.totalDistanceMeters, 0.0)
        assertEquals(0L, stats.totalDurationSeconds)
        assertNull(stats.fastestAverageSpeedMps)
        assertNull(stats.highestMaxSpeedMps)
    }
}
