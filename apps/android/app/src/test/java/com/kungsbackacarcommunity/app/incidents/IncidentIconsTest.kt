package com.kungsbackacarcommunity.app.incidents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the category → glyph table.
 *
 * The bug this whole change fixes was "every incident is the same dot", so the
 * thing worth pinning down is not that ACCIDENT happens to map to some
 * particular drawable — it is the two INVARIANTS that keep the map readable:
 * every category resolves to a real glyph, and no two categories share one.
 */
class IncidentIconsTest {

    @Test
    fun `every incident type resolves to a real drawable`() {
        // Exhaustive over the enum by construction: entries, not a hand-written
        // list that a new category could be left out of.
        for (type in IncidentType.entries) {
            val res = IncidentIcons.iconRes(type)
            assertNotEquals("$type resolved to the 0 (no-resource) id", 0, res)
        }
    }

    @Test
    fun `no two categories share a glyph`() {
        // The point of the change: a driver must be able to tell an accident from
        // roadwork at a glance. Two categories pointing at one drawable would put
        // the indistinguishable-markers bug straight back, in a form no
        // compile-time exhaustiveness check would catch.
        val byRes = IncidentType.entries.groupBy { IncidentIcons.iconRes(it) }
        val shared = byRes.filterValues { it.size > 1 }
        assertTrue("categories sharing one glyph: $shared", shared.isEmpty())
        assertEquals(IncidentType.entries.size, byRes.size)
    }

    @Test
    fun `no two categories share a colour`() {
        // Colour is the redundant second channel, not the primary one — but a
        // duplicate here would still be a regression, and it is free to check.
        val byColor = IncidentType.entries.groupBy { IncidentPalette.colorArgb(it) }
        assertEquals(IncidentType.entries.size, byColor.size)
    }

    @Test
    fun `every category colour is fully opaque`() {
        // The marker badge fill is drawn over the basemap. A translucent category
        // colour would blend with the road underneath and make the same category
        // look like two different ones on the day vs night basemap.
        for (type in IncidentType.entries) {
            val alpha = (IncidentPalette.colorArgb(type) ushr 24) and 0xFF
            assertEquals("$type is not fully opaque", 0xFF, alpha)
        }
    }

    @Test
    fun `the marker badge is big enough to hit and to read`() {
        // 34dp badge: comfortably over the 24dp the old dot rendered at, and
        // large enough to be a reliable touch target now that it is tappable.
        assertTrue(IncidentMarkerStyle.DIAMETER_DP >= 24f)
        // Both separator rings have to actually exist, or the badge loses its
        // contrast against one of the two basemaps.
        assertTrue(IncidentMarkerStyle.OUTER_RING_DP > 0f)
        assertTrue(IncidentMarkerStyle.WHITE_RING_DP > 0f)
        // The glyph must fit inside the coloured fill, not spill onto the rings.
        val ringsDp = 2f * (IncidentMarkerStyle.OUTER_RING_DP + IncidentMarkerStyle.WHITE_RING_DP)
        val fillDp = IncidentMarkerStyle.DIAMETER_DP - ringsDp
        val glyphDp = IncidentMarkerStyle.DIAMETER_DP * IncidentMarkerStyle.GLYPH_FRACTION
        assertTrue("glyph ${glyphDp}dp does not fit the ${fillDp}dp fill", glyphDp <= fillDp)
    }

    @Test
    fun `the ring colours are the two extremes the basemaps need`() {
        // The badge survives both light and dark basemaps only because it carries
        // a near-black ring AND a white ring. Pin both, since dropping either
        // (e.g. "simplifying" to one ring) silently costs one of the two modes.
        val outer = IncidentMarkerStyle.OUTER_RING_COLOR
        val outerLuma = luminance(outer)
        val whiteLuma = luminance(IncidentMarkerStyle.WHITE)
        assertTrue("outer ring is not dark (luma $outerLuma)", outerLuma < 0.1)
        assertTrue("mid ring is not light (luma $whiteLuma)", whiteLuma > 0.9)
    }

    /** Relative luminance-ish 0..1 of an ARGB colour, for the ring assertions. */
    private fun luminance(argb: Int): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
