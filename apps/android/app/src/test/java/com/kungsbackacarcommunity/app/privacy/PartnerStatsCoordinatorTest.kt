package com.kungsbackacarcommunity.app.privacy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerStatsCoordinatorTest {

    private class FakeRepo : PartnerStatsRepository {
        val writes = mutableListOf<Pair<String, Boolean>>()
        var failWith: Exception? = null

        override fun observeOptIn(uid: String): Flow<Boolean?> = flowOf(null)

        override suspend fun setOptIn(uid: String, optIn: Boolean) {
            failWith?.let { throw it }
            writes += uid to optIn
        }
    }

    @Test
    fun `save writes the opt-in and ends Saved`() = runTest {
        val repo = FakeRepo()
        val coordinator = PartnerStatsCoordinator(repo)
        coordinator.save("u1", true)
        assertEquals(listOf("u1" to true), repo.writes)
        assertEquals(PartnerStatsSaveStatus.Saved, coordinator.saveStatus.value)
    }

    @Test
    fun `a failed save surfaces Failed`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("denied") }
        val coordinator = PartnerStatsCoordinator(repo)
        coordinator.save("u1", false)
        assertEquals(PartnerStatsSaveStatus.Failed, coordinator.saveStatus.value)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = CancellationException("c") }
        val coordinator = PartnerStatsCoordinator(repo)
        var rethrown = false
        try {
            coordinator.save("u1", true)
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(PartnerStatsSaveStatus.Idle, coordinator.saveStatus.value)
    }
}
