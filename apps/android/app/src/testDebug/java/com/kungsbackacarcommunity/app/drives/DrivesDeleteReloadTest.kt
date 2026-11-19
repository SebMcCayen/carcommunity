package com.kungsbackacarcommunity.app.drives

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for #1425: resetting delete status must not cancel the history read. */
@RunWith(AndroidJUnit4::class)
class DrivesDeleteReloadTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun drive(id: String) = SavedDrive(
        rideId = id, title = id, distanceMeters = 1_000.0, durationSeconds = 600,
        averageSpeedMetersPerSecond = 10.0, startedAtMillis = 0L,
        endedAtMillis = 600_000L, createdAtMillis = 600_000L,
    )

    private fun page(vararg ids: String) = DriveHistoryPage(
        tier = DriveSubscriptionTier.COMMUNITY, drives = ids.map(::drive),
        hasMore = false, nextCursorRideId = null, hiddenDriveCount = 0,
        hasTierRestrictedHistory = false,
    )

    @Test
    fun deletingDrive_reloadsDelayedHistoryWithoutLeavingTheScreen() {
        val refreshed = CompletableDeferred<DriveHistoryPage>()
        var calls = 0
        var deleted: String? = null
        val history = object : DriveHistoryRepository {
            override suspend fun listHistory(cursorRideId: String?, pageSize: Int?): DriveHistoryPage {
                calls++
                return if (calls == 1) page("deleted-drive", "kept-drive") else refreshed.await()
            }

            override suspend fun fetchStats(monthStartMillis: Long?, monthEndMillis: Long?) =
                error("Statistics are not opened")
        }
        val repository = object : DrivesRepository {
            override fun observeDrives(uid: String) = flowOf(DrivesState.Loaded(emptyList()))
            override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult = error("Not saving")
            override suspend fun deleteDrive(rideId: String) { deleted = rideId }
        }
        compose.setContent {
            KccTheme {
                DrivesRoute(
                    repository = repository, historyRepository = history, uid = "owner",
                    initialRideId = "deleted-drive", errorReporter = null, routeRepository = null,
                )
            }
        }
        val delete = compose.activity.getString(R.string.savedDrives_deleteAction)
        compose.onNodeWithText(delete).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.savedDrives_deleteConfirmAction)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("deleted-drive", deleted)
            assertEquals(2, calls)
            // The status has already reset to Idle and recomposed while this
            // read is suspended. An effect tied to deleteStatus is cancelled.
            refreshed.complete(page("kept-drive", "promoted-drive"))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.savedDrives_loading)).assertDoesNotExist()
        compose.onNodeWithTag(DRIVE_HISTORY_LIST_TAG).performScrollToNode(hasText("kept-drive"))
        compose.onNodeWithText("kept-drive").assertIsDisplayed()
        compose.onNodeWithTag(DRIVE_HISTORY_LIST_TAG).performScrollToNode(hasText("promoted-drive"))
        compose.onNodeWithText("promoted-drive").assertIsDisplayed()
        compose.onNodeWithText("deleted-drive").assertDoesNotExist()
    }
}
