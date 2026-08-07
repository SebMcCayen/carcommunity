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

/**
 * Robolectric pilot: this Compose UI test runs on the JVM in the fast, blocking
 * `testDebugUnitTest` suite — no emulator. `@RunWith(AndroidJUnit4::class)`
 * delegates to Robolectric's runner off-device, and `createComposeRule()` drives
 * Compose against a deterministic test clock with auto-syncing assertions. SDK
 * level (34) and NATIVE graphics mode come from
 * `src/test/resources/robolectric.properties`.
 *
 * This lives in `src/testDebug` (the debug-only unit-test source set), not
 * `src/test`, on purpose: `createComposeRule()` launches into the
 * ComponentActivity host from `ui-test-manifest`, which is a `debugImplementation`
 * and so only present in the debug variant's merged manifest. Running under
 * `testReleaseUnitTest` would fail to resolve that activity, so the test is
 * scoped to the debug unit-test variant — the convention for future
 * Compose-on-Robolectric tests here.
 *
 * Note on `performScrollTo()`: the onboarding form is a vertically-scrolling
 * Column, and under Robolectric a `performClick` only lands when the target is
 * actually within the viewport — an off-screen node is clipped and the toggle
 * never fires. So every consent row is scrolled into view before it is clicked;
 * without this the checkboxes stay Off and Continue never enables.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun continueIsGatedOnAllThreeConsentsAndADisplayName() {
        var submitted = 0
        var submittedPartnerStats: Boolean? = null
        composeTestRule.setContent {
            KccTheme {
                OnboardingScreen(
                    status = OnboardingStatus.Idle,
                    onSubmit = { _, partnerStats ->
                        submitted++
                        submittedPartnerStats = partnerStats
                    },
                )
            }
        }
        val continueBtn = str(R.string.onboarding_continueButton)
        composeTestRule.onNodeWithText(continueBtn).performScrollTo().assertIsNotEnabled()

        composeTestRule.onNodeWithText(str(R.string.onboarding_licenceConfirm)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.onboarding_termsAccept)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(continueBtn).performScrollTo().assertIsNotEnabled()

        composeTestRule.onNodeWithText(str(R.string.onboarding_privacyAccept)).performScrollTo().performClick()
        // Display name is now required — consents alone are not enough.
        composeTestRule.onNodeWithText(continueBtn).performScrollTo().assertIsNotEnabled()

        composeTestRule
            .onNodeWithText(str(R.string.onboarding_displayNameLabel))
            .performScrollTo()
            .performTextInput("Sebbe")
        composeTestRule.onNodeWithText(continueBtn).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, submitted)
        // Anonymised partner statistics are default-on / opt-out: untouched, the
        // step reports true.
        assertEquals(true, submittedPartnerStats)
    }

    @Test
    fun partnerStatsCanBeOptedOutDuringOnboarding() {
        var submittedPartnerStats: Boolean? = null
        composeTestRule.setContent {
            KccTheme {
                OnboardingScreen(
                    status = OnboardingStatus.Idle,
                    onSubmit = { _, partnerStats -> submittedPartnerStats = partnerStats },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.onboarding_licenceConfirm)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.onboarding_termsAccept)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.onboarding_privacyAccept)).performScrollTo().performClick()
        composeTestRule
            .onNodeWithText(str(R.string.onboarding_displayNameLabel))
            .performScrollTo()
            .performTextInput("Sebbe")
        // Turn the default-on partner-stats toggle OFF.
        composeTestRule
            .onNodeWithText(str(R.string.onboarding_partnerStatsOptIn))
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNodeWithText(str(R.string.onboarding_continueButton))
            .performScrollTo()
            .performClick()
        assertEquals(false, submittedPartnerStats)
    }
}
