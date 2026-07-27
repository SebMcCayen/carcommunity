package com.kungsbackacarcommunity.app.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for the pure client-side block filter.
 *
 * The behaviour under test is MUTUAL invisibility: [BlockVisibility] receives a
 * set that already unions both directions, so these tests pin that a single
 * containment check is all the filter does — and, crucially, that a message with
 * no resolvable author is kept rather than silently vanishing.
 */
class BlockVisibilityTest {

    private data class Msg(val id: String, val senderUid: String?)

    private val messages =
        listOf(
            Msg("1", "alice"),
            Msg("2", "bob"),
            Msg("3", "alice"),
            Msg("4", "carol"),
        )

    @Test
    fun `filterHiddenAuthors drops every message from a hidden uid`() {
        val kept = BlockVisibility.filterHiddenAuthors(messages, setOf("alice")) { it.senderUid }
        assertEquals(listOf("2", "4"), kept.map { it.id })
    }

    @Test
    fun `filterHiddenAuthors hides both parties when both are in the set`() {
        // The set the backend maintains is symmetric, so a viewer who blocked one
        // person and was blocked by another sees neither.
        val kept =
            BlockVisibility.filterHiddenAuthors(messages, setOf("alice", "bob")) { it.senderUid }
        assertEquals(listOf("4"), kept.map { it.id })
    }

    @Test
    fun `filterHiddenAuthors returns the same list instance when nothing is hidden`() {
        // The common case is an empty set on every snapshot; it must not allocate
        // a copy of the whole window each time.
        assertSame(messages, BlockVisibility.filterHiddenAuthors(messages, emptySet()) { it.senderUid })
    }

    @Test
    fun `filterHiddenAuthors keeps a message with no resolvable author`() {
        // A null author means a malformed backend document (these collections take
        // no client writes), which is a rendering problem — not a reason to drop
        // content, and not a block-evasion route.
        val withNull = listOf(Msg("x", null))
        assertEquals(1, BlockVisibility.filterHiddenAuthors(withNull, setOf("bob")) { it.senderUid }.size)
    }

    @Test
    fun `filterHiddenAuthors preserves order`() {
        val kept = BlockVisibility.filterHiddenAuthors(messages, setOf("bob")) { it.senderUid }
        assertEquals(listOf("1", "3", "4"), kept.map { it.id })
    }

    @Test
    fun `newestVisible skips a hidden newest message and takes the next one`() {
        // The unread dot is derived from the newest message. Lighting it for a
        // blocked party's message sends the user into an apparently unchanged
        // channel, so the newest VISIBLE message is what must drive it.
        val newestFirst = listOf(Msg("newest", "bob"), Msg("older", "alice"))
        val visible = BlockVisibility.newestVisible(newestFirst, setOf("bob")) { it.senderUid }
        assertEquals("older", visible?.id)
    }

    @Test
    fun `newestVisible returns the newest when nothing is hidden`() {
        val newestFirst = listOf(Msg("newest", "bob"), Msg("older", "alice"))
        assertEquals(
            "newest",
            BlockVisibility.newestVisible(newestFirst, emptySet()) { it.senderUid }?.id,
        )
    }

    @Test
    fun `newestVisible is null when the whole window is hidden`() {
        // The documented bound: with every scanned message hidden there is nothing
        // to light the dot for, so it stays dark rather than pointing at nothing.
        val newestFirst = listOf(Msg("a", "bob"), Msg("b", "bob"))
        assertNull(BlockVisibility.newestVisible(newestFirst, setOf("bob")) { it.senderUid })
    }

    @Test
    fun `newestVisible is null for an empty window`() {
        assertNull(BlockVisibility.newestVisible(emptyList<Msg>(), setOf("bob")) { it.senderUid })
    }
}
