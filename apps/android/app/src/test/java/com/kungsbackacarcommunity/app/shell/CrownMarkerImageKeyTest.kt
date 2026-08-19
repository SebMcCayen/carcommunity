package com.kungsbackacarcommunity.app.shell

import com.kungsbackacarcommunity.app.crownhunt.CrownMarkerStyle
import com.kungsbackacarcommunity.app.crownhunt.CrownRarity
import org.junit.Assert.assertNotEquals
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
