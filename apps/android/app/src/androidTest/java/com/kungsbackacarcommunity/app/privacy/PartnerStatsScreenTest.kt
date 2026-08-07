package com.kungsbackacarcommunity.app.privacy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
                    consent = PartnerStatsConsentState.Chosen(false),
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
    fun defaultsOn_whenNoExplicitChoice() {
        // Default-on / opt-out: a null value (no explicit choice / still loading)
        // renders the toggle ON, and Save persists that ON choice.
        var saved: Boolean? = null
        composeTestRule.setContent {
            KccTheme {
                PartnerStatsScreen(
                    consent = PartnerStatsConsentState.DefaultOn,
                    saveStatus = PartnerStatsSaveStatus.Idle,
                    onSave = { saved = it },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOn()
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saveButton)).performScrollTo().performClick()
        assertEquals(true, saved)
    }

    @Test
    fun unknownState_disablesSave_soAReadErrorCannotOverwrite() {
        // Unknown = still loading or a read error. The toggle shows the default-on
        // value, but Save must be DISABLED so a transient error can never persist
        // the default over an explicitly opted-out member's real choice.
        var saved: Boolean? = null
        composeTestRule.setContent {
            KccTheme {
                PartnerStatsScreen(
                    consent = PartnerStatsConsentState.Unknown,
                    saveStatus = PartnerStatsSaveStatus.Idle,
                    onSave = { saved = it },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOn()
        composeTestRule
            .onNodeWithText(str(R.string.privacySettings_saveButton))
            .performScrollTo()
            .assertIsNotEnabled()
        assertEquals(null, saved)
    }

    @Test
    fun savedStatus_showsConfirmation() {
        composeTestRule.setContent {
            KccTheme {
                PartnerStatsScreen(
                    consent = PartnerStatsConsentState.Chosen(true),
                    saveStatus = PartnerStatsSaveStatus.Saved,
                    onSave = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saved)).assertIsDisplayed()
    }
}
