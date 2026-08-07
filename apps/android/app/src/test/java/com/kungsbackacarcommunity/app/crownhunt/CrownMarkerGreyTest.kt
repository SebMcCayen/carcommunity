package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The in-range → marker-colour mapping: a crown IN range shows its rarity/admin
 * colour; OUT of range it shows the neutral out-of-range slate, and a legendary
 * loses its glow. Pure ints, so the rule is pinned here rather than eyeballed.
 */
class CrownMarkerGreyTest {
    @Test
    fun inRangeUsesRarityColourOutOfRangeUsesSlate() {
        for (rarity in CrownRarity.entries) {
            assertEquals(
                "in range → rarity disc",
                CrownMarkerStyle.discColorArgb(rarity),
                CrownMarkerStyle.discColorArgb(rarity, inRange = true),
            )
            assertEquals(
                "out of range → slate disc",
                CrownMarkerStyle.OUT_OF_RANGE_DISC,
                CrownMarkerStyle.discColorArgb(rarity, inRange = false),
            )
        }
    }

    @Test
    fun outOfRangeSlateIsDistinctFromEveryRarityAndTheCommonPewter() {
        for (rarity in CrownRarity.entries) {
            assertNotEquals(
                "slate must not collide with a rarity disc ($rarity)",
                CrownMarkerStyle.discColorArgb(rarity),
                CrownMarkerStyle.OUT_OF_RANGE_DISC,
            )
        }
    }

    @Test
    fun adminPointGreysOutOfRange() {
        assertEquals(
            CrownMarkerStyle.ADMIN_POINT_DISC,
            CrownMarkerStyle.adminPointDiscArgb(inRange = true),
        )
        assertEquals(
            CrownMarkerStyle.OUT_OF_RANGE_DISC,
            CrownMarkerStyle.adminPointDiscArgb(inRange = false),
        )
    }

    @Test
    fun legendaryGlowsOnlyInRange() {
        assertEquals(
            CrownMarkerStyle.glowColorArgb(CrownRarity.LEGENDARY),
            CrownMarkerStyle.glowColorArgb(CrownRarity.LEGENDARY, inRange = true),
        )
        assertNull(
            "out of range → no halo, even for a legendary",
            CrownMarkerStyle.glowColorArgb(CrownRarity.LEGENDARY, inRange = false),
        )
    }

    @Test
    fun outOfRangeGlyphKeepsAReadableContrastChoice() {
        // The glyph tint is one of the two ring/glyph constants, chosen for
        // contrast — never an arbitrary colour.
        val glyph = CrownMarkerStyle.outOfRangeGlyphColorArgb()
        assertEquals(
            true,
            glyph == com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle.GLYPH_LIGHT ||
                glyph == com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle.GLYPH_DARK,
        )
    }
}
