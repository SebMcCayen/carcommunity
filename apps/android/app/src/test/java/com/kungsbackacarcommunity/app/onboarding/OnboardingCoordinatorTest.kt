package com.kungsbackacarcommunity.app.onboarding

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingCoordinatorTest {

    private class FakeRepo : OnboardingRepository {
        val calls = mutableListOf<Pair<String?, Boolean?>>()
        var failWith: Exception? = null

        override suspend fun completeOnboarding(
            displayName: String?,
            anonymousPartnerStatsOptIn: Boolean?,
        ) {
            failWith?.let { throw it }
            calls += displayName to anonymousPartnerStatsOptIn
        }
    }

    @Test
    fun `successful submit sends the display name and ends Done`() = runTest {
        val repo = FakeRepo()
        val coordinator = OnboardingCoordinator(repo)
        coordinator.submit("Sebbe")
        assertEquals(listOf<Pair<String?, Boolean?>>("Sebbe" to null), repo.calls)
        assertEquals(OnboardingStatus.Done, coordinator.status.value)
    }

    @Test
    fun `submit forwards the partner-stats opt-out choice`() = runTest {
        val repo = FakeRepo()
        val coordinator = OnboardingCoordinator(repo)
        coordinator.submit("Sebbe", anonymousPartnerStatsOptIn = false)
        assertEquals(listOf<Pair<String?, Boolean?>>("Sebbe" to false), repo.calls)
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
