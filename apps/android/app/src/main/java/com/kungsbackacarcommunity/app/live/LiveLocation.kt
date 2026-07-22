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
     * The window a Single (solo) live session starts with. Starting a Single
     * session is IMMEDIATE — the user is no longer asked to pick a time — so this
     * fixed default is the single source of truth for every Single-session start
     * (the shell's start path AND [LiveLocationScreen]). It preserves the picker's
     * former pre-selected default. The backend `live-startSession` callable still
     * requires a `duration`; this key is passed through unchanged. Users can still
     * Extend before expiry and Stop anytime. Convoy sessions are unaffected —
     * their per-member window is chosen server-side.
     */
    val DEFAULT_SESSION_DURATION: LiveSessionDuration = LiveSessionDuration.ONE_HOUR

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
     * How long before `expiresAt` the client shows the "still sharing? continue?"
     * extend prompt. Mirror of the server's `LIVE_SESSION_EXTEND_PROMPT_MS`. 15
     * min before a 6h window is the "5h45" checkpoint; before a shorter chosen
     * window it is simply 15 min before that window's end.
     */
    const val LIVE_SESSION_EXTEND_PROMPT_MS: Long = 15 * 60 * 1000L // 15 minutes

    /**
     * Whether the caller is currently sharing: an active session that has not
     * passed its expiry. Mirrors the backend isSessionActive check. A null
     * (unparseable) expiry does not hide an active session.
     */
    fun isSharing(session: LiveSessionInfo?, nowMillis: Long): Boolean =
        session != null &&
            session.status == LiveSessionStatus.ACTIVE &&
            (session.expiresAtMillis == null || session.expiresAtMillis > nowMillis)

    /**
     * Whether the session is inside its final [LIVE_SESSION_EXTEND_PROMPT_MS]
     * before expiry — the window in which the app asks the user to extend.
     *
     * Requires a currently-sharing session with a PARSEABLE expiry: an
     * unparseable/absent expiry has no known deadline to count down to, so there
     * is nothing to prompt about (the hard-ceiling backstop handles that case).
     * Already-expired sessions return false — the moment to extend has passed and
     * the stop path takes over.
     */
    fun isExpiringSoon(session: LiveSessionInfo?, nowMillis: Long): Boolean {
        if (!isSharing(session, nowMillis)) return false
        val expires = session?.expiresAtMillis ?: return false
        val remaining = expires - nowMillis
        return remaining in 1..LIVE_SESSION_EXTEND_PROMPT_MS
    }

    /** Whole seconds remaining until expiry, floored at 0; null if unknown. */
    fun remainingSeconds(session: LiveSessionInfo?, nowMillis: Long): Long? {
        val expires = session?.expiresAtMillis ?: return null
        return ((expires - nowMillis) / 1000L).coerceAtLeast(0L)
    }
}
