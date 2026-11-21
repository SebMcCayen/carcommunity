package com.kungsbackacarcommunity.app.privacy

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        composeTestRule.onNodeWithText(str(R.string.privacySettings_partnerStatsNotice)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.privacySettings_partnerStatsBody)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOff().performClick()
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOn()
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saveButton)).performScrollTo().performClick()
        assertEquals(true, saved)
    }

    @Test
    fun defaultsOff_whenNoExplicitConsent() {
        // A server-resolved missing or legacy consent cannot grant optional analytics.
        var saved: Boolean? = null
        composeTestRule.setContent {
            KccTheme {
                PartnerStatsScreen(
                    consent = PartnerStatsConsentState.DefaultOff,
                    saveStatus = PartnerStatsSaveStatus.Idle,
                    onSave = { saved = it },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOff()
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saveButton)).performScrollTo().performClick()
        assertEquals(false, saved)
    }

    @Test
    fun unknownState_disablesSave_soAReadErrorCannotOverwrite() {
        // Cache-only, loading and failed reads must stay unsaveable.
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
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOff().assertIsNotEnabled()
        composeTestRule
            .onNodeWithText(str(R.string.privacySettings_saveButton))
            .performScrollTo()
            .assertIsNotEnabled()
        assertEquals(null, saved)
    }

    @Test
    fun currentConsent_canBeWithdrawnWithExplicitSave() {
        var saved: Boolean? = null
        composeTestRule.setContent {
            KccTheme {
                PartnerStatsScreen(PartnerStatsConsentState.Chosen(true), PartnerStatsSaveStatus.Idle, { saved = it }, {})
            }
        }
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).performScrollTo().assertIsOn().performClick().assertIsOff()
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saveButton)).performScrollTo().performClick()
        assertEquals(false, saved)
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
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saved)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun laterReadError_disablesSaveEvenAfterSavedStatus() {
        var consent by mutableStateOf<PartnerStatsConsentState>(PartnerStatsConsentState.Chosen(true))
        var saved: Boolean? = null
        composeTestRule.setContent {
            KccTheme {
                PartnerStatsScreen(consent, PartnerStatsSaveStatus.Saved, { saved = it }, {})
            }
        }
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOn()
        composeTestRule.runOnIdle { consent = PartnerStatsConsentState.Unknown }
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saveButton)).performScrollTo().assertIsNotEnabled()
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsNotEnabled()
        assertEquals(null, saved)
    }
}
