package com.kungsbackacarcommunity.app.drives

import androidx.compose.ui.test.assertIsDisplayed
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
}
