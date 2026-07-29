package com.kungsbackacarcommunity.app.location

import kotlin.math.roundToLong

/** Which side of the live-position path threw a fix away. */
enum class LiveFixSource {
    /** The sharing device, before the callable round trip. */
    PUBLISH,

    /** A viewing device, before the marker was moved. */
    RENDER,
}

/**
 * One discarded live-position fix, recorded so a future "my marker jumped"
 * report is analysable AFTER THE FACT instead of unreproducible.
 *
 * ## Privacy: what is deliberately NOT in here
 * **No coordinates.** Not the fix, not the previous position, not the held
 * candidate. This repo has twice had to unpick location leaks (a display name
 * left attached to a last GPS coordinate after account erasure; raw
 * `eventAttendance` coordinates needing a retention policy), and a rejection
 * trail is a location trail if it carries positions. Everything here is a
 * RELATIVE quantity — how far, how long, how accurate, how fast — which
 * describes the FAULT without describing the place. No uid or display name
 * either: the log only ever concerns fixes this device produced or drew.
 */
data class LiveFixRejection(
    /** Device clock at the moment of the decision; local-only, never uploaded. */
    val atMillis: Long,
    val source: LiveFixSource,
    val verdict: LiveFixVerdict,
    /** The fix's own reported accuracy, or null when the platform did not say. */
    val accuracyMeters: Double? = null,
    /** Displacement from the last accepted fix. A DELTA, never a position. */
    val distanceMeters: Double? = null,
    /** Interval between the two fixes' own timestamps, when both are known. */
    val deltaMillis: Long? = null,
    /** [distanceMeters] / [deltaMillis], the quantity the old speed-only filter tested. */
    val impliedSpeedMps: Double? = null,
    /** The platform's mock-provider flag, when the producer exposes it. */
    val mockProvider: Boolean? = null,
)

/** Aggregate of everything a [LivePositionRejectionLog] currently holds. */
data class LiveFixRejectionSummary(
    /** Rejections since the last reset — NOT capped by the ring buffer's size. */
    val total: Int,
    val byVerdict: Map<LiveFixVerdict, Int>,
    val worstAccuracyMeters: Double?,
    val largestJumpMeters: Double?,
    val mockProviderSeen: Boolean,
)

/**
 * A BOUNDED, device-local ring buffer of rejected live-position fixes.
 *
 * ## Why a ring buffer and not logging
 * This runs while someone is driving. An unbounded log grows for the length of a
 * session and a per-fix upload would be a network call every few seconds, so
 * neither is acceptable. A fixed [capacity] of the most recent rejections costs
 * a few hundred bytes that never grows, and — crucially — only ever fills when
 * something is actually WRONG: a healthy session records nothing at all.
 *
 * ## Where the data goes
 * - **The buffer itself never leaves the device.** [snapshot] exists for
 *   debugging and for a future diagnostics dump.
 * - **One aggregate report per run**, and only when rejections become
 *   MEANINGFUL: [record] returns true exactly once, the first time the count
 *   crosses [ESCALATE_AFTER_REJECTIONS], and the caller then fires a single
 *   `errors-reportClientError`. One bad fix is weather; a burst is a fault. The
 *   backend deduplicates by fingerprint and rate-limits 30/hour on top, so there
 *   is deliberately no second client-side throttle.
 * - **The escalated text is bucketed** ([LivePositionRejectionReport]) because
 *   that report becomes a world-readable GitHub issue. Counts and coarse
 *   magnitude bands, never a measurement trail.
 *
 * Not a singleton: the publisher and the renderer each own one, so a session
 * reset cannot wipe the other side's evidence. Synchronized because although
 * both producers are main-thread today (the fused-location callback runs on the
 * main looper, the smoother on the Compose thread), [snapshot] is meant to be
 * callable from a diagnostics path on any thread.
 */
class LivePositionRejectionLog(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val entries = ArrayDeque<LiveFixRejection>(capacity)
    private var total = 0
    private var escalated = false
    private val lock = Any()

    /**
     * Records [rejection], evicting the oldest entry once [capacity] is reached.
     *
     * @return true exactly once per [reset] cycle — the first time the running
     *   count reaches [ESCALATE_AFTER_REJECTIONS] — so the caller can escalate
     *   without keeping its own "have I reported this yet" flag.
     */
    fun record(rejection: LiveFixRejection): Boolean = synchronized(lock) {
        entries.addLast(rejection)
        while (entries.size > capacity) entries.removeFirst()
        total++
        if (!escalated && total >= ESCALATE_AFTER_REJECTIONS) {
            escalated = true
            true
        } else {
            false
        }
    }

    /** The retained rejections, oldest first. A copy — the caller cannot mutate the log. */
    fun snapshot(): List<LiveFixRejection> = synchronized(lock) { entries.toList() }

    /** Rejections recorded since the last [reset], including any already evicted. */
    fun totalRejections(): Int = synchronized(lock) { total }

    /**
     * Aggregate over the RETAINED entries, except [total], which counts every
     * rejection since the last reset. The worst values are taken from what is
     * retained; with a burst this is the recent tail, which is what a
     * post-mortem wants anyway.
     */
    fun summary(): LiveFixRejectionSummary = synchronized(lock) {
        LiveFixRejectionSummary(
            total = total,
            byVerdict = entries.groupingBy { it.verdict }.eachCount(),
            worstAccuracyMeters = entries.mapNotNull { it.accuracyMeters }.maxOrNull(),
            largestJumpMeters = entries.mapNotNull { it.distanceMeters }.maxOrNull(),
            mockProviderSeen = entries.any { it.mockProvider == true },
        )
    }

    /** Clears the buffer, the count and the escalation flag (a fresh session/run). */
    fun reset() = synchronized(lock) {
        entries.clear()
        total = 0
        escalated = false
    }

    companion object {
        /**
         * Retained rejections. Enough to show the SHAPE of a burst (which rules
         * fired, how bad the fixes were) without the buffer ever being a
         * consideration for memory on a driving phone.
         */
        const val DEFAULT_CAPACITY = 32

        /**
         * Rejections in one run before a single aggregate report is filed.
         *
         * One rejected fix is normal — a tunnel mouth, a cold start, a moment of
         * multipath — and reporting it would be noise plus a wasted slice of the
         * 30/hour per-user budget. Five in one sharing session is a device or an
         * environment doing something worth seeing.
         */
        const val ESCALATE_AFTER_REJECTIONS = 5
    }
}

/**
 * Turns a [LiveFixRejectionSummary] into the PUBLIC-SAFE text of the one
 * escalated report.
 *
 * The `errors-reportClientError` callable files a world-readable GitHub issue,
 * so the message is app-generated, coordinate-free and BUCKETED: a band such as
 * ">1000 m" says the same diagnostic thing as "1743 m" while refusing to be a
 * measurement of anything in particular. The device-local ring buffer keeps the
 * exact figures for anyone holding the phone.
 *
 * Pure so both the wording and the banding are unit-testable.
 */
object LivePositionRejectionReport {
    /** Stable feature key for the error report's dedup fingerprint. */
    const val FEATURE = "live.positionFilter"

    /**
     * Coarse magnitude band for a metre quantity; "unknown" when absent.
     *
     * The bands are half-open ([50, 200) and so on), and the LABELS say so
     * without needing interval notation: quantities reach here through [round],
     * i.e. already in whole metres, so "50-199 m" names exactly the same set as
     * "[50, 200)" and cannot be misread at the boundary. A label like
     * "50-200 m" next to a band that excludes 200 is the sort of thing that
     * costs an hour when someone is reading these figures back off a bug report.
     */
    fun bucketMeters(value: Double?): String =
        when {
            value == null || !value.isFinite() -> "unknown"
            value < 50.0 -> "<50 m"
            value < 200.0 -> "50-199 m"
            value < 1000.0 -> "200-999 m"
            else -> "1000+ m"
        }

    /**
     * The verdict that fired most often, or null when nothing is retained. Used
     * as the report's `code`, which (with [FEATURE]) is the backend dedup
     * fingerprint — so recurrences of the same fault bump one issue rather than
     * filing a new one per burst.
     */
    fun dominantVerdict(summary: LiveFixRejectionSummary): LiveFixVerdict? =
        summary.byVerdict.entries.maxByOrNull { it.value }?.key

    /** Stable dedup code, e.g. `REJECT_ACCURACY`; `UNKNOWN` when nothing is retained. */
    fun code(summary: LiveFixRejectionSummary): String =
        dominantVerdict(summary)?.name ?: "UNKNOWN"

    /**
     * One line, no coordinates, no identity: how many fixes were discarded, how
     * bad the worst one claimed to be, how far the largest discarded jump was,
     * the per-rule breakdown, and whether a mock provider was involved.
     */
    fun message(source: LiveFixSource, summary: LiveFixRejectionSummary): String {
        val reasons =
            summary.byVerdict.entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.key.name}=${it.value}" }
                .ifEmpty { "none" }
        val mock = if (summary.mockProviderSeen) ", mock provider seen" else ""
        return "Live position (${source.name.lowercase()}): ${summary.total} fixes discarded; " +
            "worst reported accuracy ${bucketMeters(summary.worstAccuracyMeters)}, " +
            "largest discarded jump ${bucketMeters(summary.largestJumpMeters)}; " +
            "reasons $reasons$mock"
    }

    /** Rounds a metre/second quantity to whole units for the local buffer. */
    fun round(value: Double?): Double? =
        value?.takeIf { it.isFinite() }?.let { it.roundToLong().toDouble() }
}
