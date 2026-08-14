package com.kungsbackacarcommunity.app.drives

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
 * Compose UI tests for the auto-keep "Drive saved" confirmation snackbar (#856):
 * it shows the message and its two actions each fire their callback (Undo = delete
 * the just-saved drive, View = open it in History).
 */
@RunWith(AndroidJUnit4::class)
class DriveSavedSnackbarTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(
        onUndo: () -> Unit = {},
        onView: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KccTheme {
                val host = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    host.showSnackbar(
                        DriveSavedSnackbarVisuals(
                            message = "Drive saved",
                            undoLabel = "Undo",
                            viewLabel = "View",
                            onUndo = onUndo,
                            onView = onView,
                        ),
                    )
                }
                SnackbarHost(host) { data ->
                    when (val visuals = data.visuals) {
                        is DriveSavedSnackbarVisuals -> DriveSavedSnackbar(data, visuals)
                        else -> Snackbar(data)
                    }
                }
            }
        }
    }

    @Test
    fun showsMessageAndBothActions() {
        show()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_SNACKBAR_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Drive saved").assertIsDisplayed()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_SNACKBAR_UNDO_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DRIVE_SAVED_SNACKBAR_VIEW_TAG).assertIsDisplayed()
    }

    @Test
    fun undo_triggersTheDeleteCallback() {
        var undo = 0
        show(onUndo = { undo += 1 })
        composeTestRule.onNodeWithTag(DRIVE_SAVED_SNACKBAR_UNDO_TAG).performClick()
        assertEquals(1, undo)
    }

    @Test
    fun view_triggersTheViewCallback() {
        var view = 0
        show(onView = { view += 1 })
        composeTestRule.onNodeWithTag(DRIVE_SAVED_SNACKBAR_VIEW_TAG).performClick()
        assertEquals(1, view)
    }
}
