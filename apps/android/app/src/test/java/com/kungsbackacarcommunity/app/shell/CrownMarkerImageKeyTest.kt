package com.kungsbackacarcommunity.app.shell

import com.kungsbackacarcommunity.app.crownhunt.CrownMarkerStyle
import com.kungsbackacarcommunity.app.crownhunt.CrownRarity
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A crown's style-image KEY must change when its appearance changes, even though
 * its id stays the same — the property `MapboxMapSurface.renderCrownFrame` relies
 * on to know when to swap an existing annotation's `iconImage`.
 *
 * The surface keeps a stable annotation per crown id and updates only transforms
 * (size/rotation/opacity) each frame; the bitmap is refreshed ONLY when this key
 * differs from what the annotation is currently showing. If a state change did NOT
 * move the key, the marker would stay stuck on its old bitmap — which is exactly
 * the regression this guards: the collected-by-you badge (#929) never appearing,
 * and the in-range recolour never taking effect, for an already-drawn crown.
 *
 * Pure: [CrownMarkerBitmaps.imageId] is a plain string builder and
 * [CrownMarkerStyle.spawnMarkerAppearance] is pure, so this needs no device.
 */
class CrownMarkerImageKeyTest {
    @Test
    fun `compact crown renderer invalidates cached ring images`() {
        val key = CrownMarkerBitmaps.imageId(1, 2, 3, null)
        assertTrue(key.startsWith("kcc-crown-tile-v4-"))
        assertNotEquals("kcc-crown-ring-v2-1-2-3-0", key)
    }

    @Test
    fun `rarity remains part of cached appearance while outside range`() {
        val common = CrownMarkerBitmaps.imageId(1, CrownMarkerStyle.OUT_OF_RANGE_DISC, 3, null,
            rarity = CrownRarity.COMMON)
        val rare = CrownMarkerBitmaps.imageId(1, CrownMarkerStyle.OUT_OF_RANGE_DISC, 3, null,
            rarity = CrownRarity.RARE)
        assertNotEquals(common, rare)
    }

    @Test
    fun `curated reward bands invalidate the key without per-value image churn`() {
        fun key(value: Int) = CrownMarkerBitmaps.imageId(1, 2, 3, null, rewardPoints = value)
        assertNotEquals(key(10), key(100))
        org.junit.Assert.assertEquals(key(100), key(150))
    }

    @Test
    fun `every value accent stays legible on the reading surface`() {
        val ink = 0xFF121A27.toInt()
        for (rank in 0..3) {
            val ratio = com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle.contrastRatio(
                CrownMarkerBitmaps.accent(rank), ink,
            )
            assertTrue("rank $rank contrast $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `day and night reuse ready and collected images but swap distant crowns`() {
        for (rarity in CrownRarity.entries) {
            fun key(mode: MapMode, ready: Boolean = false, collected: Boolean = false): String {
                val appearance = CrownMarkerStyle.spawnMarkerAppearance(rarity, ready, collected)
                return CrownMarkerBitmaps.imageId(1, appearance.discColorArgb,
                    appearance.glyphColorArgb, appearance.glowColorArgb,
                    collectedBadge = collected, rarity = rarity, mapMode = mode)
            }
            assertNotEquals(key(MapMode.Day), key(MapMode.Night))
            org.junit.Assert.assertEquals(key(MapMode.Day, ready = true), key(MapMode.Night, ready = true))
            org.junit.Assert.assertEquals(key(MapMode.Day, collected = true), key(MapMode.Night, collected = true))
        }
    }

    @Test
    fun `approved light rarity pairs maintain legible crown and rim contrast`() {
        val fills = intArrayOf(0xFFECE9E3.toInt(), 0xFFE6F3EB.toInt(), 0xFFEEE7FA.toInt(), 0xFFFFF0CC.toInt())
        val inks = intArrayOf(0xFF5B6168.toInt(), 0xFF1B6E4B.toInt(), 0xFF6640A0.toInt(), 0xFF946000.toInt())
        for (rank in 0..3) {
            org.junit.Assert.assertEquals(fills[rank], CrownMarkerBitmaps.lightSurface(rank))
            org.junit.Assert.assertEquals(inks[rank], CrownMarkerBitmaps.lightAccent(rank))
            assertTrue(com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle.contrastRatio(
                CrownMarkerBitmaps.lightAccent(rank), CrownMarkerBitmaps.lightSurface(rank)) >= 4.5)
        }
    }

    /** The image key the surface would compute for a crown in the given state. */
    private fun imageKey(
        rarity: CrownRarity,
        inRange: Boolean,
        collectedByYou: Boolean,
    ): String {
        val appearance = CrownMarkerStyle.spawnMarkerAppearance(rarity, inRange, collectedByYou)
        // iconRes is fixed for a given rarity and does not change with in-range /
        // collected state, so hold it constant to isolate the appearance change —
        // exactly as it is constant for one crown id on the surface.
        return CrownMarkerBitmaps.imageId(
            iconRes = 1,
            discColorArgb = appearance.discColorArgb,
            glyphColorArgb = appearance.glyphColorArgb,
            glowColorArgb = appearance.glowColorArgb,
            collectedBadge = appearance.collectedBadge,
        )
    }

    @Test
    fun `flipping collected-by-you changes the image key`() {
        val collectable = imageKey(CrownRarity.COMMON, inRange = true, collectedByYou = false)
        val collected = imageKey(CrownRarity.COMMON, inRange = true, collectedByYou = true)
        assertNotEquals(
            "a crown becoming collected-by-you must produce a NEW bitmap key so iconImage is swapped",
            collectable,
            collected,
        )
    }

    @Test
    fun `crossing into collect range changes the image key`() {
        val outOfRange = imageKey(CrownRarity.RARE, inRange = false, collectedByYou = false)
        val inRange = imageKey(CrownRarity.RARE, inRange = true, collectedByYou = false)
        assertNotEquals(
            "an out-of-range crown coming into range must produce a NEW bitmap key (slate → rarity colour)",
            outOfRange,
            inRange,
        )
    }

    @Test
    fun `the legendary halo also moves the key when it comes into range`() {
        // Legendary is the one tier that gains a glow in range; the glow is part of
        // the key, so the in/out transition must still be caught.
        val outOfRange = imageKey(CrownRarity.LEGENDARY, inRange = false, collectedByYou = false)
        val inRange = imageKey(CrownRarity.LEGENDARY, inRange = true, collectedByYou = false)
        assertNotEquals(outOfRange, inRange)
    }
}
