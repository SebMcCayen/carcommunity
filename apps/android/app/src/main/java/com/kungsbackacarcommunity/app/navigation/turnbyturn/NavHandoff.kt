package com.kungsbackacarcommunity.app.navigation.turnbyturn

/**
 * Pure (Android-free) state machine for the map-home → turn-by-turn handoff, so
 * the decision that removes the white flash is JVM-unit-testable without Compose
 * or the Navigation SDK. Same shape as [com.kungsbackacarcommunity.app.shell.ShellNavigation]:
 * production and tests share one implementation, so they cannot drift.
 *
 * ## Why this exists
 *
 * Entering turn-by-turn does NOT dispose the shell's map — that was fixed for
 * tabs (#437), for search/long-press (#450) and for touch (#464), and the shell
 * map is composed exactly once and merely stood down here. The flash has a
 * different cause: turn-by-turn brings a **second, freshly-constructed
 * `MapView`**, and a brand-new Mapbox GL surface paints blank frames for the
 * whole duration of its first style load. Previously that raw surface was
 * mounted opaquely over the shell map the instant `navDestination` was set, so
 * the user saw those blank frames directly — the white flash.
 *
 * Both maps are plain `MapView(context)`, i.e. `SurfaceView`-backed, so the two
 * surfaces cannot simply be alpha-crossfaded over one another (a `SurfaceView`
 * is punched through the window rather than composited with the view alpha).
 * The handoff therefore goes through an opaque, **Compose-drawn** veil, which
 * does alpha-blend correctly:
 *
 * 1. [VeilIn] — the veil fades IN over the shell map's last painted frame. The
 *    nav `MapView` is NOT mounted yet, so there are no blank frames to see.
 * 2. [Loading] — the veil is fully opaque; the nav `MapView` mounts and loads
 *    its style behind it. Every blank frame lands here, hidden.
 * 3. [Revealing] — the style is up, so the veil fades OUT onto a fully-drawn
 *    navigation map.
 * 4. [Ready] — the veil is gone; steady-state navigation.
 *
 * The result reads as one continuous dissolve from the normal map into the
 * navigation view, and no white frame is ever presented.
 */
enum class NavHandoffPhase {
    /** Veil fading in over the still-painted shell map. Nav map not mounted. */
    VeilIn,

    /** Veil opaque; nav map mounted and loading its style behind it. */
    Loading,

    /** Style loaded; veil fading out to reveal the navigation map. */
    Revealing,

    /** Steady state — veil fully gone. */
    Ready,
    ;

    /**
     * Whether the nav [com.mapbox.maps.MapView] should be mounted in this phase.
     *
     * False only during [VeilIn]: mounting the map before the veil is opaque is
     * exactly what let the blank GL frames show through. This is the property
     * the regression test pins.
     */
    val mapMounted: Boolean
        get() = this != VeilIn

    /** Whether the veil is drawn at all (any non-zero alpha). */
    val veilVisible: Boolean
        get() = this != Ready

    /**
     * Target alpha for the veil in this phase. The animation runs between these
     * values; the phase machine only says where it is heading.
     */
    val veilTargetAlpha: Float
        get() =
            when (this) {
                // Fading in *to* opaque, and holding there while the style loads.
                VeilIn, Loading -> 1f
                // Fading out to nothing, and staying gone.
                Revealing, Ready -> 0f
            }
}

object NavHandoff {
    /**
     * Duration (ms) of each half of the dissolve. Matches the shell's existing
     * tab crossfade (`SHELL_TAB_FADE_MILLIS`) rather than inventing a second
     * transition tempo for the same app.
     */
    const val FADE_MILLIS: Int = 200

    /**
     * The alpha the veil is FIRST drawn at, before any animation runs.
     *
     * Zero, and it has to be stated explicitly rather than inferred from the
     * starting phase. [NavHandoffPhase.VeilIn] targets `1f`, so an animation
     * that simply initialises to its first target — which is what
     * `animateFloatAsState` does — would begin fully opaque and there would be
     * no fade IN at all: the map would hard-cut to the veil and only the
     * fade-out would animate. That is a snap wearing a dissolve's KDoc, so the
     * starting value is a named constant the UI seeds its animation with and
     * [NavHandoffTest] pins against the target.
     */
    const val VEIL_START_ALPHA: Float = 0f

    /**
     * Hard cap (ms) on how long [NavHandoffPhase.Loading] may last before the
     * veil is torn down anyway.
     *
     * A style load that never completes — no network, a bad style URI, an SDK
     * callback that simply does not fire — must not strand the user behind an
     * opaque veil forever. Timing out reveals whatever the map does have, which
     * is strictly better than a permanent blank screen: the failure mode
     * degrades to the OLD behaviour instead of to a dead screen.
     */
    const val STYLE_TIMEOUT_MILLIS: Long = 4_000

    /**
     * The phase to advance to once the veil has finished fading in.
     *
     * If the style somehow finished loading during the fade-in, skip straight to
     * revealing rather than sitting on an opaque veil over an already-drawn map.
     */
    fun afterVeilIn(styleLoaded: Boolean): NavHandoffPhase =
        if (styleLoaded) NavHandoffPhase.Revealing else NavHandoffPhase.Loading

    /**
     * The phase for the currently-loading map given the latest signals.
     *
     * @param styleLoaded whether the nav map reported its style up.
     * @param timedOut whether [STYLE_TIMEOUT_MILLIS] elapsed while loading.
     */
    fun whileLoading(styleLoaded: Boolean, timedOut: Boolean): NavHandoffPhase =
        if (styleLoaded || timedOut) NavHandoffPhase.Revealing else NavHandoffPhase.Loading
}
