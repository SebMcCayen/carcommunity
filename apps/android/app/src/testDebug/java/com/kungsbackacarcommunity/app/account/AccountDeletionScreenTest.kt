package com.kungsbackacarcommunity.app.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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

/** Compose UI tests for the account-deletion confirmation (Phase 12 slice 25). */
@RunWith(AndroidJUnit4::class)
class AccountDeletionScreenTest {
    @get:Rule val composeTestRule = createComposeRule()

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
        composeTestRule
            .onNodeWithText(str(R.string.settings_accountDeletionAcknowledgement))
            .performScrollTo()
            .performClick()
        // First delete button opens the confirm dialog; the dialog's confirm
        // (same label, rendered last in the tree) triggers the callback.
        composeTestRule
            .onAllNodesWithText(str(R.string.settings_deleteAccount))
            .onLast()
            .performScrollTo()
            .performClick()
        composeTestRule
            .onAllNodesWithText(str(R.string.settings_deleteAccount))
            .onLast()
            .performClick()
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
        composeTestRule
            .onNodeWithText(str(R.string.settings_accountDeletionError))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun cancel_returnsWithoutDeletingAccount() {
        var backs = 0
        var deletes = 0
        composeTestRule.setContent {
            KccTheme {
                AccountDeletionScreen(AccountDeletionStatus.Idle, { deletes++ }, { backs++ })
            }
        }
        composeTestRule
            .onNodeWithText(str(R.string.profile_cancelButton))
            .performScrollTo()
            .performClick()
        assertEquals(1, backs)
        assertEquals(0, deletes)
    }

    @Test
    fun deleting_disablesRepeatedDeleteAndCancel() {
        composeTestRule.setContent {
            KccTheme { AccountDeletionScreen(AccountDeletionStatus.Deleting, {}, {}) }
        }
        composeTestRule
            .onNodeWithText(str(R.string.settings_accountDeletionAcknowledgement))
            .performScrollTo()
            .assertIsNotEnabled()
        composeTestRule
            .onNode(hasText(str(R.string.settings_deleteAccount)) and hasClickAction())
            .performScrollTo()
            .assertIsNotEnabled()
        composeTestRule
            .onNodeWithText(str(R.string.profile_cancelButton))
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun acknowledgement_isNamedCheckbox_andRequiredBeforeDialog() {
        composeTestRule.setContent {
            KccTheme { AccountDeletionScreen(AccountDeletionStatus.Idle, {}, {}) }
        }
        val delete = composeTestRule.onNode(
            hasText(str(R.string.settings_deleteAccount)) and hasClickAction()
        )
        delete.performScrollTo().assertIsNotEnabled()
        val acknowledgement = composeTestRule.onNodeWithText(
            str(R.string.settings_accountDeletionAcknowledgement)
        )
        acknowledgement.performScrollTo().assertIsOff().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        ).performClick().assertIsOn()
        delete.performScrollTo().assertIsEnabled()
        acknowledgement.performScrollTo().performClick()
        delete.performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun dismissingSecondConfirmation_doesNotDelete() {
        var deletes = 0
        composeTestRule.setContent {
            KccTheme { AccountDeletionScreen(AccountDeletionStatus.Idle, { deletes++ }, {}) }
        }
        composeTestRule.onNodeWithText(str(R.string.settings_accountDeletionAcknowledgement))
            .performScrollTo().performClick()
        composeTestRule.onNode(hasText(str(R.string.settings_deleteAccount)) and hasClickAction())
            .performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.settings_accountDeletionConfirmTitle))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.profile_cancelButton))
            .onLast().performClick()
        composeTestRule.onNodeWithText(str(R.string.settings_accountDeletionConfirmTitle))
            .assertDoesNotExist()
        assertEquals(0, deletes)
    }
}
