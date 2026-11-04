package com.kungsbackacarcommunity.app.location

/** A continuous inability to use fresh positions, rather than a lifetime count of GPS noise. */
internal class LivePositionFailureWindow {
    private var firstRejectedAt: Long? = null
    private var lastRejectedAt: Long? = null
    private var lastRejectedFixAt: Long? = null
    private var count = 0

    fun onRejected(verdict: LiveFixVerdict, nowMillis: Long, recordedAtMillis: Long? = nowMillis): Boolean {
        // Replayed snapshots do not show a new failure or keep a failure alive.
        if (verdict == LiveFixVerdict.REJECT_NOT_NEWER) return false
        if (verdict == LiveFixVerdict.ACCEPT) {
            reset()
            return false
        }
        if (lastRejectedAt?.let { nowMillis < it } == true) reset()
        // A bad seed has no accepted anchor, so quality can reject it before
        // checking its timestamp. Roster refreshes are not fresh measurements.
        // Legacy timestamp-less records remain local evidence only.
        val fixAt = recordedAtMillis ?: return false
        val previousFixAt = lastRejectedFixAt
        if (previousFixAt != null && fixAt <= previousFixAt) return false
        lastRejectedFixAt = fixAt
        val previous = lastRejectedAt
        if (previous == null || nowMillis - previous > MAX_GAP_MS) {
            firstRejectedAt = nowMillis
            count = 0
        }
        lastRejectedAt = nowMillis
        count++
        return count >= MIN_REJECTIONS && nowMillis - firstRejectedAt!! >= MIN_FAILURE_MS
    }

    fun reset() {
        firstRejectedAt = null
        lastRejectedAt = null
        lastRejectedFixAt = null
        count = 0
    }

    companion object {
        const val MIN_FAILURE_MS = 60_000L
        const val MAX_GAP_MS = 30_000L
        const val MIN_REJECTIONS = 5
    }
}
