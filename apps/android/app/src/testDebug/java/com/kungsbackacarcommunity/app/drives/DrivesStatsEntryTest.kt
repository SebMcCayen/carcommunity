package com.kungsbackacarcommunity.app.drives

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.AeroBackButtonTag
import com.kungsbackacarcommunity.app.shell.LocalAeroBackAvailable
import org.junit.Assert.assertEquals
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
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

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
                CompositionLocalProvider(LocalAeroBackAvailable provides true) {
                    DrivesListScreen(
                        state = loadedState(listOf(drive("a"))),
                        onSelect = {},
                        onDelete = {},
                        deleteStatus = DriveDeleteStatus.Idle,
                        onShowStats = { opened = true },
                    )
                }
            }
        }

        val title = composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.shell_moreDrives))
            .fetchSemanticsNode().boundsInRoot
        val back = composeTestRule.onNodeWithTag(AeroBackButtonTag).fetchSemanticsNode().boundsInRoot
        val statsAction = composeTestRule.onNodeWithTag(DRIVE_STATS_ENTRY_TAG).assertHasClickAction()
        val stats = statsAction.fetchSemanticsNode().boundsInRoot
        assertTrue(back.right <= title.left && title.right <= stats.left)
        assertEquals(back.center.y, title.center.y, 0.5f)
        assertEquals(back.center.y, stats.center.y, 0.5f)
        statsAction.performClick()

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
