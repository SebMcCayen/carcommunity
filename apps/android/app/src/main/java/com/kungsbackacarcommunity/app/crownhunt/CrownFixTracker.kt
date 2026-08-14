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
 * ## Pre-warming and best-accuracy selection
 *
 * The tracker is deliberately fed from the map's ongoing location poll BEFORE a
 * crown popup opens, not only once it is open. The first ~4 s after opening used
 * to have no fix old enough to be a proof partner, so a tap yielded "wait a
 * moment" and the member re-tapped until the second fix aged in. A warm tracker
 * usually already holds a partner the instant the popup appears, so the common
 * case needs no wait at all.
 *
 * Selection also prefers a SETTLED fix over the raw latest: [bestRecent] and
 * [proofPartnerFor] pick the best-accuracy sample in their window (breaking ties
 * towards the tightest pair), so a single jittery reading no longer fails the
 * pair with an `outside_radius` the server would otherwise compute from a warm-up
 * sample. The server still re-derives everything from the two fixes; this only
 * chooses which two to send.
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
     * The best fix to describe where the member is RIGHT NOW — the "current" half
     * of the pair, and the one the distance/speed readout is drawn from.
     *
     * Rather than the raw [latest], this is the best-accuracy sample within the
     * last [SETTLE_WINDOW_SECONDS], so one jittery reading that lands a few metres
     * out does not, on its own, compute as `outside_radius`. Ties (equal or absent
     * accuracy) break towards the NEWEST sample, which keeps freshness — the
     * server refuses a fix older than its freshness window — and preserves the old
     * "just use latest" behaviour when no accuracy is reported.
     */
    fun bestRecent(): CrownFix? {
        val current = latest ?: return null
        val windowStart = current.recordedAtMillis - SETTLE_WINDOW_SECONDS * 1000
        return recent
            .asReversed()
            .filter { it.recordedAtMillis >= windowStart }
            .minByOrNull { accuracyRank(it) }
            ?: current
    }

    /**
     * [bestRecent], but with a WALL-CLOCK freshness gate at
     * [CrownSpawnLimits.MAX_POSITION_AGE_SECONDS] — the SAME age the server accepts
     * for the current fix, so the client never rejects a fix the server would take.
     *
     * The tracker only prunes on [record], so after a long idle a stale [latest]
     * (and the pair built on it) would otherwise still read as usable — which is
     * exactly the wrong-state class of bug this feature is trying to remove:
     * seeding a popup, and enabling/labelling Collect, off a minutes-old position.
     * But the threshold must NOT be stricter than the server's, or a device with a
     * slow location cadence whose newest fix is a routine 10–30 s old would be
     * blocked from a collect the server would happily accept — re-creating the very
     * lag this PR fixes. So it rejects only genuinely stale (minutes-old) leftovers.
     *
     * The freshness filter is applied to the sample actually RETURNED, not just to
     * [latest]: [bestRecent] may prefer a more-accurate EARLIER sample from the
     * settle window, and that one has to be fresh too — otherwise a stale older fix
     * could be handed back as "recent". Passing the clock in keeps it pure/testable.
     */
    fun bestRecent(nowMillis: Long): CrownFix? {
        val current = latest ?: return null
        val freshCutoff = nowMillis - CrownSpawnLimits.MAX_POSITION_AGE_SECONDS * 1000
        // A stale newest fix means there is no trustworthy current position at all.
        if (current.recordedAtMillis < freshCutoff) return null
        val settleStart = current.recordedAtMillis - SETTLE_WINDOW_SECONDS * 1000
        return recent
            .asReversed()
            .filter { it.recordedAtMillis >= settleStart && it.recordedAtMillis >= freshCutoff }
            .minByOrNull { accuracyRank(it) }
            ?: current
    }

    /**
     * The earlier half of the stationary proof, or null when no fix in the
     * window is old enough yet.
     *
     * Null is the honest answer for the first few seconds after arriving; the UI
     * turns it into "wait a moment", never into a refusal.
     *
     * Paired against [bestRecent] so the "current" and "previous" halves are
     * chosen consistently.
     */
    fun proofPartner(): CrownFix? = proofPartnerFor(bestRecent())

    /**
     * [proofPartner], but freshness-gated on [nowMillis]: a partner only counts
     * when there is a FRESH current fix to pair it with (see [bestRecent]).
     *
     * This is the readiness check the pre-warm loop and the seed path use, so a
     * stale pair left over from an earlier visit never short-circuits the warm-up
     * or seeds Collect with old data. (The partner itself may legitimately be up to
     * [CrownSpawnLimits.MAX_DWELL_SECONDS] old — it is the CURRENT half that must
     * be recent.)
     */
    fun proofPartner(nowMillis: Long): CrownFix? = proofPartnerFor(bestRecent(nowMillis))

    /**
     * The proof partner for a specific [current] fix: the best-accuracy sample
     * that is [CrownSpawnLimits.MIN_DWELL_SECONDS]..[CrownSpawnLimits.MAX_DWELL_SECONDS]
     * older than it, or null when none has aged in yet.
     *
     * Best-accuracy rather than merely "newest old enough": a settled earlier fix
     * makes the pair the server derives its speed from tighter, so a warm-up
     * sample cannot fail an otherwise-stationary claim. Ties break towards the
     * NEWEST candidate, so the span still hugs the minimum dwell (the regression
     * [CrownFixTrackerTest] pins) whenever accuracy says nothing.
     */
    fun proofPartnerFor(current: CrownFix?): CrownFix? {
        val anchor = current ?: return null
        val minGapMs = CrownSpawnLimits.MIN_DWELL_SECONDS * 1000
        val maxGapMs = CrownSpawnLimits.MAX_DWELL_SECONDS * 1000
        return recent
            .asReversed()
            .filter {
                val gap = anchor.recordedAtMillis - it.recordedAtMillis
                gap in minGapMs..maxGapMs
            }
            .minByOrNull { accuracyRank(it) }
    }

    /**
     * Whole seconds until a proof partner will have aged in for the current fix,
     * measured against the REAL wall clock [nowMillis].
     *
     * There is deliberately no clock-less overload: the elapsed-time answer is
     * only meaningful against a true clock, and an earlier convenience overload
     * that passed the latest fix's OWN timestamp as "now" made the age compute as
     * ~0 and silently bypassed the freshness gate. Callers pass the same clock they
     * use everywhere else ([System.currentTimeMillis], or an injected one in
     * tests).
     *
     * Returns 0 once a fresh partner is available. When there is no FRESH current
     * ([bestRecent] with this clock is null — no fix yet, or the latest has aged
     * out) the wait is the full [CrownSpawnLimits.MIN_DWELL_SECONDS] again — the
     * honest "you have the whole minimum dwell ahead of you".
     *
     * Used only to put a friendly "about N s left" on the confirming button; it is
     * a hint, never a gate — the gate is [proofPartner] being non-null.
     */
    fun secondsUntilProofReady(nowMillis: Long): Int {
        val current = bestRecent(nowMillis) ?: return CrownSpawnLimits.MIN_DWELL_SECONDS.toInt()
        if (proofPartnerFor(current) != null) return 0
        val oldest = recent.firstOrNull() ?: return CrownSpawnLimits.MIN_DWELL_SECONDS.toInt()
        val ageSeconds = (current.recordedAtMillis - oldest.recordedAtMillis) / 1000.0
        val remaining = CrownSpawnLimits.MIN_DWELL_SECONDS - ageSeconds
        return if (remaining <= 0.0) 0 else kotlin.math.ceil(remaining).toInt()
    }

    /** Forgets everything — used when the map tab is left, so a stale pair cannot be reused. */
    fun clear() {
        recent.clear()
        latest = null
    }

    /**
     * Sort key for accuracy: a smaller reported radius is better. A fix that
     * carries no accuracy sorts LAST, so a reading with a known-good radius is
     * always preferred over one that says nothing — but a window made entirely of
     * accuracy-less fixes still resolves (they all tie and the newest wins).
     */
    private fun accuracyRank(fix: CrownFix): Double =
        fix.accuracyMeters?.takeIf { it.isFinite() && it >= 0.0 } ?: Double.MAX_VALUE

    private companion object {
        /**
         * How far back [bestRecent] looks for a settled "current" fix. Two claim
         * cadences (2 s) wide, so the best of the last few samples is eligible
         * while staying comfortably inside the server's freshness window.
         */
        const val SETTLE_WINDOW_SECONDS = 6L
    }
}
