package com.kungsbackacarcommunity.app.drives

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveDetailPresentationTest {
    @get:Rule val compose = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun routeFirstDetailKeepsSummaryOnlyFactsAndConfirmedDeletion() {
        var deleted: String? = null
        val drive = SavedDrive(
            rideId = "local-drive", title = "Owner's saved title", distanceMeters = 12_400.0,
            durationSeconds = 2_520L, averageSpeedMetersPerSecond = null,
            startedAtMillis = null, endedAtMillis = null, createdAtMillis = null,
        )
        compose.setContent {
            KccTheme {
                SavedDriveDetailScreen(
                    drive = drive, deleteStatus = DriveDeleteStatus.Idle,
                    onDelete = { deleted = it }, onBack = {},
                )
            }
        }
        compose.onNodeWithText(str(R.string.savedDrives_routeOverviewPlaceholder)).assertIsDisplayed()
        compose.onNodeWithText("Owner's saved title").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(DriveFormatters.formatDistance(12_400.0)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.savedDrives_deleteAction)).performScrollTo().performClick()
        assertNull(deleted)
        compose.onNodeWithText(str(R.string.savedDrives_deleteConfirmAction)).performClick()
        assertEquals("local-drive", deleted)
    }

    @Test fun statisticsRenderServerMonthAndLifetimeSeparately() {
        compose.setContent {
            KccTheme {
                DriveStatsScreen(
                    DriveStatsUiState.Loaded(
                        DriveStatsSnapshot(
                            tier = DriveSubscriptionTier.COMMUNITY, totalDrives = 80,
                            totalDistanceMeters = 620_000.0, totalDurationSeconds = 42_000L,
                            longestDriveMeters = 50_000.0, averageDriveMeters = 7_750.0,
                            fastestAverageSpeedMps = null, highestMaxSpeedMps = 47.321,
                            thisMonthDrives = 3, thisMonthDistanceMeters = 24_000.0,
                        ),
                    ),
                )
            }
        }
        // The complete server aggregate stays free even when history shows only five drives.
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText(DriveFormatters.formatDistance(24_000.0)).assertIsDisplayed()
        compose.onNodeWithText("80").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(DriveFormatters.formatDistance(620_000.0)).performScrollTo().assertIsDisplayed()
        // A populated backend field must not introduce a new maximum-speed UI fact.
        compose.onNodeWithText(str(R.string.savedDrives_maxSpeed)).assertDoesNotExist()
        compose.onNodeWithText(DriveFormatters.formatSpeed(47.321)).assertDoesNotExist()
    }
}
