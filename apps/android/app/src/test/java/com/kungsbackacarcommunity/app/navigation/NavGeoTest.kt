package com.kungsbackacarcommunity.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nearest-first ordering of search results, and the haversine it rests on.
 */
class NavGeoTest {
    private val kungsbacka = LatLng(longitude = 12.0757, latitude = 57.4874)

    private fun place(id: String, point: LatLng) =
        PlaceSuggestion(id = id, name = id, address = null, point = point)

    @Test
    fun `distance is zero to the same point and symmetric`() {
        assertEquals(0.0, NavGeo.distanceMeters(kungsbacka, kungsbacka), 1e-6)
        val gothenburg = LatLng(longitude = 11.9746, latitude = 57.7089)
        assertEquals(
            NavGeo.distanceMeters(kungsbacka, gothenburg),
            NavGeo.distanceMeters(gothenburg, kungsbacka),
            1e-6,
        )
    }

    /**
     * Anchored against a known real-world distance: Kungsbacka → Gothenburg is
     * ~25 km great-circle. A formula that is subtly wrong (e.g. forgetting to
     * scale longitude by cos(lat)) still passes ordering tests at small scales
     * but misses this.
     */
    @Test
    fun `distance matches a known great-circle span`() {
        val gothenburg = LatLng(longitude = 11.9746, latitude = 57.7089)
        val meters = NavGeo.distanceMeters(kungsbacka, gothenburg)
        assertTrue("expected ~25km, got ${meters}m", meters in 24_000.0..26_500.0)
    }

    /** One degree of latitude is ~111 km anywhere; a cheap absolute anchor. */
    @Test
    fun `one degree of latitude is about 111 km`() {
        val a = LatLng(longitude = 12.0, latitude = 57.0)
        val b = LatLng(longitude = 12.0, latitude = 58.0)
        assertEquals(111_195.0, NavGeo.distanceMeters(a, b), 500.0)
    }

    /**
     * The reported bug: the API's relevance order can put a further match above
     * a nearer one. Nearest-first must re-order it, not preserve it.
     */
    @Test
    fun `results are ordered nearest first regardless of the API order`() {
        val near = place("near", LatLng(longitude = 12.08, latitude = 57.49)) // ~1 km
        val mid = place("mid", LatLng(longitude = 11.97, latitude = 57.71)) // ~25 km
        val far = place("far", LatLng(longitude = 18.07, latitude = 59.33)) // Stockholm

        // API order: worst-first, exactly the case being fixed.
        val ordered = NavGeo.nearestFirst(listOf(far, mid, near), kungsbacka)

        assertEquals(listOf("near", "mid", "far"), ordered.map { it.id })
    }

    @Test
    fun `no origin keeps the API relevance order untouched`() {
        val far = place("far", LatLng(longitude = 18.07, latitude = 59.33))
        val near = place("near", LatLng(longitude = 12.08, latitude = 57.49))
        val apiOrder = listOf(far, near)

        val ordered = NavGeo.nearestFirst(apiOrder, origin = null)

        assertEquals(apiOrder, ordered)
    }

    @Test
    fun `an empty list is returned as-is`() {
        assertTrue(NavGeo.nearestFirst(emptyList(), kungsbacka).isEmpty())
        assertTrue(NavGeo.nearestFirst(emptyList(), null).isEmpty())
    }

    /** Equidistant results keep their relative API order (stable sort). */
    @Test
    fun `equidistant results keep their API order`() {
        val point = LatLng(longitude = 12.08, latitude = 57.49)
        val first = place("first", point)
        val second = place("second", point)

        val ordered = NavGeo.nearestFirst(listOf(first, second), kungsbacka)

        assertEquals(listOf("first", "second"), ordered.map { it.id })
        assertSame(first, ordered[0])
    }
}
