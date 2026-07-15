package com.kungsbackacarcommunity.app.welcome

/**
 * The ordered steps of the one-time first-login welcome flow. Kept as a pure
 * (Android-free) enum + helpers so the step progression is JVM-unit-testable
 * without Compose.
 */
enum class WelcomeStep {
    /** (1) Welcome to the community. */
    Welcome,

    /** (2) What the map / live-location / convoys do. */
    Map,

    /** (3) Membership explainer + "See membership" CTA. */
    Membership,

    /** (4) Complete profile + add first car + "Get started". */
    Profile,
    ;

    companion object {
        /** The step shown first. */
        val FIRST = Welcome

        /** Total number of steps (for the "Step X of N" progress label). */
        val COUNT = entries.size
    }
}

/** Pure navigation helpers over [WelcomeStep] ordering. */
object WelcomeFlow {
    /** 1-based position of [step], for display (e.g. "Step 3 of 4"). */
    fun position(step: WelcomeStep): Int = step.ordinal + 1

    /** Whether [step] is the final step (shows "Get started" instead of "Next"). */
    fun isLast(step: WelcomeStep): Boolean = step.ordinal == WelcomeStep.COUNT - 1

    /**
     * The step after [step], or the same step when already at the last one (the
     * caller finishes the flow instead of advancing on the last step).
     */
    fun next(step: WelcomeStep): WelcomeStep =
        if (isLast(step)) step else WelcomeStep.entries[step.ordinal + 1]
}
