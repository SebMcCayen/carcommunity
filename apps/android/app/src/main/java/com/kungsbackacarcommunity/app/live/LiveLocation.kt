package com.kungsbackacarcommunity.app.live

/**
 * Live-location domain model + pure logic (Phase 12 slice 5).
 *
 * Mirrors the backend contract (functions/src/live/live-core.ts): session
 * durations, the session status vocabulary, and the "is the caller currently
 * sharing" rule. Pure Kotlin — no Firebase/Android types — so it is
 * JVM-unit-testable and reused by both the coordinator and the screen.
 */

/** Session durations — mirror the backend LIVE_SESSION_DURATIONS map. */
enum class LiveSessionDuration(val key: String, val hours: Int) {
    ONE_HOUR("1h", 1),
    TWO_HOURS("2h", 2),
    FOUR_HOURS("4h", 4),
    ;

    companion object {
        fun fromKey(value: String?): LiveSessionDuration? =
            values().firstOrNull { it.key == value }
    }
}

/** Session status stored at liveLocation/{uid}/session.status. */
enum class LiveSessionStatus(val wire: String) {
    ACTIVE("active"),
    STOPPED("stopped"),
    EXPIRED("expired"),
    ;

    companion object {
        fun fromWire(value: String?): LiveSessionStatus? =
            values().firstOrNull { it.wire == value }
    }
}

/**
 * The caller's own session node (owner-readable at liveLocation/{uid}/session).
 * [expiresAtMillis] is the parsed ISO expiry, or null when it could not be
 * parsed — in which case an ACTIVE session is still treated as sharing so the
 * user is never left without a stop control.
 */
data class LiveSessionInfo(
    val sessionId: String,
    val status: LiveSessionStatus,
    val duration: LiveSessionDuration?,
    val expiresAtMillis: Long?,
)

object LiveLocation {
    /**
     * Whether the caller is currently sharing: an active session that has not
     * passed its expiry. Mirrors the backend isSessionActive check. A null
     * (unparseable) expiry does not hide an active session.
     */
    fun isSharing(session: LiveSessionInfo?, nowMillis: Long): Boolean =
        session != null &&
            session.status == LiveSessionStatus.ACTIVE &&
            (session.expiresAtMillis == null || session.expiresAtMillis > nowMillis)

    /** Whole seconds remaining until expiry, floored at 0; null if unknown. */
    fun remainingSeconds(session: LiveSessionInfo?, nowMillis: Long): Long? {
        val expires = session?.expiresAtMillis ?: return null
        return ((expires - nowMillis) / 1000L).coerceAtLeast(0L)
    }
}
