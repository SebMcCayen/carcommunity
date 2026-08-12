package com.kungsbackacarcommunity.app.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the account-deletion confirmation (Phase 12 slice 25).
 */
@RunWith(AndroidJUnit4::class)
class AccountDeletionScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun confirmDialog_thenDelete_invokesCallback() {
        var deletes = 0
        composeTestRule.setContent {
            KccTheme {
                AccountDeletionScreen(
                    status = AccountDeletionStatus.Idle,
                    onDelete = { deletes++ },
                    onBack = {},
                )
            }
        }
        // First delete button opens the confirm dialog; the dialog's confirm
        // (same label, rendered last in the tree) triggers the callback.
        composeTestRule.onAllNodesWithText(str(R.string.settings_deleteAccount)).onLast()
            .performScrollTo().performClick()
        composeTestRule.onAllNodesWithText(str(R.string.settings_deleteAccount)).onLast().performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun failedStatus_showsError() {
        composeTestRule.setContent {
            KccTheme {
                AccountDeletionScreen(
                    status = AccountDeletionStatus.Failed,
                    onDelete = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.settings_accountDeletionError)).assertIsDisplayed()
    }
}
