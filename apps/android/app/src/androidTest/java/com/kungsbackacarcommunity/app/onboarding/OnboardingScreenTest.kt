package com.kungsbackacarcommunity.app.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun continueIsGatedOnAllThreeConsentsAndADisplayName() {
        var submitted = 0
        composeTestRule.setContent {
            KccTheme { OnboardingScreen(status = OnboardingStatus.Idle, onSubmit = { submitted++ }) }
        }
        val continueBtn = str(R.string.onboarding_continueButton)
        composeTestRule.onNodeWithText(continueBtn).performScrollTo().assertIsNotEnabled()

        composeTestRule.onNodeWithText(str(R.string.onboarding_ageConfirm)).performClick()
        composeTestRule.onNodeWithText(str(R.string.onboarding_termsAccept)).performClick()
        composeTestRule.onNodeWithText(continueBtn).performScrollTo().assertIsNotEnabled()

        composeTestRule.onNodeWithText(str(R.string.onboarding_privacyAccept)).performClick()
        // Display name is now required — consents alone are not enough.
        composeTestRule.onNodeWithText(continueBtn).performScrollTo().assertIsNotEnabled()

        composeTestRule
            .onNodeWithText(str(R.string.onboarding_displayNameLabel))
            .performScrollTo()
            .performTextInput("Sebbe")
        composeTestRule.onNodeWithText(continueBtn).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, submitted)
    }
}
