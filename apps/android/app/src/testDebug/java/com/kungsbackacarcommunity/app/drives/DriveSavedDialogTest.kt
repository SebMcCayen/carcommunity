package com.kungsbackacarcommunity.app.drives

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import com.kungsbackacarcommunity.app.live.LiveStopAttempt
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the auto-keep "Drive saved" confirmation dialog (#856): it
 * shows the message and both actions, OK dismisses it, and History navigates to
 * the Drives/History route (both fire their respective callback).
 */
@RunWith(AndroidJUnit4::class)
class DriveSavedDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(
        onDismiss: () -> Unit = {},
        onHistory: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KccTheme {
                DriveSavedDialog(
                    message = "Your drive has been saved.",
                    confirmLabel = "OK",
                    historyLabel = "History",
                    onDismiss = onDismiss,
                    onHistory = onHistory,
                )
            }
        }
    }

    @Test
    fun showsMessageAndBothActions() {
        show()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Your drive has been saved.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_OK_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_HISTORY_TAG).assertIsDisplayed()
    }

    @Test
    fun ok_triggersTheDismissCallback() {
        var dismiss = 0
        show(onDismiss = { dismiss += 1 })
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_OK_TAG).performClick()
        assertEquals(1, dismiss)
    }

    @Test
    fun history_navigatesAndClosesTheDialog() {
        var history = 0
        var dismiss = 0
        // History is self-contained: it navigates AND closes, so the caller never
        // has to dismiss it and the dialog can't be left open.
        show(onDismiss = { dismiss += 1 }, onHistory = { history += 1 })
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_HISTORY_TAG).performClick()
        assertEquals(1, history)
        assertEquals(1, dismiss)
    }

    @Test
    fun pendingSaveNeverClaimsSuccessOrOpensHistory() {
        composeTestRule.setContent {
            KccTheme {
                DriveSavedDialog("Saved", "OK", "History", {}, {},
                    isSaved = false, motionEnabled = false)
            }
        }
        composeTestRule.onNodeWithTag(DRIVE_SAVING_STATUS_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved").assertDoesNotExist()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_HISTORY_TAG).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_OK_TAG).assertIsEnabled()
    }

    @Test
    fun confirmedResultReplacesProgressAndEnablesHistoryWithoutADelay() {
        val saved = mutableStateOf(false)
        composeTestRule.setContent {
            KccTheme {
                DriveSavedDialog("Saved", "OK", "History", {}, {},
                    isSaved = saved.value, motionEnabled = false)
            }
        }
        composeTestRule.onNodeWithTag(DRIVE_SAVING_STATUS_TAG).assertIsDisplayed()
        composeTestRule.runOnIdle { saved.value = true }
        composeTestRule.onNodeWithTag(DRIVE_SAVE_SUCCESS_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DRIVE_SAVING_STATUS_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_HISTORY_TAG).assertIsEnabled()
    }

    @Test
    fun closingPendingFeedbackDoesNotNavigateOrCancelTheSave() {
        var dismiss = 0
        var history = 0
        composeTestRule.setContent {
            KccTheme {
                DriveSavedDialog("Saved", "OK", "History", { dismiss++ }, { history++ },
                    isSaved = false, motionEnabled = false)
            }
        }
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_OK_TAG).performClick()
        assertEquals(1, dismiss)
        assertEquals(0, history)
    }

    @Test
    fun animationNeverInventsSaveSuccess() {
        val saved = mutableStateOf(false)
        composeTestRule.setContent {
            KccTheme {
                DriveSavedDialog("Saved", "OK", "History", {}, {},
                    isSaved = saved.value, motionEnabled = true)
            }
        }
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(10_000)
        composeTestRule.onNodeWithText("Saved").assertDoesNotExist()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_HISTORY_TAG).assertIsNotEnabled()
        composeTestRule.runOnIdle { saved.value = true }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        // Resume frame/layout work after the manually controlled transition.
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(DRIVE_SAVE_SUCCESS_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_HISTORY_TAG).assertIsEnabled()
    }

    @Test
    fun immediateRejectedStopRetractsFeedbackWithoutWaitingForAnotherComposition() {
        val attempts = MutableStateFlow<LiveStopAttempt>(LiveStopAttempt.None)
        val visible = mutableStateOf(false)
        var raised = false
        composeTestRule.setContent {
            val observed = attempts.collectAsState().value
            LaunchedEffect(observed) {
                if (observed == LiveStopAttempt.None) visible.value = false
            }
            KccTheme {
                Button(onClick = {
                    // These emissions can conflate back to None in the same click.
                    attempts.value = LiveStopAttempt.InFlight(0L)
                    visible.value = true
                    raised = true
                    attempts.value = LiveStopAttempt.None
                    DriveSaveConfirmation.onStopRejected(
                        RecordingState.Recording(pointCount = 1, elapsedMillis = 1_000L),
                        confirmed = false,
                    ) { visible.value = false; raised = false }
                }) { Text("Stop") }
                if (visible.value) {
                    DriveSavedDialog("Saved", "OK", "History", {}, {},
                        isSaved = false, motionEnabled = false)
                }
            }
        }
        composeTestRule.onNodeWithText("Stop").performClick()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_DIALOG_TAG).assertDoesNotExist()
        assertEquals(false, raised)
        assertEquals(LiveStopAttempt.None, attempts.value)
    }

    @Test
    fun lateStopFailureCannotRetractASaveAlreadyUnderWayOrConfirmed() {
        var retracted = 0
        DriveSaveConfirmation.onStopRejected(RecordingState.Saving, false) { retracted++ }
        DriveSaveConfirmation.onStopRejected(RecordingState.KeptPendingSave(1_000L), false) { retracted++ }
        DriveSaveConfirmation.onStopRejected(RecordingState.Kept, true) { retracted++ }
        assertEquals(0, retracted)
    }
}
