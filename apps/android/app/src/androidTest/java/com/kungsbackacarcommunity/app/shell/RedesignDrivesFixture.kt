package com.kungsbackacarcommunity.app.shell

import com.kungsbackacarcommunity.app.drives.DriveHistoryPage
import com.kungsbackacarcommunity.app.drives.DriveHistoryRepository
import com.kungsbackacarcommunity.app.drives.DriveSaveResult
import com.kungsbackacarcommunity.app.drives.DriveStatsSnapshot
import com.kungsbackacarcommunity.app.drives.DriveSubscriptionTier
import com.kungsbackacarcommunity.app.drives.DrivesRepository
import com.kungsbackacarcommunity.app.drives.DrivesState
import com.kungsbackacarcommunity.app.drives.SavedDrive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Synthetic instrumentation data only. Never installed as an app repository. */
internal object RedesignDrivesFixture : DrivesRepository, DriveHistoryRepository {
    private val drives = (1..3).map { index ->
        val start = 1792836000000L - index * 86400000L
        SavedDrive(rideId = "redesign-test-$index", title = "Test drive $index",
            distanceMeters = 12400.0 + index * 100, durationSeconds = 2520,
            averageSpeedMetersPerSecond = null, startedAtMillis = start,
            endedAtMillis = start + 2520000L, createdAtMillis = start)
    }

    override fun observeDrives(uid: String): Flow<DrivesState> = flowOf(DrivesState.Loaded(drives))
    override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult =
        error("Read-only instrumentation fixture")
    override suspend fun deleteDrive(rideId: String) = Unit
    override suspend fun listHistory(cursorRideId: String?, pageSize: Int?) = DriveHistoryPage(
        tier = DriveSubscriptionTier.COMMUNITY, drives = drives, hasMore = false,
        nextCursorRideId = null, hiddenDriveCount = 0, hasTierRestrictedHistory = false,
    )
    override suspend fun fetchStats(monthStartMillis: Long?, monthEndMillis: Long?) = DriveStatsSnapshot(
        tier = DriveSubscriptionTier.COMMUNITY, totalDrives = 3,
        totalDistanceMeters = 37800.0, totalDurationSeconds = 7560L,
        longestDriveMeters = 12700.0, averageDriveMeters = 12600.0,
        fastestAverageSpeedMps = null, highestMaxSpeedMps = null,
        thisMonthDrives = 3, thisMonthDistanceMeters = 37800.0,
    )
}
