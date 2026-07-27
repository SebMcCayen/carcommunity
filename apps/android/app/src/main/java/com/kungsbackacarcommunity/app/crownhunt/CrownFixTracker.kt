package com.kungsbackacarcommunity.app.crownhunt

/**
 * Holds the rolling pair of position fixes a claim needs: the latest one, and
 * the newest EARLIER one that is far enough back in time to prove the member has
 * been standing still.
 *
 * `crownHunt.claimSpawn` will not accept a single sample — a reported speed of
 * zero is just a number the client sent — so it wants two fixes
 * [CrownSpawnLimits.MIN_DWELL_SECONDS]..[CrownSpawnLimits.MAX_DWELL_SECONDS]
 * apart and derives its own speed from the pair. Something has to remember the
 * earlier one; this is that something, and it is pure so the window arithmetic
 * is unit-tested rather than discovered by sitting in a car park.
 *
 * ## Why "newest earlier fix" and not "oldest kept"
 *
 * The pair should describe the member's CURRENT dwell, not the longest one
 * available. Holding the oldest fix in the window would mean that after five
 * minutes parked, every claim was evaluated against a five-minute-old
 * coordinate — which the server rejects the moment it passes
 * [CrownSpawnLimits.MAX_DWELL_SECONDS], turning a stationary member's claim into
 * an inexplicable refusal. Taking the newest fix that is at least the minimum
 * dwell old keeps the pair as tight as the rule permits.
 *
 * Not thread-safe; it is driven from a single location callback.
 */
class CrownFixTracker {
    private val recent = ArrayDeque<CrownFix>()

    /** The most recent fix, or null before the first one lands. */
    var latest: CrownFix? = null
        private set

    /**
     * Records [fix] and prunes anything now outside the dwell window.
     *
     * An OUT-OF-ORDER fix (an older timestamp than the latest — clock adjustment,
     * or a provider replaying a cached sample) is dropped rather than accepted:
     * inserting it would let the pair span backwards in time, and the server
     * would read the resulting negative interval as a malformed claim.
     */
    fun record(fix: CrownFix) {
        val current = latest
        if (current != null && fix.recordedAtMillis < current.recordedAtMillis) return
        latest = fix
        recent.addLast(fix)
        val cutoff = fix.recordedAtMillis - CrownSpawnLimits.MAX_DWELL_SECONDS * 1000
        while (recent.isNotEmpty() && recent.first().recordedAtMillis < cutoff) {
            recent.removeFirst()
        }
    }

    /**
     * The earlier half of the stationary proof, or null when no fix in the
     * window is old enough yet.
     *
     * Null is the honest answer for the first few seconds after arriving; the UI
     * turns it into "wait a moment", never into a refusal.
     */
    fun proofPartner(): CrownFix? {
        val current = latest ?: return null
        val minGapMs = CrownSpawnLimits.MIN_DWELL_SECONDS * 1000
        return recent
            .asReversed()
            .firstOrNull { current.recordedAtMillis - it.recordedAtMillis >= minGapMs }
    }

    /** Forgets everything — used when the map tab is left, so a stale pair cannot be reused. */
    fun clear() {
        recent.clear()
        latest = null
    }
}
