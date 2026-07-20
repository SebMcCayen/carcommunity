package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the incident-tap seam on [MapSurface].
 *
 * The real hit-testing happens inside the Mapbox GL surface and can only be
 * verified on a token-provisioned device. What IS assertable off-device — and
 * what actually matters for correctness — is that a marker tap and the two
 * "navigate here?" gestures stay on SEPARATE channels. Routing an incident tap
 * through `placeRequest` would open a route preview to the crash the user was
 * asking about, which is the failure this test exists to catch.
 */
class IncidentTapSeamTest {

    @Test
    fun `no tap is pending on a fresh surface`() {
        assertNull(StubMapSurface(autoLoad = false).incidentTap.value)
    }

    @Test
    fun `tapping a marker publishes its incident id`() {
        val surface = StubMapSurface(autoLoad = false)
        surface.emitIncidentTap("incident-42")
        assertEquals("incident-42", surface.incidentTap.value)
    }

    @Test
    fun `consuming the tap clears it so the sheet does not reopen`() {
        val surface = StubMapSurface(autoLoad = false)
        surface.emitIncidentTap("incident-42")
        surface.consumeIncidentTap()
        assertNull(surface.incidentTap.value)
    }

    @Test
    fun `tapping a second marker supersedes the first`() {
        val surface = StubMapSurface(autoLoad = false)
        surface.emitIncidentTap("first")
        surface.emitIncidentTap("second")
        assertEquals("second", surface.incidentTap.value)
    }

    @Test
    fun `an incident tap never raises a navigate-here request`() {
        // The whole reason incidentTap is its own flow.
        val surface = StubMapSurface(autoLoad = false)
        surface.emitIncidentTap("incident-42")
        assertNull("an incident tap leaked into placeRequest", surface.placeRequest.value)
    }

    @Test
    fun `the navigate-here gestures never raise an incident tap`() {
        // ...and the converse: long-press and place-tap still mean "navigate
        // there" and must not open an incident sheet.
        val surface = StubMapSurface(autoLoad = false)
        surface.emitLongPress(MapPoint(longitude = 12.0, latitude = 57.0))
        assertNull(surface.incidentTap.value)
        surface.emitPlaceTap(MapPoint(longitude = 12.0, latitude = 57.0), name = "Circle K")
        assertNull(surface.incidentTap.value)
    }

    @Test
    fun `consuming one channel does not clear the other`() {
        val surface = StubMapSurface(autoLoad = false)
        surface.emitIncidentTap("incident-42")
        surface.emitLongPress(MapPoint(longitude = 12.0, latitude = 57.0))

        surface.consumePlaceRequest()
        assertEquals("consuming a place request dropped the incident tap", "incident-42", surface.incidentTap.value)

        surface.emitLongPress(MapPoint(longitude = 12.0, latitude = 57.0))
        surface.consumeIncidentTap()
        assertEquals(
            57.0,
            requireNotNull(surface.placeRequest.value).point.latitude,
            0.0001,
        )
    }
}
