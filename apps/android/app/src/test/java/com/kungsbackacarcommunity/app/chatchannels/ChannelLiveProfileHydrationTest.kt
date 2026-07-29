package com.kungsbackacarcommunity.app.chatchannels

import com.kungsbackacarcommunity.app.profile.LiveProfile
import com.kungsbackacarcommunity.app.profile.LiveProfileRepository
import com.kungsbackacarcommunity.app.profile.LiveProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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

    /**
     * A repository that violates [LiveProfileRepository]'s never-throws contract,
     * to prove the overlay is CONTAINED at the call site rather than merely
     * documented as safe somewhere else.
     */
    private object ThrowingProfiles : LiveProfileRepository {
        override fun observeProfiles(uids: Set<String>): Flow<Map<String, LiveProfile>> = flow {
            throw IllegalStateException("profile read blew up")
        }

        override suspend fun loadProfiles(uids: Set<String>): Map<String, LiveProfile> =
            throw IllegalStateException("profile read blew up")
    }

    @Test
    fun `a failing overlay leaves the live window on its stored copies`() = runTest {
        // Without containment the throw would terminate the live message stream:
        // the chat would freeze on its last frame and stop receiving messages,
        // with no error state to show for it.
        val loaded = ChannelMessagesState.Loaded(listOf(message("m1", "eva")))

        val emitted = flowOf<ChannelMessagesState>(loaded).hydrateSenders(ThrowingProfiles).toList()

        assertEquals(listOf(loaded), emitted)
    }

    @Test
    fun `a failing overlay does not throw away an older page that was fetched`() = runTest {
        // The page reached the client; only the cosmetic refresh failed. Without
        // containment ChannelChatCoordinator.loadOlder would map the throw to a
        // retryable Error and discard these messages.
        val page =
            ChannelMessagesPage(
                messages = listOf(message("m1", "eva")),
                nextBefore = "2026-07-28T09:00:00.000Z",
                hasMore = true,
            )

        assertEquals(page, page.hydrateSenders(ThrowingProfiles))
    }

    @Test
    fun `containment does not swallow cancellation of the older-page read`() = runTest {
        // Cancellation must still unwind: swallowing it would turn a cancelled
        // pagination into an apparently successful un-hydrated page.
        val cancelling =
            object : LiveProfileRepository {
                override fun observeProfiles(uids: Set<String>): Flow<Map<String, LiveProfile>> =
                    flowOf(emptyMap())

                override suspend fun loadProfiles(uids: Set<String>): Map<String, LiveProfile> =
                    throw CancellationException("collector went away")
            }
        val page = ChannelMessagesPage(listOf(message("m1", "eva")), nextBefore = null, hasMore = false)

        assertThrows(CancellationException::class.java) {
            runBlocking { page.hydrateSenders(cancelling) }
        }
    }
}
