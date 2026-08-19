package com.kungsbackacarcommunity.app.chat

import com.kungsbackacarcommunity.app.chatchannels.ChannelResponseParser
import com.kungsbackacarcommunity.app.dm.DmResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure logic behind the inline reply-to-message UI: the quote-preview bound, the
 * tap-to-scroll lookup, and the tolerant snapshot parsing each chat domain reuses.
 * No Compose / Firebase, so it runs on the JVM.
 */
class ChatReplyTest {
    @Test
    fun quotePreview_trimsAndBoundsToPreviewLength() {
        // Trimmed…
        assertEquals("hello", ChatReply.quotePreview("   hello   "))
        // …and capped at the shared backend preview length (120), so the optimistic
        // quote matches the snapshot the server will store.
        val long = "x".repeat(200)
        assertEquals(120, ChatReply.quotePreview(long).length)
        assertEquals(ChatReply.QUOTE_PREVIEW_MAX_LENGTH, ChatReply.quotePreview(long).length)
    }

    @Test
    fun indexOfMessage_findsTargetAndReturnsNullWhenAbsent() {
        val items = listOf("a", "b", "c")
        assertEquals(1, ChatReply.indexOfMessage(items, "b") { it })
        // A quoted parent that is no longer loaded (expired / paged out) resolves to
        // null so tap-to-scroll does nothing gracefully rather than jumping wrong.
        assertNull(ChatReply.indexOfMessage(items, "z") { it })
    }

    @Test
    fun channelParseReplyTo_readsSnapshotAndDropsMalformed() {
        val parsed =
            ChannelResponseParser.parseReplyTo(
                mapOf(
                    "messageId" to "m1",
                    "senderUid" to "u1",
                    "senderDisplayName" to "Alice",
                    "textPreview" to "hi there",
                ),
            )
        assertEquals("m1", parsed?.messageId)
        assertEquals("u1", parsed?.senderUid)
        assertEquals("Alice", parsed?.senderDisplayName)
        assertEquals("hi there", parsed?.textPreview)

        // An ordinary message carries no replyTo, and a snapshot missing its
        // messageId/senderUid is dropped rather than rendered as a half-quote.
        assertNull(ChannelResponseParser.parseReplyTo(null))
        assertNull(ChannelResponseParser.parseReplyTo(mapOf("senderUid" to "u1")))
    }

    @Test
    fun dmParseReplyTo_readsSnapshotAndCoalescesMissingPreview() {
        val parsed =
            DmResponseParser.parseReplyTo(
                mapOf("messageId" to "m9", "senderUid" to "u2"),
            )
        assertEquals("m9", parsed?.messageId)
        assertEquals("u2", parsed?.senderUid)
        assertNull(parsed?.senderDisplayName)
        // A missing preview coalesces to empty rather than throwing.
        assertEquals("", parsed?.textPreview)

        assertNull(DmResponseParser.parseReplyTo(mapOf("messageId" to "m9")))
    }
}
