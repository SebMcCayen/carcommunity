package com.kungsbackacarcommunity.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapboxRequestsTest {
    @Test
    fun `forward geocode is null for a blank query`() {
        assertNull(MapboxRequests.forwardGeocode("   ", token = "pk.test"))
    }

    @Test
    fun `forward search hits the Search Box endpoint with query, country and token`() {
        val url = MapboxRequests.forwardGeocode("Kungsbacka torg", token = "pk.abc")!!
        assertTrue(url.startsWith("https://api.mapbox.com/search/searchbox/v1/forward?"))
        assertTrue(url.contains("q=Kungsbacka+torg"))
        assertTrue(url.contains("limit=6"))
        // No live fix in this call, so the home fallback applies: Sweden-restricted
        // so businesses/POIs resolve in the right region even with no position.
        assertTrue(url.contains("country=SE"))
        assertTrue(url.contains("access_token=pk.abc"))
        // `types` is left unset so POIs/businesses (e.g. Kungsmässan) are returned
        // alongside addresses — a `types=address` restriction would drop them.
        assertFalse(url.contains("types="))
    }

    @Test
    fun `forward geocode falls back to the Kungsbacka centroid when no proximity is given`() {
        // No live fix (GPS denied / no fix yet) must still bias nearby-first, not
        // omit proximity entirely — otherwise same-named places elsewhere in
        // Sweden could outrank the local result.
        val url = MapboxRequests.forwardGeocode("Kungsmässan", token = "pk.abc")!!
        assertTrue(url.contains("proximity=12.073000,57.487400"))
    }

    @Test
    fun `forward geocode prefers a real fix over the fallback proximity`() {
        val url =
            MapboxRequests.forwardGeocode(
                "Kungsmässan",
                token = "pk.abc",
                proximity = LatLng(longitude = 11.9746, latitude = 57.7089),
            )!!
        assertTrue(url.contains("proximity=11.974600,57.708900"))
        assertFalse(url.contains("proximity=12.073000,57.487400"))
    }

    @Test
    fun `forward search home country restriction can be disabled`() {
        val url = MapboxRequests.forwardGeocode("cafe", token = "pk.abc", homeCountry = null)!!
        assertFalse(url.contains("country="))
    }

    @Test
    fun `forward search with a live fix is NOT country-restricted`() {
        // The point of the abroad change: standing in Berlin, a search for a German
        // address must not be filtered down to Swedish features (which returned an
        // empty list). Proximity does the regional ranking instead.
        val url =
            MapboxRequests.forwardGeocode(
                "Unter den Linden 1",
                token = "pk.abc",
                proximity = LatLng(longitude = 13.4050, latitude = 52.5200),
            )!!
        assertFalse(url.contains("country="))
        assertTrue(url.contains("proximity=13.405000,52.520000"))
    }

    @Test
    fun `forward search with a live fix in Sweden is unrestricted but locally biased`() {
        // Local search quality is carried by the proximity decay, not the filter:
        // with a real Kungsbacka fix the request is biased to the user's ACTUAL
        // position, a stronger local signal than the old countrywide restriction.
        val url =
            MapboxRequests.forwardGeocode(
                "Kungsmässan",
                token = "pk.abc",
                proximity = LatLng(longitude = 12.0757, latitude = 57.4874),
            )!!
        assertFalse(url.contains("country="))
        assertTrue(url.contains("proximity=12.075700,57.487400"))
    }

    @Test
    fun `forward search without a fix keeps BOTH home fallbacks`() {
        // No position => no distance decay to lean on, so the country restriction
        // is still doing real work. This path must behave exactly as it did before
        // the abroad change: Kungsbacka centroid AND Sweden.
        val url = MapboxRequests.forwardGeocode("torget", token = "pk.abc")!!
        assertTrue(url.contains("proximity=12.073000,57.487400"))
        assertTrue(url.contains("country=SE"))
    }

    @Test
    fun `forward search language is sent regardless of where the user is`() {
        // `language` is a presentation parameter, kept abroad so a Swedish member
        // still gets Swedish labels (and, via the same value, Swedish turn
        // instructions on a German motorway).
        val abroad =
            MapboxRequests.forwardGeocode(
                "Hauptbahnhof",
                token = "pk.abc",
                proximity = LatLng(longitude = 11.5820, latitude = 48.1351),
                language = "sv",
            )!!
        assertTrue(abroad.contains("language=sv"))
        val home = MapboxRequests.forwardGeocode("torget", token = "pk.abc", language = "sv")!!
        assertTrue(home.contains("language=sv"))
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
    fun `reverse geocode hits the Search Box reverse endpoint with lng, lat and token`() {
        val url =
            MapboxRequests.reverseGeocode(
                LatLng(longitude = 12.0757, latitude = 57.4874),
                token = "pk.abc",
            )
        assertTrue(url.startsWith("https://api.mapbox.com/search/searchbox/v1/reverse?"))
        assertTrue(url.contains("longitude=12.075700"))
        assertTrue(url.contains("latitude=57.487400"))
        assertTrue(url.contains("limit=1"))
        assertTrue(url.contains("access_token=pk.abc"))
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
