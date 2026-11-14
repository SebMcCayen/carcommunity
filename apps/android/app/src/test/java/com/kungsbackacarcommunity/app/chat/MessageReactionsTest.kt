package com.kungsbackacarcommunity.app.chat

import org.junit.Assert.*
import org.junit.Test

class MessageReactionsTest {
    @Test fun `rank is frequency then recency with deterministic ties`() {
        assertEquals(listOf("a", "c", "b", "d"), rankReactionEmojis(mapOf(
            "d" to (1L to 200L), "b" to (3L to 10L), "c" to (3L to 20L), "a" to (10L to 0L),
        )))
        assertEquals(listOf("a", "b"), rankReactionEmojis(mapOf("b" to (1L to 1L), "a" to (1L to 1L))))
    }
    @Test fun `counts reject forged identity text nonintegral zero negative and unsafe numeric values`() {
        assertEquals(mapOf("1f44d" to 2L, "2764-fe0f" to 3L), parseReactionCounts(mapOf(
            "1f44d" to 2, "2764-fe0f" to 3L, "victim" to 100, "1f602" to 1.5,
            "1f60a" to -1, "1f451" to 0, "1f680" to Long.MAX_VALUE, "1f603" to true,
        )))
        assertTrue(parseReactionCounts(null).isEmpty())
        assertTrue(parseReactionCounts(listOf("1f44d")).isEmpty())
    }
    @Test fun `legacy and paginated messages keep additive reaction counts`() {
        val message = com.kungsbackacarcommunity.app.chatchannels.ChannelResponseParser.parseMessage(mapOf(
            "id" to "m", "senderUid" to "author", "text" to "fixture", "reactionCounts" to mapOf("1f44d" to 3),
        ))!!
        assertEquals(mapOf("1f44d" to 3L), message.reactionCounts)
        assertEquals(message.reactionCounts, message.copy(senderDisplayName = "Current author").reactionCounts)
        val dm = com.kungsbackacarcommunity.app.dm.DmResponseParser.parseMessage(mapOf(
            "id" to "m", "senderUid" to "author", "text" to "fixture", "reactionCounts" to mapOf("1f44d" to 2),
        ))!!
        assertEquals(mapOf("1f44d" to 2L), dm.reactionCounts)
    }
}
