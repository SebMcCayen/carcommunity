package com.kungsbackacarcommunity.app.crownhunt

/** Legacy zoom seam. Static density now groups all rarities without discarding sources. */
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
    @Suppress("UNUSED_PARAMETER")
    fun visibleSpawns(spawns: List<CrownSpawn>, zoomedOut: Boolean): List<CrownSpawn> =
        spawns
}
