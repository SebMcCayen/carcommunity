package com.kungsbackacarcommunity.app.events

import com.kungsbackacarcommunity.app.dm.DmConversationsState
import com.kungsbackacarcommunity.app.dm.DmOlderResult
import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.dm.DmSendError
import com.kungsbackacarcommunity.app.dm.DmSendResult
import com.kungsbackacarcommunity.app.dm.DmThreadState
import com.kungsbackacarcommunity.app.friends.FriendActionError
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsData
import com.kungsbackacarcommunity.app.friends.FriendsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [EventShareCoordinator] — the "share an event with a friend"
 * orchestration. Mirrors ShareLocationCoordinatorTest: the ONLY difference is the
 * message body carries a `kccevent:` token instead of a `geo:` one.
 */
class EventShareCoordinatorTest {
    private val eventId = "evt42"
    private val title = "Cars & Coffee"

    private fun friend(uid: String, name: String?) =
        FriendSummary(uid = uid, displayName = name, avatarPath = null, friendsSince = null)

    private class FakeDm(var result: DmSendResult) : DmRepository {
        var lastToUid: String? = null
        var lastText: String? = null
        var lastClientId: String? = null
        val clientIds = mutableListOf<String?>()
        var sendCalls = 0

        override fun observeConversations(uid: String): Flow<DmConversationsState> = emptyFlow()

        override fun observeThread(conversationId: String): Flow<DmThreadState> = emptyFlow()

        override suspend fun sendMessage(toUid: String, text: String, clientId: String?): DmSendResult {
            sendCalls++
            lastToUid = toUid
            lastText = text
            lastClientId = clientId
            clientIds += clientId
            return result
        }

        override suspend fun loadOlder(conversationId: String, before: String): DmOlderResult =
            DmOlderResult.Failed

        override suspend fun markRead(conversationId: String) = Unit
    }

    @Test
    fun `load maps a loaded snapshot to a name-ordered Ready state`() = runTest {
        val friends = FriendsData(listOf(friend("u2", "Bo"), friend("u1", "Anna")), emptyList(), emptyList())
        val coordinator =
            EventShareCoordinator({ FriendsResult.Loaded(friends) }, FakeDm(DmSendResult.Sent("c", "m")))

        coordinator.load()

        val state = coordinator.state.value
        assertTrue(state is EventShareState.Ready)
        assertEquals(listOf("Anna", "Bo"), (state as EventShareState.Ready).friends.map { it.displayName })
    }

    @Test
    fun `load maps a failed snapshot to Error`() = runTest {
        val coordinator =
            EventShareCoordinator(
                { FriendsResult.Failed(FriendActionError.Generic) },
                FakeDm(DmSendResult.Sent("c", "m")),
            )

        coordinator.load()

        assertEquals(EventShareState.Error, coordinator.state.value)
    }

    @Test
    fun `share sends an event-link message to the chosen friend and reports success`() = runTest {
        val dm = FakeDm(DmSendResult.Sent("c", "m"))
        val coordinator =
            EventShareCoordinator({ FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList())) }, dm)

        val ok = coordinator.share(friend("u1", "Anna"), eventId, title)

        assertTrue(ok)
        assertEquals("u1", dm.lastToUid)
        assertTrue(dm.lastText!!.startsWith(title))
        // The message carries the kccevent token the recipient's chat renders as a
        // tappable "Open event" chip resolving to THIS event.
        assertEquals(eventId, EventShareLinks.findAll(dm.lastText!!).single().link.eventId)
        assertTrue(dm.lastClientId!!.matches(Regex("^[A-Za-z0-9_-]{1,64}$")))
        assertNull(coordinator.sending.value)
    }

    @Test
    fun `a retry of a failed share reuses the same clientId, but a new event does not`() = runTest {
        val dm = FakeDm(DmSendResult.Failed(DmSendError.Generic))
        val coordinator =
            EventShareCoordinator({ FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList())) }, dm)

        assertFalse(coordinator.share(friend("u1", "Anna"), eventId, title))
        val firstId = dm.lastClientId
        // Retry of the SAME event to the SAME friend reuses the key (dedups a landed send).
        assertFalse(coordinator.share(friend("u1", "Anna"), eventId, title))
        assertEquals(firstId, dm.lastClientId)

        // A DIFFERENT event to the same friend mints a fresh clientId.
        dm.result = DmSendResult.Sent("c", "m")
        assertTrue(coordinator.share(friend("u1", "Anna"), "other99", "Other"))
        assertTrue("expected a new clientId for a different event", dm.lastClientId != firstId)
    }

    @Test
    fun `a confirmed share retires its clientId so a later re-share is a new message`() = runTest {
        val dm = FakeDm(DmSendResult.Sent("c", "m"))
        val coordinator =
            EventShareCoordinator({ FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList())) }, dm)

        assertTrue(coordinator.share(friend("u1", "Anna"), eventId, title))
        val firstId = dm.lastClientId
        assertTrue(coordinator.share(friend("u1", "Anna"), eventId, title))
        assertTrue(dm.lastClientId != firstId)
        assertEquals(listOf(firstId, dm.lastClientId), dm.clientIds)
    }

    /** A DM fake whose send suspends until [gate] completes, to model an in-flight send. */
    private class GatedDm(private val gate: CompletableDeferred<Unit>) : DmRepository {
        var sendCalls = 0

        override fun observeConversations(uid: String): Flow<DmConversationsState> = emptyFlow()

        override fun observeThread(conversationId: String): Flow<DmThreadState> = emptyFlow()

        override suspend fun sendMessage(toUid: String, text: String, clientId: String?): DmSendResult {
            sendCalls++
            gate.await()
            return DmSendResult.Sent("c", "m")
        }

        override suspend fun loadOlder(conversationId: String, before: String): DmOlderResult =
            DmOlderResult.Failed

        override suspend fun markRead(conversationId: String) = Unit
    }

    @Test
    fun `a second share while one is in flight is ignored, not a duplicate send`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val dm = GatedDm(gate)
        val coordinator =
            EventShareCoordinator({ FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList())) }, dm)

        val first = launch { coordinator.share(friend("u1", "Anna"), eventId, title) }
        runCurrent()
        assertEquals("u1", coordinator.sending.value)

        assertFalse(coordinator.share(friend("u2", "Bo"), eventId, title))
        assertEquals(1, dm.sendCalls)

        gate.complete(Unit)
        first.join()
        assertNull(coordinator.sending.value)
        assertEquals(1, dm.sendCalls)
    }
}
