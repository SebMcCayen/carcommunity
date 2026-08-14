package com.kungsbackacarcommunity.app.crownhunt

/**
 * Counts how many times a single crown's Collect refused for lack of a usable
 * stationary proof, and decides — ONCE per crown — when that is worth a telemetry
 * signal.
 *
 * ## Why this exists
 *
 * The dominant collect-lag cause is client-side: for the first few seconds a
 * crown popup is open there is no fix old enough to be a dwell partner, so a tap
 * yields [CrownClaimStatus.NeedsPosition] instead of a claim. The pre-warmed
 * tracker and the "confirming you're stopped" button state remove that loop in
 * the common case — but when it still bites (a cold GPS, a fix that never
 * settles) the BACKEND never hears about it: a `NeedsPosition` is resolved
 * entirely on the device and no callable is made. A separate backend detector
 * cannot see a cause that never reaches the server.
 *
 * So after [threshold] refusals for one crown this reports a small structured
 * signal through the ordinary client-error pipeline, exactly once, letting the
 * detector count this cause alongside the ones that do reach the callable.
 *
 * ## Rate limiting is structural
 *
 * The tracker is scoped to ONE crown (recreated per opened crown) and latches
 * [reported] after it fires, so a member who taps ten more times, or re-opens the
 * same popup, produces no further reports. There is no timer to get wrong and no
 * global counter to leak between crowns: the dedup is "one signal per crown, per
 * open" by construction.
 *
 * Pure Kotlin — no Android, no clock — so the threshold arithmetic is unit-tested
 * rather than observed by tapping a disabled button.
 */
class CrownCollectSignalTracker(
    private val threshold: Int = DEFAULT_THRESHOLD,
) {
    private var refusals = 0
    private var reported = false

    /**
     * Records one refused ([CrownClaimStatus.NeedsPosition]) tap.
     *
     * @return the refusal COUNT to report when this tap crosses [threshold] for
     *   the first time, or null when there is nothing (yet, or ever again) to
     *   report — below the threshold, or already reported for this crown.
     */
    fun onRefused(): Int? {
        if (reported) return null
        refusals += 1
        if (refusals < threshold) return null
        reported = true
        return refusals
    }

    companion object {
        /**
         * Three refusals before a signal: one impatient double-tap is normal and
         * says nothing; a third within the same open means the dwell genuinely is
         * not arriving and the cause is worth surfacing.
         */
        const val DEFAULT_THRESHOLD = 3

        /**
         * Stable feature key + code for the signal, fingerprinted by the backend
         * detector (and the auto-issue dedup), so they must not drift.
         */
        const val SIGNAL_FEATURE = "crownHunt.collect"
        const val SIGNAL_CODE = "crown_collect_dwell_not_ready"
    }
}
