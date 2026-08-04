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

    /**
     * The window every session now starts with (single AND convoy): 6 hours, i.e.
     * the hard cap ([LIVE_SESSION_MAX_MS]). A session simply runs to 6h and
     * auto-stops — nothing prompts the user to prolong it. The shorter keys above
     * are kept only for backward compatibility (sessions/older clients that still
     * carry them); mirror of the backend LIVE_SESSION_DURATIONS map.
     */
    SIX_HOURS("6h", 6),
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
     * The window a Single (solo) live session starts with. Starting a Single
     * session is IMMEDIATE — the user is no longer asked to pick a time — so this
     * fixed default is the single source of truth for every Single-session start
     * (the shell's start path AND [LiveLocationScreen]). It is the 6h hard cap
     * ([LIVE_SESSION_MAX_MS]): the session runs for 6 hours and then auto-stops,
     * with nothing asking the user to prolong it. The user can Stop (or Hide me
     * now) at any time. The backend `live-startSession` callable still requires a
     * `duration`; this `6h` key is passed through unchanged. Convoy sessions match
     * this — their window is the same 6h cap, chosen server-side.
     */
    val DEFAULT_SESSION_DURATION: LiveSessionDuration = LiveSessionDuration.SIX_HOURS

    /**
     * Absolute hard cap on any one live-sharing window (single AND convoy — a
     * convoy member shares through the same session node). The CLIENT copy of the
     * server's `LIVE_SESSION_MAX_MS` (functions/src/live/live-core.ts). The two
     * cannot literally share a constant across the Kotlin/TS boundary, so they are
     * defined on each side and their agreement is asserted by tests
     * ([com.kungsbackacarcommunity.app.live.LiveLocationTest] here,
     * live-core.test.ts on the server). Retune in BOTH places.
     */
    const val LIVE_SESSION_MAX_MS: Long = 6 * 60 * 60 * 1000L // 6 hours

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
