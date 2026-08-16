package com.kungsbackacarcommunity.app.coachmark

/**
 * The ordered steps of the one-time first-login coach-mark tour — short
 * chat-bubble tooltips that point at the map-home's primary controls the first
 * time a user reaches it.
 *
 * Kept as a pure (Android-free) enum + helpers, mirroring
 * [com.kungsbackacarcommunity.app.welcome.WelcomeStep] / `WelcomeFlow`, so the
 * sequence and "which step is next" logic is JVM-unit-testable without Compose.
 * The Android side ([CoachMarkOverlay]) maps each step to the control it anchors
 * to and to its title/body string resources; the enum ITSELF is the anchor key
 * ([CoachMarkAnchorRegistry] is keyed by it), so the tour order and the anchor
 * identity can never drift apart.
 *
 * Declaration order IS display order. [Drive] is deliberately FIRST: it is the
 * whole reason the tour exists (issue #845 — testers didn't realise that the
 * centre "share position / start drive" control RECORDS their drive), so the
 * newcomer sees that message before any other.
 */
enum class CoachMarkStep {
    /**
     * (1) The centre "+" control in the bottom bar — start a drive / share
     * position. Its tip makes explicit that starting it RECORDS the route
     * (issue #845). Always the first tip.
     */
    Drive,

    /** (2) The Social bottom-nav tab — friends, convoys and the community. */
    Social,

    /**
     * (3) The top-right menu button on the map home — the gateway to Crown Hunt,
     * events, the user's profile and settings.
     */
    Explore,

    /** (4) The History bottom-nav tab — where recorded drives are saved. */
    History,
    ;

    companion object {
        /** The step shown first. */
        val FIRST = Drive

        /** Total number of steps (for the "1/N" progress label). Always ≤ 4. */
        val COUNT = entries.size
    }
}

/**
 * Pure geometry for placing the bubble's tail, kept Android-free so the
 * degenerate cases are unit-testable off-device.
 */
object CoachMarkGeometry {
    /**
     * The x of the tail's tip: the target's centre, held [insetPerSide] in from
     * each of the bubble's edges so the tail stays on the card's flat run rather
     * than sliding onto a rounded corner.
     *
     * When the bubble is too narrow to fit both insets (its width is below
     * `2 * insetPerSide` — reachable now that the bubble width is clamped down to
     * `0.dp` on pathologically narrow layouts), the valid band inverts. Rather
     * than call `coerceIn` with `max < min` (which throws), fall back to the
     * bubble's horizontal centre — a well-defined, always-valid position.
     */
    fun tailCenterX(
        targetCenterX: Float,
        bubbleLeft: Float,
        bubbleWidth: Float,
        insetPerSide: Float,
    ): Float {
        val lo = bubbleLeft + insetPerSide
        val hi = bubbleLeft + bubbleWidth - insetPerSide
        return if (hi < lo) bubbleLeft + bubbleWidth / 2f else targetCenterX.coerceIn(lo, hi)
    }
}

/** Pure navigation helpers over [CoachMarkStep] ordering. */
object CoachMarkTour {
    /** The tour steps in display order. */
    val ORDERED: List<CoachMarkStep> = CoachMarkStep.entries.toList()

    /** 1-based position of [step], for the "1/4" progress label. */
    fun position(step: CoachMarkStep): Int = step.ordinal + 1

    /** Whether [step] is the final tip (its primary button reads "Done" not "Next"). */
    fun isLast(step: CoachMarkStep): Boolean = step.ordinal == CoachMarkStep.COUNT - 1

    /**
     * The step after [step], or the same step when already at the last one (the
     * caller finishes the tour instead of advancing on the last step).
     */
    fun next(step: CoachMarkStep): CoachMarkStep =
        if (isLast(step)) step else CoachMarkStep.entries[step.ordinal + 1]
}
