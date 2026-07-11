package com.kungsbackacarcommunity.app.navigation

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapboxRequestsTest {
    @Test
    fun `forward geocode is null for a blank query`() {
        assertNull(MapboxRequests.forwardGeocode("   ", token = "pk.test"))
    }

    @Test
    fun `forward geocode encodes the query and includes autocomplete and token`() {
        val url = MapboxRequests.forwardGeocode("Kungsbacka torg", token = "pk.abc")!!
        assertTrue(url.startsWith("https://api.mapbox.com/search/geocode/v6/forward?"))
        assertTrue(url.contains("q=Kungsbacka+torg"))
        assertTrue(url.contains("autocomplete=true"))
        assertTrue(url.contains("limit=6"))
        assertTrue(url.contains("access_token=pk.abc"))
    }

    @Test
    fun `forward geocode adds proximity as lng,lat with a dot separator`() {
        val url =
            MapboxRequests.forwardGeocode(
                "cafe",
                token = "pk.abc",
                proximity = LatLng(longitude = 12.0757, latitude = 57.4874),
            )!!
        assertTrue(url.contains("proximity=12.075700,57.487400"))
    }

    @Test
    fun `directions requests polyline6 geometry, steps and full overview`() {
        val url =
            MapboxRequests.directions(
                origin = LatLng(longitude = 12.0, latitude = 57.0),
                destination = LatLng(longitude = 12.5, latitude = 57.5),
                token = "pk.xyz",
            )
        assertTrue(
            url.startsWith(
                "https://api.mapbox.com/directions/v5/mapbox/driving/" +
                    "12.000000,57.000000;12.500000,57.500000?",
            ),
        )
        assertTrue(url.contains("geometries=polyline6"))
        assertTrue(url.contains("overview=full"))
        assertTrue(url.contains("steps=true"))
        assertTrue(url.contains("access_token=pk.xyz"))
    }
}
