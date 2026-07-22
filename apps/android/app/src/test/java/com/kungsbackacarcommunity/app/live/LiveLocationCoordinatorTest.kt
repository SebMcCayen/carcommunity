package com.kungsbackacarcommunity.app.live

import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocationCoordinatorTest {

    private class FakeRepo : LiveLocationRepository {
        val started = mutableListOf<LiveSessionDuration>()
        var stops = 0
        var extends = 0
        var hides = 0
        var failWith: Exception? = null

        override suspend fun startSession(duration: LiveSessionDuration) {
            failWith?.let { throw it }
            started += duration
        }

        override suspend fun updatePosition(coordinate: LiveCoordinate) = Unit

        override suspend fun stopSession() {
            failWith?.let { throw it }
            stops++
        }

        override suspend fun extendSession() {
            failWith?.let { throw it }
            extends++
        }

        override suspend fun hideMeNow() {
            failWith?.let { throw it }
            hides++
        }

        override fun observeOwnSession(uid: String): Flow<LiveSessionInfo?> = flowOf(null)

        override fun observeLatest(uid: String): Flow<LiveMarker?> = flowOf(null)

        override suspend fun listNearby(center: LatLng, radiusMeters: Double) =
            emptyList<NearbyLiveSession>()
    }

    @Test
    fun `start forwards the duration and ends Idle`() = runTest {
        val repo = FakeRepo()
        val coordinator = LiveLocationCoordinator(repo)
        coordinator.start(LiveSessionDuration.TWO_HOURS)
        assertEquals(listOf(LiveSessionDuration.TWO_HOURS), repo.started)
        assertEquals(LiveActionStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `stop and hideMeNow call through`() = runTest {
        val repo = FakeRepo()
        val coordinator = LiveLocationCoordinator(repo)
        coordinator.stop()
        coordinator.hideMeNow()
        assertEquals(1, repo.stops)
        assertEquals(1, repo.hides)
        assertEquals(LiveActionStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `extend calls through and ends Idle`() = runTest {
        val repo = FakeRepo()
        val coordinator = LiveLocationCoordinator(repo)
        coordinator.extend()
        assertEquals(1, repo.extends)
        assertEquals(LiveActionStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `a failed command surfaces Failed and can be reset`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("boom") }
        val coordinator = LiveLocationCoordinator(repo)
        coordinator.start(LiveSessionDuration.ONE_HOUR)
        assertEquals(LiveActionStatus.Failed, coordinator.status.value)
        coordinator.reset()
        assertEquals(LiveActionStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = CancellationException("cancelled") }
        val coordinator = LiveLocationCoordinator(repo)
        var rethrown = false
        try {
            coordinator.start(LiveSessionDuration.ONE_HOUR)
        } catch (cancellation: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(LiveActionStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `hideMeNow is a no-op relay when repo throws generic - stays Failed`() = runTest {
        val repo = FakeRepo().apply { failWith = RuntimeException("x") }
        val coordinator = LiveLocationCoordinator(repo)
        coordinator.hideMeNow()
        assertTrue(coordinator.status.value is LiveActionStatus.Failed)
    }
}
