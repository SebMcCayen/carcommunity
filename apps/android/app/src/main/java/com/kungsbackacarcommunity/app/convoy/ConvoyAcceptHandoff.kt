package com.kungsbackacarcommunity.app.convoy

/**
 * Where the convoy surface goes once an invite has been answered with Accept.
 *
 * Sibling of [PostCreateNav]: the same "only a SUCCESS leaves the surface"
 * discipline, expressed as a pure enum so the decision is unit-testable off the
 * Composable and production and its test cannot drift.
 */
enum class ConvoyAcceptNav {
    /**
     * Stay on the convoy list. The accept failed (its inline error belongs on
     * the screen the member is looking at), or there is no map host to land on —
     * a config-less / test surface, where dismissing would dead-end.
     */
    Stay,

    /**
     * Dissolve out of the convoy surface and hand off to the MAP, where the
     * convoy bar reflects the convoy just joined and the camera frames the
     * group.
     */
    FadeToMap,
}

/**
 * The post-accept hand-off: accepting an invite should not leave the member
 * staring at a list, it should put them on the map with their convoy framed.
 *
 * ## Why a fade rather than a straight swap
 * Route → map is a hard cut in this shell (the route host swaps branches), and
 * cutting straight from an opaque page to the map reads as a glitch rather than
 * as an arrival. Fading the convoy surface out reveals the map that has been
 * composed underneath it the whole time (the shell composes exactly one map —
 * see AuthenticatedApp), so the dissolve lands on a real, already-drawn map and
 * flows straight into the camera's own eased convoy fit.
 *
 * ## Why these numbers
 * [FADE_MILLIS] is the shell's existing transition tempo — the same 200 ms as
 * the bottom-nav tab crossfade (`SHELL_TAB_FADE_MILLIS`) and the turn-by-turn
 * dissolve ([com.kungsbackacarcommunity.app.navigation.turnbyturn.NavHandoff.FADE_MILLIS]).
 * One app, one tempo: a third duration invented here would make the same
 * gesture feel different depending on where it started.
 *
 * Nothing here is a celebration screen. The whole hand-off is a fifth of a
 * second; the thing the member is meant to notice is the map, not the
 * transition.
 */
object ConvoyAcceptHandoff {
    /** Duration (ms) of the dissolve out of the convoy surface. */
    const val FADE_MILLIS: Int = 200

    /** Alpha the convoy surface is drawn at before the hand-off starts. */
    const val START_ALPHA: Float = 1f

    /** Alpha it fades to; the host is called once it lands here. */
    const val END_ALPHA: Float = 0f

    /**
     * What to do once `convoy.respond(accept = true)` has settled.
     *
     * @param succeeded whether the accept actually landed (no action error).
     * @param hasMapHost whether the host gave the route somewhere to hand off
     *   to. Without one there is no map to dissolve into, so the flow stays put
     *   rather than fading a page out onto nothing.
     */
    fun navFor(succeeded: Boolean, hasMapHost: Boolean): ConvoyAcceptNav =
        if (succeeded && hasMapHost) ConvoyAcceptNav.FadeToMap else ConvoyAcceptNav.Stay

    /** Target alpha for the convoy surface given whether the hand-off is running. */
    fun contentAlpha(handingOff: Boolean): Float = if (handingOff) END_ALPHA else START_ALPHA
}
