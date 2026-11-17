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
    fun `zoomed out retains every rarity for accessible density groups`() {
        val kept = CrownZoomThinning.visibleSpawns(all, zoomedOut = true)
        assertEquals(all, kept)
    }

    @Test
    fun `common crowns remain accessible when zoomed out`() {
        val onlyCommons = listOf(spawn("c1", CrownRarity.COMMON), spawn("c2", CrownRarity.UNCOMMON))
        assertEquals(onlyCommons, CrownZoomThinning.visibleSpawns(onlyCommons, zoomedOut = true))
    }
}
