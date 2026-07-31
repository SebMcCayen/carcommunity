package com.kungsbackacarcommunity.app.billboards

import com.kungsbackacarcommunity.app.shell.MapBillboardMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The off-Compose half of the billboards map layer: the activation/scheduling
 * filter, the CTA resolution, and the marker mapping.
 *
 * None of this needs a device, a map or a Firestore. What it CANNOT cover — and
 * what is therefore verified on device — is the annotation draw and hit-testing
 * inside the GL surface, which have no JVM seam at all.
 */
class BillboardMapVisibilityTest {

    private fun billboard(
        id: String = "b1",
        from: Long? = null,
        until: Long? = null,
        ctaType: BillboardCtaType? = null,
        ctaValue: String? = null,
    ) = Billboard(
        id = id,
        headline = "Headline",
        message = "Message",
        companyId = "c1",
        latitude = 57.49,
        longitude = 12.07,
        callToActionType = ctaType,
        callToActionValue = ctaValue,
        availableFromMillis = from,
        availableUntilMillis = until,
    )

    // ---- Activation / scheduling filter -------------------------------------

    @Test
    fun `a billboard with no window is always visible`() {
        assertTrue(BillboardVisibility.isVisible(billboard(), nowMillis = 1_000L))
    }

    @Test
    fun `a billboard whose window has not opened is hidden`() {
        assertFalse(BillboardVisibility.isVisible(billboard(from = 2_000L), nowMillis = 1_999L))
    }

    @Test
    fun `the start boundary is inclusive`() {
        assertTrue(BillboardVisibility.isVisible(billboard(from = 2_000L), nowMillis = 2_000L))
    }

    @Test
    fun `the end boundary is exclusive - an expired billboard is hidden`() {
        // Half-open [from, until): a billboard whose end instant is exactly now
        // has, by the admin's own definition, finished. Matches
        // isBillboardMapVisible in functions/src/billboards/billboards-core.ts.
        assertFalse(BillboardVisibility.isVisible(billboard(until = 3_000L), nowMillis = 3_000L))
        assertTrue(BillboardVisibility.isVisible(billboard(until = 3_000L), nowMillis = 2_999L))
    }

    @Test
    fun `both bounds must hold`() {
        val scheduled = billboard(from = 1_000L, until = 2_000L)
        assertFalse(BillboardVisibility.isVisible(scheduled, nowMillis = 999L))
        assertTrue(BillboardVisibility.isVisible(scheduled, nowMillis = 1_500L))
        assertFalse(BillboardVisibility.isVisible(scheduled, nowMillis = 2_000L))
    }

    @Test
    fun `visibleAt keeps only the drawable ones and preserves order`() {
        val list =
            listOf(
                billboard(id = "past", until = 500L),
                billboard(id = "now"),
                billboard(id = "future", from = 9_000L),
                billboard(id = "open", from = 100L, until = 9_000L),
            )
        assertEquals(
            listOf("now", "open"),
            BillboardVisibility.visibleAt(list, nowMillis = 1_000L).map { it.id },
        )
    }

    // ---- The scheduled wake-up ----------------------------------------------
    //
    // This is what replaces a polling timer: the layer filters once and then
    // sleeps until the soonest instant at which the answer could change.

    @Test
    fun `next boundary is null when nothing is time-limited`() {
        assertNull(BillboardVisibility.nextBoundaryMillis(listOf(billboard()), nowMillis = 1_000L))
    }

    @Test
    fun `next boundary is the soonest FUTURE bound across both ends`() {
        val list =
            listOf(
                billboard(id = "a", until = 5_000L),
                // A future START counts too: this one is not drawn yet and the
                // layer must wake to draw it, not only to remove things.
                billboard(id = "b", from = 3_000L, until = 8_000L),
                // Already-passed bounds must never be chosen, or the layer would
                // busy-loop on an instant it can never wait for.
                billboard(id = "c", from = 10L, until = 900L),
            )
        assertEquals(3_000L, BillboardVisibility.nextBoundaryMillis(list, nowMillis = 1_000L))
    }

    @Test
    fun `next boundary ignores an instant exactly at now`() {
        // `> now`, not `>= now`: a zero-length delay would spin.
        assertEquals(
            2_000L,
            BillboardVisibility.nextBoundaryMillis(
                listOf(billboard(until = 1_000L), billboard(id = "b", until = 2_000L)),
                nowMillis = 1_000L,
            ),
        )
    }

    // ---- Marker mapping ------------------------------------------------------

    @Test
    fun `marker mapping carries the id and puts longitude and latitude the right way round`() {
        // The one mistake this mapping can make that still compiles: Mapbox
        // takes lng/lat, the document stores lat/lng, and swapping them puts
        // every Swedish billboard in the Indian Ocean.
        val markers =
            BillboardVisibility.visibleAt(listOf(billboard(id = "b7")), nowMillis = 0L).map {
                MapBillboardMarker(id = it.id, longitude = it.longitude, latitude = it.latitude)
            }
        assertEquals(listOf(MapBillboardMarker("b7", 12.07, 57.49)), markers)
    }

    // ---- Call to action ------------------------------------------------------

    @Test
    fun `no cta pair resolves to no action`() {
        assertNull(BillboardCallToAction.resolve(billboard()))
        assertNull(BillboardCallToAction.resolve(billboard(ctaType = BillboardCtaType.PHONE)))
        assertNull(
            BillboardCallToAction.resolve(
                billboard(ctaType = BillboardCtaType.PHONE, ctaValue = "   "),
            ),
        )
    }

    @Test
    fun `cta types the admin form collects no value for resolve to nothing`() {
        for (type in
            listOf(
                BillboardCtaType.NAVIGATE,
                BillboardCtaType.OFFER_VIEW,
                BillboardCtaType.PARTNER_PROFILE,
            )
        ) {
            assertNull(
                "$type must not offer a button it cannot act on",
                BillboardCallToAction.resolve(billboard(ctaType = type, ctaValue = "x")),
            )
        }
    }

    @Test
    fun `a phone cta opens the dialler rather than placing a call`() {
        val action =
            BillboardCallToAction.resolve(
                billboard(ctaType = BillboardCtaType.PHONE, ctaValue = "+46 300-12 34 56"),
            )
        assertEquals("tel:+46300123456", action?.uri)
        assertEquals(BillboardInteractionType.PHONE, action?.interactionType)
    }

    @Test
    fun `a schemeless website cta defaults to https`() {
        val action =
            BillboardCallToAction.resolve(
                billboard(ctaType = BillboardCtaType.WEBSITE, ctaValue = "example.se"),
            )
        assertEquals("https://example.se", action?.uri)
        assertEquals(BillboardInteractionType.WEBSITE, action?.interactionType)
    }

    @Test
    fun `an explicit scheme on a website cta is left alone`() {
        assertEquals(
            "http://legacy.example.se/kampanj",
            BillboardCallToAction.resolve(
                billboard(
                    ctaType = BillboardCtaType.WEBSITE,
                    ctaValue = "http://legacy.example.se/kampanj",
                ),
            )?.uri,
        )
    }

    @Test
    fun `cta wire values match the shared contract`() {
        assertEquals("navigate", BillboardCtaType.NAVIGATE.wire)
        assertEquals("phone", BillboardCtaType.PHONE.wire)
        assertEquals("website", BillboardCtaType.WEBSITE.wire)
        assertEquals("offer_view", BillboardCtaType.OFFER_VIEW.wire)
        assertEquals("partner_profile", BillboardCtaType.PARTNER_PROFILE.wire)
        assertEquals(5, BillboardCtaType.entries.size)
        assertNull(BillboardCtaType.fromWire("something_new"))
        assertNull(BillboardCtaType.fromWire(null))
    }
}
