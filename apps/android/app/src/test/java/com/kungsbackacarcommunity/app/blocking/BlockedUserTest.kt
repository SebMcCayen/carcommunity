package com.kungsbackacarcommunity.app.blocking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockedUserTest {

    private fun user(id: String, blockedAt: Long?) =
        BlockedUser(userId = id, displayName = null, blockedAtMillis = blockedAt)

    @Test
    fun `sortedForList is newest first with undated last`() {
        val users = listOf(user("a", 100L), user("b", null), user("c", 300L), user("d", 200L))
        assertEquals(
            listOf("c", "d", "a", "b"),
            BlockedUsers.sortedForList(users).map { it.userId },
        )
    }

    @Test
    fun `coordinator marks done on successful unblock`() = runTest {
        val repo = FakeBlockingRepository(shouldFail = false)
        val coordinator = BlockingCoordinator(repo)
        coordinator.unblock("u1")
        assertEquals(BlockActionStatus.Done, coordinator.actionStatus.value)
        assertEquals(listOf("unblock:u1"), repo.calls)
    }

    @Test
    fun `coordinator marks done on successful block`() = runTest {
        val repo = FakeBlockingRepository(shouldFail = false)
        val coordinator = BlockingCoordinator(repo)
        coordinator.block("u2")
        assertEquals(BlockActionStatus.Done, coordinator.actionStatus.value)
        assertEquals(listOf("block:u2"), repo.calls)
    }

    @Test
    fun `coordinator marks failed when the callable throws`() = runTest {
        val coordinator = BlockingCoordinator(FakeBlockingRepository(shouldFail = true))
        coordinator.unblock("u1")
        assertEquals(BlockActionStatus.Failed, coordinator.actionStatus.value)
    }

    @Test
    fun `reset returns the coordinator to idle`() = runTest {
        val coordinator = BlockingCoordinator(FakeBlockingRepository(shouldFail = false))
        coordinator.unblock("u1")
        coordinator.reset()
        assertEquals(BlockActionStatus.Idle, coordinator.actionStatus.value)
    }

    @Test
    fun `block-from-chat marks done and calls the blocking-block callable`() = runTest {
        // Mirrors the chat block-from-message flow: reset then block the author.
        val repo = FakeBlockingRepository(shouldFail = false)
        val coordinator = BlockingCoordinator(repo)
        coordinator.reset()
        coordinator.block("author-9")
        assertEquals(BlockActionStatus.Done, coordinator.actionStatus.value)
        assertEquals(listOf("block:author-9"), repo.calls)
    }

    @Test
    fun `block-from-chat marks failed when the callable throws`() = runTest {
        val coordinator = BlockingCoordinator(FakeBlockingRepository(shouldFail = true))
        coordinator.block("author-9")
        assertEquals(BlockActionStatus.Failed, coordinator.actionStatus.value)
    }
}

private class FakeBlockingRepository(private val shouldFail: Boolean) : BlockingRepository {
    val calls = mutableListOf<String>()

    override fun observeBlocked(uid: String): Flow<BlockedUsersState> =
        throw UnsupportedOperationException()

    override suspend fun block(targetUserId: String) {
        if (shouldFail) throw IllegalStateException("block failed")
        calls.add("block:$targetUserId")
    }

    override suspend fun unblock(targetUserId: String) {
        if (shouldFail) throw IllegalStateException("unblock failed")
        calls.add("unblock:$targetUserId")
    }
}
