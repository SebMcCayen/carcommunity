package com.kungsbackacarcommunity.app.drives

/**
 * Effective subscription tier as reported by the server-authoritative drives
 * history/stats callables. The wire value is authoritative (the client never
 * sends it); [UNKNOWN] is only a defensive fallback for an unrecognised string,
 * treated by the UI as the most-restrictive Community behaviour.
 */
enum class DriveSubscriptionTier {
    COMMUNITY,
    PLUS,
    SUPPORTER,
    UNKNOWN,
}

/**
 * One server-authoritative page of saved-drive history ([drives-listHistory]).
 *
 * The callable enforces tier visibility (Community = newest 5 with NO paging,
 * Plus = a rolling 90-day window, Supporter = the complete history), so the
 * client never queries `rides` directly for this list. [hiddenDriveCount] and
 * [hasTierRestrictedHistory] are populated ONLY on the first page (cursor-less
 * request) and are null on appended pages — they drive the upgrade banner.
 */
data class DriveHistoryPage(
    val tier: DriveSubscriptionTier,
    val drives: List<SavedDrive>,
    val hasMore: Boolean,
    /** Cursor for the next page, or null when this tier cannot page / no more pages. */
    val nextCursorRideId: String?,
    /** First-page-only exact count of retained-but-hidden drives; null on later pages. */
    val hiddenDriveCount: Int?,
    /** First-page-only "at least one older drive is hidden" flag; null on later pages. */
    val hasTierRestrictedHistory: Boolean?,
)

/**
 * Free server-authoritative statistics over all retained owner drives
 * ([drives-stats]). Aggregates never include individual drive IDs or routes;
 * history browsing remains separately tier-limited.
 *
 * [fastestAverageSpeedMps] / [highestMaxSpeedMps] are null when NO retained
 * drive contributes one (the server returns 0 for "none"; the mapper normalises
 * that to null so the screen renders the missing-value dash rather than a false
 * "0 km/h"). [thisMonthDrives] / [thisMonthDistanceMeters] are 0 when no month
 * range was supplied.
 */
data class DriveStatsSnapshot(
    val tier: DriveSubscriptionTier,
    val totalDrives: Int,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Long,
    val longestDriveMeters: Double,
    val averageDriveMeters: Double,
    val fastestAverageSpeedMps: Double?,
    val highestMaxSpeedMps: Double?,
    val thisMonthDrives: Int,
    val thisMonthDistanceMeters: Double,
)

/**
 * Thrown by [DriveHistoryRepository] for ANY history/stats read failure. [code]
 * is the callable status name (e.g. `PERMISSION_DENIED`, `UNAVAILABLE`), carried
 * out of the Firebase layer so the pure domain/UI can classify and the auto error
 * report files a stable, fingerprintable code instead of free text. Null when the
 * failure carried no callable status (a raw network/IO or protocol fault).
 */
class DriveHistoryException(
    val code: String?,
    cause: Throwable? = null,
) : Exception(cause)

/**
 * Server-authoritative read side of saved drives (slice B1): the tier-gated
 * history list ([drives-listHistory]) and the statistics aggregate
 * ([drives-stats]). Deliberately SEPARATE from [DrivesRepository]: the owner
 * `observeDrives` query stays for the profile "my stats" lifetime fold (and other
 * released clients) until the later Firestore lockdown PR, whereas THESE reads are
 * the History and Statistics screens use with their distinct access policies.
 * Firebase-free interface for testability.
 */
interface DriveHistoryRepository {
    /**
     * Fetches one tier-visible page of history. [cursorRideId] is the
     * [DriveHistoryPage.nextCursorRideId] of the previous page (null for the first
     * page); [pageSize] is clamped server-side to 1..25. Community rejects any
     * cursor (it cannot page), so callers must only page for paid tiers.
     *
     * @throws DriveHistoryException on any failure, carrying the callable status.
     */
    suspend fun listHistory(cursorRideId: String?, pageSize: Int?): DriveHistoryPage

    /**
     * Fetches the free lifetime statistics aggregate. [monthStartMillis] /
     * [monthEndMillis] define the viewer's LOCAL calendar month for the
     * "this month" fields and MUST be supplied together (or both null); the server
     * validates them strictly against its own clock.
     *
     * @throws DriveHistoryException on any failure, carrying the callable status.
     */
    suspend fun fetchStats(monthStartMillis: Long?, monthEndMillis: Long?): DriveStatsSnapshot
}

/** Default page size for paid-tier history paging (backend max is 25). */
const val DRIVE_HISTORY_PAGE_SIZE: Int = 25

/**
 * Pure wire→domain mapping for the drives history/stats callable responses, kept
 * out of the Firebase layer so it is JVM-unit-testable without the SDK. The
 * callables return plain JSON (numbers, not Firestore Timestamps), so every
 * timestamp field is already epoch-millis.
 */
object DriveHistoryMapper {
    fun tierFromWire(raw: Any?): DriveSubscriptionTier =
        when ((raw as? String)?.lowercase()) {
            "community" -> DriveSubscriptionTier.COMMUNITY
            "plus" -> DriveSubscriptionTier.PLUS
            "supporter" -> DriveSubscriptionTier.SUPPORTER
            else -> DriveSubscriptionTier.UNKNOWN
        }

    /**
     * Maps one `drives[]` entry to a [SavedDrive], or null when it lacks a usable
     * rideId or durationSeconds — mirrors the backend's own drop of a malformed
     * drive so a corrupt row can never break the list. All other fields degrade to
     * null (the UI's placeholder path), never a fabricated value.
     */
    fun driveFromWire(raw: Any?): SavedDrive? {
        val map = raw as? Map<*, *> ?: return null
        val rideId = (map["rideId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val duration = (map["durationSeconds"] as? Number)?.toLong() ?: return null
        if (duration < 0) return null
        return SavedDrive(
            rideId = rideId,
            title = (map["title"] as? String)?.takeIf { it.isNotBlank() },
            distanceMeters = nonNegativeDoubleOrNull(map["distanceMeters"]),
            durationSeconds = duration,
            averageSpeedMetersPerSecond = nonNegativeDoubleOrNull(map["averageSpeedMetersPerSecond"]),
            startedAtMillis = (map["startedAtMillis"] as? Number)?.toLong(),
            endedAtMillis = (map["endedAtMillis"] as? Number)?.toLong(),
            createdAtMillis = (map["createdAtMillis"] as? Number)?.toLong(),
            maxSpeedMetersPerSecond = nonNegativeDoubleOrNull(map["maxSpeedMetersPerSecond"]),
            routeThumbnail = (map["routeThumbnail"] as? String)?.takeIf { it.isNotBlank() },
            carImagePath = (map["carImagePath"] as? String)?.takeIf { it.isNotBlank() },
            // NOTE: routePath / previewImagePath / sourceSessionId are deliberately
            // absent from this list model — route replay is slice B2, so the list
            // never depends on them (SavedDrive carries no such field).
            convoyMembers = ConvoyDriveMembers.parse(map["convoyMembers"]),
        )
    }

    fun pageFromWire(data: Map<*, *>?): DriveHistoryPage {
        val drives =
            (data?.get("drives") as? List<*>)
                ?.mapNotNull { driveFromWire(it) }
                ?: emptyList()
        return DriveHistoryPage(
            tier = tierFromWire(data?.get("tier")),
            drives = drives,
            hasMore = data?.get("hasMore") as? Boolean ?: false,
            nextCursorRideId = (data?.get("nextCursorRideId") as? String)?.takeIf { it.isNotBlank() },
            hiddenDriveCount = (data?.get("hiddenDriveCount") as? Number)?.toInt(),
            hasTierRestrictedHistory = data?.get("hasTierRestrictedHistory") as? Boolean,
        )
    }

    fun statsFromWire(data: Map<*, *>?): DriveStatsSnapshot =
        DriveStatsSnapshot(
            tier = tierFromWire(data?.get("tier")),
            totalDrives = (data?.get("totalDrives") as? Number)?.toInt() ?: 0,
            totalDistanceMeters = doubleOrZero(data?.get("totalDistanceMeters")),
            totalDurationSeconds = (data?.get("totalDurationSeconds") as? Number)?.toLong() ?: 0L,
            longestDriveMeters = doubleOrZero(data?.get("longestDriveMeters")),
            averageDriveMeters = doubleOrZero(data?.get("averageDriveMeters")),
            // The server returns 0 for "no drive with this stat"; normalise to null
            // so the screen shows the missing-value dash, never a false "0 km/h".
            fastestAverageSpeedMps = positiveDoubleOrNull(data?.get("fastestAverageSpeedMps")),
            highestMaxSpeedMps = positiveDoubleOrNull(data?.get("highestMaxSpeedMps")),
            thisMonthDrives = (data?.get("thisMonthDrives") as? Number)?.toInt() ?: 0,
            thisMonthDistanceMeters = doubleOrZero(data?.get("thisMonthDistanceMeters")),
        )

    private fun nonNegativeDoubleOrNull(value: Any?): Double? {
        val d = (value as? Number)?.toDouble() ?: return null
        return if (d.isFinite() && d >= 0) d else null
    }

    private fun positiveDoubleOrNull(value: Any?): Double? {
        val d = (value as? Number)?.toDouble() ?: return null
        return if (d.isFinite() && d > 0) d else null
    }

    private fun doubleOrZero(value: Any?): Double {
        val d = (value as? Number)?.toDouble() ?: return 0.0
        return if (d.isFinite() && d >= 0) d else 0.0
    }
}
