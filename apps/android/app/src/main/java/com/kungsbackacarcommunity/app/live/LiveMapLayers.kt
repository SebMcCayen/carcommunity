package com.kungsbackacarcommunity.app.live

/**
 * Which live-marker layers a map should draw right now.
 *
 * @property convoy draw the convoy awareness layer (member markers + the
 *   off-screen direction arrows).
 * @property nearby draw the nearby public live-sharer layer.
 */
data class LiveMapLayerPlan(
    val convoy: Boolean,
    val nearby: Boolean,
) {
    /** Whether there is anything at all to draw. */
    val any: Boolean get() = convoy || nearby
}

/**
 * The one rule for "is there anybody to draw on the map?", shared by the map
 * home and by turn-by-turn.
 *
 * ## Why it is shared rather than restated
 * Other members sharing a live position were invisible in navigation. The cause
 * was structural: turn-by-turn owns a second, Navigation-SDK `MapView`, and both
 * marker overlays were bound to the SHELL surface — which is stood down the
 * instant navigation starts. Fixing that meant giving navigation its own overlay
 * slot, and a second slot is a second place for the "who is on the map?"
 * decision to live, i.e. a second place for it to drift. It lives here instead,
 * and both call sites go through it.
 *
 * ## Entitlement
 * This function deliberately takes ROSTER SIZES, not identities, permissions or
 * repositories, and it cannot widen anything: it is fed the very lists the map
 * home already renders, which were produced by the host's existing gated data
 * path — the convoy backend's `livePositionUids` (accepted members only), the
 * `live.listNearby` discovery callable, and one per-uid RTDB read each under the
 * `liveLocation/$uid/latest` rules. A viewer who may not see a member never has
 * them in the list, so they cannot be drawn on either map. There is no
 * navigation-only source and no second subscription.
 */
object LiveMapLayers {
    fun plan(convoyMemberCount: Int, nearbySharerCount: Int): LiveMapLayerPlan =
        LiveMapLayerPlan(
            convoy = convoyMemberCount > 0,
            nearby = nearbySharerCount > 0,
        )
}
