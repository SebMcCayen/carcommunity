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

/**
 * PER-TARGET, range-based anti-spam gate for the wave control — the UX rule that
 * you may wave a given nearby driver only ONCE per in-range visit.
 *
 * The 45 s server cooldown ([WAVE_COOLDOWN_MS]) is a time gate on the single
 * broadcast button: when it lapses you could wave the SAME driver again while they
 * are still right next to you, which spams them. This gate layers on top of (does
 * NOT replace) that server backstop: once you wave, every driver currently in
 * range is remembered as "already waved THIS visit" and the wave is no longer
 * offered for them. Only when a driver LEAVES wave range (drops out of the
 * in-range set) is their mark cleared, so coming back INTO range later re-enables
 * waving them.
 *
 * A pure, Compose-free, Firebase-free holder so the rule is unit-testable off the
 * UI ([WaveRangeGateTest]). The caller drives it from the SAME nearby/in-range
 * roster that decides whether the wave affordance appears at all, so range
 * exit/re-entry is detected against the true wave-eligibility set.
 */
class WaveRangeGate {
    // Drivers we have already waved during their CURRENT in-range visit. A uid is
    // added on a wave and removed the instant it leaves the in-range set.
    private val wavedThisVisit = mutableSetOf<String>()

    /**
     * Records that [uid] has been waved during their current visit, so the wave is
     * no longer offered for them until they leave and re-enter range.
     */
    fun onWaved(uid: String) {
        wavedThisVisit.add(uid)
    }

    /**
     * Records that EVERY uid in [uids] has been waved this visit — the broadcast
     * case, where one tap waves every driver currently in range at once.
     */
    fun onWaved(uids: Collection<String>) {
        wavedThisVisit.addAll(uids)
    }

    /**
     * Reconciles with the CURRENT in-range roster [currentInRangeUids]: any driver
     * we had marked waved who is no longer in range is forgotten, so if they come
     * back INTO range later the wave is offered again. Drivers still in range keep
     * their mark. Call this whenever the in-range set changes.
     */
    fun onRangeSet(currentInRangeUids: Collection<String>) {
        wavedThisVisit.retainAll(currentInRangeUids.toSet())
    }

    /**
     * Whether the wave may be offered for [uid] right now: true unless they have
     * already been waved during their current in-range visit.
     */
    fun canWave(uid: String): Boolean = uid !in wavedThisVisit

    /**
     * How many of [inRangeUids] are still waveable this visit — the count the
     * visibility gate uses in place of the raw nearby count, so the control hides
     * once every in-range driver has already been waved and reappears only when a
     * not-yet-waved driver is around.
     */
    fun waveableCount(inRangeUids: Collection<String>): Int = inRangeUids.count { canWave(it) }
}
