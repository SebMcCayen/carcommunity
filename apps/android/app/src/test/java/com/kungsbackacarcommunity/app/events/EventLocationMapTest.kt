package com.kungsbackacarcommunity.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure map-presentation gate ([EventMapPresentation.markerPoint]).
 * This is the single decision behind "show the embedded map + Navigate button, or
 * hide both": a valid, complete pin yields a point; anything else yields null.
 */
class EventLocationMapTest {
    private fun event(latitude: Double?, longitude: Double?) =
        EventSummary(
            id = "e1",
            title = "Cars & Coffee",
            summary = null,
            startsAtMillis = 0L,
            endsAtMillis = null,
            approximateArea = null,
            locationName = "Torg",
            latitude = latitude,
            longitude = longitude,
            isOfficial = false,
            status = EventStatus.PUBLISHED,
            counts = RsvpCounts.EMPTY,
        )

    @Test
    fun `a valid pin yields a lng-first LatLng at the event coordinate`() {
        val point = EventMapPresentation.markerPoint(event(latitude = 57.4874, longitude = 12.0757))!!
        assertEquals(57.4874, point.latitude, 1e-9)
        assertEquals(12.0757, point.longitude, 1e-9)
    }

    @Test
    fun `no pin (both null) yields null so the map and Navigate button hide`() {
        assertNull(EventMapPresentation.markerPoint(event(latitude = null, longitude = null)))
    }

    @Test
    fun `a half-set pair is treated as no location`() {
        assertNull(EventMapPresentation.markerPoint(event(latitude = 57.4874, longitude = null)))
        assertNull(EventMapPresentation.markerPoint(event(latitude = null, longitude = 12.0757)))
    }

    @Test
    fun `an out-of-range coordinate is rejected`() {
        assertNull(EventMapPresentation.markerPoint(event(latitude = 91.0, longitude = 12.0757)))
        assertNull(EventMapPresentation.markerPoint(event(latitude = 57.4874, longitude = 181.0)))
    }
}
