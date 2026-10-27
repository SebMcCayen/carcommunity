package com.kungsbackacarcommunity.app.drives

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.map.DriveRouteMapRenderer
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Shared JVM/native detail-screen tests; no Firebase or native Mapbox GL is needed. */
@RunWith(AndroidJUnit4::class)
class SavedDriveRouteReplayScreenTest {
    @get:Rule val compose = createComposeRule()

    // Always inject the available fake, including legacy screen setups. A real
    // pk-token in a configured build must never initialize Mapbox JNI on the JVM.
    private val defaultRenderer = CapturingMapRenderer()

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
                SavedDriveDetailScreen(drive, DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner", routeMapRenderer = defaultRenderer)
            }
        }
        node(R.string.savedDrives_driveReplay_missing).assertExists()
        node(R.string.savedDrives_driveReplay_simplifiedOverview).assertExists()
        node(R.string.savedDrives_retry).performScrollTo().performClick()
        node(R.string.savedDrives_routeLoading).assertExists()
        node(R.string.savedDrives_retry).assertDoesNotExist()
        compose.runOnIdle { retry.complete(RouteReplayState.Ready(emptyList())) }
        node(R.string.savedDrives_driveReplay_exactRouteEmpty).assertExists()
        node(R.string.savedDrives_retry).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test
    fun legacyDriveWithoutThumbnailDoesNotInventAnOverview() {
        val repo = immediate(RouteReplayFailure.MISSING)
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(drive.copy(routeThumbnail = null), DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner", routeMapRenderer = defaultRenderer)
            }
        }
        node(R.string.savedDrives_driveReplay_missing).assertExists()
        node(R.string.savedDrives_driveReplay_simplifiedOverview).assertDoesNotExist()
    }

    @Test
    fun accessDenialSuppressesOldThumbnailAndRetry() {
        val repo = immediate(RouteReplayFailure.ACCESS_DENIED)
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(drive, DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner", routeMapRenderer = defaultRenderer)
            }
        }
        node(R.string.savedDrives_driveReplay_accessDenied).assertExists()
        node(R.string.savedDrives_driveReplay_simplifiedOverview).assertDoesNotExist()
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
                SavedDriveDetailScreen(drive, DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner", routeMapRenderer = defaultRenderer)
            }
        }
        node(R.string.savedDrives_driveReplay_serviceUnavailable).assertExists()
        node(R.string.savedDrives_retry).performScrollTo().performClick()
        node(R.string.savedDrives_driveReplay_downloadFailed).assertExists()
        node(R.string.savedDrives_driveReplay_serviceUnavailable).assertDoesNotExist()
    }

    @Test
    fun corruptThumbnailAndFullRouteDoNotDrawInventedGeometry() {
        val repo = immediate(RouteReplayFailure.CORRUPT)
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(drive.copy(routeThumbnail = "_"), DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner", routeMapRenderer = defaultRenderer)
            }
        }
        node(R.string.savedDrives_driveReplay_corrupt).assertExists()
        node(R.string.savedDrives_driveReplay_simplifiedOverview).assertDoesNotExist()
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
                SavedDriveDetailScreen(selectedDrive.value, DriveDeleteStatus.Idle, {}, {}, routeRepository = reader.value, uid = owner.value, routeMapRenderer = defaultRenderer)
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
        node(R.string.savedDrives_driveReplay_accessDenied).assertExists()
        node(R.string.savedDrives_driveReplay_missing).assertDoesNotExist()
        compose.runOnIdle { selectedDrive.value = drive.copy(rideId = "drive-two") }
        node(R.string.savedDrives_routeLoading).assertExists()
        node(R.string.savedDrives_driveReplay_accessDenied).assertDoesNotExist()
        compose.runOnIdle { reader.value = immediate(RouteReplayFailure.SIGN_IN_REQUIRED) }
        node(R.string.savedDrives_driveReplay_signInRequired).assertExists()
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
                    SavedDriveDetailScreen(drive.copy(title = title.value), DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner", routeMapRenderer = defaultRenderer)
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

    @Test
    fun simplifiedRouteOpensFullWidthMapWithoutInventingStatistics() {
        val replay = RouteReplayState.Unavailable(RouteReplayFailure.MISSING)
        val renderer = CapturingMapRenderer()
        val saved = drive.copy(maxSpeedMetersPerSecond = 12.5)
        val repo = immediate(RouteReplayFailure.MISSING)
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(saved, DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner", routeMapRenderer = renderer)
            }
        }
        node(R.string.savedDrives_driveReplay_simplifiedOverview).assertExists()
        node(R.string.savedDrives_driveReplay_missing).assertExists()
        node(R.string.savedDrives_retry).assertExists()
        compose.onNodeWithTag(DRIVE_ROUTE_MAP_PREVIEW_TAG)
            .assertHeightIsEqualTo(240.dp)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(FULLSCREEN_FIXTURE_TAG).assertExists()
        // Saved facts survive even though the synthetic overview spans thousands
        // of kilometres. Geometry must never recompute this drive's statistics.
        compose.onNodeWithText(DriveFormatters.formatDistance(saved.distanceMeters)).assertExists()
        compose.onNodeWithText(DriveFormatters.formatSpeed(saved.maxSpeedMetersPerSecond)).assertExists()
        compose.runOnIdle {
            assertTrue(renderer.preview is DriveRouteGeometry.Simplified)
            assertEquals(RouteThumbnail.decode(saved.routeThumbnail), renderer.preview!!.coordinates)
            assertEquals(renderer.preview, renderer.fullscreen)
            assertNull(routeReplayTopSpeed(replay))
            assertEquals(RouteReplayFailure.MISSING, replay.reason)
        }
    }

    @Test
    fun retryReplacesSimplifiedMapWithActualFixesAndDismissesTheOldPopup() {
        val renderer = CapturingMapRenderer()
        val result = CompletableDeferred<RouteReplayState>()
        var calls = 0
        val points = listOf(RoutePoint(57.0, 12.0, 1_000L), RoutePoint(57.0001, 12.0, 11_000L))
        val repo = object : RouteReplayRepository {
            override suspend fun loadRoute(uid: String, rideId: String): RouteReplayState =
                if (calls++ == 0) RouteReplayState.Unavailable(RouteReplayFailure.MISSING) else result.await()
        }
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(drive, DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner", routeMapRenderer = renderer)
            }
        }
        compose.onNodeWithContentDescription(text(R.string.savedDrives_routeExpand)).performScrollTo().performClick()
        compose.onNodeWithTag(FULLSCREEN_FIXTURE_TAG).assertExists()
        compose.onNodeWithTag(CLOSE_FIXTURE_TAG).performClick()
        node(R.string.savedDrives_retry).performScrollTo().performClick()
        node(R.string.savedDrives_routeLoading).assertExists()
        compose.onNodeWithTag(DRIVE_ROUTE_MAP_PREVIEW_TAG).assertDoesNotExist()
        compose.runOnIdle { result.complete(RouteReplayState.Ready(points)) }
        node(R.string.savedDrives_driveReplay_simplifiedOverview).assertDoesNotExist()
        node(R.string.savedDrives_driveReplay_missing).assertDoesNotExist()
        node(R.string.savedDrives_retry).assertDoesNotExist()
        compose.onNodeWithTag(FULLSCREEN_FIXTURE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DRIVE_ROUTE_MAP_PREVIEW_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            val full = renderer.fullscreen as DriveRouteGeometry.Recorded
            assertEquals(points, full.points)
            assertEquals(renderer.preview, full)
            assertTrue(routeReplayTopSpeed(RouteReplayState.Ready(full.points))!! > 0.0)
        }
    }

    @Test
    fun freshDenialsRemoveTheOpenSimplifiedMapAndItsOpenAction() {
        val renderer = CapturingMapRenderer()
        val reader = mutableStateOf<RouteReplayRepository>(immediate(RouteReplayFailure.MISSING))
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(drive, DriveDeleteStatus.Idle, {}, {}, routeRepository = reader.value, uid = "owner", routeMapRenderer = renderer)
            }
        }
        compose.onNodeWithTag(DRIVE_ROUTE_MAP_PREVIEW_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(FULLSCREEN_FIXTURE_TAG).assertExists()
        for ((failure, message) in listOf(
            RouteReplayFailure.ACCESS_DENIED to R.string.savedDrives_driveReplay_accessDenied,
            RouteReplayFailure.SIGN_IN_REQUIRED to R.string.savedDrives_driveReplay_signInRequired,
            RouteReplayFailure.NOT_FOUND to R.string.savedDrives_driveReplay_notFound,
        )) {
            compose.runOnIdle { reader.value = immediate(failure) }
            node(message).assertExists()
            node(R.string.savedDrives_driveReplay_simplifiedOverview).assertDoesNotExist()
            node(R.string.savedDrives_retry).assertDoesNotExist()
            compose.onNodeWithTag(DRIVE_ROUTE_MAP_PREVIEW_TAG).assertDoesNotExist()
            compose.onNodeWithTag(FULLSCREEN_FIXTURE_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun configuredMapAvailabilityUsesInjectedRendererForFullRecordedRoute() {
        // The fake reports available=true independently of mapbox_access_token,
        // exercising the configured production branch in token-less CI as well.
        val renderer = CapturingMapRenderer()
        val points = listOf(RoutePoint(57.0, 12.0, 1_000L), RoutePoint(57.0001, 12.0, 11_000L))
        val repo = object : RouteReplayRepository {
            override suspend fun loadRoute(uid: String, rideId: String) = RouteReplayState.Ready(points)
        }
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(drive, DriveDeleteStatus.Idle, {}, {}, routeRepository = repo, uid = "owner", routeMapRenderer = renderer)
            }
        }
        compose.onNodeWithTag(DRIVE_ROUTE_MAP_PREVIEW_TAG).assertHeightIsEqualTo(240.dp)
        node(R.string.savedDrives_routeOverviewPlaceholder).assertDoesNotExist()
        node(R.string.savedDrives_driveReplay_simplifiedOverview).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.savedDrives_routeExpand)).performScrollTo().performClick()
        compose.onNodeWithTag(FULLSCREEN_FIXTURE_TAG).assertExists()
        compose.runOnIdle {
            assertEquals(points, (renderer.preview as DriveRouteGeometry.Recorded).points)
            assertEquals(renderer.preview, renderer.fullscreen)
        }
    }

    private class CapturingMapRenderer : DriveRouteMapRenderer {
        var preview: DriveRouteGeometry? = null
        var fullscreen: DriveRouteGeometry? = null

        @Composable override fun isAvailable() = true
        @Composable override fun Preview(route: DriveRouteGeometry, modifier: Modifier) {
            SideEffect { preview = route }
            Box(modifier)
        }
        @Composable override fun Fullscreen(route: DriveRouteGeometry, onDismiss: () -> Unit) {
            SideEffect { fullscreen = route }
            Text("Map renderer test fixture", Modifier.testTag(FULLSCREEN_FIXTURE_TAG))
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(CLOSE_FIXTURE_TAG)) { Text("Close test map") }
        }
    }

    private companion object {
        const val FULLSCREEN_FIXTURE_TAG = "route_fullscreen_fixture"
        const val CLOSE_FIXTURE_TAG = "route_close_fixture"
    }

    private fun immediate(reason: RouteReplayFailure) = object : RouteReplayRepository {
        override suspend fun loadRoute(uid: String, rideId: String) = RouteReplayState.Unavailable(reason)
    }
}
