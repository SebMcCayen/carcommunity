package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrownZoomThinningTest {
    private fun spawn(id: String, rarity: CrownRarity): CrownSpawn =
        CrownSpawn(
            id = id,
            latitude = 0.0,
            longitude = 0.0,
            rarity = rarity,
            rewardPoints = rarity.rewardPoints,
            collectRadiusMeters = 20.0,
            expiresAtMillis = null,
        )

    private val all =
        listOf(
            spawn("c", CrownRarity.COMMON),
            spawn("u", CrownRarity.UNCOMMON),
            spawn("r", CrownRarity.RARE),
            spawn("l", CrownRarity.LEGENDARY),
        )

    @Test
    fun `isZoomedOut is true only strictly below the threshold`() {
        assertTrue(CrownZoomThinning.isZoomedOut(CrownZoomThinning.DECLUTTER_ZOOM - 0.01))
        // Boundary: exactly at the threshold is NOT zoomed out (full set shown).
        assertFalse(CrownZoomThinning.isZoomedOut(CrownZoomThinning.DECLUTTER_ZOOM))
        assertFalse(CrownZoomThinning.isZoomedOut(CrownZoomThinning.DECLUTTER_ZOOM + 0.01))
    }

    @Test
    fun `not zoomed out keeps every spawn`() {
        assertEquals(all, CrownZoomThinning.visibleSpawns(all, zoomedOut = false))
    }

    @Test
    fun `zoomed out keeps only rare and legendary`() {
        val kept = CrownZoomThinning.visibleSpawns(all, zoomedOut = true)
        assertEquals(listOf("r", "l"), kept.map { it.id })
        assertTrue(kept.all { it.rarity in CrownZoomThinning.KEEP_WHEN_ZOOMED_OUT })
    }

    @Test
    fun `zoomed out with no high tiers hides everything`() {
        val onlyCommons = listOf(spawn("c1", CrownRarity.COMMON), spawn("c2", CrownRarity.UNCOMMON))
        assertTrue(CrownZoomThinning.visibleSpawns(onlyCommons, zoomedOut = true).isEmpty())
    }
}
