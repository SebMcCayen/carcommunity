package com.kungsbackacarcommunity.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure in-app event share link ([EventShareLinks]) and the
 * message builder ([EventShare]) behind the event detail's "Share" button.
 */
class EventShareLinkTest {
    @Test
    fun `format produces a kccevent token for the event id`() {
        assertEquals("kccevent:abc123", EventShareLinks.format("abc123"))
    }

    @Test
    fun `parse round-trips a well-formed token to its event id`() {
        assertEquals("abc123", EventShareLinks.parse("kccevent:abc123")?.eventId)
        // Trimmed before matching.
        assertEquals("XyZ_9-0", EventShareLinks.parse("  kccevent:XyZ_9-0  ")?.eventId)
    }

    @Test
    fun `parse rejects a non-token, an empty id, and an over-long id`() {
        assertNull(EventShareLinks.parse("not a link"))
        assertNull(EventShareLinks.parse("kccevent:"))
        assertNull(EventShareLinks.parse("kccevent:has space"))
        assertNull(EventShareLinks.parse("kccevent:" + "a".repeat(EventShareLinks.MAX_ID_LENGTH + 1)))
    }

    @Test
    fun `findAll locates a token embedded in a message and reports its range`() {
        val text = "Cars & Coffee\n🎟️ kccevent:evt42"
        val matches = EventShareLinks.findAll(text)
        assertEquals(1, matches.size)
        assertEquals("evt42", matches.single().link.eventId)
        // The reported range covers exactly the kccevent:… token so a renderer
        // replaces all of it with the chip.
        assertEquals("kccevent:evt42", text.substring(matches.single().range))
    }

    @Test
    fun `findAll does not mistake an id embedded in a larger word for a link`() {
        // A preceding letter/digit excludes the match (mirrors GeoLinks' guard).
        assertTrue(EventShareLinks.findAll("xkccevent:evt42").isEmpty())
        // The cheap fast-path (no scheme substring) returns empty too.
        assertTrue(EventShareLinks.findAll("no scheme here").isEmpty())
    }

    @Test
    fun `messageText carries the title and a token that round-trips to the event id`() {
        val text = EventShare.messageText("Cars & Coffee", "evt42")
        assertTrue(text.startsWith("Cars & Coffee"))
        val matches = EventShareLinks.findAll(text)
        assertEquals(1, matches.size)
        assertEquals("evt42", matches.single().link.eventId)
    }

    @Test
    fun `a blank title still produces a token-only message`() {
        val text = EventShare.messageText("   ", "evt42")
        // No leading empty title line — just the (emoji-prefixed) token.
        assertTrue(text.startsWith("🎟️ kccevent:evt42"))
        assertEquals("evt42", EventShareLinks.findAll(text).single().link.eventId)
    }
}
