package com.kungsbackacarcommunity.app.privacy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
 * Compose UI tests for the partner-stats opt-in (Phase 12 slice 19).
 */
@RunWith(AndroidJUnit4::class)
class PartnerStatsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun reflectsCurrentValue_andSavesToggled() {
        var saved: Boolean? = null
        composeTestRule.setContent {
            KccTheme {
                PartnerStatsScreen(
                    currentOptIn = false,
                    saveStatus = PartnerStatsSaveStatus.Idle,
                    onSave = { saved = it },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.privacySettings_partnerStatsBody)).assertIsDisplayed()
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOff().performClick()
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOn()
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saveButton)).performScrollTo().performClick()
        assertEquals(true, saved)
    }

    @Test
    fun savedStatus_showsConfirmation() {
        composeTestRule.setContent {
            KccTheme {
                PartnerStatsScreen(
                    currentOptIn = true,
                    saveStatus = PartnerStatsSaveStatus.Saved,
                    onSave = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saved)).assertIsDisplayed()
    }
}
