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
     * TWO gates, ANDed: the [featureEnabled] flag (the feature as a whole) and
     * the member's own [participating] choice. Either off hides the game. Pure,
     * so the gating rule is a proven property rather than a scattered `if`.
     */
    fun crownsVisible(featureEnabled: Boolean, participating: Boolean): Boolean =
        featureEnabled && participating

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
     *   location to judge by" — every point is drawn in colour, exactly the
     *   pre-greying behaviour, so a build with no fix never paints the whole layer
     *   grey.
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
            // null inRangeIds → treat every point as in range (colour it), so a
            // location-less build renders exactly as before.
            val inRange = inRangeIds == null || point.id in inRangeIds
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
