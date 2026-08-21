package com.kungsbackacarcommunity.app.crownhunt

/**
 * Zoom-based decluttering of the AUTO-SPAWN crown layer.
 *
 * When the map is zoomed OUT it can cover a whole town, and up to the ~150-crown
 * draw cap all land on screen at once. Mapbox symbol collision is deliberately
 * disabled for crowns (`iconAllowOverlap`, so none is ever silently dropped), so
 * at low zoom they pile up and bury the basemap and the navigation route.
 *
 * Below [DECLUTTER_ZOOM] we therefore keep only the highest tiers
 * ([KEEP_WHEN_ZOOMED_OUT]): the standout crowns still say "there is treasure over
 * there", while the common/uncommon noise drops away. The full set returns as the
 * member zooms back in. Pure and tuned by the two constants below, so the rule is
 * unit-tested rather than buried in the map host.
 *
 * Applies to auto-spawn crowns ONLY — they carry a [CrownRarity] tier. Hand-placed
 * admin points have no tier and are never thinned.
 */
object CrownZoomThinning {
    /**
     * Below this Mapbox zoom the crown layer thins. At ~13 the viewport is a
     * town-sized overview where the ≥150 m-spaced crowns start to overlap; above
     * it the member is close enough that every crown is worth showing. Tune here.
     */
    const val DECLUTTER_ZOOM: Double = 13.0

    /** The tiers kept while zoomed out; commons and uncommons are dropped as noise. */
    val KEEP_WHEN_ZOOMED_OUT: Set<CrownRarity> =
        setOf(CrownRarity.RARE, CrownRarity.LEGENDARY)

    /** Whether [zoom] is far enough out that the crown layer should thin. */
    fun isZoomedOut(zoom: Double): Boolean = zoom < DECLUTTER_ZOOM

    /**
     * The spawns to draw at the current zoom: all of them when not [zoomedOut],
     * else only the [KEEP_WHEN_ZOOMED_OUT] tiers.
     */
    fun visibleSpawns(spawns: List<CrownSpawn>, zoomedOut: Boolean): List<CrownSpawn> =
        if (!zoomedOut) spawns else spawns.filter { it.rarity in KEEP_WHEN_ZOOMED_OUT }
}
