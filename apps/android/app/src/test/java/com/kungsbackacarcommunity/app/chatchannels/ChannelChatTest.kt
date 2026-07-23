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
    fun `parsePostSuccess carries the ACCEPTED mention set the server echoed`() {
        assertEquals(
            ChannelSendResult.Sent("m1", listOf("uid-a", "uid-b")),
            ChannelResponseParser.parsePostSuccess(
                mapOf("messageId" to "m1", "mentionedUids" to listOf("uid-a", "uid-b")),
            ),
        )
    }

    @Test
    fun `a missing or malformed mention echo does not fail a message that posted`() {
        // convoyChat-post echoes none at all, and a payload we can't read must not
        // undo a message a human wrote — both parse as the empty accepted set.
        assertEquals(
            ChannelSendResult.Sent("m1", emptyList()),
            ChannelResponseParser.parsePostSuccess(mapOf("messageId" to "m1")),
        )
        assertEquals(
            ChannelSendResult.Sent("m1", emptyList()),
            ChannelResponseParser.parsePostSuccess(
                mapOf("messageId" to "m1", "mentionedUids" to "not-a-list"),
            ),
        )
    }

    @Test
    fun `parseMentionedUids drops blanks and non-strings and dedupes`() {
        assertEquals(
            listOf("uid-a", "uid-b"),
            ChannelResponseParser.parseMentionedUids(
                listOf("uid-a", "", "uid-b", "uid-a", 7, null),
            ),
        )
        assertEquals(emptyList<String>(), ChannelResponseParser.parseMentionedUids(null))
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
    fun `a listed message carries its mentions, and pre-mentions history carries none`() {
        assertEquals(
            listOf("uid-a"),
            ChannelResponseParser.parseMessage(
                mapOf("id" to "m1", "senderUid" to "u1", "mentionedUids" to listOf("uid-a")),
            )?.mentionedUids,
        )
        // Absent on pre-mentions history (and always [] for convoy) — never null.
        assertEquals(
            emptyList<String>(),
            ChannelResponseParser.parseMessage(mapOf("id" to "m1", "senderUid" to "u1"))
                ?.mentionedUids,
        )
    }

    @Test
    fun `parseMessage drops rows with a missing or blank senderUid`() {
        // Valid row survives.
        assertEquals(
            "u1",
            ChannelResponseParser.parseMessage(mapOf("id" to "m1", "senderUid" to "u1"))?.senderUid,
        )
        // senderUid is required (author identity + own/other + unread logic):
        // missing, empty, and whitespace-only are all malformed → dropped.
        assertNull(ChannelResponseParser.parseMessage(mapOf("id" to "m1")))
        assertNull(ChannelResponseParser.parseMessage(mapOf("id" to "m1", "senderUid" to "")))
        assertNull(ChannelResponseParser.parseMessage(mapOf("id" to "m1", "senderUid" to "   ")))
    }

    @Test
    fun `parseLastReadAt returns the marker or null`() {
        assertEquals("2020-01-01T00:00:00Z", ChannelResponseParser.parseLastReadAt(mapOf("lastReadAt" to "2020-01-01T00:00:00Z")))
        assertNull(ChannelResponseParser.parseLastReadAt(mapOf("lastReadAt" to "")))
        assertNull(ChannelResponseParser.parseLastReadAt(null))
    }

    @Test
    fun `chatEligibleConvoys keeps every accepted convoy, ended included as history`() {
        val data =
            mapOf(
                "convoys" to
                    listOf(
                        // Accepted + active → kept (the ongoing convoy).
                        mapOf(
                            "convoyId" to "c1",
                            "title" to "Sunday cruise",
                            "status" to "active",
                            "viewer" to mapOf("inviteStatus" to "accepted"),
                            "createdAt" to "2020-01-02T14:05:00Z",
                            "memberUids" to listOf("a", "b", "c"),
                            "members" to
                                listOf(
                                    mapOf("uid" to "a", "inviteStatus" to "accepted", "displayName" to "Alice"),
                                    mapOf("uid" to "b", "inviteStatus" to "accepted", "displayName" to "Bob"),
                                    // Still-invited member: counted for neither the
                                    // accepted names nor the accepted count.
                                    mapOf("uid" to "c", "inviteStatus" to "invited", "displayName" to "Cara"),
                                ),
                        ),
                        // Still invited → dropped (can't read the chat).
                        mapOf(
                            "convoyId" to "c2",
                            "status" to "forming",
                            "viewer" to mapOf("inviteStatus" to "invited"),
                            "memberUids" to listOf("a", "x"),
                        ),
                        // Accepted but ended → KEPT (its chat is readable history).
                        mapOf(
                            "convoyId" to "c3",
                            "status" to "ended",
                            "viewer" to mapOf("inviteStatus" to "accepted"),
                            "createdAt" to "2020-01-01T09:00:00Z",
                            "memberUids" to listOf("a"),
                            "members" to
                                listOf(
                                    mapOf("uid" to "a", "inviteStatus" to "accepted", "displayName" to "Alice"),
                                ),
                        ),
                    ),
            )
        val convoys = ConvoyChatMapper.chatEligibleConvoys(data)
        assertEquals(2, convoys.size)

        val c1 = convoys.first { it.convoyId == "c1" }
        assertEquals("Sunday cruise", c1.title)
        assertEquals("active", c1.status)
        // Only the two ACCEPTED members are counted / named (Cara is still invited).
        assertEquals(2, c1.memberCount)
        assertEquals(listOf("Alice", "Bob"), c1.memberNames)
        assertEquals(
            java.time.Instant.parse("2020-01-02T14:05:00Z").toEpochMilli(),
            c1.createdAtMillis,
        )

        val c3 = convoys.first { it.convoyId == "c3" }
        assertEquals("ended", c3.status)
        assertEquals(listOf("Alice"), c3.memberNames)
    }

    @Test
    fun `chatEligibleConvoys falls back to memberUids count when the roster is absent`() {
        val data =
            mapOf(
                "convoys" to
                    listOf(
                        mapOf(
                            "convoyId" to "c1",
                            "status" to "active",
                            "viewer" to mapOf("inviteStatus" to "accepted"),
                            "memberUids" to listOf("a", "b", "c"),
                        ),
                    ),
            )
        val convoys = ConvoyChatMapper.chatEligibleConvoys(data)
        assertEquals(1, convoys.size)
        assertEquals(3, convoys[0].memberCount)
        assertTrue(convoys[0].memberNames.isEmpty())
        assertNull(convoys[0].createdAtMillis)
    }

    @Test
    fun `chatEligibleConvoys tolerates a missing or malformed payload`() {
        assertTrue(ConvoyChatMapper.chatEligibleConvoys(null).isEmpty())
        assertTrue(ConvoyChatMapper.chatEligibleConvoys(mapOf("convoys" to "nope")).isEmpty())
    }
}
