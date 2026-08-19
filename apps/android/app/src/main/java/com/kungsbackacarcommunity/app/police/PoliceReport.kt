package com.kungsbackacarcommunity.app.police

/**
 * A user-reported, short-lived POLICE pin drawn on the map and used to fire the
 * proximity alert (the police-proximity alert feature). The client mirror of the
 * backend `PoliceReportView` returned by `police-listNearby` / `police-report`.
 *
 * Deliberately tiny: a police pin has no votes, no notes, no age filter — it is a
 * transient "police reported here" marker that ages out on its own short TTL. The
 * `id` identifies the pin both for de-duplication and, crucially, for the
 * once-per-pin proximity de-dupe (see [PoliceProximity]).
 *
 * @property expiresAtIso the pin's server expiry as an ISO-8601 instant, or null
 *   when the payload carried none. [PoliceReport.isLiveAt] treats a null/malformed
 *   expiry as NOT live, matching the server rule that hides an expired pin.
 */
data class PoliceReport(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val source: String,
    val expiresAtIso: String?,
) {
    /**
     * Whether this pin is still live at [nowMillis] — active and strictly before
     * its expiry. A null/unparseable expiry is treated as NOT live (fail-closed),
     * so a malformed pin can never keep firing a stale alert. Mirrors the server's
     * `expiresAt > now` gate exactly (strictly greater).
     */
    fun isLiveAt(nowMillis: Long): Boolean {
        val expiry = PoliceReportTime.parseIsoMillis(expiresAtIso) ?: return false
        return expiry > nowMillis
    }
}

/** ISO-8601 parsing shared by the model and its tests, isolated for testability. */
object PoliceReportTime {
    /**
     * Parses an ISO-8601 instant (the shape the callable returns, e.g.
     * `2026-08-19T10:40:00.000Z`) to epoch millis, or null when absent/malformed.
     * Uses [java.time.Instant], which the app already targets (API 26+).
     */
    fun parseIsoMillis(iso: String?): Long? {
        val trimmed = iso?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            java.time.Instant.parse(trimmed).toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }
}
