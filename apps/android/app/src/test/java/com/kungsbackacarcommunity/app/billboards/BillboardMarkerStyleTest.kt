package com.kungsbackacarcommunity.app.billboards

import com.kungsbackacarcommunity.app.crownhunt.CrownMarkerStyle
import com.kungsbackacarcommunity.app.crownhunt.CrownRarity
import com.kungsbackacarcommunity.app.incidents.IncidentMarkerStyle
import com.kungsbackacarcommunity.app.incidents.IncidentPalette
import com.kungsbackacarcommunity.app.incidents.IncidentType
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the ONE property the billboard marker exists to have: that it is not
 * confusable with the three marker layers already on this map.
 *
 * The map now carries incidents, Kronjakt crowns, community events and
 * billboards. The first three are all coloured discs, so the fourth had to
 * differ on something other than hue — see [BillboardMarkerStyle]'s KDoc. These
 * tests pin the non-colour differences, because those are the ones that survive
 * a colour-vision deficiency and a glance from a driver, and they are also the
 * ones a future "let's just tidy the marker sizes" edit would quietly undo.
 */
class BillboardMarkerStyleTest {

    /** The teal event-pin disc, mirrored from MapboxMapSurface.EVENT_MARKER_DISC_COLOR. */
    private val eventMarkerDiscColor = 0xFF00897B.toInt()

    @Test
    fun `the plaque is not square, so it cannot read as one of the discs`() {
        // A disc is width == height by construction. The single strongest
        // at-a-glance difference is that this one is a wide panel — assert a
        // real margin, not merely "not equal", so a future tweak toward a
        // squarer badge trips this rather than sliding through.
        val aspect = BillboardMarkerStyle.PLAQUE_WIDTH_DP / BillboardMarkerStyle.PLAQUE_HEIGHT_DP
        assertTrue("Plaque must be clearly landscape, was aspect $aspect", aspect >= 1.4f)
    }

    @Test
    fun `the marker stands on a post, which is what makes it bottom-anchored`() {
        // The post is the reason this marker is anchored BOTTOM while every
        // other layer anchors CENTER. A zero-height post would leave the anchor
        // choice looking arbitrary and the silhouette looking like a plain
        // rectangle.
        assertTrue(BillboardMarkerStyle.POST_HEIGHT_DP > 0f)
        assertTrue(BillboardMarkerStyle.POST_WIDTH_DP > 0f)
        // The post must be visibly narrower than the plaque it holds up,
        // otherwise the silhouette is a single blob rather than a sign on a leg.
        assertTrue(
            BillboardMarkerStyle.POST_WIDTH_DP < BillboardMarkerStyle.PLAQUE_WIDTH_DP / 3f,
        )
    }

    @Test
    fun `the plaque colour collides with no other marker colour on this map`() {
        val taken =
            buildList {
                IncidentType.entries.forEach { add(IncidentPalette.colorArgb(it)) }
                CrownRarity.entries.forEach { add(CrownMarkerStyle.discColorArgb(it)) }
                add(eventMarkerDiscColor)
            }
        for (other in taken) {
            assertNotEquals(
                "Billboard plaque colour must not reuse another layer's marker colour",
                other,
                BillboardMarkerStyle.PLAQUE_COLOR,
            )
        }
    }

    @Test
    fun `the plaque is opaque and its content contrasts against it`() {
        // Fully opaque: a translucent plaque would composite differently over
        // the day and night basemaps, so the one bitmap would no longer read the
        // same in both — the property the two-tone outline exists to guarantee.
        val alpha = (BillboardMarkerStyle.PLAQUE_COLOR ushr 24) and 0xFF
        assertTrue("Plaque fill must be opaque, alpha was $alpha", alpha == 0xFF)
        assertNotEquals(
            BillboardMarkerStyle.PLAQUE_COLOR,
            BillboardMarkerStyle.PLAQUE_CONTENT_COLOR,
        )
        // The content bars are drawn in the same colour as the light ring, so
        // the marker reads as one object rather than two palettes.
        assertTrue(
            BillboardMarkerStyle.PLAQUE_CONTENT_COLOR == IncidentMarkerStyle.RING_LIGHT,
        )
    }

    @Test
    fun `the two content bars are unequal, so they read as text and not a symbol`() {
        assertTrue(
            BillboardMarkerStyle.CONTENT_BAR_LONG_FRACTION >
                BillboardMarkerStyle.CONTENT_BAR_SHORT_FRACTION,
        )
        // Both must fit inside the plaque with room to spare.
        assertTrue(BillboardMarkerStyle.CONTENT_BAR_LONG_FRACTION < 1f)
        assertTrue(BillboardMarkerStyle.CONTENT_BAR_SHORT_FRACTION > 0f)
        // The stacked pair plus the gap must fit the plaque's height.
        val block =
            BillboardMarkerStyle.CONTENT_BAR_THICKNESS_FRACTION * 2f +
                BillboardMarkerStyle.CONTENT_BAR_GAP_FRACTION
        assertTrue("Content bars must fit inside the plaque, block was $block", block < 1f)
    }
}
