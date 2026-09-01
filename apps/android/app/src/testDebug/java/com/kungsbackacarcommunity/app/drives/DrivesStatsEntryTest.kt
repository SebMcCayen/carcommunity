package com.kungsbackacarcommunity.app.drives

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The small "your driving stats" action in the History header replaced the
 * full-width stats card that used to sit between the header and the filters. Two
 * things must hold for the stats page to stay reachable-but-unobtrusive: the
 * action opens the stats page when tapped, and it is offered only once a drive
 * exists (so it never opens a page of zeroes).
 */
@RunWith(AndroidJUnit4::class)
class DrivesStatsEntryTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun drive(id: String) =
        SavedDrive(
            rideId = id,
            title = "Drive $id",
            distanceMeters = 1_000.0,
            durationSeconds = 600,
            averageSpeedMetersPerSecond = 10.0,
            startedAtMillis = 0L,
            endedAtMillis = 600_000L,
            createdAtMillis = 600_000L,
        )

    private fun loadedState(drives: List<SavedDrive>) =
        DriveHistoryListState.Loaded(
            drives = drives,
            tier = DriveSubscriptionTier.COMMUNITY,
            hiddenDriveCount = 0,
            hasMore = false,
        )

    @Test
    fun statsActionOpensTheStatsPage() {
        var opened = false
        composeTestRule.setContent {
            KccTheme {
                DrivesListScreen(
                    state = loadedState(listOf(drive("a"))),
                    onSelect = {},
                    onDelete = {},
                    deleteStatus = DriveDeleteStatus.Idle,
                    onShowStats = { opened = true },
                )
            }
        }

        composeTestRule.onNodeWithTag(DRIVE_STATS_ENTRY_TAG).assertHasClickAction().performClick()

        assertTrue("Tapping the stats action must open the stats page", opened)
    }

    @Test
    fun statsActionIsHiddenWhenThereAreNoDrives() {
        composeTestRule.setContent {
            KccTheme {
                DrivesListScreen(
                    state = loadedState(emptyList()),
                    onSelect = {},
                    onDelete = {},
                    deleteStatus = DriveDeleteStatus.Idle,
                    onShowStats = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(DRIVE_STATS_ENTRY_TAG).assertDoesNotExist()
    }
}
