package com.kungsbackacarcommunity.app.chatchannels

import com.kungsbackacarcommunity.app.profile.LiveProfile
import com.kungsbackacarcommunity.app.profile.LiveProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The chat-channel half of live-profile hydration.
 *
 * Product decision under test: a channel shows a member as they are NOW, so a
 * member who changes their avatar changes it on their whole posting history —
 * the per-message denormalization exists to spare the render a profile lookup,
 * not to pin identity to post time.
 */
class ChannelLiveProfileHydrationTest {

    private fun message(
        id: String,
        senderUid: String,
        displayName: String? = "Old Name",
        avatarPath: String? = "profileImages/$senderUid/old.jpg",
        createdAtMillis: Long? = 1_000L,
    ) = ChannelMessage(
        id = id,
        senderUid = senderUid,
        text = "hej",
        senderDisplayName = displayName,
        senderAvatarPath = avatarPath,
        createdAtMillis = createdAtMillis,
        createdAtIso = "2026-07-28T10:00:00.000Z",
    )

    @Test
    fun `every past message from a sender picks up their new avatar`() {
        // The whole point: not just the newest message.
        val messages = List(5) { message("m$it", "eva") }
        val live = mapOf("eva" to LiveProfile("Eva Ny", "profileImages/eva/new.jpg"))

        val hydrated = ChannelThread.hydrate(messages, live)

        assertEquals(5, hydrated.size)
        assertEquals(listOf("profileImages/eva/new.jpg"), hydrated.map { it.senderAvatarPath }.distinct())
        assertEquals(listOf("Eva Ny"), hydrated.map { it.senderDisplayName }.distinct())
    }

    @Test
    fun `a deleted avatar disappears from the sender's history`() {
        val messages = listOf(message("m1", "eva"))
        val live = mapOf("eva" to LiveProfile("Eva", null))

        assertEquals(null, ChannelThread.hydrate(messages, live).single().senderAvatarPath)
    }

    @Test
    fun `a sender with no live profile keeps the copy stamped at post time`() {
        val messages = listOf(message("m1", "gone"))

        val hydrated = ChannelThread.hydrate(messages, mapOf("eva" to LiveProfile("Eva", null)))

        assertEquals("Old Name", hydrated.single().senderDisplayName)
        assertEquals("profileImages/gone/old.jpg", hydrated.single().senderAvatarPath)
    }

    @Test
    fun `one sender never receives another sender's picture`() {
        val messages = listOf(message("m1", "eva"), message("m2", "nils"))
        val live =
            mapOf(
                "eva" to LiveProfile("Eva", "profileImages/eva/new.jpg"),
                "nils" to LiveProfile("Nils", "profileImages/nils/new.jpg"),
            )

        val hydrated = ChannelThread.hydrate(messages, live)

        assertEquals("profileImages/eva/new.jpg", hydrated[0].senderAvatarPath)
        assertEquals("profileImages/nils/new.jpg", hydrated[1].senderAvatarPath)
    }

    @Test
    fun `a busy window costs one profile read per SENDER, not per message`() {
        // The read-cost guarantee for a channel with hundreds of messages.
        val messages = (1..300).map { message("m$it", if (it % 3 == 0) "eva" else "nils") }

        assertEquals(setOf("eva", "nils"), LiveProfiles.uidsOf(messages) { it.senderUid })
    }

    @Test
    fun `hydration preserves message identity, text and ordering keys`() {
        val messages = listOf(message("m1", "eva", createdAtMillis = 10L), message("m2", "eva", createdAtMillis = 20L))
        val live = mapOf("eva" to LiveProfile("Eva", "profileImages/eva/new.jpg"))

        val hydrated = ChannelThread.hydrate(messages, live)

        assertEquals(listOf("m1", "m2"), hydrated.map { it.id })
        assertEquals(listOf(10L, 20L), hydrated.map { it.createdAtMillis })
        assertEquals(listOf("hej", "hej"), hydrated.map { it.text })
    }

    @Test
    fun `hydration does not disturb the merge of older pages with the live window`() {
        val older = listOf(message("m1", "eva", createdAtMillis = 10L))
        val liveWindow = listOf(message("m2", "eva", createdAtMillis = 20L))
        val profiles = mapOf("eva" to LiveProfile("Eva", "profileImages/eva/new.jpg"))

        val mergedThenHydrated = ChannelThread.hydrate(ChannelThread.merge(older, liveWindow), profiles)
        val hydratedThenMerged =
            ChannelThread.merge(
                ChannelThread.hydrate(older, profiles),
                ChannelThread.hydrate(liveWindow, profiles),
            )

        // Hydrating each source independently (what the repository actually does:
        // the live window in observeMessages, older pages in loadOlder) must give
        // the same thread as hydrating the merged result.
        assertEquals(mergedThenHydrated, hydratedThenMerged)
    }

    @Test
    fun `no live profiles at all is a no-op`() {
        val messages = listOf(message("m1", "eva"))
        assertSame(messages, ChannelThread.hydrate(messages, emptyMap()))
    }
}
