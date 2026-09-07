package com.kungsbackacarcommunity.app.crownhunt

import com.kungsbackacarcommunity.app.shell.MapCrownMarker

/**
 * Turns active admin Kronjakt points (`crownHuntPoints`) into map markers, and
 * decides whether the Crown-Hunt layer should be on a member's screen at all.
 *
 * ## Why this exists
 *
 * The admin `crownHuntPoints` a member is allowed to read (status `active`) were
 * always meant for the map — the security rule that grants the read even says
 * "map display". But until now nothing DREW them: the map's only crown layer
 * read the auto-spawn collection (`crownSpawns`), which is off by default, so an
 * admin who created and activated a point saw nothing appear on the user map.
 * This mapper is the missing render step — it converts a point the member can
 * already read into the drawing primitive the surface's crown layer takes, so a
 * created point shows as a crown at its location.
 *
 * Pure Kotlin (only the drawable id is an Android resource int, resolved by the
 * caller), so "an eligible point yields a marker at its coordinate" and "hidden
 * when not participating" are unit-tested rather than inferred from an empty map.
 */
object CrownPointMarkers {
    /**
     * Whether the Crown-Hunt map layer + UI should be shown to this member.
     *
     * THREE gates, ANDed: the [featureEnabled] flag (the feature as a whole),
     * the member's own [participating] choice, and the paywall [unlocked] state
     * (see [crownHuntUnlocked]). Any one off hides the game. Pure, so the gating
     * rule is a proven property rather than a scattered `if`.
     *
     * [unlocked] defaults to true so previews/tests and any caller that predates
     * the paywall keep the pre-paywall behaviour (feature + participation only).
     */
    fun crownsVisible(
        featureEnabled: Boolean,
        participating: Boolean,
        unlocked: Boolean = true,
    ): Boolean = featureEnabled && participating && unlocked

    /** Legacy flag arguments are ignored: free accounts can participate too.
     * Account restrictions are enforced by the session and server.
     */
    @Suppress("UNUSED_PARAMETER")
    fun crownHuntUnlocked(requirePaid: Boolean, activeMember: Boolean): Boolean = true

    /**
     * The markers for [points], or an EMPTY list when [visible] is false.
     *
     * A point with a missing latitude or longitude is skipped rather than drawn
     * at (0, 0): a crown off the Gulf of Guinea is worse than a point that is
     * briefly absent from the map while its document is repaired. [glyphRes] is
     * passed in (rather than referenced here) to keep this object Android-free.
     *
     * @param inRangeIds the ids of the points the member is currently within
     *   collect range of. A point in this set is drawn in its royal magenta; one
     *   NOT in it is greyed to the neutral out-of-range slate (see
     *   [CrownMarkerStyle.adminPointDiscArgb]) so the map shows at a glance which
     *   points are reachable right now. `null` (the default) means "no live
     *   location to judge by" — which FAILS CLOSED: every point is greyed, exactly
     *   as [CrownRange.isInRange] treats a missing fix, so a crown never looks
     *   collectible before a real location proves the member is actually in range.
     *   (Before the fix this defaulted the other way — every crown lit up on app
     *   open, looking reachable while the member was far away.)
     */
    fun markers(
        points: List<CrownHuntPoint>,
        visible: Boolean,
        glyphRes: Int,
        inRangeIds: Set<String>? = null,
    ): List<MapCrownMarker> {
        if (!visible) return emptyList()
        return points.mapNotNull { point ->
            val latitude = point.latitude ?: return@mapNotNull null
            val longitude = point.longitude ?: return@mapNotNull null
            // null inRangeIds → no live location, so treat every point as OUT of
            // range (grey it): a crown only lights up once a real fix proves the
            // member is inside the ring, never on the pre-fix default.
            val inRange = inRangeIds != null && point.id in inRangeIds
            MapCrownMarker(
                id = point.id,
                longitude = longitude,
                latitude = latitude,
                discColorArgb = CrownMarkerStyle.adminPointDiscArgb(inRange),
                iconRes = glyphRes,
                glyphColorArgb = CrownMarkerStyle.adminPointGlyphColorArgb(inRange),
                // Admin points carry no glow; the halo stays reserved for the
                // legendary spawn tier so it keeps meaning "walk to that one".
                glowColorArgb = null,
            )
        }
    }
}
