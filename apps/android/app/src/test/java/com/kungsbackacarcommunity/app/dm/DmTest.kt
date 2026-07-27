package com.kungsbackacarcommunity.app.dm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure domain logic: pair-id derivation, mapping, merge, and callable parsing. */
class DmTest {

    @Test
    fun `dmPairId is order-independent and joined with double underscore`() {
        assertEquals("a__b", dmPairId("a", "b"))
        assertEquals(dmPairId("a", "b"), dmPairId("b", "a"))
        assertEquals("uid1__uid2", dmPairId("uid2", "uid1"))
    }

    @Test
    fun `mapSend branches on the HttpsError code`() {
        assertEquals(DmSendError.SignedOut, DmErrorMapper.mapSend(DmErrorCode.Unauthenticated))
        assertEquals(DmSendError.NotMember, DmErrorMapper.mapSend(DmErrorCode.PermissionDenied))
        assertEquals(DmSendError.Invalid, DmErrorMapper.mapSend(DmErrorCode.InvalidArgument))
        // Both NOT_FRIENDS and NOT_DELIVERABLE arrive as failed-precondition and
        // collapse to one neutral error (a block is never revealed).
        assertEquals(DmSendError.CannotDeliver, DmErrorMapper.mapSend(DmErrorCode.FailedPrecondition))
        assertEquals(DmSendError.Generic, DmErrorMapper.mapSend(DmErrorCode.NotFound))
        assertEquals(DmSendError.Generic, DmErrorMapper.mapSend(DmErrorCode.Other))
    }

    @Test
    fun `only a Generic send error is retryable`() {
        // Transient/unknown → offer a resend.
        assertTrue(DmSendError.Generic.isRetryable)
        // Terminal → no pointless retry (would just fail the same way).
        assertFalse(DmSendError.SignedOut.isRetryable)
        assertFalse(DmSendError.NotMember.isRetryable)
        assertFalse(DmSendError.Invalid.isRetryable)
        assertFalse(DmSendError.CannotDeliver.isRetryable)
    }

    @Test
    fun `otherMember picks the member that is not the caller`() {
        assertEquals("b", DmMapper.otherMember(listOf("a", "b"), "a"))
        assertEquals("a", DmMapper.otherMember(listOf("a", "b"), "b"))
        assertNull(DmMapper.otherMember(listOf("a"), "a"))
    }

    @Test
    fun `unreadFor clamps to a non-negative Int`() {
        assertEquals(3, DmMapper.unreadFor(mapOf("me" to 3L), "me"))
        assertEquals(0, DmMapper.unreadFor(mapOf("me" to 0L), "me"))
        assertEquals(0, DmMapper.unreadFor(mapOf("me" to -1L), "me"))
        assertEquals(0, DmMapper.unreadFor(emptyMap(), "me"))
        // A value beyond Int range must saturate, not overflow negative.
        assertEquals(Int.MAX_VALUE, DmMapper.unreadFor(mapOf("me" to Int.MAX_VALUE.toLong() + 1L), "me"))
        assertEquals(Int.MAX_VALUE, DmMapper.unreadFor(mapOf("me" to Long.MAX_VALUE), "me"))
    }

    @Test
    fun `conversation projects the other member profile, unread, and preview`() {
        val doc =
            DmConversationDoc(
                members = listOf("me", "friend"),
                memberProfiles =
                    mapOf(
                        "me" to DmUser("me", "Me", null),
                        "friend" to DmUser("friend", "Robin", "avatars/friend"),
                    ),
                lastMessageText = "Hej!",
                lastMessageSenderUid = "friend",
                lastMessageAtMillis = 1000L,
                unread = mapOf("me" to 2L, "friend" to 0L),
            )
        val conversation = DmMapper.conversation("me__friend", doc, "me")
        assertEquals("me__friend", conversation.conversationId)
        assertEquals("friend", conversation.otherUser.uid)
        assertEquals("Robin", conversation.otherUser.displayName)
        assertEquals("avatars/friend", conversation.otherUser.avatarPath)
        assertEquals(2, conversation.unreadCount)
        assertEquals("Hej!", conversation.lastMessage?.text)
        assertEquals("friend", conversation.lastMessage?.senderUid)
    }

    @Test
    fun `conversation with no last message yields a null preview`() {
        val doc =
            DmConversationDoc(
                members = listOf("me", "friend"),
                memberProfiles = mapOf("friend" to DmUser("friend", "Robin", null)),
                lastMessageText = null,
                lastMessageSenderUid = null,
                lastMessageAtMillis = null,
                unread = emptyMap(),
            )
        assertNull(DmMapper.conversation("me__friend", doc, "me").lastMessage)
    }

    @Test
    fun `isHiddenByBlock drops a conversation the backend marked as a blocked pair`() {
        // The inbox is a LIST query, so a Firestore rule cannot filter this row
        // out — the document IS delivered and the client has to drop it. This is
        // the signal covering the window before the blockVisibility mirror
        // catches up, and the case where that mirror sits at its cap. Note the
        // blanked preview but still-set lastMessageAt: without this check the row
        // renders as a contentless entry holding its place in the ordering.
        val doc =
            DmConversationDoc(
                members = listOf("me", "friend"),
                memberProfiles = mapOf("friend" to DmUser("friend", "Robin", null)),
                lastMessageText = null,
                lastMessageSenderUid = null,
                lastMessageAtMillis = 1000L,
                unread = emptyMap(),
                blockedPair = true,
            )
        assertTrue(DmMapper.isHiddenByBlock(doc))
    }

    @Test
    fun `isHiddenByBlock keeps a normal conversation and defaults to visible`() {
        // Default-false is load-bearing: conversation documents written before
        // the marker existed carry no such field, and must read as visible rather
        // than vanishing from every inbox.
        val doc =
            DmConversationDoc(
                members = listOf("me", "friend"),
                memberProfiles = mapOf("friend" to DmUser("friend", "Robin", null)),
                lastMessageText = "Hej!",
                lastMessageSenderUid = "friend",
                lastMessageAtMillis = 1000L,
                unread = emptyMap(),
            )
        assertFalse(DmMapper.isHiddenByBlock(doc))
    }

    @Test
    fun `sortConversations orders newest first`() {
        val a = conversation("a", 100L)
        val b = conversation("b", 300L)
        val c = conversation("c", 200L)
        val sorted = DmMapper.sortConversations(listOf(a, b, c))
        assertEquals(listOf("b", "c", "a"), sorted.map { it.conversationId })
    }

    @Test
    fun `isSendable enforces the 1_2000 trimmed bound`() {
        assertFalse(DmThread.isSendable(""))
        assertFalse(DmThread.isSendable("   "))
        assertTrue(DmThread.isSendable("hi"))
        assertTrue(DmThread.isSendable("x".repeat(DM_MESSAGE_MAX_LENGTH)))
        assertFalse(DmThread.isSendable("x".repeat(DM_MESSAGE_MAX_LENGTH + 1)))
    }

    @Test
    fun `merge de-duplicates by id and sorts chronologically`() {
        val older = listOf(message("m1", 100L), message("m2", 200L))
        val live = listOf(message("m2", 200L), message("m3", 300L))
        val merged = DmThread.merge(older, live)
        assertEquals(listOf("m1", "m2", "m3"), merged.map { it.id })
    }

    @Test
    fun `mergeWithPending appends an optimistic bubble at the end while it is in-flight`() {
        val live = listOf(message("m1", 100L))
        val pending =
            DmMessage(
                id = "cid-1",
                senderUid = "me",
                text = "hi",
                createdAtMillis = 200L,
                createdAtIso = null,
                clientId = "cid-1",
                deliveryState = DmDeliveryState.Sending,
            )
        val display = DmThread.mergeWithPending(emptyList(), live, listOf(pending))
        assertEquals(listOf("m1", "cid-1"), display.map { it.id })
        assertEquals(DmDeliveryState.Sending, display.last().deliveryState)
    }

    @Test
    fun `mergeWithPending renders the optimistic message and its delivered doc as ONE`() {
        // The delivered document's id equals the optimistic bubble's clientId,
        // so once it arrives in the live window the bubble must NOT also show —
        // exactly one message, not two.
        val delivered = message("cid-1", 200L).copy(clientId = "cid-1")
        val pending =
            DmMessage(
                id = "cid-1",
                senderUid = "me",
                text = "hi",
                createdAtMillis = 200L,
                createdAtIso = null,
                clientId = "cid-1",
                deliveryState = DmDeliveryState.Sent,
            )
        val display = DmThread.mergeWithPending(emptyList(), listOf(delivered), listOf(pending))
        assertEquals(1, display.size)
        assertEquals("cid-1", display.single().id)
        // The server copy wins (it is [DmDeliveryState.Sent] with no local flag).
        assertEquals("friend", display.single().senderUid)
    }

    @Test
    fun `mergeWithPending with no pending is just the server merge`() {
        val live = listOf(message("m1", 100L), message("m2", 200L))
        assertEquals(
            listOf("m1", "m2"),
            DmThread.mergeWithPending(emptyList(), live, emptyList()).map { it.id },
        )
    }

    @Test
    fun `oldestCursor returns the earliest message ISO`() {
        val messages =
            listOf(
                message("m2", 200L).copy(createdAtIso = "2026-07-11T00:00:02Z"),
                message("m1", 100L).copy(createdAtIso = "2026-07-11T00:00:01Z"),
            )
        assertEquals("2026-07-11T00:00:01Z", DmThread.oldestCursor(messages))
        assertNull(DmThread.oldestCursor(emptyList()))
    }

    @Test
    fun `parseSendSuccess requires both ids`() {
        val ok =
            DmResponseParser.parseSendSuccess(mapOf("conversationId" to "c1", "messageId" to "m1"))
        assertEquals(DmSendResult.Sent("c1", "m1"), ok)
        assertTrue(DmResponseParser.parseSendSuccess(mapOf("conversationId" to "c1")) is DmSendResult.Failed)
        assertTrue(DmResponseParser.parseSendSuccess(null) is DmSendResult.Failed)
    }

    @Test
    fun `parseMessagesPage maps rows, cursor, and hasMore`() {
        val data =
            mapOf(
                "messages" to
                    listOf(
                        mapOf(
                            "id" to "m1",
                            "senderUid" to "friend",
                            "text" to "hi",
                            "createdAt" to "2026-07-11T00:00:01Z",
                        ),
                        // Dropped: no id.
                        mapOf("senderUid" to "friend", "text" to "x"),
                    ),
                "nextBefore" to "2026-07-11T00:00:00Z",
                "hasMore" to true,
            )
        val page = DmResponseParser.parseMessagesPage(data)
        assertEquals(1, page.messages.size)
        assertEquals("m1", page.messages.first().id)
        assertEquals("2026-07-11T00:00:01Z", page.messages.first().createdAtIso)
        assertEquals("2026-07-11T00:00:00Z", page.nextBefore)
        assertTrue(page.hasMore)
    }

    @Test
    fun `parseMessagesPage defaults hasMore to false and tolerates an empty payload`() {
        val page = DmResponseParser.parseMessagesPage(emptyMap())
        assertTrue(page.messages.isEmpty())
        assertNull(page.nextBefore)
        assertFalse(page.hasMore)
    }

    @Test
    fun `iso round-trips through millis`() {
        val millis = 1_752_192_001_000L
        assertEquals(millis, isoToMillisOrNull(millisToIso(millis)))
        assertNull(isoToMillisOrNull("not-a-date"))
    }

    private fun conversation(id: String, lastAt: Long?) =
        DmConversation(
            conversationId = id,
            otherUser = DmUser("o-$id", null, null),
            lastMessage = null,
            unreadCount = 0,
            lastMessageAtMillis = lastAt,
        )

    private fun message(id: String, millis: Long) =
        DmMessage(id = id, senderUid = "friend", text = id, createdAtMillis = millis, createdAtIso = null)
}
