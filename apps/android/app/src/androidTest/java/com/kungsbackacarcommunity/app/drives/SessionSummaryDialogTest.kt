package com.kungsbackacarcommunity.app.drives

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the end-of-session Keep/Delete summary.
 *
 * The drive is AUTO-saved when a live session ends, so the load-bearing claims
 * here are the Delete branch's: deleting the just-saved drive must ask before it
 * removes it, cancelling must remove nothing, and a save failure must never let
 * the drive vanish silently.
 */
@RunWith(AndroidJUnit4::class)
class SessionSummaryDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val points =
        listOf(
            RecordedPoint(57.0, 12.0, 1_000L),
            RecordedPoint(57.1, 12.1, 61_000L),
        )

    private fun setDialog(
        state: RecordingState =
            RecordingState.SavedPendingChoice(rideId = "ride", elapsedMillis = 60_000L),
        onKeep: () -> Unit = {},
        onDelete: () -> Unit = {},
        onRetry: () -> Unit = {},
        onDiscard: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KccTheme {
                SessionSummaryDialog(
                    state = state,
                    pointsProvider = { points },
                    onKeep = onKeep,
                    onDelete = onDelete,
                    onRetry = onRetry,
                    onDiscard = onDiscard,
                )
            }
        }
    }

    @Test
    fun delete_asksForConfirmationBeforeRemovingTheSavedDrive() {
        var deleted = false
        setDialog(onDelete = { deleted = true })

        composeTestRule.onNodeWithText(str(R.string.savedDrives_deleteSessionAction)).performClick()

        // The confirmation is up and NOTHING has been deleted yet.
        composeTestRule.onNodeWithTag(DELETE_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.savedDrives_deleteSessionConfirmTitle))
            .assertIsDisplayed()
        assertFalse("Delete must not fire until confirmed", deleted)
    }

    @Test
    fun delete_confirmed_deletesTheDrive() {
        var deleted = 0
        setDialog(onDelete = { deleted += 1 })

        composeTestRule.onNodeWithText(str(R.string.savedDrives_deleteSessionAction)).performClick()
        composeTestRule
            .onNodeWithText(str(R.string.savedDrives_deleteSessionConfirmAction))
            .performClick()

        assertEquals(1, deleted)
    }

    @Test
    fun delete_cancelled_keepsTheSummaryAndDeletesNothing() {
        var deleted = false
        setDialog(onDelete = { deleted = true })

        composeTestRule.onNodeWithText(str(R.string.savedDrives_deleteSessionAction)).performClick()
        composeTestRule
            .onNodeWithText(str(R.string.savedDrives_deleteSessionConfirmCancel))
            .performClick()

        assertFalse("Cancelling the confirmation must not delete", deleted)
        // Back to the Keep/Delete choice — the just-saved drive is still there.
        composeTestRule.onNodeWithTag(SESSION_SUMMARY_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_keepAction)).assertIsDisplayed()
    }

    @Test
    fun keep_isNotGuardedByTheConfirmation() {
        var kept = 0
        setDialog(onKeep = { kept += 1 })

        composeTestRule.onNodeWithText(str(R.string.savedDrives_keepAction)).performClick()

        // Keeping only dismisses — it goes straight through.
        assertEquals(1, kept)
    }

    @Test
    fun deleteFailed_showsTheErrorAndKeepsTheChoice() {
        setDialog(
            state =
                RecordingState.SavedPendingChoice(
                    rideId = "ride",
                    elapsedMillis = 60_000L,
                    deleteFailed = true,
                ),
        )

        // The drive stays safely saved; the delete error is shown and the choice stands.
        composeTestRule.onNodeWithText(str(R.string.savedDrives_deleteError)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_keepAction)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_deleteSessionAction)).assertIsDisplayed()
    }

    @Test
    fun routeMap_startsCollapsedAndTogglesOpenAndShutAgain() {
        setDialog()

        // Collapsed by default: only the "show" control is offered, so the
        // Keep/Delete choice is never pushed down by an unasked-for map.
        composeTestRule
            .onNodeWithText(str(R.string.savedDrives_sessionRouteShow))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(SESSION_ROUTE_AREA_TAG).assertDoesNotExist()

        composeTestRule.onNodeWithTag(SESSION_ROUTE_TOGGLE_TAG).performClick()

        // Expanded: the route area is up and the control now offers to hide it.
        composeTestRule.onNodeWithTag(SESSION_ROUTE_AREA_TAG).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.savedDrives_sessionRouteHide))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.savedDrives_sessionRouteShow))
            .assertDoesNotExist()

        composeTestRule.onNodeWithTag(SESSION_ROUTE_TOGGLE_TAG).performClick()

        // Minimized again, back to the starting state.
        composeTestRule.onNodeWithTag(SESSION_ROUTE_AREA_TAG).assertDoesNotExist()
        composeTestRule
            .onNodeWithText(str(R.string.savedDrives_sessionRouteShow))
            .assertIsDisplayed()
    }

    @Test
    fun routeMap_expandingDoesNotDismissTheDialogOrResolveTheChoice() {
        var kept = 0
        var deleted = 0
        setDialog(onKeep = { kept += 1 }, onDelete = { deleted += 1 })

        composeTestRule.onNodeWithTag(SESSION_ROUTE_TOGGLE_TAG).performClick()

        // The forced Keep/Delete choice still stands with the map open.
        composeTestRule.onNodeWithTag(SESSION_SUMMARY_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_keepAction)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.savedDrives_deleteSessionAction))
            .assertIsDisplayed()
        assertEquals(0, kept)
        assertEquals(0, deleted)
    }

    @Test
    fun routeMap_isNotOfferedOnTheSaveFailedPrompt() {
        // Nothing was stored, so the question is save-or-not, not where you drove.
        setDialog(
            state =
                RecordingState.Failed(
                    pointCount = 2,
                    elapsedMillis = 60_000L,
                    code = "UNAVAILABLE",
                ),
        )

        composeTestRule.onNodeWithTag(SESSION_ROUTE_TOGGLE_TAG).assertDoesNotExist()
    }

    @Test
    fun memberGateRefusal_namesTheMissingMembershipAndOffersCloseInsteadOfRetry() {
        var discarded = 0
        setDialog(
            state =
                RecordingState.Failed(
                    pointCount = 2,
                    elapsedMillis = 60_000L,
                    code = RecordingState.PERMISSION_DENIED,
                ),
            onDiscard = { discarded += 1 },
        )

        // A refusal no retry can fix names the missing membership and offers Close.
        composeTestRule.onNodeWithText(str(R.string.savedDrives_memberRequired)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_retryAction)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_closeButton)).performClick()
        assertEquals(1, discarded)
    }

    @Test
    fun transientFailure_offersRetryAndDoesNotLetTheDriveVanish() {
        var retried = 0
        setDialog(
            state =
                RecordingState.Failed(
                    pointCount = 2,
                    elapsedMillis = 60_000L,
                    code = "UNAVAILABLE",
                ),
            onRetry = { retried += 1 },
        )

        composeTestRule.onNodeWithText(str(R.string.savedDrives_saveError)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_memberRequired)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.savedDrives_retryAction)).performClick()
        assertEquals(1, retried)
    }
}
