package com.kungsbackacarcommunity.app.live

/**
 * PURE decisions for the wave-to-nearby-live-users control — no Compose, no
 * Firebase — so the visibility gate and the client cooldown mirror are
 * unit-testable off the UI ([WavePresenceTest]).
 */
object WavePresence {
    /**
     * Whether the wave control should be shown. It appears ONLY when you are
     * yourself sharing a live session AND at least one OTHER live user is within
     * range — the two conditions the server also requires (you must be sharing to
     * have an authoritative origin, and a wave with nobody nearby reaches no one).
     * Showing it otherwise would offer a button that can only fail.
     */
    fun isWaveControlVisible(isSharingLive: Boolean, nearbyLiveUserCount: Int): Boolean =
        isSharingLive && nearbyLiveUserCount > 0

    /**
     * Whether a tap may send right now: only once the client cooldown mirror at
     * [cooldownUntilMs] has elapsed. The SERVER is the real gate (it refuses an
     * early send); this just greys the icon so it dims the instant you tap.
     */
    fun isSendEnabled(nowMs: Long, cooldownUntilMs: Long): Boolean = nowMs >= cooldownUntilMs

    /**
     * The cooldown deadline to grey the icon until, after an optimistic send —
     * [nowMs] + [windowMs] (the client mirror of the server window by default).
     */
    fun cooldownUntil(nowMs: Long, windowMs: Long = WAVE_COOLDOWN_MS): Long = nowMs + windowMs
}
