package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry.ProjectedPoint
import kotlin.math.hypot

/**
 * Field telemetry for the two map-camera / off-screen-projection bugs, kept as
 * pure logic so the classification and the public-safe wording are unit-tested
 * rather than trusted through Compose.
 *
 * ## What it captures and why
 * Both bugs are invisible on a screenshot after the fact and hard to reproduce on
 * a bench: an off-screen live user's chip pinned to the top-left corner, and
 * convoy members dropping off screen while the "keep everyone framed" camera is
 * on. Neither leaves a stack trace. So instead of verbose logcat, this mirrors the
 * live-position rejection detector ([com.kungsbackacarcommunity.app.location.LivePositionRejectionLog]):
 * a bounded, device-local ring buffer that only fills when something is actually
 * wrong, and escalates ONE bucketed, coordinate-free
 * `errors-reportClientError` when a burst crosses a threshold — which the backend
 * dedups by fingerprint and rate-limits, surfacing the signal as a single
 * de-duplicated issue.
 *
 * ## Privacy
 * Like the rejection log, nothing here is a coordinate or an identity. A verdict,
 * a viewport SIZE, and small counts describe the FAULT (a projection folded, a
 * member fell out of a fit) without describing WHERE anyone is.
 */
object MapAwarenessDiagnostics {

    /**
     * Why a live chip's projected pixel was (or was not) drawn this frame — the
     * same decision [NearbyChipVisibility.isVisible] makes, but naming the reason
     * so a hidden chip is diagnosable.
     */
    enum class ChipProjectionVerdict {
        /** Projected honestly inside the (margin-expanded) viewport: drawn. */
        ON_SCREEN,

        /** Projected honestly, but outside the viewport: hidden (nearby) / arrowed (convoy). */
        OFF_SCREEN,

        /** Pixel was NaN / infinite: no honest position. */
        HIDDEN_NONFINITE,

        /**
         * Pixel fell inside the viewport but its azimuth disagreed with the
         * target's true bearing — a point behind a tilted camera folded back into
         * view (mirrored through the centre).
         */
        HIDDEN_FOLD,

        /**
         * The specific fold that IS the reported bug: the pixel is clamped to a
         * viewport CORNER (typically the origin), where a bare bounds test would
         * pin the chip. Distinguished from [HIDDEN_FOLD] because a corner clamp is
         * bearing-independent and is exactly what stuck a chip to the top-left.
         */
        HIDDEN_CORNER_CLAMP,
    }

    /**
     * Radius (px) around a viewport corner within which a projected pixel is
     * judged an SDK out-of-range clamp rather than an honest near-corner position.
     * A genuinely on-screen sharer never lands within a few pixels of the exact
     * corner; the clamp lands ON it.
     */
    const val CORNER_CLAMP_RADIUS_PX: Float = 6f

    /** Whether [point] sits within [CORNER_CLAMP_RADIUS_PX] of any viewport corner. */
    fun isCornerClamp(
        point: ProjectedPoint,
        viewportWidth: Float,
        viewportHeight: Float,
        radiusPx: Float = CORNER_CLAMP_RADIUS_PX,
    ): Boolean {
        if (!point.x.isFinite() || !point.y.isFinite()) return false
        val corners =
            listOf(
                0f to 0f,
                viewportWidth to 0f,
                0f to viewportHeight,
                viewportWidth to viewportHeight,
            )
        return corners.any { (cx, cy) -> hypot(point.x - cx, point.y - cy) <= radiusPx }
    }

    /**
     * Classify one chip's projection for diagnostics, and decide whether it is
     * drawn: [ON_SCREEN][ChipProjectionVerdict.ON_SCREEN] is exactly the
     * draw-this-chip case, every other verdict is hidden.
     *
     * @param roundTripTrustworthy the renderer's OWN verdict from a coordinate
     *   round trip ([MapScreenPoint.trustworthy][com.kungsbackacarcommunity.app.shell.MapScreenPoint.trustworthy]).
     *   This is the deterministic signal — the reason the classifier is given it
     *   rather than deriving trust from the bearing alone. A point behind a tilted
     *   camera clamped to the ORIGIN CORNER can align with the target's bearing and
     *   sail past the ≤90° angle heuristic (the stuck-in-corner bug); the round
     *   trip catches it every time. False here means the pixel is a fold/clamp.
     *
     * The angle heuristic is still consulted as a SECONDARY guard: even a
     * round-trip-trusted point whose projected azimuth flatly contradicts its
     * bearing is not drawn.
     */
    fun classifyChipProjection(
        projected: ProjectedPoint?,
        roundTripTrustworthy: Boolean,
        viewportWidth: Float,
        viewportHeight: Float,
        marginPx: Float,
        expectedScreenAngle: Double,
    ): ChipProjectionVerdict {
        if (projected == null || !projected.x.isFinite() || !projected.y.isFinite()) {
            return ChipProjectionVerdict.HIDDEN_NONFINITE
        }
        val insideWithMargin =
            projected.x >= -marginPx &&
                projected.y >= -marginPx &&
                projected.x <= viewportWidth + marginPx &&
                projected.y <= viewportHeight + marginPx
        // Outside the viewport is honestly off-screen (a real coordinate to one
        // side), regardless of the round trip — the caller hides or arrows it.
        if (!insideWithMargin) return ChipProjectionVerdict.OFF_SCREEN

        val angleTrustworthy =
            ConvoyEdgeGeometry.isProjectionTrustworthy(
                point = projected,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                expectedScreenAngle = expectedScreenAngle,
            )
        if (roundTripTrustworthy && angleTrustworthy) return ChipProjectionVerdict.ON_SCREEN

        // Inside the rectangle but not believed: a fold. Name the corner-clamp
        // specifically because that is the one the angle heuristic alone accepted.
        return if (isCornerClamp(projected, viewportWidth, viewportHeight)) {
            ChipProjectionVerdict.HIDDEN_CORNER_CLAMP
        } else {
            ChipProjectionVerdict.HIDDEN_FOLD
        }
    }

    /** Whether a verdict represents a projection FAULT worth escalating. */
    fun isFault(verdict: ChipProjectionVerdict): Boolean =
        when (verdict) {
            ChipProjectionVerdict.HIDDEN_NONFINITE,
            ChipProjectionVerdict.HIDDEN_FOLD,
            ChipProjectionVerdict.HIDDEN_CORNER_CLAMP,
            -> true
            ChipProjectionVerdict.ON_SCREEN,
            ChipProjectionVerdict.OFF_SCREEN,
            -> false
        }

    /**
     * One settled convoy-fit frame: how the camera fit is doing at keeping the
     * group framed. Recorded only while the "keep everyone framed" mode is active.
     *
     * [offScreenNonStale] is the bug-1 signal: with the fit ON, a member whose
     * position is fresh should be inside the frame, and one that is off-screen
     * right after the camera settled is the fit failing to contain them.
     */
    data class ConvoyFitFrame(
        val memberCount: Int,
        val offScreenNonStale: Int,
    ) {
        /** A fit that left a fresh member out of frame is the reported failure. */
        val isFault: Boolean get() = offScreenNonStale > 0
    }

    /**
     * A BOUNDED, device-local ring buffer of map-awareness observations, and a
     * one-shot escalation the same shape as
     * [com.kungsbackacarcommunity.app.location.LivePositionRejectionLog]: [record]
     * returns true exactly once, the first time the running fault count crosses
     * [escalateAfter], and the caller then fires a single aggregate report.
     *
     * One folded projection is weather (a real pan off-screen, one settling
     * frame); a burst is a fault. The counts and worst viewport are retained for
     * the escalated report and for a future diagnostics dump; the buffer never
     * leaves the device.
     */
    class MapAwarenessLog(
        private val capacity: Int = DEFAULT_CAPACITY,
        private val escalateAfter: Int = ESCALATE_AFTER_FAULTS,
    ) {
        private val verdicts = ArrayDeque<ChipProjectionVerdict>(capacity)
        private var faultTotal = 0
        private var escalated = false
        private val lock = Any()

        /**
         * Records one chip verdict.
         *
         * @return true exactly once per [reset] cycle — when the fault count first
         *   reaches [escalateAfter] — so the caller escalates without keeping its
         *   own flag.
         */
        fun recordChip(verdict: ChipProjectionVerdict): Boolean = synchronized(lock) {
            verdicts.addLast(verdict)
            while (verdicts.size > capacity) verdicts.removeFirst()
            if (isFault(verdict)) {
                faultTotal++
                if (!escalated && faultTotal >= escalateAfter) {
                    escalated = true
                    return@synchronized true
                }
            }
            false
        }

        /** Faults recorded since the last [reset], including any already evicted. */
        fun faultTotal(): Int = synchronized(lock) { faultTotal }

        /** Per-verdict counts over the RETAINED tail — the recent shape of a burst. */
        fun verdictCounts(): Map<ChipProjectionVerdict, Int> =
            synchronized(lock) { verdicts.groupingBy { it }.eachCount() }

        /** Clears the buffer, counts and the escalation flag. */
        fun reset() = synchronized(lock) {
            verdicts.clear()
            faultTotal = 0
            escalated = false
        }

        companion object {
            const val DEFAULT_CAPACITY = 32

            /**
             * Chip projection faults in one map session before a single aggregate
             * report is filed. A handful of folded frames while panning is normal;
             * a run of them is the bug pinning a chip to a corner.
             */
            const val ESCALATE_AFTER_FAULTS = 6
        }
    }

    /**
     * A BOUNDED ring buffer + one-shot escalation for convoy-FIT faults (bug 1):
     * fresh members left out of frame while the fit is active.
     */
    class ConvoyFitLog(
        private val capacity: Int = DEFAULT_CAPACITY,
        private val escalateAfter: Int = ESCALATE_AFTER_FAULTY_FITS,
    ) {
        private val frames = ArrayDeque<ConvoyFitFrame>(capacity)
        private var faultTotal = 0
        private var worstOffScreen = 0
        private var largestMemberCount = 0
        private var escalated = false
        private val lock = Any()

        /**
         * Records one settled fit frame.
         *
         * @return true exactly once per [reset] cycle, when the count of faulty
         *   fits first reaches [escalateAfter].
         */
        fun recordFrame(frame: ConvoyFitFrame): Boolean = synchronized(lock) {
            frames.addLast(frame)
            while (frames.size > capacity) frames.removeFirst()
            largestMemberCount = maxOf(largestMemberCount, frame.memberCount)
            if (frame.isFault) {
                faultTotal++
                worstOffScreen = maxOf(worstOffScreen, frame.offScreenNonStale)
                if (!escalated && faultTotal >= escalateAfter) {
                    escalated = true
                    return@synchronized true
                }
            }
            false
        }

        fun summary(): ConvoyFitSummary = synchronized(lock) {
            ConvoyFitSummary(
                faultyFits = faultTotal,
                worstOffScreenNonStale = worstOffScreen,
                largestMemberCount = largestMemberCount,
            )
        }

        fun reset() = synchronized(lock) {
            frames.clear()
            faultTotal = 0
            worstOffScreen = 0
            largestMemberCount = 0
            escalated = false
        }

        companion object {
            const val DEFAULT_CAPACITY = 32

            /**
             * Faulty fits (a fresh member off-screen while framing) before a report
             * is filed. One settling frame can momentarily leave someone out while
             * the ease finishes; a persistent run is the fit genuinely failing.
             */
            const val ESCALATE_AFTER_FAULTY_FITS = 5
        }
    }

    /** Aggregate of a [ConvoyFitLog], for the escalated report. */
    data class ConvoyFitSummary(
        val faultyFits: Int,
        val worstOffScreenNonStale: Int,
        val largestMemberCount: Int,
    )
}

/**
 * The PUBLIC-SAFE wording of the two escalated map-awareness reports.
 *
 * `errors-reportClientError` files a world-readable GitHub issue, so — exactly
 * like [com.kungsbackacarcommunity.app.location.LivePositionRejectionReport] —
 * every string here is app-generated, coordinate-free and BUCKETED: counts and
 * the dominant verdict, never a position or an identity.
 *
 * Pure so both the wording and the fingerprint code are unit-testable.
 */
object MapAwarenessReport {

    /** Feature key (dedup fingerprint) for the off-screen-chip projection fault. */
    const val FEATURE_CHIP = "map.offscreenChipProjection"

    /** Feature key (dedup fingerprint) for the convoy-fit off-screen-member fault. */
    const val FEATURE_FIT = "map.convoyFitOffscreen"

    /**
     * The dominant fault verdict, used as the report `code` so recurrences of the
     * same shape (corner-clamp vs generic fold) bump ONE issue.
     */
    fun dominantFault(
        counts: Map<MapAwarenessDiagnostics.ChipProjectionVerdict, Int>,
    ): MapAwarenessDiagnostics.ChipProjectionVerdict? =
        counts.entries
            .filter { MapAwarenessDiagnostics.isFault(it.key) }
            // Break ties by verdict name, NOT by map iteration order: this result
            // becomes the report's dedup `code`, so a tie that resolved differently
            // between runs would split one fault into several GitHub issues.
            .maxWithOrNull(compareBy({ it.value }, { it.key.name }))
            ?.key

    fun chipCode(
        counts: Map<MapAwarenessDiagnostics.ChipProjectionVerdict, Int>,
    ): String = dominantFault(counts)?.name ?: "UNKNOWN"

    /**
     * One line for the off-screen-chip fault: how many chip projections folded
     * this session, the per-reason breakdown, and the surface size band — no
     * coordinate, no uid.
     */
    fun chipMessage(
        faultTotal: Int,
        counts: Map<MapAwarenessDiagnostics.ChipProjectionVerdict, Int>,
        viewportWidth: Float,
        viewportHeight: Float,
    ): String {
        val reasons =
            counts.entries
                .filter { MapAwarenessDiagnostics.isFault(it.key) }
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.key.name}=${it.value}" }
                .ifEmpty { "none" }
        val fixes = if (faultTotal == 1) "projection" else "projections"
        return "Off-screen live chip: $faultTotal folded/clamped $fixes on a " +
            "${sizeBucket(viewportWidth, viewportHeight)} surface; reasons $reasons"
    }

    /** Dedup code for a convoy-fit fault: fixed shape, the magnitude is in the body. */
    fun fitCode(summary: MapAwarenessDiagnostics.ConvoyFitSummary): String =
        "OFFSCREEN_MEMBER"

    /**
     * One line for the convoy-fit fault: how many settling frames left a fresh
     * member out of frame, the worst count, and the group size band.
     */
    fun fitMessage(summary: MapAwarenessDiagnostics.ConvoyFitSummary): String {
        val fits = if (summary.faultyFits == 1) "fit" else "fits"
        return "Convoy fit off-screen: ${summary.faultyFits} $fits left a fresh member " +
            "out of frame (worst ${summary.worstOffScreenNonStale} at once), " +
            "group ${memberBucket(summary.largestMemberCount)}"
    }

    /** Coarse viewport size band — a surface class, not a measurement. */
    fun sizeBucket(width: Float, height: Float): String {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return "unknown"
        val minSide = minOf(width, height)
        return when {
            minSide < 600f -> "small"
            minSide < 1200f -> "medium"
            else -> "large"
        }
    }

    /**
     * Coarse group-size band. "1" is its own bucket because a convoy of exactly
     * one other member is common (the viewer plus one), and folding it into "2-3"
     * would mislabel that count.
     */
    fun memberBucket(count: Int): String =
        when {
            count <= 0 -> "unknown"
            count == 1 -> "1"
            count <= 3 -> "2-3"
            count <= 8 -> "4-8"
            else -> "9+"
        }
}
