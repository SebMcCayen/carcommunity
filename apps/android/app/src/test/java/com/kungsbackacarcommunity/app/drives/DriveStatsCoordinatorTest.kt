package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the server-authoritative stats loader ([DriveStatsCoordinator]). */
class DriveStatsCoordinatorTest {

    private class FakeRepository(
        private val result: Result<DriveStatsSnapshot>,
    ) : DriveHistoryRepository {
        var lastMonthStart: Long? = null
        var lastMonthEnd: Long? = null

        override suspend fun listHistory(cursorRideId: String?, pageSize: Int?) =
            throw UnsupportedOperationException("not used here")

        override suspend fun fetchStats(
            monthStartMillis: Long?,
            monthEndMillis: Long?,
        ): DriveStatsSnapshot {
            lastMonthStart = monthStartMillis
            lastMonthEnd = monthEndMillis
            return result.getOrThrow()
        }
    }

    private fun snapshot(totalDrives: Int) =
        DriveStatsSnapshot(
            tier = DriveSubscriptionTier.PLUS,
            totalDrives = totalDrives,
            totalDistanceMeters = 100.0,
            totalDurationSeconds = 10L,
            longestDriveMeters = 100.0,
            averageDriveMeters = 100.0,
            fastestAverageSpeedMps = 10.0,
            highestMaxSpeedMps = 20.0,
            thisMonthDrives = 0,
            thisMonthDistanceMeters = 0.0,
        )

    @Test
    fun `load publishes the snapshot and forwards the month range`() = runTest {
        val repo = FakeRepository(Result.success(snapshot(4)))
        val coordinator = DriveStatsCoordinator(repo)

        coordinator.load(monthStartMillis = 1000L, monthEndMillis = 2000L)

        val loaded = coordinator.state.value as DriveStatsUiState.Loaded
        assertEquals(4, loaded.snapshot.totalDrives)
        assertEquals(1000L, repo.lastMonthStart)
        assertEquals(2000L, repo.lastMonthEnd)
    }

    @Test
    fun `a zero-drive snapshot still loads (the screen renders its own empty state)`() = runTest {
        val repo = FakeRepository(Result.success(snapshot(0)))
        val coordinator = DriveStatsCoordinator(repo)

        coordinator.load(null, null)

        val loaded = coordinator.state.value as DriveStatsUiState.Loaded
        assertEquals(0, loaded.snapshot.totalDrives)
    }

    @Test
    fun `a failure surfaces Error carrying the callable code`() = runTest {
        val repo = FakeRepository(Result.failure(DriveHistoryException(code = "UNAVAILABLE")))
        val coordinator = DriveStatsCoordinator(repo)

        coordinator.load(1000L, 2000L)

        val error = coordinator.state.value as DriveStatsUiState.Error
        assertEquals("UNAVAILABLE", error.code)
    }
}
