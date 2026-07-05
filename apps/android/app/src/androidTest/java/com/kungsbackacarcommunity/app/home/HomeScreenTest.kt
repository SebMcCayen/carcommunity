package com.kungsbackacarcommunity.app.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Compose UI tests for the authenticated home shell (Phase 12 slice 1).
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun tappingSignOut_invokesCallback() {
        var signOutCount = 0
        composeTestRule.setContent {
            KccTheme { HomeScreen(displayName = "Sebbe", onSignOut = { signOutCount++ }) }
        }
        composeTestRule.onNodeWithText(str(R.string.auth_signOut)).performScrollTo().performClick()
        assertEquals(1, signOutCount)
    }

    @Test
    fun nullOnSignOut_hidesTheSignOutAction() {
        composeTestRule.setContent {
            KccTheme { HomeScreen(displayName = null, onSignOut = null) }
        }
        composeTestRule.onNodeWithText(str(R.string.home_communityStatusTitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.auth_signOut)).assertDoesNotExist()
    }
}
