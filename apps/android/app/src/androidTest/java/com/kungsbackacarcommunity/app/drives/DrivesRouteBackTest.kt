package com.kungsbackacarcommunity.app.drives

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.AeroBackButtonTag
import com.kungsbackacarcommunity.app.testutil.RetryRunner
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for #844: the History drill-in levels (Statistics and the
 * per-drive detail) must expose a visible in-app Back affordance that returns to
 * the History list. The list root itself stays arrow-free by design (#807).
 *
 * The pinned Back arrow is provided by wrapping only the STATS/DETAIL branches of
 * [DrivesRoute] in `LocalAeroBackAvailable = true`; the arrow re-fires the back
 * dispatcher, which the route's own `BackHandler` catches and unwinds one level.
 * Rendered in the no-Firebase configuration (errorReporter/routeRepository null).
 */
@RunWith(RetryRunner::class)
class DrivesRouteBackTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    /** Delete-only repository (the list + stats now come from the history repo). */
    private class FakeDrivesRepository : DrivesRepository {
        override fun observeDrives(uid: String): Flow<DrivesState> =
            throw UnsupportedOperationException("not used in this test")

        override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult =
            throw UnsupportedOperationException("not used in this test")

        override suspend fun deleteDrive(rideId: String) = Unit
    }

    /**
     * Serves the given drives as a single tier-visible page (and a matching stats
     * aggregate) so the list, its stats entry, and per-drive detail all render.
     */
    private class FakeDriveHistoryRepository(private val drives: List<SavedDrive>) :
        DriveHistoryRepository {
        override suspend fun listHistory(cursorRideId: String?, pageSize: Int?): DriveHistoryPage =
            DriveHistoryPage(
                tier = DriveSubscriptionTier.COMMUNITY,
                drives = drives,
                hasMore = false,
                nextCursorRideId = null,
                hiddenDriveCount = 0,
                hasTierRestrictedHistory = false,
            )

        override suspend fun fetchStats(
            monthStartMillis: Long?,
            monthEndMillis: Long?,
        ): DriveStatsSnapshot =
            DriveStatsSnapshot(
                tier = DriveSubscriptionTier.COMMUNITY,
                totalDrives = drives.size,
                totalDistanceMeters = 0.0,
                totalDurationSeconds = 0L,
                longestDriveMeters = 0.0,
                averageDriveMeters = 0.0,
                fastestAverageSpeedMps = null,
                highestMaxSpeedMps = null,
                thisMonthDrives = 0,
                thisMonthDistanceMeters = 0.0,
            )
    }

    private val drive =
        SavedDrive(
            rideId = "ride-1",
            title = "My test drive",
            distanceMeters = 1000.0,
            durationSeconds = 600,
            averageSpeedMetersPerSecond = 5.0,
            startedAtMillis = 1_000_000L,
            endedAtMillis = 1_600_000L,
            createdAtMillis = 1_000_000L,
        )

    private fun setRoute() {
        composeTestRule.setContent {
            KccTheme {
                DrivesRoute(
                    repository = FakeDrivesRepository(),
                    historyRepository = FakeDriveHistoryRepository(listOf(drive)),
                    uid = "uid-1",
                    errorReporter = null,
                    routeRepository = null,
                )
            }
        }
    }

    @Test
    fun statisticsDrillInHasBackAffordanceThatReturnsToHistory() {
        setRoute()

        // The History list root is shown, arrow-free.
        composeTestRule.onNodeWithText(str(R.string.savedDrives_screenTitle)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AeroBackButtonTag).assertDoesNotExist()

        // Open Statistics.
        composeTestRule.onNodeWithTag(DRIVE_STATS_ENTRY_TAG).performClick()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_statsTitle)).assertIsDisplayed()

        // The #844 fix: the Statistics page now shows the pinned Back arrow.
        composeTestRule.onNodeWithTag(AeroBackButtonTag).assertIsDisplayed()

        // Tapping it returns to the History list.
        composeTestRule.onNodeWithTag(AeroBackButtonTag).performClick()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_statsTitle)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_screenTitle)).assertIsDisplayed()
    }

    /** A long history so the last drive starts well below the fold. */
    private fun manyDrives(count: Int): List<SavedDrive> =
        (0 until count).map { i ->
            SavedDrive(
                rideId = "ride-$i",
                title = String.format(Locale.ROOT, "Drive %02d", i),
                distanceMeters = 1000.0,
                durationSeconds = 600,
                averageSpeedMetersPerSecond = 5.0,
                startedAtMillis = 1_000_000L + i,
                endedAtMillis = 1_600_000L + i,
                createdAtMillis = 1_000_000L + i,
            )
        }

    /**
     * #996: scrolling the History list, opening a drive's detail, and pressing the
     * in-app Back arrow must return to the SAME scroll position, not the top. The
     * detail level swaps [DrivesListScreen] out of the composition, so the list's
     * scroll state must be owned by [DrivesRoute] (above the swap) to survive.
     */
    @Test
    fun scrollPositionIsRetainedAcrossDetailRoundTrip() {
        composeTestRule.setContent {
            KccTheme {
                DrivesRoute(
                    repository = FakeDrivesRepository(),
                    historyRepository = FakeDriveHistoryRepository(manyDrives(50)),
                    uid = "uid-1",
                    errorReporter = null,
                    routeRepository = null,
                )
            }
        }

        // The default History order is newest-first (DriveSort.NEWEST ->
        // SavedDrives.sortedForList): the drive with the LARGEST startedAtMillis
        // renders at the TOP, the oldest at the bottom. Our fixture stamps
        // startedAtMillis = base + i, so "Drive 49" is newest (top of the list)
        // and "Drive 00" is oldest (bottom, off-screen at the top of the scroll).
        // We assert against these two ENDPOINTS — the newest is always at the very
        // top and the oldest always decomposes once we scroll far to the bottom —
        // so both directions are viewport-independent and deterministic (no
        // reliance on which mid-list rows the emulator's viewport height composes).
        val topTitle = "Drive 49" // newest -> initially visible at the top
        val bottomTitle = "Drive 00" // oldest -> off-screen until we scroll down

        // We start at the top: the newest drive is visible, the oldest is not.
        composeTestRule.onNodeWithText(topTitle).assertIsDisplayed()
        composeTestRule.onNodeWithText(bottomTitle).assertDoesNotExist()

        // Scroll the oldest drive (bottom of the list) into view.
        composeTestRule
            .onNodeWithTag(DRIVE_HISTORY_LIST_TAG)
            .performScrollToNode(hasText(bottomTitle))
        composeTestRule.onNodeWithText(bottomTitle).assertIsDisplayed()
        // The newest drive has now scrolled off the top (decomposed) — proves we moved.
        composeTestRule.onNodeWithText(topTitle).assertDoesNotExist()

        // Open the oldest drive's detail, then return via the pinned Back arrow.
        composeTestRule.onNodeWithText(bottomTitle).performClick()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_detailTitle)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AeroBackButtonTag).performClick()

        // Back at the list AT THE SAME SCROLL POSITION: the oldest drive is still
        // shown and the newest is still off the top (a top-reset would flip both).
        composeTestRule.onNodeWithText(bottomTitle).assertIsDisplayed()
        composeTestRule.onNodeWithText(topTitle).assertDoesNotExist()
    }

    @Test
    fun detailDrillInHasBackAffordanceThatReturnsToHistory() {
        setRoute()

        // Open a drive's detail by tapping its card.
        composeTestRule.onNodeWithText("My test drive").performClick()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_detailTitle)).assertIsDisplayed()

        // Detail also exposes the pinned Back arrow (same gap as Statistics).
        composeTestRule.onNodeWithTag(AeroBackButtonTag).assertIsDisplayed()

        // Tapping it returns to the History list.
        composeTestRule.onNodeWithTag(AeroBackButtonTag).performClick()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_detailTitle)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_screenTitle)).assertIsDisplayed()
    }
}
