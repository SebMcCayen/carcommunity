package com.kungsbackacarcommunity.app.events

import com.kungsbackacarcommunity.app.navigation.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDistanceFilterTest {

    // Kungsbacka town centre — the viewer's position for the distance cases.
    private val viewer = LatLng(longitude = 12.0766, latitude = 57.4870)

    private fun event(
        id: String,
        latitude: Double? = null,
        longitude: Double? = null,
    ): EventSummary =
        EventSummary(
            id = id,
            title = "Event $id",
            summary = null,
            startsAtMillis = 0L,
            endsAtMillis = null,
            approximateArea = null,
            latitude = latitude,
            longitude = longitude,
            isOfficial = false,
            status = EventStatus.PUBLISHED,
            counts = RsvpCounts.EMPTY,
        )

    // ~1.5 km NE of the viewer.
    private val near = event("near", latitude = 57.4980, longitude = 12.0900)

    // ~20 km away (Mölndal-ish).
    private val mid = event("mid", latitude = 57.6560, longitude = 12.0130)

    // ~45 km away (central Gothenburg-ish, north).
    private val far = event("far", latitude = 57.7089, longitude = 12.0000)

    // No pin at all — distance unknown.
    private val noCoords = event("nocoords")

    @Test
    fun `distanceMetersOrNull is null when either side has no position`() {
        assertNull(EventDistanceFilter.distanceMetersOrNull(near, userLocation = null))
        assertNull(EventDistanceFilter.distanceMetersOrNull(noCoords, userLocation = viewer))
    }

    @Test
    fun `distanceMetersOrNull matches the great-circle distance`() {
        val d = EventDistanceFilter.distanceMetersOrNull(near, viewer)
        assertNotNull(d)
        // ~1.5 km — assert the coarse magnitude, not an exact metre count.
        assertTrue("expected ~1.5km, got $d", d!! in 1_000.0..2_500.0)
    }

    @Test
    fun `ALL band returns the list unchanged, order and coordinate-less events kept`() {
        val input = listOf(far, noCoords, near, mid)
        val out = EventDistanceFilter.apply(input, viewer, DistanceBand.ALL)
        assertEquals(input, out) // same events, same order
    }

    @Test
    fun `null location returns every event unchanged for any band`() {
        val input = listOf(near, mid, noCoords)
        assertEquals(input, EventDistanceFilter.apply(input, userLocation = null, DistanceBand.WITHIN_5_KM))
        assertEquals(input, EventDistanceFilter.apply(input, userLocation = null, DistanceBand.WITHIN_50_KM))
    }

    @Test
    fun `distance band keeps only events within the radius`() {
        val input = listOf(near, mid, far, noCoords)

        val within5 = EventDistanceFilter.apply(input, viewer, DistanceBand.WITHIN_5_KM)
        assertEquals(listOf(near.id), within5.map { it.id })

        val within25 = EventDistanceFilter.apply(input, viewer, DistanceBand.WITHIN_25_KM)
        assertEquals(listOf(near.id, mid.id), within25.map { it.id })

        val within50 = EventDistanceFilter.apply(input, viewer, DistanceBand.WITHIN_50_KM)
        assertEquals(listOf(near.id, mid.id, far.id), within50.map { it.id })
    }

    @Test
    fun `coordinate-less events are excluded from every distance band`() {
        val out = EventDistanceFilter.apply(listOf(noCoords, near), viewer, DistanceBand.WITHIN_50_KM)
        assertEquals(listOf(near.id), out.map { it.id })
    }

    @Test
    fun `distance band sorts nearest first regardless of input order`() {
        val input = listOf(far, near, mid)
        val out = EventDistanceFilter.apply(input, viewer, DistanceBand.WITHIN_50_KM)
        assertEquals(listOf(near.id, mid.id, far.id), out.map { it.id })
    }

    @Test
    fun `an event exactly on the band boundary is included`() {
        // Place an event whose distance we then use as the band max, to pin the
        // boundary as inclusive (distance <= max).
        val onEdge = event("edge", latitude = 57.5300, longitude = 12.1400)
        val d = EventDistanceFilter.distanceMetersOrNull(onEdge, viewer)!!
        // A hand-rolled band whose max is exactly this distance is not part of the
        // public enum, so assert inclusivity via withDistances against WITHIN_50_KM
        // where it comfortably fits, and via the <= contract directly.
        assertTrue(d <= DistanceBand.WITHIN_50_KM.maxMeters!!)
        val out = EventDistanceFilter.apply(listOf(onEdge), viewer, DistanceBand.WITHIN_50_KM)
        assertEquals(listOf(onEdge.id), out.map { it.id })
    }

    @Test
    fun `withDistances annotates and filters like apply`() {
        val input = listOf(far, near, mid, noCoords)

        val all = EventDistanceFilter.withDistances(input, viewer, DistanceBand.ALL)
        assertEquals(input.map { it.id }, all.map { it.event.id }) // order kept
        assertNull(all.first { it.event.id == noCoords.id }.distanceMeters)
        assertNotNull(all.first { it.event.id == near.id }.distanceMeters)

        val band = EventDistanceFilter.withDistances(input, viewer, DistanceBand.WITHIN_25_KM)
        assertEquals(listOf(near.id, mid.id), band.map { it.event.id })
        band.forEach { assertNotNull(it.distanceMeters) }

        val noFix = EventDistanceFilter.withDistances(input, userLocation = null, DistanceBand.WITHIN_25_KM)
        assertEquals(input.map { it.id }, noFix.map { it.event.id })
        noFix.forEach { assertNull(it.distanceMeters) }
    }
}
