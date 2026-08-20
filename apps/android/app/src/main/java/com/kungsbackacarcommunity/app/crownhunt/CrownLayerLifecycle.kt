package com.kungsbackacarcommunity.app.crownhunt

/**
 * What the map host should do with the auto-spawn crown layer for a given
 * (is-the-map-tab-showing, is-the-feature-enabled) pair — extracted as a PURE
 * decision so the retain-vs-clear rule is unit-tested rather than living only
 * inside a Compose `LaunchedEffect`.
 *
 * ## Why this exists — the "reload flash on nav return" fix
 *
 * The crown poll effect used to `clear()` the layer whenever the Map tab was
 * covered by ANY other page (History, Garage, Social …). Because the map surface
 * itself is composed once and never disposed — every page draws OVER it — the
 * crowns were still on a live GL surface underneath; clearing the state on the
 * way out tore those markers down (a full despawn) and returning to the map
 * cold-re-polled and re-spawned every crown from scratch, so the whole layer
 * visibly reloaded and replayed its spawn animation on every back-navigation.
 *
 * Leaving the map while the feature is still ON is NOT the feature going away, so
 * the layer must be RETAINED, not cleared: the poll simply stops, the already-
 * loaded crowns stay drawn on the persistent surface, and the next poll on return
 * diffs against the retained set ([CrownMarkerAnimator.sync] is idempotent for an
 * unchanged set, so no crown re-animates). Only the feature genuinely going away
 * — the flag flipping off or the member opting out — still CLEARs, because a
 * disabled feature must not leave stale crowns painted.
 */
enum class CrownLayerAction {
    /** On the map with the feature on: keep the layer live (run the poll). */
    POLL,

    /**
     * Covered by another tab/page but the feature is still on: STOP polling yet
     * KEEP the already-loaded crowns, so returning to the map shows the same set
     * without a reload flash or a replayed spawn animation.
     */
    RETAIN,

    /**
     * The feature went away (flag off or the member opted out): take the layer
     * DOWN so no stale crowns stay painted.
     */
    CLEAR,
}

/** The pure retain-vs-clear-vs-poll rule for the crown layer. */
object CrownLayerLifecycle {
    /**
     * @param onMapTab whether the Map tab is the one currently selected.
     * @param enabled whether the auto-spawn crown feature is enabled for this
     *   member right now (feature flag AND spawn flag AND participation).
     */
    fun actionFor(onMapTab: Boolean, enabled: Boolean): CrownLayerAction =
        when {
            !enabled -> CrownLayerAction.CLEAR
            onMapTab -> CrownLayerAction.POLL
            else -> CrownLayerAction.RETAIN
        }
}
