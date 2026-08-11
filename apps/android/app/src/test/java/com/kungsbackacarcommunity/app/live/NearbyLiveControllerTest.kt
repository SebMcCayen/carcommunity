package com.kungsbackacarcommunity.app.live

import com.kungsbackacarcommunity.app.diagnostics.CrashFeatures
import com.kungsbackacarcommunity.app.diagnostics.RecordingCrashTelemetry
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyLiveControllerTest {

    /** Fake whose listNearby is scriptable (a value or a throw). */
    private class FakeRepo(
        var result: List<NearbyLiveSession> = emptyList(),
        var failWith: Throwable? = null,
    ) : LiveLocationRepository {
        var calls = 0
        var lastCenter: LatLng? = null

        override suspend fun startSession(duration: LiveSessionDuration, vehicleId: String?) = Unit
        override suspend fun updatePosition(coordinate: LiveCoordinate) = Unit
        override suspend fun stopSession() = Unit
        override suspend fun hideMeNow() = Unit
        override fun observeOwnSession(uid: String): Flow<LiveSessionInfo?> = flowOf(null)
        override fun observeLatest(uid: String): Flow<LiveMarker?> = flowOf(null)

        override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<NearbyLiveSession> {
            calls++
            lastCenter = center
            failWith?.let { throw it }
            return result
        }
    }

    @Test
    fun `refresh publishes the fetched sharers`() = runTest {
        val repo = FakeRepo(result = listOf(NearbyLiveSession("u1", 59.0, 18.0, "A")))
        val controller = NearbyLiveController(repo)

        controller.refresh(LatLng(longitude = 18.0, latitude = 59.0))

        val published = controller.nearbySharers.first()
        assertEquals(listOf("u1"), published.map { it.uid })
        assertEquals(1, repo.calls)
    }

    @Test
    fun `a failed fetch leaves the previous list intact`() = runTest {
        val repo = FakeRepo(result = listOf(NearbyLiveSession("u1", 59.0, 18.0, "A")))
        val controller = NearbyLiveController(repo)
        controller.refresh(LatLng(longitude = 18.0, latitude = 59.0))

        // Now make the next fetch fail: the map must keep the last-known sharers.
        repo.failWith = RuntimeException("network")
        controller.refresh(LatLng(longitude = 18.1, latitude = 59.1))

        assertEquals(listOf("u1"), controller.nearbySharers.first().map { it.uid })
    }

    @Test
    fun `cancellation propagates rather than swallowing`() = runTest {
        val repo = FakeRepo(failWith = CancellationException("cancelled"))
        val controller = NearbyLiveController(repo)

        var propagated = false
        try {
            controller.refresh(LatLng(longitude = 18.0, latitude = 59.0))
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test
    fun `a failed fetch is recorded as a non-fatal`() = runTest {
        // The list is deliberately kept, so the failure is otherwise invisible;
        // the non-fatal is the only trace it leaves.
        val boom = RuntimeException("network")
        val repo = FakeRepo(failWith = boom)
        val telemetry = RecordingCrashTelemetry()
        val controller = NearbyLiveController(repo, telemetry)

        controller.refresh(LatLng(longitude = 18.0, latitude = 59.0))

        assertEquals(1, telemetry.nonFatals.size)
        assertEquals(CrashFeatures.LIVE_NEARBY_REFRESH, telemetry.nonFatals.single().first)
        assertEquals(boom, telemetry.nonFatals.single().second)
    }

    @Test
    fun `a cancelled fetch is NOT recorded as a non-fatal`() = runTest {
        // Cancellation is cooperative shutdown, not a fault; recording it would
        // fill the console with noise every time a screen is left.
        val telemetry = RecordingCrashTelemetry()
        val controller = NearbyLiveController(FakeRepo(failWith = CancellationException("cancelled")), telemetry)

        try {
            controller.refresh(LatLng(longitude = 18.0, latitude = 59.0))
        } catch (_: CancellationException) {
            // expected
        }

        assertTrue(telemetry.nonFatals.isEmpty())
    }

    @Test
    fun `a successful fetch records nothing`() = runTest {
        val telemetry = RecordingCrashTelemetry()
        val controller =
            NearbyLiveController(FakeRepo(result = listOf(NearbyLiveSession("u1", 59.0, 18.0, "A"))), telemetry)

        controller.refresh(LatLng(longitude = 18.0, latitude = 59.0))

        assertTrue(telemetry.nonFatals.isEmpty())
    }

    @Test
    fun `clear empties the list`() = runTest {
        val repo = FakeRepo(result = listOf(NearbyLiveSession("u1", 59.0, 18.0, "A")))
        val controller = NearbyLiveController(repo)
        controller.refresh(LatLng(longitude = 18.0, latitude = 59.0))

        controller.clear()
        assertTrue(controller.nearbySharers.first().isEmpty())
    }
}
