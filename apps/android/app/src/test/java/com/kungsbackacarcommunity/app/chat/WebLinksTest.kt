package com.kungsbackacarcommunity.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure http/https URL detector ([WebLinks]) behind
 * auto-linkifying URLs pasted into chat messages. Kept off-Compose so the
 * "which substrings become links" decision — the security-critical part — is
 * verified on the JVM.
 */
class WebLinksTest {
    @Test
    fun `a plain-text message has no links`() {
        assertTrue(WebLinks.findAll("just some ordinary words, no address here").isEmpty())
        // The cheap fast-path (no `://` substring) returns empty too.
        assertTrue(WebLinks.findAll("email me at bob at example dot com").isEmpty())
    }

    @Test
    fun `an https URL becomes one link with the right range`() {
        val text = "check https://example.com/path out"
        val matches = WebLinks.findAll(text)
        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals("https://example.com/path", match.link.url)
        // The reported range covers exactly the URL substring, nothing around it.
        assertEquals("https://example.com/path", text.substring(match.range))
    }

    @Test
    fun `an http URL is also detected`() {
        val matches = WebLinks.findAll("http://foo.test/page")
        assertEquals(1, matches.size)
        assertEquals("http://foo.test/page", matches.single().link.url)
    }

    @Test
    fun `a javascript URL does NOT become a link`() {
        // The single most important negative: script schemes are never linkified.
        assertTrue(WebLinks.findAll("javascript:alert(1)").isEmpty())
        assertTrue(WebLinks.findAll("tap javascript:void(0) here").isEmpty())
    }

    @Test
    fun `a tel URL does NOT become a link`() {
        assertTrue(WebLinks.findAll("tel:+46701234567").isEmpty())
    }

    @Test
    fun `other dangerous or non-web schemes are NOT linkified`() {
        assertTrue(WebLinks.findAll("intent://scan/#Intent;scheme=x;end").isEmpty())
        assertTrue(WebLinks.findAll("file:///etc/passwd").isEmpty())
        assertTrue(WebLinks.findAll("content://com.app/secret").isEmpty())
        assertTrue(WebLinks.findAll("mailto:bob@example.com").isEmpty())
        assertTrue(WebLinks.findAll("data:text/html,<h1>x</h1>").isEmpty())
        // Bare www. with no scheme is not a link either.
        assertTrue(WebLinks.findAll("visit www.example.com today").isEmpty())
    }

    @Test
    fun `trailing sentence punctuation is trimmed off the link`() {
        val text = "see https://example.com."
        val match = WebLinks.findAll(text).single()
        assertEquals("https://example.com", match.link.url)
        assertEquals("https://example.com", text.substring(match.range))
    }

    @Test
    fun `a balanced closing paren inside the URL is kept`() {
        val text = "https://en.wikipedia.org/wiki/Foo_(disambiguation)"
        val match = WebLinks.findAll(text).single()
        assertEquals(text, match.link.url)
    }

    @Test
    fun `a URL wrapped in parentheses drops the unbalanced closing paren`() {
        val text = "(https://example.com/x)"
        val match = WebLinks.findAll(text).single()
        assertEquals("https://example.com/x", match.link.url)
    }

    @Test
    fun `multiple URLs are all detected in order`() {
        val text = "one http://a.test two https://b.test/z end"
        val matches = WebLinks.findAll(text)
        assertEquals(2, matches.size)
        assertEquals("http://a.test", matches[0].link.url)
        assertEquals("https://b.test/z", matches[1].link.url)
    }

    @Test
    fun `scheme is matched case-insensitively`() {
        assertEquals("HTTPS://Example.com", WebLinks.findAll("HTTPS://Example.com").single().link.url)
    }

    @Test
    fun `a scheme embedded in a larger token is not a fresh link`() {
        assertTrue(WebLinks.findAll("xhttps://example.com").isEmpty())
    }

    @Test
    fun `a bare scheme with no host is not a link`() {
        assertTrue(WebLinks.findAll("https://").isEmpty())
    }
}
