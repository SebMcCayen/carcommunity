package com.kungsbackacarcommunity.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `geo:` link contract shared by the map's "Copy position" clipboard
 * writer and the chat renderer's link detector — the format round-trip, the
 * range/precision/finiteness validation, malformed-token rejection, and the
 * in-message detection offsets.
 */
class GeoLinkTest {
    @Test
    fun `format writes a dot-decimal geo token rounded to five places`() {
        // Deliberately a value whose 6th decimal would round the 5th, and one that
        // in a Swedish locale would otherwise use a comma decimal separator.
        assertEquals("geo:57.49102,12.07660", GeoLinks.format(57.491023, 12.076600))
    }

    @Test
    fun `format handles negatives and never emits a locale comma`() {
        val token = GeoLinks.format(-33.86880, -151.20930)
        assertEquals("geo:-33.86880,-151.20930", token)
        // No decimal comma leaked (Swedish locale uses ',' as the decimal mark):
        // exactly one comma, the lat/lng separator.
        assertEquals(1, token.count { it == ',' })
    }

    @Test
    fun `clipboard payload carries the pin prefix but the same token`() {
        val payload = GeoLinks.formatForClipboard(57.49102, 12.07660)
        assertEquals("📍 geo:57.49102,12.07660", payload)
        // The decorated payload is still detected as one link.
        assertEquals(1, GeoLinks.findAll(payload).size)
    }

    @Test
    fun `format then parse round-trips within rounding tolerance`() {
        val link = GeoLinks.parse(GeoLinks.format(57.491023, 12.076600))
        requireNotNull(link)
        assertEquals(57.49102, link.latitude, 1e-9)
        assertEquals(12.07660, link.longitude, 1e-9)
    }

    @Test
    fun `parse accepts a bare in-range token`() {
        val link = GeoLinks.parse("geo:0,0")
        assertEquals(GeoLink(0.0, 0.0), link)
    }

    @Test
    fun `parse rejects an out-of-range latitude`() {
        assertNull(GeoLinks.parse("geo:91.0,10.0"))
        assertNull(GeoLinks.parse("geo:-90.5,10.0"))
    }

    @Test
    fun `parse rejects an out-of-range longitude`() {
        assertNull(GeoLinks.parse("geo:10.0,181.0"))
        assertNull(GeoLinks.parse("geo:10.0,-180.5"))
    }

    @Test
    fun `parse rejects absurd precision`() {
        // Ten fractional digits — beyond MAX_PARSE_DECIMALS (9).
        assertNull(GeoLinks.parse("geo:57.1234567890,12.0"))
    }

    @Test
    fun `parse rejects malformed tokens`() {
        assertNull(GeoLinks.parse("geo:"))
        assertNull(GeoLinks.parse("geo:57.49102"))
        assertNull(GeoLinks.parse("geo:abc,def"))
        assertNull(GeoLinks.parse("http://example.com"))
        assertNull(GeoLinks.parse("57.49102,12.07660"))
    }

    @Test
    fun `findAll returns empty for a message with no geo token`() {
        assertTrue(GeoLinks.findAll("meet me at the workshop at 5").isEmpty())
        assertTrue(GeoLinks.findAll("").isEmpty())
    }

    @Test
    fun `findAll yields a clickable range at the right offsets`() {
        val text = "here: geo:57.49102,12.07660 see you"
        val matches = GeoLinks.findAll(text)
        assertEquals(1, matches.size)
        val match = matches.single()
        // The range spans exactly the geo:… token, prefix included.
        assertEquals("geo:57.49102,12.07660", text.substring(match.range))
        assertEquals(6, match.range.first)
        assertEquals(GeoLink(57.49102, 12.07660), match.link)
    }

    @Test
    fun `findAll skips an invalid token so it stays plain text`() {
        // In-range one is detected; the out-of-range one is not, so it renders plain.
        val text = "good geo:10.0,20.0 bad geo:200.0,20.0"
        val matches = GeoLinks.findAll(text)
        assertEquals(1, matches.size)
        assertEquals("geo:10.0,20.0", text.substring(matches.single().range))
    }

    @Test
    fun `findAll does not match geo embedded in a word`() {
        assertTrue(GeoLinks.findAll("videogeo:10.0,20.0").isEmpty())
    }

    @Test
    fun `findAll ignores altitude and parameters but keeps the coordinate`() {
        val text = "geo:57.49102,12.07660,42;u=35"
        val matches = GeoLinks.findAll(text)
        assertEquals(1, matches.size)
        // Only the lat,lng is captured; altitude/params are left outside the chip.
        assertEquals("geo:57.49102,12.07660", text.substring(matches.single().range))
        assertEquals(GeoLink(57.49102, 12.07660), matches.single().link)
    }
}
