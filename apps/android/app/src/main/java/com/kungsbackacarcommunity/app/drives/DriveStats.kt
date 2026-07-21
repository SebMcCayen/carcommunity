package com.kungsbackacarcommunity.app.drives

/**
 * Aggregated "your driving" stats folded over a member's own saved drives.
 *
 * There is NO backend aggregate: the History read model
 * ([FirebaseDrivesRepository.observeDrives]) is an owner query with no `limit`,
 * so the snapshot already carries the member's ENTIRE drive history in memory.
 * A client-side fold over that list is therefore a correct lifetime total, not a
 * per-page total. WARNING: this correctness depends on the drives list being
 * FULLY loaded (not paginated). If the read model ever gains pagination, these
 * figures would silently become "loaded drives only" and would need a backend
 * aggregate instead of this fold.
 *
 * Every figure reuses the same per-drive fields the list already shows
 * (server-computed distance/duration/average speed); nothing here reads route
 * GPS points (those are not in the read model — see [SavedDrive]).
 */
data class DriveStats(
    /** Total number of saved drives. Always > 0 (an empty list yields null). */
    val totalDrives: Int,
    /** Sum of per-drive distances; unusable (null/negative/non-finite) count as 0. */
    val totalDistanceMeters: Double,
    /** Sum of per-drive durations; negative values are clamped to 0. */
    val totalDurationSeconds: Long,
    /** Distance of the single longest drive, or 0 when no drive has a distance. */
    val longestDriveMeters: Double,
    /** [totalDistanceMeters] divided by [totalDrives] — mean distance per drive. */
    val averageDriveMeters: Double,
    /**
     * Highest per-drive average speed (m/s) across all drives, or null when no
     * drive yields a usable average speed. Uses the same effective-average-speed
     * resolution as the detail view ([DriveFormatters.effectiveAverageSpeed]).
     */
    val fastestAverageSpeedMps: Double?,
    /** Number of drives started (or, absent a start, created) this month. */
    val thisMonthDrives: Int,
    /** Sum of this month's drive distances; unusable distances count as 0. */
    val thisMonthDistanceMeters: Double,
)

object DriveStatsCalculator {
    /**
     * Folds [drives] into a [DriveStats]. Returns null for an empty list so the
     * UI can render a dedicated "no drives yet" empty state rather than a row of
     * zeroes that reads as broken.
     *
     * @param monthStartMillis epoch-millis of the start of the current calendar
     *   month in the viewer's local time zone. Computed at the call site (a
     *   [java.util.Calendar] concern) so this fold stays pure and deterministic.
     *   A drive counts toward "this month" when its start (or, if absent, its
     *   creation) timestamp is at or after this boundary.
     */
    fun compute(drives: List<SavedDrive>, monthStartMillis: Long): DriveStats? {
        if (drives.isEmpty()) return null

        var totalDistance = 0.0
        var totalDuration = 0L
        var longest = 0.0
        var fastest: Double? = null
        var monthDrives = 0
        var monthDistance = 0.0

        for (drive in drives) {
            val distance = usableDistance(drive.distanceMeters)
            totalDistance += distance
            if (distance > longest) longest = distance

            if (drive.durationSeconds > 0) totalDuration += drive.durationSeconds

            val speed =
                DriveFormatters.effectiveAverageSpeed(
                    drive.averageSpeedMetersPerSecond,
                    drive.distanceMeters,
                    drive.durationSeconds,
                )
            if (speed != null && (fastest == null || speed > fastest)) fastest = speed

            val timestamp = drive.startedAtMillis ?: drive.createdAtMillis
            if (timestamp != null && timestamp >= monthStartMillis) {
                monthDrives += 1
                monthDistance += distance
            }
        }

        return DriveStats(
            totalDrives = drives.size,
            totalDistanceMeters = totalDistance,
            totalDurationSeconds = totalDuration,
            longestDriveMeters = longest,
            averageDriveMeters = totalDistance / drives.size,
            fastestAverageSpeedMps = fastest,
            thisMonthDrives = monthDrives,
            thisMonthDistanceMeters = monthDistance,
        )
    }

    /**
     * A distance that may safely contribute to a total: null, negative, or
     * non-finite persisted distances contribute 0 (they render as an em dash on
     * their own row and must not poison a sum or a maximum).
     */
    private fun usableDistance(distanceMeters: Double?): Double =
        if (distanceMeters != null && distanceMeters.isFinite() && distanceMeters >= 0) {
            distanceMeters
        } else {
            0.0
        }
}
