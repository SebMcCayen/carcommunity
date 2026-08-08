package com.kungsbackacarcommunity.app.dm

import com.kungsbackacarcommunity.app.friends.FriendRequestDirection
import com.kungsbackacarcommunity.app.friends.FriendRequestSummary
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendUser
import com.kungsbackacarcommunity.app.friends.FriendsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NewDialogue] — the pure logic behind the DM inbox's
 * "start a new dialogue" friend picker (eligibility + the selection →
 * open-existing-vs-new mapping).
 */
class NewDialogueTest {
    private fun friend(uid: String, name: String?) =
        FriendSummary(uid = uid, displayName = name, avatarPath = null, friendsSince = null)

    private fun conversation(otherUid: String, otherName: String?) =
        DmConversation(
            conversationId = dmPairId("me", otherUid),
            otherUser = DmUser(uid = otherUid, displayName = otherName, avatarPath = null),
            lastMessage = null,
            unreadCount = 0,
            lastMessageAtMillis = null,
        )

    @Test
    fun `targets are the established friends, name-ordered, blank-uid dropped`() {
        val data =
            FriendsData(
                friends = listOf(friend("u2", "Bo"), friend("", "Ghost"), friend("u1", "Anna")),
                incoming = emptyList(),
                outgoing = emptyList(),
            )

        val result = NewDialogue.targets(data)

        assertEquals(listOf("u1", "u2"), result.map { it.uid })
        assertEquals(listOf("Anna", "Bo"), result.map { it.displayName })
    }

    @Test
    fun `pending requests are never targets`() {
        val data =
            FriendsData(
                friends = emptyList(),
                incoming =
                    listOf(
                        FriendRequestSummary(
                            requestId = "r1",
                            fromUid = "u9",
                            toUid = "me",
                            direction = FriendRequestDirection.Incoming,
                            otherUser = FriendUser("u9", "Pending", null),
                            createdAt = null,
                        ),
                    ),
                outgoing = emptyList(),
            )

        assertTrue(NewDialogue.targets(data).isEmpty())
    }

    @Test
    fun `openTargetFor a friend with no existing conversation is a new thread`() {
        val target = NewDialogue.openTargetFor(friend("u1", "Anna"), conversations = emptyList())

        assertEquals("u1", target.uid)
        assertEquals("Anna", target.displayName)
        assertFalse(target.isExisting)
    }

    @Test
    fun `openTargetFor a friend with an existing conversation prefers the inbox name`() {
        // The inbox card is the name the member just saw; it can be fresher than the
        // friend-graph row (live-hydrated), so it wins when present.
        val target =
            NewDialogue.openTargetFor(
                friend("u1", "Anna"),
                conversations = listOf(conversation("u1", "Anna Svensson"), conversation("u2", "Bo")),
            )

        assertEquals("u1", target.uid)
        assertEquals("Anna Svensson", target.displayName)
        assertTrue(target.isExisting)
    }

    @Test
    fun `openTargetFor falls back to the friend name when the existing row has none`() {
        val target =
            NewDialogue.openTargetFor(
                friend("u1", "Anna"),
                conversations = listOf(conversation("u1", null)),
            )

        // Still flagged existing, but the blank inbox name never shadows a usable one.
        assertTrue(target.isExisting)
        assertEquals("Anna", target.displayName)
    }

    @Test
    fun `openTargetFor tolerates a friend and existing row that are both nameless`() {
        val target =
            NewDialogue.openTargetFor(
                friend("u1", null),
                conversations = listOf(conversation("u1", "   ")),
            )

        assertEquals("u1", target.uid)
        assertNull(target.displayName)
        assertTrue(target.isExisting)
    }
}
