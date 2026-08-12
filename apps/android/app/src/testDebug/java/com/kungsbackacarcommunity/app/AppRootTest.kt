package com.kungsbackacarcommunity.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.auth.AuthState
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the auth-state-driven app shell (Phase 12 slice 1;
 * CI-run in the instrumented-tests emulator job in validate-android.yml).
 */
@RunWith(AndroidJUnit4::class)
class AppRootTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun signedIn_showsHomeWithNameAndSignOut() {
        composeTestRule.setContent {
            KccTheme {
                AppRoot(authState = AuthState.SignedIn(uid = "u1", displayName = "Sebbe"))
            }
        }
        composeTestRule.onNodeWithText(str(R.string.home_communityStatusTitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Sebbe").assertIsDisplayed()
        // The sign-out action sits at the bottom of a scrollable column, so
        // assert existence (it may be below the fold on a small screen).
        composeTestRule.onNodeWithText(str(R.string.auth_signOut)).assertExists()
    }

    @Test
    fun signedOut_showsSignInScreen() {
        composeTestRule.setContent { KccTheme { AppRoot(authState = AuthState.SignedOut) } }
        composeTestRule.onNodeWithText(str(R.string.auth_loginTitle)).assertIsDisplayed()
    }

    @Test
    fun unavailable_showsHomeShellWithoutSignOut() {
        // Default AuthState.Unavailable — the CI / no-Firebase build.
        composeTestRule.setContent { KccTheme { AppRoot() } }
        composeTestRule.onNodeWithText(str(R.string.home_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.auth_signOut)).assertDoesNotExist()
    }
}
