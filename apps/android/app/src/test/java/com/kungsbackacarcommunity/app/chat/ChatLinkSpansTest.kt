package com.kungsbackacarcommunity.app.chat

import com.kungsbackacarcommunity.app.events.EventShareLinks
import com.kungsbackacarcommunity.app.location.GeoLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChatLinkSpans.nonOverlapping] — the reconciliation that keeps a
 * chat renderer from duplicating/misordering output when the independent link
 * matchers ([WebLinks] / [GeoLinks] / [EventShareLinks]) produce OVERLAPPING ranges
 * (a web URL whose path contains a `geo:`/`kccevent:`-looking substring).
 */
class ChatLinkSpansTest {
    @Test
    fun `disjoint ranges are all kept, sorted by start`() {
        val kept = ChatLinkSpans.nonOverlapping(listOf(10..12, 0..3, 5..7)) { it }
        assertEquals(listOf(0..3, 5..7, 10..12), kept)
    }

    @Test
    fun `an inner range nested in an earlier outer range is dropped`() {
        // Outer 0..20 starts first, so it wins; the nested 5..10 is discarded.
        val kept = ChatLinkSpans.nonOverlapping(listOf(0..20, 5..10)) { it }
        assertEquals(listOf(0..20), kept)
    }

    @Test
    fun `an http URL whose path contains a geo-like substring renders as ONE web link`() {
        // The URL's path literally contains `geo:59.1,18.2` — which GeoLinks also
        // matches (the `/` before it is a non-alnum boundary). Without reconciliation
        // the renderer would emit the URL AND the nested geo chip, duplicating text.
        val text = "look https://example.com/geo:59.1,18.2/x end"
        val webMatches = WebLinks.findAll(text)
        val geoMatches = GeoLinks.findAll(text)
        // Sanity: both matchers fire, and their ranges overlap (the bug's precondition).
        assertEquals(1, webMatches.size)
        assertEquals(1, geoMatches.size)
        val webRange = webMatches.single().range
        val geoRange = geoMatches.single().range
        assertTrue("expected the geo match nested inside the URL", geoRange.first in webRange)

        val combined = webMatches.map { it.range } + geoMatches.map { it.range }
        val kept = ChatLinkSpans.nonOverlapping(combined) { it }
        // Exactly one span survives — the whole URL — so it renders as one web link.
        assertEquals(listOf(webRange), kept)
    }

    @Test
    fun `an http URL whose path contains a kccevent-like substring renders as ONE web link`() {
        val text = "see https://example.com/kccevent:abc123/more done"
        val webMatches = WebLinks.findAll(text)
        val eventMatches = EventShareLinks.findAll(text)
        assertEquals(1, webMatches.size)
        assertEquals(1, eventMatches.size)
        val webRange = webMatches.single().range
        assertTrue(eventMatches.single().range.first in webRange)

        val combined = webMatches.map { it.range } + eventMatches.map { it.range }
        val kept = ChatLinkSpans.nonOverlapping(combined) { it }
        assertEquals(listOf(webRange), kept)
    }

    @Test
    fun `a real geo link BEFORE a url is kept alongside it`() {
        // A standalone geo token and a later, separate URL do NOT overlap — both kept.
        val text = "geo:59.1,18.2 then https://example.com"
        val geoRange = GeoLinks.findAll(text).single().range
        val webRange = WebLinks.findAll(text).single().range
        val kept = ChatLinkSpans.nonOverlapping(listOf(webRange, geoRange)) { it }
        assertEquals(listOf(geoRange, webRange), kept)
    }
}
