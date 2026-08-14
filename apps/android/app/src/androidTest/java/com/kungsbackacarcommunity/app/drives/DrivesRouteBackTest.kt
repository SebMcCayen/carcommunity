package com.kungsbackacarcommunity.app.drives

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.AeroBackButtonTag
import com.kungsbackacarcommunity.app.testutil.RetryRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

    /** A single-drive repository so the list, its stats entry, and detail all render. */
    private class FakeDrivesRepository(private val drives: List<SavedDrive>) : DrivesRepository {
        override fun observeDrives(uid: String): Flow<DrivesState> =
            flowOf(DrivesState.Loaded(drives))

        override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult =
            throw UnsupportedOperationException("not used in this test")

        override suspend fun deleteDrive(rideId: String) = Unit
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
                    repository = FakeDrivesRepository(listOf(drive)),
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
