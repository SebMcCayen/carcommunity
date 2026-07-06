package com.kungsbackacarcommunity.app.partners

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerApplicationCoordinatorTest {

    private class FakeRepo : PartnerApplicationRepository {
        var submits = 0
        var failWith: Exception? = null

        override suspend fun submit(input: PartnerApplicationInput) {
            failWith?.let { throw it }
            submits++
        }
    }

    private val input =
        PartnerApplicationInput("Co", PartnerCategory.RETAIL, "Ada", "ada@example.com", null, null, null)

    @Test
    fun `submit succeeds to Done`() = runTest {
        val repo = FakeRepo()
        val coordinator = PartnerApplicationCoordinator(repo)
        coordinator.submit(input)
        assertEquals(1, repo.submits)
        assertEquals(PartnerApplicationStatus.Done, coordinator.status.value)
    }

    @Test
    fun `reset clears Done so the form can be reused`() = runTest {
        val repo = FakeRepo()
        val coordinator = PartnerApplicationCoordinator(repo)
        coordinator.submit(input)
        assertEquals(PartnerApplicationStatus.Done, coordinator.status.value)
        coordinator.reset()
        assertEquals(PartnerApplicationStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `a failed submit surfaces Failed and can reset`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("dup") }
        val coordinator = PartnerApplicationCoordinator(repo)
        coordinator.submit(input)
        assertEquals(PartnerApplicationStatus.Failed, coordinator.status.value)
        coordinator.reset()
        assertEquals(PartnerApplicationStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = CancellationException("c") }
        val coordinator = PartnerApplicationCoordinator(repo)
        var rethrown = false
        try {
            coordinator.submit(input)
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(PartnerApplicationStatus.Idle, coordinator.status.value)
    }
}
