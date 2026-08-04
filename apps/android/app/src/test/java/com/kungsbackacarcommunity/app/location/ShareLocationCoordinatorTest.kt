package com.kungsbackacarcommunity.app.location

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
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLocationCoordinatorTest {
    private val point = LatLng(longitude = 12.0766, latitude = 57.49102)
    private val location = ShareableLocation(name = "Mamma", point = point)

    private fun friend(uid: String, name: String?) =
        FriendSummary(uid = uid, displayName = name, avatarPath = null, friendsSince = null)

    private class FakeDm(private val result: DmSendResult) : DmRepository {
        var lastToUid: String? = null
        var lastText: String? = null
        var sendCalls = 0

        override fun observeConversations(uid: String): Flow<DmConversationsState> = emptyFlow()

        override fun observeThread(conversationId: String): Flow<DmThreadState> = emptyFlow()

        override suspend fun sendMessage(toUid: String, text: String, clientId: String?): DmSendResult {
            sendCalls++
            lastToUid = toUid
            lastText = text
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
            ShareLocationCoordinator({ FriendsResult.Loaded(friends) }, FakeDm(DmSendResult.Sent("c", "m")))

        coordinator.load()

        val state = coordinator.state.value
        assertTrue(state is ShareLocationState.Ready)
        assertEquals(listOf("Anna", "Bo"), (state as ShareLocationState.Ready).friends.map { it.displayName })
    }

    @Test
    fun `load maps a failed snapshot to Error`() = runTest {
        val coordinator =
            ShareLocationCoordinator(
                { FriendsResult.Failed(FriendActionError.Generic) },
                FakeDm(DmSendResult.Sent("c", "m")),
            )

        coordinator.load()

        assertEquals(ShareLocationState.Error, coordinator.state.value)
    }

    @Test
    fun `share sends the location message to the chosen friend and reports success`() = runTest {
        val dm = FakeDm(DmSendResult.Sent("c", "m"))
        val coordinator = ShareLocationCoordinator({ FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList())) }, dm)

        val ok = coordinator.share(friend("u1", "Anna"), location)

        assertTrue(ok)
        assertEquals("u1", dm.lastToUid)
        assertTrue(dm.lastText!!.contains("geo:57.49102,12.07660"))
        assertTrue(dm.lastText!!.startsWith("Mamma"))
        // The in-flight marker is cleared once the send resolves.
        assertNull(coordinator.sending.value)
    }

    @Test
    fun `a failed send reports failure`() = runTest {
        val dm = FakeDm(DmSendResult.Failed(DmSendError.Generic))
        val coordinator = ShareLocationCoordinator({ FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList())) }, dm)

        assertFalse(coordinator.share(friend("u1", "Anna"), location))
        assertEquals(1, dm.sendCalls)
    }
}
