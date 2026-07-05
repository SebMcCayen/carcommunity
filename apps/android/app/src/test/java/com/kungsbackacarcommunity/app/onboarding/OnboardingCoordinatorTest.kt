package com.kungsbackacarcommunity.app.onboarding

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingCoordinatorTest {

    private class FakeRepo : OnboardingRepository {
        val calls = mutableListOf<String?>()
        var failWith: Exception? = null

        override suspend fun completeOnboarding(displayName: String?) {
            failWith?.let { throw it }
            calls += displayName
        }
    }

    @Test
    fun `successful submit sends the display name and ends Done`() = runTest {
        val repo = FakeRepo()
        val coordinator = OnboardingCoordinator(repo)
        coordinator.submit("Sebbe")
        assertEquals(listOf("Sebbe"), repo.calls)
        assertEquals(OnboardingStatus.Done, coordinator.status.value)
    }

    @Test
    fun `failed submit surfaces Failed and can be reset`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("nope") }
        val coordinator = OnboardingCoordinator(repo)
        coordinator.submit(null)
        assertEquals(OnboardingStatus.Failed, coordinator.status.value)
        coordinator.resetFailure()
        assertEquals(OnboardingStatus.Idle, coordinator.status.value)
    }
}
