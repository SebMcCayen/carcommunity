package com.kungsbackacarcommunity.app.drives

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Shared JVM/native detail-screen tests; no Firebase or native Mapbox GL is needed. */
@RunWith(AndroidJUnit4::class)
class SavedDriveRouteReplayScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun node(id: Int) = compose.onNodeWithText(text(id))

    private val drive = SavedDrive(
        rideId = "drive-one",
        title = "Test drive",
        distanceMeters = 1000.0,
        durationSeconds = 60L,
        averageSpeedMetersPerSecond = null,
        startedAtMillis = null,
        endedAtMillis = null,
        createdAtMillis = null,
        // Standard Google polyline test vector: synthetic, never a real user's data.
        routeThumbnail = "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
    )

    @Test
    fun missingFullRouteShowsThumbnailAndRetryWaitsForFreshResult() {
        var calls = 0
        val retry = CompletableDeferred<RouteReplayState>()
        val repo = object : RouteReplayRepository {
            override suspend fun loadRoute(uid: String, rideId: String): RouteReplayState {
                calls++
                return if (calls == 1) RouteReplayState.Unavailable(RouteReplayFailure.MISSING) else retry.await()
            }
        }
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(drive, DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner")
            }
        }
        node(R.string.driveReplay_missing).assertExists()
        node(R.string.driveReplay_simplifiedOverview).assertExists()
        node(R.string.savedDrives_retry).performScrollTo().performClick()
        node(R.string.savedDrives_routeLoading).assertExists()
        node(R.string.savedDrives_retry).assertDoesNotExist()
        compose.runOnIdle { retry.complete(RouteReplayState.Ready(emptyList())) }
        node(R.string.savedDrives_routeEmpty).assertExists()
        node(R.string.savedDrives_retry).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test
    fun legacyDriveWithoutThumbnailDoesNotInventAnOverview() {
        val repo = immediate(RouteReplayFailure.MISSING)
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(drive.copy(routeThumbnail = null), DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner")
            }
        }
        node(R.string.driveReplay_missing).assertExists()
        node(R.string.driveReplay_simplifiedOverview).assertDoesNotExist()
    }

    @Test
    fun accessDenialSuppressesOldThumbnailAndRetry() {
        val repo = immediate(RouteReplayFailure.ACCESS_DENIED)
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(drive, DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner")
            }
        }
        node(R.string.driveReplay_accessDenied).assertExists()
        node(R.string.driveReplay_simplifiedOverview).assertDoesNotExist()
        node(R.string.savedDrives_retry).assertDoesNotExist()
    }

    @Test
    fun serviceAndDownloadErrorsRemainDistinctAcrossRetry() {
        var calls = 0
        val repo = object : RouteReplayRepository {
            override suspend fun loadRoute(uid: String, rideId: String) = RouteReplayState.Unavailable(
                if (calls++ == 0) RouteReplayFailure.SERVICE_UNAVAILABLE else RouteReplayFailure.DOWNLOAD_FAILED,
            )
        }
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(drive, DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner")
            }
        }
        node(R.string.driveReplay_serviceUnavailable).assertExists()
        node(R.string.savedDrives_retry).performScrollTo().performClick()
        node(R.string.driveReplay_downloadFailed).assertExists()
        node(R.string.driveReplay_serviceUnavailable).assertDoesNotExist()
    }

    @Test
    fun corruptThumbnailAndFullRouteDoNotDrawInventedGeometry() {
        val repo = immediate(RouteReplayFailure.CORRUPT)
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(drive.copy(routeThumbnail = "_"), DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner")
            }
        }
        node(R.string.driveReplay_corrupt).assertExists()
        node(R.string.driveReplay_simplifiedOverview).assertDoesNotExist()
    }

    @Test
    fun ownerDriveAndReaderChangesCancelOldLoadAndResetPrivateState() {
        val owner = mutableStateOf("first-owner")
        val selectedDrive = mutableStateOf(drive)
        val secondResult = CompletableDeferred<RouteReplayState>()
        val firstResult = CompletableDeferred<RouteReplayState>()
        val thirdResult = CompletableDeferred<RouteReplayState>()
        val calls = mutableListOf<Pair<String, String>>()
        var cancellations = 0
        val repo = object : RouteReplayRepository {
            override suspend fun loadRoute(uid: String, rideId: String): RouteReplayState {
                calls += uid to rideId
                return try {
                    when (calls.size) {
                        1 -> firstResult.await()
                        2 -> secondResult.await()
                        else -> thirdResult.await()
                    }
                } catch (cancelled: CancellationException) {
                    cancellations++
                    throw cancelled
                }
            }
        }
        val reader = mutableStateOf<RouteReplayRepository>(repo)
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(selectedDrive.value, DriveDeleteStatus.Idle, {}, {}, routeRepository = reader.value, uid = owner.value)
            }
        }
        node(R.string.savedDrives_routeLoading).assertExists()
        compose.runOnIdle { owner.value = "second-owner" }
        node(R.string.savedDrives_routeLoading).assertExists()
        compose.runOnIdle {
            assertEquals(1, cancellations)
            firstResult.complete(RouteReplayState.Unavailable(RouteReplayFailure.MISSING))
            secondResult.complete(RouteReplayState.Unavailable(RouteReplayFailure.ACCESS_DENIED))
        }
        node(R.string.driveReplay_accessDenied).assertExists()
        node(R.string.driveReplay_missing).assertDoesNotExist()
        compose.runOnIdle { selectedDrive.value = drive.copy(rideId = "drive-two") }
        node(R.string.savedDrives_routeLoading).assertExists()
        node(R.string.driveReplay_accessDenied).assertDoesNotExist()
        compose.runOnIdle { reader.value = immediate(RouteReplayFailure.SIGN_IN_REQUIRED) }
        node(R.string.driveReplay_signInRequired).assertExists()
        compose.runOnIdle {
            assertEquals(2, cancellations)
            assertEquals(listOf("first-owner" to "drive-one", "second-owner" to "drive-one", "second-owner" to "drive-two"), calls)
        }
    }

    @Test
    fun leavingDetailCancelsLoadAndReentryRetriesWithoutRecompositionRefetch() {
        val visible = mutableStateOf(true)
        val title = mutableStateOf("First")
        var calls = 0
        var cancellations = 0
        val repo = object : RouteReplayRepository {
            override suspend fun loadRoute(uid: String, rideId: String): RouteReplayState {
                calls++
                try {
                    return CompletableDeferred<RouteReplayState>().await()
                } catch (cancelled: CancellationException) {
                    cancellations++
                    throw cancelled
                }
            }
        }
        compose.setContent {
            KccTheme {
                if (visible.value) {
                    SavedDriveDetailScreen(drive.copy(title = title.value), DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner")
                }
            }
        }
        node(R.string.savedDrives_routeLoading).assertExists()
        compose.runOnIdle { title.value = "Recomposed" }
        compose.runOnIdle { assertEquals(1, calls); visible.value = false }
        compose.runOnIdle { assertEquals(1, cancellations); visible.value = true }
        node(R.string.savedDrives_routeLoading).assertExists()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    private fun immediate(reason: RouteReplayFailure) = object : RouteReplayRepository {
        override suspend fun loadRoute(uid: String, rideId: String) = RouteReplayState.Unavailable(reason)
    }
}
