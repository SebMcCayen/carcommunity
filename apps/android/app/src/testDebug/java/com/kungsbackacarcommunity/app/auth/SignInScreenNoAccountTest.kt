package com.kungsbackacarcommunity.app.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the user actually sees when the device has no Google account.
 *
 * The unit tests cover the state and the message choice; this covers the part
 * only a real composition can show — that the guidance renders instead of the
 * generic error, that the add-account button appears and is wired, and that a
 * device which cannot resolve the add-account activity degrades to text-only
 * rather than offering a button that does nothing.
 */
@RunWith(AndroidJUnit4::class)
class SignInScreenNoAccountTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun noGoogleAccount_showsGuidanceAndAddAccountButton() {
        composeTestRule.setContent {
            KccTheme {
                SignInScreen(
                    status = SignInStatus.Failed(SignInFailure.NO_GOOGLE_ACCOUNT),
                    onSignInClick = {},
                    quoteIndex = 0,
                    canAddAccount = true,
                    onAddGoogleAccountClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(str(R.string.auth_errorNoGoogleAccount)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.auth_addGoogleAccountButton)).assertIsDisplayed()
        // The dead end this PR removes: the generic error must be gone.
        composeTestRule.onNodeWithText(str(R.string.auth_errorGeneric)).assertDoesNotExist()
    }

    @Test
    fun addAccountButton_invokesTheHandler() {
        var taps = 0
        composeTestRule.setContent {
            KccTheme {
                SignInScreen(
                    status = SignInStatus.Failed(SignInFailure.NO_GOOGLE_ACCOUNT),
                    onSignInClick = {},
                    quoteIndex = 0,
                    canAddAccount = true,
                    onAddGoogleAccountClick = { taps++ },
                )
            }
        }

        composeTestRule.onNodeWithText(str(R.string.auth_addGoogleAccountButton)).performClick()

        assertEquals("The add-account button must open the add-account screen", 1, taps)
    }

    @Test
    fun deviceWithoutAddAccountActivity_fallsBackToTextOnly() {
        // Managed profiles and some OEM ROMs cannot resolve ACTION_ADD_ACCOUNT.
        // The guidance still names Settings > Accounts, so the user is not stuck;
        // what must NOT happen is a visible button that silently does nothing.
        composeTestRule.setContent {
            KccTheme {
                SignInScreen(
                    status = SignInStatus.Failed(SignInFailure.NO_GOOGLE_ACCOUNT),
                    onSignInClick = {},
                    quoteIndex = 0,
                    canAddAccount = false,
                    onAddGoogleAccountClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(str(R.string.auth_errorNoGoogleAccount)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.auth_addGoogleAccountButton)).assertDoesNotExist()
    }

    @Test
    fun genuineFailure_stillShowsTheGenericErrorAndNoAddAccountButton() {
        composeTestRule.setContent {
            KccTheme {
                SignInScreen(
                    status = SignInStatus.Failed(SignInFailure.GENERIC),
                    onSignInClick = {},
                    quoteIndex = 0,
                    canAddAccount = true,
                    onAddGoogleAccountClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(str(R.string.auth_errorGeneric)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.auth_addGoogleAccountButton)).assertDoesNotExist()
    }
}
