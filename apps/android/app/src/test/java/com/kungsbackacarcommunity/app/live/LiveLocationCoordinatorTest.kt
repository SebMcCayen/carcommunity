package com.kungsbackacarcommunity.app.live

import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocationCoordinatorTest {

    private class FakeRepo : LiveLocationRepository {
        val started = mutableListOf<LiveSessionDuration>()
        val startedVehicleIds = mutableListOf<String?>()
        var stops = 0
        var hides = 0
        var failWith: Exception? = null

        /** Holds startSession open so a second command can race it (Busy). */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun startSession(duration: LiveSessionDuration, vehicleId: String?) {
            gate?.await()
            failWith?.let { throw it }
            started += duration
            startedVehicleIds += vehicleId
        }

        override suspend fun updatePosition(coordinate: LiveCoordinate) = Unit

        override suspend fun stopSession() {
            failWith?.let { throw it }
            stops++
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
    fun `start forwards the chosen vehicleId, and null when none chosen`() = runTest {
        val repo = FakeRepo()
        val coordinator = LiveLocationCoordinator(repo)
        coordinator.start(LiveSessionDuration.SIX_HOURS, vehicleId = "veh-7")
        coordinator.start(LiveSessionDuration.SIX_HOURS)
        assertEquals(listOf("veh-7", null), repo.startedVehicleIds)
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

    // --- LiveCommandResult (the optimistic-start overlay's contract) ------

    @Test
    fun `a command reports Success or Failed to its caller`() = runTest {
        val ok = LiveLocationCoordinator(FakeRepo())
        assertEquals(LiveCommandResult.Success, ok.start(LiveSessionDuration.ONE_HOUR))

        val broken = FakeRepo().apply { failWith = IllegalStateException("boom") }
        assertEquals(
            LiveCommandResult.Failed,
            LiveLocationCoordinator(broken).start(LiveSessionDuration.ONE_HOUR),
        )
    }

    @Test
    fun `a command issued while another is in flight is Busy and never reaches the repository`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repo = FakeRepo().apply { this.gate = gate }
            val coordinator = LiveLocationCoordinator(repo)

            val inFlight = async { coordinator.start(LiveSessionDuration.ONE_HOUR) }
            yield()
            assertEquals(LiveActionStatus.Working, coordinator.status.value)

            // Busy is NOT a success: the callable was never invoked, so a caller
            // holding an optimistic "starting…" state (LiveShareStart) must take
            // it back rather than wait for a session that is not coming.
            assertEquals(LiveCommandResult.Busy, coordinator.stop())
            assertEquals(0, repo.stops)

            gate.complete(Unit)
            assertEquals(LiveCommandResult.Success, inFlight.await())
            assertEquals(listOf(LiveSessionDuration.ONE_HOUR), repo.started)
        }

    @Test
    fun `hideMeNow is a no-op relay when repo throws generic - stays Failed`() = runTest {
        val repo = FakeRepo().apply { failWith = RuntimeException("x") }
        val coordinator = LiveLocationCoordinator(repo)
        coordinator.hideMeNow()
        assertTrue(coordinator.status.value is LiveActionStatus.Failed)
    }
}
