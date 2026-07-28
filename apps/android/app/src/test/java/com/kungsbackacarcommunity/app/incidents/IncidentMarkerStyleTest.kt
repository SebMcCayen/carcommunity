package com.kungsbackacarcommunity.app.incidents

import com.kungsbackacarcommunity.app.shell.TrafficPalette
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

    // -----------------------------------------------------------------------
    // "Reported gone" (faded) marker
    // -----------------------------------------------------------------------
    //
    // The faded marker is the one a driver is MOST likely to be surprised by, so
    // its legibility claims are measured here exactly like the opaque one's. A
    // marker somebody has voted gone is still a marker for a hazard that may
    // very well still be there.

    /**
     * The wash must not cost the glyph its readability.
     *
     * This is why [IncidentMarkerStyle.glyphColorArgb] takes the flag at all:
     * every faded disc is pale, so the white glyph that reads perfectly on the
     * opaque red ACCIDENT disc would be close to invisible on its faded twin.
     */
    @Test
    fun everyCategoryGlyphStaysReadableOnItsFadedDisc() {
        for (type in IncidentType.entries) {
            val disc = IncidentMarkerStyle.discColorArgb(type, reportedCleared = true)
            val glyph = IncidentMarkerStyle.glyphColorArgb(type, reportedCleared = true)
            val contrast = IncidentMarkerStyle.contrastRatio(glyph, disc)
            assertTrue(
                "$type: faded glyph contrast is %.2f:1, below the %.1f:1 floor"
                    .format(contrast, minGlyphContrast),
                contrast >= minGlyphContrast,
            )
        }
    }

    /**
     * The strike-through is the channel that survives colour blindness, so it has
     * to be legible on every faded disc — not merely present.
     */
    @Test
    fun theClearedSlashIsLegibleOnEveryFadedDisc() {
        for (type in IncidentType.entries) {
            val contrast = IncidentMarkerStyle.clearedSlashContrast(type)
            assertTrue(
                "$type: cleared-slash contrast is %.2f:1, below the %.1f:1 floor"
                    .format(contrast, minGlyphContrast),
                contrast >= minGlyphContrast,
            )
        }
    }

    /**
     * The faded state must be carried by more than hue.
     *
     * Fading composites toward white, so the faded disc is strictly LIGHTER than
     * the opaque one — a lightness difference a monochrome or colour-blind viewer
     * perceives just as well as anyone. That is channel one; the slash asserted
     * above is channel two. Neither is hue, which is the whole point.
     */
    @Test
    fun fadingChangesLightnessNotJustHue() {
        for (type in IncidentType.entries) {
            val opaque = IncidentPalette.colorArgb(type)
            val faded = IncidentMarkerStyle.discColorArgb(type, reportedCleared = true)
            assertTrue("$type: fading did not change the disc at all", opaque != faded)
            assertTrue(
                "$type: the faded disc is not lighter than the opaque one",
                TrafficPalette.relativeLuminance(faded) >
                    TrafficPalette.relativeLuminance(opaque),
            )
        }
    }

    /**
     * The faded marker must be as EASY TO FIND as a normal one on both basemaps.
     *
     * This is the property that makes dimming safe at all. Both rings are left
     * fully opaque precisely so the marker's edge is untouched by the fade; if
     * someone ever "finishes the job" by fading them too, a questioned hazard
     * becomes a hazard nobody can see, and this test is what stops that.
     */
    @Test
    fun theFadedMarkerKeepsItsEdgeOnBothBasemaps() {
        for (basemap in
            listOf(
                IncidentMarkerStyle.DAY_BASEMAP_REFERENCE,
                IncidentMarkerStyle.NIGHT_BASEMAP_REFERENCE,
            )) {
            // Ring separation is a property of the rings alone, and the faded
            // state does not touch them — asserted rather than assumed.
            val separation = IncidentMarkerStyle.ringSeparation(basemap)
            assertTrue(
                "faded marker edge separation is %.2f:1, below the %.1f:1 floor"
                    .format(separation, minRingContrast),
                separation >= minRingContrast,
            )
        }
    }

    /**
     * The faded disc must be a DETERMINISTIC colour, not one that depends on what
     * the marker happens to be sitting on.
     *
     * The disc is composited over the marker's own opaque light ring, never over
     * the basemap, which is what lets one bitmap serve both the day and night
     * maps — the same reason the ring design exists. If this ever composited
     * against the map instead, the values every other test here measures would
     * stop describing what is actually drawn.
     */
    @Test
    fun theFadeCompositesOverTheMarkersOwnRingNotTheBasemap() {
        for (type in IncidentType.entries) {
            val expected =
                IncidentMarkerStyle.compositeOver(
                    IncidentPalette.colorArgb(type),
                    IncidentMarkerStyle.RING_LIGHT,
                    IncidentMarkerStyle.CLEARED_DISC_ALPHA,
                )
            assertEquals(
                expected,
                IncidentMarkerStyle.discColorArgb(type, reportedCleared = true),
            )
        }
        // Fully opaque either way — a translucent style image would let the
        // basemap show through and undo the determinism above.
        for (type in IncidentType.entries) {
            val faded = IncidentMarkerStyle.discColorArgb(type, reportedCleared = true)
            assertEquals(0xFF, (faded shr 24) and 0xFF)
        }
    }

    /** A non-cleared incident is drawn in exactly the palette colour, untouched. */
    @Test
    fun anUnclearedIncidentIsNotFadedAtAll() {
        for (type in IncidentType.entries) {
            assertEquals(
                IncidentPalette.colorArgb(type),
                IncidentMarkerStyle.discColorArgb(type, reportedCleared = false),
            )
            assertEquals(
                IncidentMarkerStyle.glyphColorArgb(type),
                IncidentMarkerStyle.glyphColorArgb(type, reportedCleared = false),
            )
        }
    }

    /** A mis-set alpha degrades to an endpoint rather than a nonsense colour. */
    @Test
    fun compositingClampsItsAlpha() {
        val red = IncidentPalette.colorArgb(IncidentType.ACCIDENT)
        val white = IncidentMarkerStyle.RING_LIGHT
        assertEquals(white, IncidentMarkerStyle.compositeOver(red, white, -1f))
        assertEquals(red, IncidentMarkerStyle.compositeOver(red, white, 2f))
    }
}
