package com.kungsbackacarcommunity.app.drives

/**
 * Client-side search + filter + sort for the saved-drives (History) list.
 *
 * The History read model ([FirebaseDrivesRepository.observeDrives]) is an owner
 * query with NO `limit`, so the snapshot already carries the member's ENTIRE
 * drive history in memory. Filtering that in-memory list is therefore complete
 * (a real "no matches", never a "no matches on this page"). The same caveat as
 * [DriveStatsCalculator] applies: if the read model ever gains pagination, these
 * results would silently become "loaded drives only" and would need a backend
 * query instead of this fold.
 *
 * Every predicate is a pure function of the drive plus the criteria and the
 * caller-supplied period boundaries, so it is fully unit-testable. Time-zone /
 * calendar concerns (resolving "this week" / "this month" to epoch-millis) live
 * at the composable edge and are injected here as plain [Long]s, mirroring
 * [DriveStatsScreen].
 */

/** Date-range presets offered by the History filter. */
enum class DriveDateRange { ALL, THIS_WEEK, THIS_MONTH }

/**
 * Distance bands (km) offered by the History filter. A drive with no usable
 * distance (null / negative / non-finite) matches only [ALL] — it is never
 * forced into a numeric band where it would misrepresent the band.
 */
enum class DriveDistanceBand { ALL, UNDER_10_KM, FROM_10_TO_50_KM, OVER_50_KM }

/** Sort order for the History list. None of these ever hides a drive. */
enum class DriveSort { NEWEST, LONGEST, FASTEST_AVERAGE }

/**
 * The full search/filter/sort state for the History list.
 *
 * [hasActiveFilters] deliberately EXCLUDES [sort]: a sort change only reorders
 * the list, it can never empty it, so a non-default sort must not trigger the
 * "no drives match — clear filters" empty state or the clear affordance.
 */
data class DriveFilterCriteria(
    val query: String = "",
    val dateRange: DriveDateRange = DriveDateRange.ALL,
    val distanceBand: DriveDistanceBand = DriveDistanceBand.ALL,
    val sort: DriveSort = DriveSort.NEWEST,
) {
    val hasActiveFilters: Boolean
        get() =
            query.isNotBlank() ||
                dateRange != DriveDateRange.ALL ||
                distanceBand != DriveDistanceBand.ALL
}

object DriveFilters {
    private const val TEN_KM_METERS = 10_000.0
    private const val FIFTY_KM_METERS = 50_000.0

    /**
     * Filters then sorts [drives] by [criteria].
     *
     * Text search matches the drive TITLE only (case-insensitive substring,
     * trimmed). Titles are the sole free-text field on a drive; dates are served
     * far more precisely by the date-range filter than by string-matching a
     * locale/time-zone-formatted date, and a pure function cannot format one.
     * An untitled drive (null/blank title) therefore never matches a non-empty
     * query — it is reachable via the filters instead.
     *
     * @param weekStartMillis epoch-millis of the start of the current week in the
     *   viewer's local time zone (first day of week at 00:00). Used for
     *   [DriveDateRange.THIS_WEEK].
     * @param monthStartMillis epoch-millis of the start of the current calendar
     *   month in the viewer's local time zone. Used for
     *   [DriveDateRange.THIS_MONTH].
     */
    fun filterDrives(
        drives: List<SavedDrive>,
        criteria: DriveFilterCriteria,
        weekStartMillis: Long,
        monthStartMillis: Long,
    ): List<SavedDrive> {
        val query = criteria.query.trim().lowercase()
        val matched =
            drives.filter { drive ->
                matchesQuery(drive, query) &&
                    matchesDateRange(drive, criteria.dateRange, weekStartMillis, monthStartMillis) &&
                    matchesDistanceBand(drive, criteria.distanceBand)
            }
        return sortDrives(matched, criteria.sort)
    }

    /** Sorts (without filtering) — exposed for focused sort-correctness tests. */
    fun sortDrives(drives: List<SavedDrive>, sort: DriveSort): List<SavedDrive> =
        when (sort) {
            // Delegates to the shared newest-first ordering (undated drives last),
            // so the default History order is identical to [SavedDrives.sortedForList].
            DriveSort.NEWEST -> SavedDrives.sortedForList(drives)
            // Unusable distances sort last (NEGATIVE_INFINITY key). sortedByDescending
            // is stable, so ties keep their incoming (newest-first) relative order.
            DriveSort.LONGEST ->
                drives.sortedByDescending { sortableDistance(it.distanceMeters) }
            // Drives with no usable average speed sort last, same stability guarantee.
            DriveSort.FASTEST_AVERAGE ->
                drives.sortedByDescending { sortableAverageSpeed(it) }
        }

    private fun matchesQuery(drive: SavedDrive, loweredQuery: String): Boolean {
        if (loweredQuery.isEmpty()) return true
        val title = drive.title?.trim()?.lowercase()
        return !title.isNullOrEmpty() && title.contains(loweredQuery)
    }

    private fun matchesDateRange(
        drive: SavedDrive,
        range: DriveDateRange,
        weekStartMillis: Long,
        monthStartMillis: Long,
    ): Boolean {
        val boundary =
            when (range) {
                DriveDateRange.ALL -> return true
                DriveDateRange.THIS_WEEK -> weekStartMillis
                DriveDateRange.THIS_MONTH -> monthStartMillis
            }
        // Same timestamp resolution as the stats fold: prefer the drive's start,
        // fall back to its creation. An undated drive can't be placed in a period,
        // so it is excluded from every non-ALL range.
        val timestamp = drive.startedAtMillis ?: drive.createdAtMillis ?: return false
        return timestamp >= boundary
    }

    private fun matchesDistanceBand(drive: SavedDrive, band: DriveDistanceBand): Boolean {
        if (band == DriveDistanceBand.ALL) return true
        val distance = drive.distanceMeters
        if (distance == null || !distance.isFinite() || distance < 0) return false
        return when (band) {
            // Band boundaries are half-open [lower, upper): 10 km lands in 10–50,
            // 50 km lands in 50+. Never double-counted, never dropped.
            DriveDistanceBand.UNDER_10_KM -> distance < TEN_KM_METERS
            DriveDistanceBand.FROM_10_TO_50_KM ->
                distance >= TEN_KM_METERS && distance < FIFTY_KM_METERS
            DriveDistanceBand.OVER_50_KM -> distance >= FIFTY_KM_METERS
            DriveDistanceBand.ALL -> true
        }
    }

    /** Distance as a sort key; unusable distances sink to the bottom. */
    private fun sortableDistance(distanceMeters: Double?): Double =
        if (distanceMeters != null && distanceMeters.isFinite() && distanceMeters >= 0) {
            distanceMeters
        } else {
            Double.NEGATIVE_INFINITY
        }

    /** Effective average speed as a sort key; no usable speed sinks to the bottom. */
    private fun sortableAverageSpeed(drive: SavedDrive): Double =
        DriveFormatters.effectiveAverageSpeed(
            drive.averageSpeedMetersPerSecond,
            drive.distanceMeters,
            drive.durationSeconds,
        ) ?: Double.NEGATIVE_INFINITY
}
