package com.kungsbackacarcommunity.app.incidents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Legibility of the incident markers, measured rather than eyeballed.
 *
 * Modelled on `TrafficPaletteTest`, which exists because the night traffic
 * colours were "obviously fine" right up until they were not. Same reasoning
 * applies here: an icon that vanishes on the night basemap is exactly the sort
 * of regression a human reviewer signs off on, because they are looking at the
 * day map when they review it.
 */
class IncidentMarkerStyleTest {
    /** WCAG AA for normal text. A glyph at map scale is small, so this is the right bar. */
    private val minGlyphContrast = 4.5

    /** WCAG AA for non-text/UI boundaries — the bar for the marker's edge. */
    private val minRingContrast = 3.0

    /**
     * Every category's glyph must be readable on its own disc.
     *
     * This is why the glyph colour is chosen per category instead of being a
     * fixed white: white measures 1.66:1 on the amber HAZARD disc and 2.70:1 on
     * the orange ROADWORK disc — both unreadable — while clearing 4.5:1 on the
     * red, blue and purple ones. A single glyph colour cannot serve this
     * palette, and this test is what stops someone "tidying" it into one.
     */
    @Test
    fun everyCategoryGlyphIsReadableOnItsOwnDisc() {
        for (type in IncidentType.entries) {
            val disc = IncidentPalette.colorArgb(type)
            val glyph = IncidentMarkerStyle.glyphColorArgb(type)
            val contrast = IncidentMarkerStyle.contrastRatio(glyph, disc)
            assertTrue(
                "$type: glyph contrast against its disc is %.2f:1, below the %.1f:1 floor"
                    .format(contrast, minGlyphContrast),
                contrast >= minGlyphContrast,
            )
        }
    }

    /**
     * A fixed white glyph would FAIL the above — pinned explicitly so the
     * rationale is testable, not just narrated in a comment.
     */
    @Test
    fun aFixedWhiteGlyphWouldBeUnreadableOnTheLightDiscs() {
        val amber = IncidentPalette.colorArgb(IncidentType.HAZARD)
        val whiteOnAmber =
            IncidentMarkerStyle.contrastRatio(IncidentMarkerStyle.GLYPH_LIGHT, amber)
        assertTrue(
            "White on the amber HAZARD disc measures %.2f:1 — if this ever clears "
                .format(whiteOnAmber) +
                "$minGlyphContrast:1 the per-category glyph colour is no longer needed.",
            whiteOnAmber < minGlyphContrast,
        )
        // And the chosen colour there is consequently the dark one.
        assertEquals(
            IncidentMarkerStyle.GLYPH_DARK,
            IncidentMarkerStyle.glyphColorArgb(IncidentType.HAZARD),
        )
    }

    /**
     * **The light/dark claim.**
     *
     * One icon set has to work on both basemaps, because this map does day/night
     * by flipping the Standard style's `lightPreset`, which re-lights only the
     * basemap and would leave a per-mode icon untouched.
     *
     * The two-tone ring is what makes that possible: the light inner ring
     * carries a dark basemap, the dark outer hairline carries a light one. On
     * either basemap at least one of them must clear the UI-contrast bar.
     */
    @Test
    fun markerEdgeSeparatesFromBothTheDayAndNightBasemaps() {
        for ((name, basemap) in
            listOf(
                "day" to IncidentMarkerStyle.DAY_BASEMAP_REFERENCE,
                "night" to IncidentMarkerStyle.NIGHT_BASEMAP_REFERENCE,
            )) {
            val separation = IncidentMarkerStyle.ringSeparation(basemap)
            assertTrue(
                "The marker edge measures %.2f:1 against the %s basemap, below the %.1f:1 floor"
                    .format(separation, name, minRingContrast),
                separation >= minRingContrast,
            )
        }
    }

    /**
     * The rings must be COMPLEMENTARY, not redundant: each basemap should be
     * carried by a different one. If both rings ever ended up light (or both
     * dark) the max above could still pass on one basemap while the marker
     * disappeared on the other, so pin which ring does the work.
     */
    @Test
    fun eachBasemapIsCarriedByTheOppositeRing() {
        val day = IncidentMarkerStyle.DAY_BASEMAP_REFERENCE
        val night = IncidentMarkerStyle.NIGHT_BASEMAP_REFERENCE
        assertTrue(
            "The DARK hairline must be what separates the marker from the LIGHT basemap",
            IncidentMarkerStyle.contrastRatio(IncidentMarkerStyle.RING_DARK, day) >
                IncidentMarkerStyle.contrastRatio(IncidentMarkerStyle.RING_LIGHT, day),
        )
        assertTrue(
            "The LIGHT ring must be what separates the marker from the DARK basemap",
            IncidentMarkerStyle.contrastRatio(IncidentMarkerStyle.RING_LIGHT, night) >
                IncidentMarkerStyle.contrastRatio(IncidentMarkerStyle.RING_DARK, night),
        )
    }

    /**
     * **The colour-blindness fix.**
     *
     * The whole point of moving off coloured circles is that the SHAPE carries
     * the category. If two categories shared a glyph they would be
     * indistinguishable to anyone who cannot separate their disc colours —
     * which is the exact failure PR #474 addressed for traffic.
     */
    @Test
    fun everyCategoryHasItsOwnDistinctGlyph() {
        val glyphs = IncidentType.entries.map { IncidentMarkerStyle.glyph(it) }
        assertEquals(
            "Every incident category must have a distinct glyph, or colour is " +
                "still the only thing telling them apart",
            IncidentType.entries.size,
            glyphs.toSet().size,
        )
    }

    /** The geometry has to actually produce a ring on both sides of the disc. */
    @Test
    fun bothRingsHaveWidth() {
        assertTrue(IncidentMarkerStyle.RING_LIGHT_WIDTH_DP > 0f)
        assertTrue(IncidentMarkerStyle.RING_DARK_WIDTH_DP > 0f)
        assertTrue(
            "The glyph must fit inside the disc with margin to spare",
            IncidentMarkerStyle.GLYPH_SCALE > 0f && IncidentMarkerStyle.GLYPH_SCALE < 1f,
        )
    }

    /** Sanity-check the shared contrast helper against known extremes. */
    @Test
    fun contrastRatioMatchesKnownValues() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        assertEquals(21.0, IncidentMarkerStyle.contrastRatio(white, black), 0.01)
        assertEquals(1.0, IncidentMarkerStyle.contrastRatio(white, white), 0.001)
    }
}
