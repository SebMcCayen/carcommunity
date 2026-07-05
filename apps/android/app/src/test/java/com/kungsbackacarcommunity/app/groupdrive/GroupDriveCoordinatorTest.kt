package com.kungsbackacarcommunity.app.groupdrive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupDriveCoordinatorTest {

    private class FakeRepo : GroupDriveRepository {
        var joins = 0
        var leaves = 0
        val statuses = mutableListOf<GroupDriveStatus>()
        var failWith: Exception? = null

        override fun observeParticipants(eventId: String): Flow<List<GroupDriveParticipant>> = flowOf(emptyList())

        override fun observeMyStatus(eventId: String, uid: String): Flow<GroupDriveStatus?> = flowOf(null)

        override suspend fun join(eventId: String) {
            failWith?.let { throw it }
            joins++
        }

        override suspend fun updateStatus(eventId: String, status: GroupDriveStatus) {
            failWith?.let { throw it }
            statuses += status
        }

        override suspend fun leave(eventId: String) {
            failWith?.let { throw it }
            leaves++
        }
    }

    @Test
    fun `join, updateStatus, leave call through and end Idle`() = runTest {
        val repo = FakeRepo()
        val coordinator = GroupDriveCoordinator(repo)
        coordinator.join("e1")
        coordinator.updateStatus("e1", GroupDriveStatus.ON_THE_WAY)
        coordinator.leave("e1")
        assertEquals(1, repo.joins)
        assertEquals(listOf(GroupDriveStatus.ON_THE_WAY), repo.statuses)
        assertEquals(1, repo.leaves)
        assertEquals(GroupDriveActionStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `a failure surfaces Failed and can reset`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("x") }
        val coordinator = GroupDriveCoordinator(repo)
        coordinator.join("e1")
        assertEquals(GroupDriveActionStatus.Failed, coordinator.status.value)
        coordinator.reset()
        assertEquals(GroupDriveActionStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = CancellationException("c") }
        val coordinator = GroupDriveCoordinator(repo)
        var rethrown = false
        try {
            coordinator.leave("e1")
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(GroupDriveActionStatus.Idle, coordinator.status.value)
    }
}
