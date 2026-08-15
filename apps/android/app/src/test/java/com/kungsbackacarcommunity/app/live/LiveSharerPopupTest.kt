package com.kungsbackacarcommunity.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure state/logic behind the tap-a-live-user profile sub-menu popup. */
class LiveSharerPopupTest {

    private fun marker(uid: String = "u1", name: String? = "Anna") =
        LiveMarker(uid = uid, latitude = 57.0, longitude = 12.0, displayName = name)

    @Test
    fun `nickname falls back to the unknown label when the marker has no name`() {
        assertEquals("Anna", LiveSharerPopupContent.nickname(marker(name = "Anna"), "Någon"))
        assertEquals("Någon", LiveSharerPopupContent.nickname(marker(name = null), "Någon"))
        assertEquals("Någon", LiveSharerPopupContent.nickname(marker(name = "  "), "Någon"))
    }

    @Test
    fun `visit-profile is offered only with a uid and a wired navigation`() {
        assertTrue(LiveSharerPopupContent.canVisitProfile("u1", hasNavigation = true))
        assertFalse(LiveSharerPopupContent.canVisitProfile("u1", hasNavigation = false))
        assertFalse(LiveSharerPopupContent.canVisitProfile("", hasNavigation = true))
        assertFalse(LiveSharerPopupContent.canVisitProfile("  ", hasNavigation = true))
    }

    @Test
    fun `points resolve to the member's balance, or zero when absent`() {
        assertEquals(
            LiveSharerPoints.Loaded(1_240L),
            LiveSharerPoints.fromBalances("u1", mapOf("u1" to 1_240L)),
        )
        // A member with no wallet / a failed read is absent from the map → 0, never
        // stuck on Loading.
        assertEquals(
            LiveSharerPoints.Loaded(0L),
            LiveSharerPoints.fromBalances("u1", emptyMap()),
        )
    }
}
