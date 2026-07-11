package com.kungsbackacarcommunity.app.friends

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendsCoordinatorTest {

    private class FakeRepo : FriendsRepository {
        var listResult: FriendsResult =
            FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList()))
        var sendResult: SendRequestResult = SendRequestResult.Requested
        var sendToUidResult: SendRequestResult = SendRequestResult.Requested
        var respondResult: RespondResult = RespondResult.Accepted
        var removeResult: RemoveResult = RemoveResult.Removed

        var lastNickname: String? = null
        var lastToUid: String? = null
        var listCalls = 0

        override suspend fun list(): FriendsResult {
            listCalls++
            return listResult
        }

        override suspend fun sendRequestByNickname(nickname: String): SendRequestResult {
            lastNickname = nickname
            return sendResult
        }

        override suspend fun sendRequestToUid(toUid: String): SendRequestResult {
            lastToUid = toUid
            return sendToUidResult
        }

        override suspend fun respond(requestId: String, accept: Boolean): RespondResult = respondResult

        override suspend fun remove(friendUid: String): RemoveResult = removeResult
    }

    private fun loaded(friends: List<FriendSummary>) =
        FriendsResult.Loaded(FriendsData(friends, emptyList(), emptyList()))

    @Test
    fun `load publishes the snapshot`() = runTest {
        val repo =
            FakeRepo().apply { listResult = loaded(listOf(FriendSummary("f1", "Robin", null, null))) }
        val coordinator = FriendsCoordinator(repo)
        coordinator.load()
        val status = coordinator.status.value
        assertTrue(status is FriendsStatus.Loaded)
        assertEquals(1, (status as FriendsStatus.Loaded).friends.size)
    }

    @Test
    fun `load failure surfaces Error`() = runTest {
        val repo = FakeRepo().apply { listResult = FriendsResult.Failed(FriendActionError.NotMember) }
        val coordinator = FriendsCoordinator(repo)
        coordinator.load()
        assertEquals(FriendsStatus.Error, coordinator.status.value)
    }

    @Test
    fun `blank nickname is rejected without calling the backend`() = runTest {
        val repo = FakeRepo()
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("   ")
        assertEquals(AddFriendState.Error(FriendActionError.Invalid), coordinator.add.value)
        assertNull(repo.lastNickname)
    }

    @Test
    fun `send trims the nickname and reports Sent`() = runTest {
        val repo = FakeRepo()
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("  Alex ")
        assertEquals("Alex", repo.lastNickname)
        assertEquals(AddFriendState.Sent(nowFriends = false), coordinator.add.value)
        // A landed request reloads the snapshot.
        assertTrue(repo.listCalls >= 1)
    }

    @Test
    fun `send that auto-accepts reports now friends`() = runTest {
        val repo = FakeRepo().apply { sendResult = SendRequestResult.NowFriends }
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("Alex")
        assertEquals(AddFriendState.Sent(nowFriends = true), coordinator.add.value)
    }

    @Test
    fun `ambiguous nickname surfaces the candidate chooser`() = runTest {
        val candidates = listOf(FriendUser("a", "Alex", null), FriendUser("b", "Alex", null))
        val repo = FakeRepo().apply { sendResult = SendRequestResult.Ambiguous(candidates) }
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("Alex")
        val state = coordinator.add.value
        assertTrue(state is AddFriendState.Chooser)
        assertEquals(candidates, (state as AddFriendState.Chooser).candidates)
    }

    @Test
    fun `choosing a candidate re-sends by uid`() = runTest {
        val repo = FakeRepo().apply { sendToUidResult = SendRequestResult.Requested }
        val coordinator = FriendsCoordinator(repo)
        coordinator.chooseCandidate("b")
        assertEquals("b", repo.lastToUid)
        assertEquals(AddFriendState.Sent(nowFriends = false), coordinator.add.value)
    }

    @Test
    fun `send failure surfaces the mapped error`() = runTest {
        val repo = FakeRepo().apply { sendResult = SendRequestResult.Failed(FriendActionError.NotAddable) }
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("Alex")
        assertEquals(AddFriendState.Error(FriendActionError.NotAddable), coordinator.add.value)
    }

    @Test
    fun `accept reloads and clears any prior action error`() = runTest {
        val repo = FakeRepo()
        val coordinator = FriendsCoordinator(repo)
        val before = repo.listCalls
        coordinator.accept("r1")
        assertTrue(repo.listCalls > before)
        assertNull(coordinator.actionError.value)
    }

    @Test
    fun `respond failure surfaces action error and still resyncs`() = runTest {
        val repo = FakeRepo().apply { respondResult = RespondResult.Failed(FriendActionError.RequestGone) }
        val coordinator = FriendsCoordinator(repo)
        val before = repo.listCalls
        coordinator.decline("r1")
        assertEquals(FriendActionError.RequestGone, coordinator.actionError.value)
        assertTrue(repo.listCalls > before)
    }

    @Test
    fun `remove reloads on success`() = runTest {
        val repo = FakeRepo()
        val coordinator = FriendsCoordinator(repo)
        val before = repo.listCalls
        coordinator.remove("f1")
        assertTrue(repo.listCalls > before)
        assertNull(coordinator.actionError.value)
    }

    @Test
    fun `remove failure surfaces action error`() = runTest {
        val repo = FakeRepo().apply { removeResult = RemoveResult.Failed(FriendActionError.Generic) }
        val coordinator = FriendsCoordinator(repo)
        coordinator.remove("f1")
        assertEquals(FriendActionError.Generic, coordinator.actionError.value)
    }

    @Test
    fun `resetAdd returns to Idle`() = runTest {
        val repo = FakeRepo().apply { sendResult = SendRequestResult.Requested }
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("Alex")
        coordinator.resetAdd()
        assertEquals(AddFriendState.Idle, coordinator.add.value)
    }
}
