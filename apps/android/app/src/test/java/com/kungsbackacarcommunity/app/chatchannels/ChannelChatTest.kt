package com.kungsbackacarcommunity.app.chatchannels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure domain logic for the community/convoy chat channels: mapping, merge, parsing, unread. */
class ChannelChatTest {

    @Test
    fun `mapSend branches on the HttpsError code`() {
        assertEquals(ChannelSendError.SignedOut, ChannelErrorMapper.mapSend(ChannelErrorCode.Unauthenticated))
        assertEquals(ChannelSendError.NotMember, ChannelErrorMapper.mapSend(ChannelErrorCode.PermissionDenied))
        assertEquals(ChannelSendError.Invalid, ChannelErrorMapper.mapSend(ChannelErrorCode.InvalidArgument))
        // profile-missing / still-invited convoy member / convoy-not-found all
        // collapse to one neutral error (never reveal which).
        assertEquals(
            ChannelSendError.CannotDeliver,
            ChannelErrorMapper.mapSend(ChannelErrorCode.FailedPrecondition),
        )
        assertEquals(ChannelSendError.CannotDeliver, ChannelErrorMapper.mapSend(ChannelErrorCode.NotFound))
        assertEquals(ChannelSendError.Generic, ChannelErrorMapper.mapSend(ChannelErrorCode.Other))
    }

    @Test
    fun `isSendable trims and bounds the draft`() {
        assertFalse(ChannelThread.isSendable(""))
        assertFalse(ChannelThread.isSendable("   "))
        assertTrue(ChannelThread.isSendable("hi"))
        assertTrue(ChannelThread.isSendable("x".repeat(CHANNEL_MESSAGE_MAX_LENGTH)))
        assertFalse(ChannelThread.isSendable("x".repeat(CHANNEL_MESSAGE_MAX_LENGTH + 1)))
    }

    private fun msg(id: String, millis: Long?, senderUid: String = "u") =
        ChannelMessage(
            id = id,
            senderUid = senderUid,
            text = id,
            senderDisplayName = null,
            senderAvatarPath = null,
            createdAtMillis = millis,
            createdAtIso = millis?.let { java.time.Instant.ofEpochMilli(it).toString() },
        )

    @Test
    fun `merge de-dupes by id keeping the live copy and sorts chronologically`() {
        val older = listOf(msg("a", 1000), msg("b", 2000))
        val live = listOf(msg("b", 2000), msg("c", 3000))
        val merged = ChannelThread.merge(older, live)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test
    fun `oldestCursor returns the earliest message ISO`() {
        val messages = listOf(msg("a", 3000), msg("b", 1000), msg("c", 2000))
        assertEquals(java.time.Instant.ofEpochMilli(1000).toString(), ChannelThread.oldestCursor(messages))
        assertNull(ChannelThread.oldestCursor(emptyList()))
    }

    @Test
    fun `hasUnread is true only for a newer message from someone else`() {
        // No message → no unread.
        assertFalse(ChannelThread.hasUnread(null, "me", 500))
        // My own newest message never counts as unread.
        assertFalse(ChannelThread.hasUnread(msg("a", 1000, senderUid = "me"), "me", null))
        // Someone else's message, never read → unread.
        assertTrue(ChannelThread.hasUnread(msg("a", 1000, senderUid = "other"), "me", null))
        // Newer than my marker → unread.
        assertTrue(ChannelThread.hasUnread(msg("a", 2000, senderUid = "other"), "me", 1000))
        // Not newer than my marker → read.
        assertFalse(ChannelThread.hasUnread(msg("a", 1000, senderUid = "other"), "me", 1000))
    }

    @Test
    fun `parsePostSuccess needs a non-blank messageId`() {
        assertEquals(
            ChannelSendResult.Sent("m1"),
            ChannelResponseParser.parsePostSuccess(mapOf("messageId" to "m1")),
        )
        assertTrue(
            ChannelResponseParser.parsePostSuccess(mapOf("messageId" to "")) is ChannelSendResult.Failed,
        )
        assertTrue(ChannelResponseParser.parsePostSuccess(null) is ChannelSendResult.Failed)
    }

    @Test
    fun `parseMessagesPage maps rows and pagination flags`() {
        val data =
            mapOf(
                "messages" to
                    listOf(
                        mapOf(
                            "id" to "m1",
                            "senderUid" to "u1",
                            "text" to "hello",
                            "senderDisplayName" to "Ada",
                            "senderAvatarPath" to "a/b.jpg",
                            "createdAt" to "1970-01-01T00:00:01Z",
                        ),
                        // Dropped: missing id.
                        mapOf("senderUid" to "u2", "text" to "x"),
                    ),
                "nextBefore" to "1970-01-01T00:00:01Z",
                "hasMore" to true,
            )
        val page = ChannelResponseParser.parseMessagesPage(data)
        assertEquals(1, page.messages.size)
        assertEquals("m1", page.messages[0].id)
        assertEquals("Ada", page.messages[0].senderDisplayName)
        assertTrue(page.hasMore)
        assertEquals("1970-01-01T00:00:01Z", page.nextBefore)
    }

    @Test
    fun `parseLastReadAt returns the marker or null`() {
        assertEquals("2020-01-01T00:00:00Z", ChannelResponseParser.parseLastReadAt(mapOf("lastReadAt" to "2020-01-01T00:00:00Z")))
        assertNull(ChannelResponseParser.parseLastReadAt(mapOf("lastReadAt" to "")))
        assertNull(ChannelResponseParser.parseLastReadAt(null))
    }

    @Test
    fun `chatEligibleConvoys keeps only accepted non-ended convoys`() {
        val data =
            mapOf(
                "convoys" to
                    listOf(
                        // Accepted + active → kept.
                        mapOf(
                            "convoyId" to "c1",
                            "title" to "Sunday cruise",
                            "status" to "active",
                            "viewer" to mapOf("inviteStatus" to "accepted"),
                            "memberUids" to listOf("a", "b", "c"),
                        ),
                        // Still invited → dropped (can't read the chat).
                        mapOf(
                            "convoyId" to "c2",
                            "status" to "forming",
                            "viewer" to mapOf("inviteStatus" to "invited"),
                            "memberUids" to listOf("a", "x"),
                        ),
                        // Accepted but ended → dropped (historical).
                        mapOf(
                            "convoyId" to "c3",
                            "status" to "ended",
                            "viewer" to mapOf("inviteStatus" to "accepted"),
                            "memberUids" to listOf("a"),
                        ),
                    ),
            )
        val convoys = ConvoyChatMapper.chatEligibleConvoys(data)
        assertEquals(1, convoys.size)
        assertEquals("c1", convoys[0].convoyId)
        assertEquals("Sunday cruise", convoys[0].title)
        assertEquals(3, convoys[0].memberCount)
    }

    @Test
    fun `chatEligibleConvoys tolerates a missing or malformed payload`() {
        assertTrue(ConvoyChatMapper.chatEligibleConvoys(null).isEmpty())
        assertTrue(ConvoyChatMapper.chatEligibleConvoys(mapOf("convoys" to "nope")).isEmpty())
    }
}
