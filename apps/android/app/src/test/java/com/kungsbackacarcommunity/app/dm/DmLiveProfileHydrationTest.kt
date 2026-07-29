package com.kungsbackacarcommunity.app.dm

import com.kungsbackacarcommunity.app.profile.LiveProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The DM inbox half of live-profile hydration: the counterparty card must show
 * the member's CURRENT name and avatar, not the copy frozen onto
 * `conversations/{pairId}.memberProfiles` when they last messaged you.
 */
class DmLiveProfileHydrationTest {

    private fun conversation(
        otherUid: String,
        displayName: String? = "Old Name",
        avatarPath: String? = "profileImages/$otherUid/old.jpg",
        lastMessageAtMillis: Long? = 1_000L,
    ) = DmConversation(
        conversationId = "me__$otherUid",
        otherUser = DmUser(otherUid, displayName, avatarPath),
        lastMessage = null,
        unreadCount = 0,
        lastMessageAtMillis = lastMessageAtMillis,
    )

    @Test
    fun `the other party's avatar is refreshed from their live profile`() {
        // The reported bug: they changed their picture after last messaging you,
        // so the stored copy — and only the stored copy — is stale.
        val rows = listOf(conversation("eva"))
        val live = mapOf("eva" to LiveProfile("Eva Ny", "profileImages/eva/new.jpg"))

        val hydrated = DmMapper.hydrateConversations(rows, live).single()

        assertEquals("Eva Ny", hydrated.otherUser.displayName)
        assertEquals("profileImages/eva/new.jpg", hydrated.otherUser.avatarPath)
    }

    @Test
    fun `an avatar uploaded for the first time appears on an existing row`() {
        val rows = listOf(conversation("eva", displayName = "Eva", avatarPath = null))
        val live = mapOf("eva" to LiveProfile("Eva", "profileImages/eva/first.jpg"))

        assertEquals(
            "profileImages/eva/first.jpg",
            DmMapper.hydrateConversations(rows, live).single().otherUser.avatarPath,
        )
    }

    @Test
    fun `a deleted avatar disappears from the row`() {
        val rows = listOf(conversation("eva"))
        val live = mapOf("eva" to LiveProfile("Eva", null))

        assertEquals(null, DmMapper.hydrateConversations(rows, live).single().otherUser.avatarPath)
    }

    @Test
    fun `a row whose member has no live profile keeps its stored copy`() {
        // Deleted account, or a read that failed — the row must still name them.
        val rows = listOf(conversation("gone"))

        val hydrated = DmMapper.hydrateConversations(rows, mapOf("eva" to LiveProfile("Eva", null)))

        assertEquals("Old Name", hydrated.single().otherUser.displayName)
        assertEquals("profileImages/gone/old.jpg", hydrated.single().otherUser.avatarPath)
    }

    @Test
    fun `each row gets its OWN member's profile`() {
        val rows = listOf(conversation("eva"), conversation("nils"))
        val live =
            mapOf(
                "eva" to LiveProfile("Eva", "profileImages/eva/new.jpg"),
                "nils" to LiveProfile("Nils", "profileImages/nils/new.jpg"),
            )

        val hydrated = DmMapper.hydrateConversations(rows, live)

        assertEquals("profileImages/eva/new.jpg", hydrated[0].otherUser.avatarPath)
        assertEquals("profileImages/nils/new.jpg", hydrated[1].otherUser.avatarPath)
    }

    @Test
    fun `hydration never reorders or drops rows, and preserves unread state`() {
        val rows = listOf(conversation("eva"), conversation("nils"), conversation("gone"))
        val live = mapOf("eva" to LiveProfile("Eva", null))

        val hydrated = DmMapper.hydrateConversations(rows, live)

        assertEquals(rows.map { it.conversationId }, hydrated.map { it.conversationId })
        assertEquals(rows.map { it.lastMessageAtMillis }, hydrated.map { it.lastMessageAtMillis })
        assertEquals(rows.map { it.unreadCount }, hydrated.map { it.unreadCount })
    }

    @Test
    fun `no live profiles at all is a no-op`() {
        val rows = listOf(conversation("eva"))
        assertSame(rows, DmMapper.hydrateConversations(rows, emptyMap()))
    }
}
