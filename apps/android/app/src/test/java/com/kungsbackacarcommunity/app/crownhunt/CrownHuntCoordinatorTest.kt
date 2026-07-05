package com.kungsbackacarcommunity.app.crownhunt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrownHuntCoordinatorTest {

    private class FakeRepo : CrownHuntRepository {
        val claims = mutableListOf<Triple<String, ClaimCoordinate, String>>()
        var outcome = ClaimOutcome(CrownHuntClaimResult.AWARDED, 50, 150)
        var failWith: Exception? = null

        override fun observeActivePoints(): Flow<CrownHuntPointsState> =
            flowOf(CrownHuntPointsState.Loaded(emptyList()))

        override suspend fun submitClaim(
            pointId: String,
            coordinate: ClaimCoordinate,
            idempotencyKey: String,
        ): ClaimOutcome {
            failWith?.let { throw it }
            claims += Triple(pointId, coordinate, idempotencyKey)
            return outcome
        }
    }

    private val coord = ClaimCoordinate(57.0, 12.0, "2026-07-06T00:00:00.000Z")

    @Test
    fun `claim with a coordinate calls the callable and reports Done`() = runTest {
        val repo = FakeRepo()
        val coordinator = CrownHuntCoordinator(repo)
        coordinator.claim("p1", coord, "key-1")
        assertEquals(1, repo.claims.size)
        assertEquals("p1", repo.claims[0].first)
        val status = coordinator.status.value
        assertTrue(status is CrownHuntClaimStatus.Done)
        assertEquals(CrownHuntClaimResult.AWARDED, (status as CrownHuntClaimStatus.Done).outcome.result)
        assertEquals(50, status.outcome.pointsAwarded)
    }

    @Test
    fun `claim without a coordinate surfaces NeedsLocation and does not call the backend`() = runTest {
        val repo = FakeRepo()
        val coordinator = CrownHuntCoordinator(repo)
        coordinator.claim("p1", null, "key-1")
        assertTrue(repo.claims.isEmpty())
        assertEquals(CrownHuntClaimStatus.NeedsLocation, coordinator.status.value)
    }

    @Test
    fun `an eligibility result code is surfaced as Done, not Failed`() = runTest {
        val repo = FakeRepo().apply { outcome = ClaimOutcome(CrownHuntClaimResult.OUTSIDE_GEOFENCE, null, null) }
        val coordinator = CrownHuntCoordinator(repo)
        coordinator.claim("p1", coord, "key-1")
        val status = coordinator.status.value as CrownHuntClaimStatus.Done
        assertEquals(CrownHuntClaimResult.OUTSIDE_GEOFENCE, status.outcome.result)
    }

    @Test
    fun `a thrown error surfaces Failed`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("network") }
        val coordinator = CrownHuntCoordinator(repo)
        coordinator.claim("p1", coord, "key-1")
        assertEquals(CrownHuntClaimStatus.Failed, coordinator.status.value)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = CancellationException("cancel") }
        val coordinator = CrownHuntCoordinator(repo)
        var rethrown = false
        try {
            coordinator.claim("p1", coord, "key-1")
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(CrownHuntClaimStatus.Idle, coordinator.status.value)
    }
}
