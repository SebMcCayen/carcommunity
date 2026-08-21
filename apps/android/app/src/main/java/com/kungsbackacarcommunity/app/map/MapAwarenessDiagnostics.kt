package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry.ProjectedPoint

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
 * a viewport SIZE, and small counts describe the FAULT (a projection could not be
 * placed honestly, a member fell out of a fit) without describing WHERE anyone is.
 */
object MapAwarenessDiagnostics {

    /**
     * Why a live chip's projected pixel was (or was not) drawn this frame — the
     * draw/not-draw decision [classifyChipProjection] makes, naming the reason so a
     * hidden chip is diagnosable.
     */
    enum class ChipProjectionVerdict {
        /** Projected honestly inside the (margin-expanded) viewport: drawn. */
        ON_SCREEN,

        /** Projected honestly, but outside the viewport: hidden (nearby) / arrowed (convoy). */
        OFF_SCREEN,

        /**
         * No honest position at all: the projection was null (no map yet, the
         * stub, or the seam declined a folded point) OR the pixel was NaN /
         * infinite. Either way there is nothing to place.
         */
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

    /**
     * Whether [point] sits within [radiusPx] of any viewport corner.
     *
     * Compares SQUARED distances (`dx*dx + dy*dy` vs `radiusPx*radiusPx`) rather
     * than calling `hypot`/`sqrt` per corner, and allocates no list: this can run
     * per marker per frame on the UI thread during a fold burst, so it stays
     * arithmetic. Behaviour is identical to the straight distance test.
     */
    fun isCornerClamp(
        point: ProjectedPoint,
        viewportWidth: Float,
        viewportHeight: Float,
        radiusPx: Float = CORNER_CLAMP_RADIUS_PX,
    ): Boolean {
        if (!point.x.isFinite() || !point.y.isFinite()) return false
        val radiusSq = radiusPx * radiusPx
        return nearCorner(point.x, point.y, 0f, 0f, radiusSq) ||
            nearCorner(point.x, point.y, viewportWidth, 0f, radiusSq) ||
            nearCorner(point.x, point.y, 0f, viewportHeight, radiusSq) ||
            nearCorner(point.x, point.y, viewportWidth, viewportHeight, radiusSq)
    }

    private fun nearCorner(x: Float, y: Float, cornerX: Float, cornerY: Float, radiusSq: Float): Boolean {
        val dx = x - cornerX
        val dy = y - cornerY
        return dx * dx + dy * dy <= radiusSq
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
     * The verdict to RECORD for a nearby (on-screen-ONLY) sharer, given the raw
     * [classifyChipProjection] result — collapsing a fold/corner-clamp to plain
     * [ChipProjectionVerdict.OFF_SCREEN] so it is not counted as a chip FAULT.
     *
     * ## Why the nearby overlay needs this and the convoy overlay does not
     * The two overlays do DIFFERENT things with an off-screen member. The convoy
     * overlay draws a bearing-based EDGE ARROW, so a member behind the tilted
     * camera is still surfaced and a genuinely misplaced projection matters. The
     * nearby overlay draws a sharer ONLY while they are inside the viewport and
     * shows nothing for one who is off-screen (see NearbyLiveOverlay) — a
     * deliberate "don't pin arrows to every stranger" choice.
     *
     * On the default pitched (45°) map an off-screen sharer's coordinate has no
     * honest screen pixel, so `pixelForCoordinate` FOLDS or CORNER-CLAMPS it back
     * into view. The render decision already refuses to draw anything but
     * [ChipProjectionVerdict.ON_SCREEN], so that sharer is correctly hidden — the
     * SAME outcome as an honestly [ChipProjectionVerdict.OFF_SCREEN] one. Recording
     * the fold as a FAULT ([isFault] is true for it) instead escalates a public
     * "off-screen chip" issue for what is now normal, fully-handled behaviour:
     * every time a user pans a nearby sharer off a 3D map. That is why
     * `map.offscreenChipProjection` keeps auto-filing (issue #912) even though the
     * chip is no longer drawn in the corner — the stuck-chip render bug this
     * telemetry was built to catch was already fixed at the projection seam
     * ([com.kungsbackacarcommunity.app.shell.MapScreenPoint.trustworthy]).
     *
     * Collapsing both folds to [ChipProjectionVerdict.OFF_SCREEN] keeps the ONE
     * genuinely anomalous nearby case — a [ChipProjectionVerdict.HIDDEN_NONFINITE]
     * projection, where the SDK could not place a sharer that DOES carry a valid
     * coordinate — as the only nearby chip fault, so the escalation still fires for
     * a real projection breakdown and stays quiet for the handled off-screen case.
     */
    fun nearbyRecordVerdict(verdict: ChipProjectionVerdict): ChipProjectionVerdict =
        when (verdict) {
            ChipProjectionVerdict.HIDDEN_FOLD,
            ChipProjectionVerdict.HIDDEN_CORNER_CLAMP,
            -> ChipProjectionVerdict.OFF_SCREEN
            else -> verdict
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

    /** An atomic snapshot of a [MapAwarenessLog], for one escalation report. */
    data class ChipFaultSnapshot(
        val faultTotal: Int,
        val counts: Map<ChipProjectionVerdict, Int>,
    )

    /**
     * A device-local tally of chip projection FAULTS, and a one-shot escalation the
     * same shape as [com.kungsbackacarcommunity.app.location.LivePositionRejectionLog]:
     * [recordChip] returns true exactly once, the first time the fault count crosses
     * [escalateAfter], and the caller then fires a single aggregate report.
     *
     * ## Why an accumulating count, not a ring buffer of verdicts
     * The report needs the FAULT distribution that drove the escalation, and there
     * are only three fault verdicts, so this keeps a per-verdict count that never
     * evicts — O(1) memory regardless of how long a session runs. A bounded ring of
     * raw verdicts (the first cut) was actively wrong here: a settled frame records
     * one verdict PER SHARER (up to ~50), the vast majority ON_SCREEN/OFF_SCREEN
     * non-faults, so a single busy frame could evict every fault that triggered the
     * report before the report read them back — leaving the dominant-fault `code`
     * stale or "UNKNOWN" even though [faultTotal] had crossed the threshold.
     * Non-fault verdicts are therefore not stored at all: they cannot displace a
     * fault, and the report never needs their counts.
     */
    class MapAwarenessLog(
        private val escalateAfter: Int = ESCALATE_AFTER_FAULTS,
    ) {
        private val faultCounts = HashMap<ChipProjectionVerdict, Int>()
        private var faultTotal = 0
        private var escalated = false
        private val lock = Any()

        /**
         * Records one chip verdict; non-faults are ignored (they cannot evict a
         * fault and the report never needs them).
         *
         * @return true exactly once per [reset] cycle — when the fault count first
         *   reaches [escalateAfter] — so the caller escalates without keeping its
         *   own flag.
         */
        fun recordChip(verdict: ChipProjectionVerdict): Boolean = synchronized(lock) {
            if (!isFault(verdict)) return@synchronized false
            faultCounts[verdict] = (faultCounts[verdict] ?: 0) + 1
            faultTotal++
            if (!escalated && faultTotal >= escalateAfter) {
                escalated = true
                return@synchronized true
            }
            false
        }

        /** Faults recorded since the last [reset]. */
        fun faultTotal(): Int = synchronized(lock) { faultTotal }

        /** Per-fault-verdict counts since the last [reset] (a copy). */
        fun verdictCounts(): Map<ChipProjectionVerdict, Int> =
            synchronized(lock) { HashMap(faultCounts) }

        /**
         * An ATOMIC snapshot of the fault total and per-verdict counts. Taken under
         * one lock so the escalation report's message and dedup code are computed
         * from a single consistent state, never two reads that a concurrent record
         * could have changed between.
         */
        fun snapshot(): ChipFaultSnapshot =
            synchronized(lock) { ChipFaultSnapshot(faultTotal, HashMap(faultCounts)) }

        /** Clears the counts and the escalation flag. */
        fun reset() = synchronized(lock) {
            faultCounts.clear()
            faultTotal = 0
            escalated = false
        }

        companion object {
            /**
             * Chip projection faults in one map session before a single aggregate
             * report is filed. A handful of faulty frames while panning is normal;
             * a run of them is the bug pinning a chip to a corner.
             */
            const val ESCALATE_AFTER_FAULTS = 6
        }
    }

    /**
     * A running tally + one-shot escalation for convoy-FIT faults (bug 1): fresh
     * members left out of frame while the fit is active.
     *
     * Keeps only RUNNING AGGREGATES (the fault count, the worst off-screen count,
     * the largest group seen) rather than a buffer of frames: [summary] — the one
     * thing the report reads — is derived from those aggregates directly, so, unlike
     * a bounded frame buffer, a long run of faults can never evict the numbers the
     * report needs. [summary] is itself the atomic snapshot (one lock).
     */
    class ConvoyFitLog(
        private val escalateAfter: Int = ESCALATE_AFTER_FAULTY_FITS,
    ) {
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

        /** An atomic snapshot of the running aggregates, for one report. */
        fun summary(): ConvoyFitSummary = synchronized(lock) {
            ConvoyFitSummary(
                faultyFits = faultTotal,
                worstOffScreenNonStale = worstOffScreen,
                largestMemberCount = largestMemberCount,
            )
        }

        fun reset() = synchronized(lock) {
            faultTotal = 0
            worstOffScreen = 0
            largestMemberCount = 0
            escalated = false
        }

        companion object {
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
     * same shape (corner-clamp, generic fold, or non-finite) bump ONE issue.
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
     * One line for the off-screen-chip fault: how many chip projections were
     * faulty this session, the per-reason breakdown, and the surface size band —
     * no coordinate, no uid. "Faulty" is deliberately general: the counted set is
     * every [MapAwarenessDiagnostics.isFault] verdict, which is a fold, a corner
     * clamp OR a non-finite projection — the per-reason breakdown names which.
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
        val projectionWord = if (faultTotal == 1) "projection" else "projections"
        return "Off-screen live chip: $faultTotal faulty $projectionWord on a " +
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
