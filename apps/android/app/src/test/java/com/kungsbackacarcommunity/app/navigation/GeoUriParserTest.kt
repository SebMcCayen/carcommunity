package com.kungsbackacarcommunity.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure `geo:` / `google.navigation:` incoming-link parser. Every real-world
 * form the manifest handler can be handed is exercised here, off-device, so the
 * Activity holds no parsing logic and a regression is caught in CI.
 */
class GeoUriParserTest {

    private fun point(target: GeoUriTarget?): GeoUriTarget.Point {
        assertTrue("expected a Point, got $target", target is GeoUriTarget.Point)
        return target as GeoUriTarget.Point
    }

    @Test
    fun `bare geo lat,lng parses latitude-first into lng-first LatLng`() {
        val result = point(GeoUriParser.parse("geo:57.4874,12.0757"))
        assertEquals(57.4874, result.point.latitude, 1e-9)
        assertEquals(12.0757, result.point.longitude, 1e-9)
        assertNull(result.label)
    }

    @Test
    fun `zoom and other query params are ignored`() {
        val result = point(GeoUriParser.parse("geo:57.4874,12.0757?z=15"))
        assertEquals(57.4874, result.point.latitude, 1e-9)
        assertEquals(12.0757, result.point.longitude, 1e-9)
    }

    @Test
    fun `a trailing altitude in the path is ignored`() {
        val result = point(GeoUriParser.parse("geo:57.4874,12.0757,42"))
        assertEquals(57.4874, result.point.latitude, 1e-9)
        assertEquals(12.0757, result.point.longitude, 1e-9)
    }

    @Test
    fun `RFC 5870 semicolon parameters after the coordinates are ignored`() {
        val result = point(GeoUriParser.parse("geo:57.4874,12.0757;u=35"))
        assertEquals(57.4874, result.point.latitude, 1e-9)
        assertEquals(12.0757, result.point.longitude, 1e-9)
    }

    @Test
    fun `q coordinate is the destination when the path is the 0,0 placeholder`() {
        val result = point(GeoUriParser.parse("geo:0,0?q=57.4874,12.0757"))
        assertEquals(57.4874, result.point.latitude, 1e-9)
        assertEquals(12.0757, result.point.longitude, 1e-9)
    }

    @Test
    fun `q coordinate carries a parenthesised label`() {
        val result = point(GeoUriParser.parse("geo:0,0?q=57.4874,12.0757(Kungsbacka Torg)"))
        assertEquals(57.4874, result.point.latitude, 1e-9)
        assertEquals("Kungsbacka Torg", result.label)
    }

    @Test
    fun `a url-encoded label is decoded`() {
        val result = point(GeoUriParser.parse("geo:0,0?q=57.4874,12.0757(Torg%20%26%20Fika)"))
        assertEquals("Torg & Fika", result.label)
    }

    @Test
    fun `a plus-encoded label is decoded`() {
        val result = point(GeoUriParser.parse("geo:0,0?q=57.4874,12.0757(My+Place)"))
        assertEquals("My Place", result.label)
    }

    @Test
    fun `q wins over a real path coordinate (google convention)`() {
        val result = point(GeoUriParser.parse("geo:10.0,10.0?q=57.4874,12.0757(Here)"))
        assertEquals(57.4874, result.point.latitude, 1e-9)
        assertEquals("Here", result.label)
    }

    @Test
    fun `a free-text q address becomes a Query`() {
        val result = GeoUriParser.parse("geo:0,0?q=Storgatan 1, Kungsbacka")
        assertTrue("expected a Query, got $result", result is GeoUriTarget.Query)
        assertEquals("Storgatan 1, Kungsbacka", (result as GeoUriTarget.Query).text)
    }

    @Test
    fun `a url-encoded free-text q address is decoded into the Query`() {
        val result = GeoUriParser.parse("geo:0,0?q=Storgatan%201%2C%20Kungsbacka")
        assertTrue(result is GeoUriTarget.Query)
        assertEquals("Storgatan 1, Kungsbacka", (result as GeoUriTarget.Query).text)
    }

    @Test
    fun `google navigation q coordinate parses`() {
        val result = point(GeoUriParser.parse("google.navigation:q=57.4874,12.0757"))
        assertEquals(57.4874, result.point.latitude, 1e-9)
        assertEquals(12.0757, result.point.longitude, 1e-9)
    }

    @Test
    fun `google navigation q address becomes a Query`() {
        val result = GeoUriParser.parse("google.navigation:q=Ikea Kungsbacka")
        assertTrue(result is GeoUriTarget.Query)
        assertEquals("Ikea Kungsbacka", (result as GeoUriTarget.Query).text)
    }

    @Test
    fun `scheme is case-insensitive`() {
        assertTrue(GeoUriParser.parse("GEO:57.4874,12.0757") is GeoUriTarget.Point)
    }

    @Test
    fun `bare 0,0 with no usable query is the no-location sentinel`() {
        assertNull(GeoUriParser.parse("geo:0,0"))
    }

    @Test
    fun `an out-of-range latitude is rejected`() {
        assertNull(GeoUriParser.parse("geo:91.0,12.0"))
    }

    @Test
    fun `an out-of-range longitude is rejected`() {
        assertNull(GeoUriParser.parse("geo:57.0,181.0"))
    }

    @Test
    fun `non-numeric coordinates are rejected`() {
        assertNull(GeoUriParser.parse("geo:abc,def"))
    }

    @Test
    fun `a NaN coordinate is rejected`() {
        assertNull(GeoUriParser.parse("geo:NaN,12.0"))
    }

    @Test
    fun `an infinite coordinate is rejected`() {
        assertNull(GeoUriParser.parse("geo:Infinity,12.0"))
    }

    @Test
    fun `a non-map scheme is not handled`() {
        assertNull(GeoUriParser.parse("https://example.com/57.4874,12.0757"))
        assertNull(GeoUriParser.parse("mailto:test@example.com"))
    }

    @Test
    fun `null blank and schemeless input yield null`() {
        assertNull(GeoUriParser.parse(null))
        assertNull(GeoUriParser.parse(""))
        assertNull(GeoUriParser.parse("   "))
        assertNull(GeoUriParser.parse("57.4874,12.0757"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val result = point(GeoUriParser.parse("  geo:57.4874,12.0757  "))
        assertEquals(57.4874, result.point.latitude, 1e-9)
    }

    @Test
    fun `negative southern-hemisphere coordinates parse`() {
        val result = point(GeoUriParser.parse("geo:-33.8688,151.2093"))
        assertEquals(-33.8688, result.point.latitude, 1e-9)
        assertEquals(151.2093, result.point.longitude, 1e-9)
    }
}
