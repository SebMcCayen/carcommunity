package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveFilterTest {

    private val weekStart = 2_000_000L
    private val monthStart = 1_000_000L

    private fun drive(
        id: String = "r",
        title: String? = null,
        distanceMeters: Double? = 5_000.0,
        durationSeconds: Long = 600,
        averageSpeedMetersPerSecond: Double? = 10.0,
        startedAtMillis: Long? = null,
        createdAtMillis: Long? = null,
    ) = SavedDrive(
        rideId = id,
        title = title,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        averageSpeedMetersPerSecond = averageSpeedMetersPerSecond,
        startedAtMillis = startedAtMillis,
        endedAtMillis = null,
        createdAtMillis = createdAtMillis,
    )

    private fun filter(
        drives: List<SavedDrive>,
        criteria: DriveFilterCriteria,
    ): List<String> =
        DriveFilters.filterDrives(drives, criteria, weekStart, monthStart).map { it.rideId }

    // --- Text search -------------------------------------------------------

    @Test
    fun `empty query returns every drive`() {
        val drives = listOf(drive("a", createdAtMillis = 3L), drive("b", createdAtMillis = 1L))
        assertEquals(listOf("a", "b"), filter(drives, DriveFilterCriteria()))
    }

    @Test
    fun `query matches title case-insensitively as a substring`() {
        val drives =
            listOf(
                drive("a", title = "Morning Commute", createdAtMillis = 3L),
                drive("b", title = "Weekend trip", createdAtMillis = 2L),
                drive("c", title = "Grocery run", createdAtMillis = 1L),
            )
        assertEquals(listOf("b"), filter(drives, DriveFilterCriteria(query = "WEEK")))
    }

    @Test
    fun `query with no matches yields an empty list`() {
        val drives = listOf(drive("a", title = "Commute"))
        assertEquals(emptyList<String>(), filter(drives, DriveFilterCriteria(query = "zzz")))
    }

    @Test
    fun `untitled or blank-title drive never matches a non-empty query`() {
        val drives =
            listOf(
                drive("a", title = null),
                drive("b", title = "   "),
                drive("c", title = ""),
            )
        assertEquals(emptyList<String>(), filter(drives, DriveFilterCriteria(query = "a")))
    }

    @Test
    fun `query is trimmed before matching`() {
        val drives = listOf(drive("a", title = "Track day"))
        assertEquals(listOf("a"), filter(drives, DriveFilterCriteria(query = "  track  ")))
    }

    @Test
    fun `whitespace-only query is treated as no query`() {
        val drives = listOf(drive("a", title = null, createdAtMillis = 1L))
        assertEquals(listOf("a"), filter(drives, DriveFilterCriteria(query = "   ")))
    }

    // --- Date range --------------------------------------------------------

    @Test
    fun `this week keeps drives at or after the week boundary and drops earlier ones`() {
        val drives =
            listOf(
                drive("before", startedAtMillis = weekStart - 1, createdAtMillis = 9L),
                drive("onBoundary", startedAtMillis = weekStart, createdAtMillis = 8L),
                drive("after", startedAtMillis = weekStart + 1, createdAtMillis = 7L),
            )
        assertEquals(
            listOf("after", "onBoundary"),
            filter(drives, DriveFilterCriteria(dateRange = DriveDateRange.THIS_WEEK)).sorted(),
        )
    }

    @Test
    fun `this month uses the month boundary`() {
        val drives =
            listOf(
                drive("before", startedAtMillis = monthStart - 1),
                drive("onBoundary", startedAtMillis = monthStart),
            )
        assertEquals(
            listOf("onBoundary"),
            filter(drives, DriveFilterCriteria(dateRange = DriveDateRange.THIS_MONTH)),
        )
    }

    @Test
    fun `date range falls back to createdAt when startedAt is absent`() {
        val drives =
            listOf(
                drive("a", startedAtMillis = null, createdAtMillis = weekStart + 10),
                drive("b", startedAtMillis = null, createdAtMillis = weekStart - 10),
            )
        assertEquals(
            listOf("a"),
            filter(drives, DriveFilterCriteria(dateRange = DriveDateRange.THIS_WEEK)),
        )
    }

    @Test
    fun `undated drive is excluded from a period but kept under ALL`() {
        val drives = listOf(drive("a", startedAtMillis = null, createdAtMillis = null))
        assertEquals(
            emptyList<String>(),
            filter(drives, DriveFilterCriteria(dateRange = DriveDateRange.THIS_WEEK)),
        )
        assertEquals(
            listOf("a"),
            filter(drives, DriveFilterCriteria(dateRange = DriveDateRange.ALL)),
        )
    }

    // --- Distance bands ----------------------------------------------------

    @Test
    fun `distance bands are half-open with 10km and 50km on the upper band`() {
        val under = drive("under", distanceMeters = 9_999.0)
        val tenKm = drive("ten", distanceMeters = 10_000.0)
        val mid = drive("mid", distanceMeters = 30_000.0)
        val fiftyKm = drive("fifty", distanceMeters = 50_000.0)
        val over = drive("over", distanceMeters = 80_000.0)
        val all = listOf(under, tenKm, mid, fiftyKm, over)

        assertEquals(
            listOf("under"),
            filter(all, DriveFilterCriteria(distanceBand = DriveDistanceBand.UNDER_10_KM)),
        )
        assertEquals(
            listOf("ten", "mid").sorted(),
            filter(all, DriveFilterCriteria(distanceBand = DriveDistanceBand.FROM_10_TO_50_KM)).sorted(),
        )
        assertEquals(
            listOf("fifty", "over").sorted(),
            filter(all, DriveFilterCriteria(distanceBand = DriveDistanceBand.OVER_50_KM)).sorted(),
        )
    }

    @Test
    fun `a specific band excludes drives with no usable distance`() {
        val drives =
            listOf(
                drive("nullDist", distanceMeters = null),
                drive("negDist", distanceMeters = -5.0),
                drive("nanDist", distanceMeters = Double.NaN),
                drive("infDist", distanceMeters = Double.POSITIVE_INFINITY),
                drive("ok", distanceMeters = 5_000.0),
            )
        assertEquals(
            listOf("ok"),
            filter(drives, DriveFilterCriteria(distanceBand = DriveDistanceBand.UNDER_10_KM)),
        )
    }

    @Test
    fun `ALL band keeps drives regardless of distance usability`() {
        val drives = listOf(drive("nullDist", distanceMeters = null, createdAtMillis = 1L))
        assertEquals(
            listOf("nullDist"),
            filter(drives, DriveFilterCriteria(distanceBand = DriveDistanceBand.ALL)),
        )
    }

    // --- Combined ----------------------------------------------------------

    @Test
    fun `combined query date and distance filters all apply`() {
        val drives =
            listOf(
                // Matches all three.
                drive("match", title = "City loop", distanceMeters = 20_000.0, startedAtMillis = weekStart + 5),
                // Right title + distance, but before the week.
                drive("old", title = "City loop", distanceMeters = 20_000.0, startedAtMillis = weekStart - 5),
                // Right title + date, wrong distance band.
                drive("short", title = "City loop", distanceMeters = 2_000.0, startedAtMillis = weekStart + 5),
                // Wrong title.
                drive("other", title = "Highway", distanceMeters = 20_000.0, startedAtMillis = weekStart + 5),
            )
        val criteria =
            DriveFilterCriteria(
                query = "city",
                dateRange = DriveDateRange.THIS_WEEK,
                distanceBand = DriveDistanceBand.FROM_10_TO_50_KM,
            )
        assertEquals(listOf("match"), filter(drives, criteria))
    }

    // --- Sorting -----------------------------------------------------------

    @Test
    fun `newest sort is createdAt descending with undated last`() {
        val drives =
            listOf(
                drive("a", createdAtMillis = 100L),
                drive("b", createdAtMillis = null),
                drive("c", createdAtMillis = 300L),
                drive("d", createdAtMillis = 200L),
            )
        assertEquals(
            listOf("c", "d", "a", "b"),
            filter(drives, DriveFilterCriteria(sort = DriveSort.NEWEST)),
        )
    }

    @Test
    fun `longest sort is distance descending with unusable distances last`() {
        val drives =
            listOf(
                drive("small", distanceMeters = 1_000.0, createdAtMillis = 3L),
                drive("big", distanceMeters = 90_000.0, createdAtMillis = 2L),
                drive("none", distanceMeters = null, createdAtMillis = 1L),
                drive("mid", distanceMeters = 30_000.0, createdAtMillis = 4L),
            )
        assertEquals(
            listOf("big", "mid", "small", "none"),
            filter(drives, DriveFilterCriteria(sort = DriveSort.LONGEST)),
        )
    }

    @Test
    fun `fastest sort is effective average speed descending with no-speed last`() {
        val drives =
            listOf(
                // Stored speed used directly.
                drive("slow", averageSpeedMetersPerSecond = 5.0, createdAtMillis = 3L),
                drive("fast", averageSpeedMetersPerSecond = 25.0, createdAtMillis = 2L),
                // No stored speed, no distance/duration fallback -> sorts last.
                drive("noSpeed", averageSpeedMetersPerSecond = null, distanceMeters = null, durationSeconds = 0, createdAtMillis = 1L),
                drive("mid", averageSpeedMetersPerSecond = 15.0, createdAtMillis = 4L),
            )
        assertEquals(
            listOf("fast", "mid", "slow", "noSpeed"),
            filter(drives, DriveFilterCriteria(sort = DriveSort.FASTEST_AVERAGE)),
        )
    }

    @Test
    fun `sort is stable so equal keys keep newest-first incoming order`() {
        // All three share the same distance; LONGEST must preserve the input order,
        // which filterDrives receives already newest-first from the caller. Here we
        // hand sortDrives an explicit newest-first list and expect it preserved.
        val drives =
            listOf(
                drive("c", distanceMeters = 10_000.0, createdAtMillis = 300L),
                drive("b", distanceMeters = 10_000.0, createdAtMillis = 200L),
                drive("a", distanceMeters = 10_000.0, createdAtMillis = 100L),
            )
        assertEquals(
            listOf("c", "b", "a"),
            DriveFilters.sortDrives(drives, DriveSort.LONGEST).map { it.rideId },
        )
    }

    // --- Criteria ----------------------------------------------------------

    @Test
    fun `hasActiveFilters ignores sort but tracks query date and distance`() {
        assertFalse(DriveFilterCriteria().hasActiveFilters)
        assertFalse(DriveFilterCriteria(sort = DriveSort.LONGEST).hasActiveFilters)
        assertTrue(DriveFilterCriteria(query = "x").hasActiveFilters)
        assertTrue(DriveFilterCriteria(query = "  ").hasActiveFilters.not())
        assertTrue(DriveFilterCriteria(dateRange = DriveDateRange.THIS_WEEK).hasActiveFilters)
        assertTrue(DriveFilterCriteria(distanceBand = DriveDistanceBand.OVER_50_KM).hasActiveFilters)
    }

    @Test
    fun `filtering an empty list yields an empty list`() {
        assertEquals(
            emptyList<String>(),
            filter(emptyList(), DriveFilterCriteria(query = "anything")),
        )
    }
}
