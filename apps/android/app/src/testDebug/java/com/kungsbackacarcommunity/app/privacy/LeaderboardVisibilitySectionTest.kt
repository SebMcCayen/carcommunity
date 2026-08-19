package com.kungsbackacarcommunity.app.privacy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
 * Compose UI tests for the leaderboard-visibility opt-out (Leaderboard PR4).
 * Mirrors PartnerStatsScreenTest — the switch models "shown"; onSave receives
 * that shown value (the caller persists the inverse leaderboardOptOut).
 */
@RunWith(AndroidJUnit4::class)
class LeaderboardVisibilitySectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun reflectsOptedOut_andSavesToggledBackToShown() {
        // Stored optOut = true → the "shown" switch renders OFF; toggling it ON and
        // saving reports shown = true (caller persists leaderboardOptOut = false).
        var savedShown: Boolean? = null
        composeTestRule.setContent {
            KccTheme {
                LeaderboardVisibilitySection(
                    visibility = LeaderboardVisibilityState.Chosen(optOut = true),
                    saveStatus = LeaderboardVisibilitySaveStatus.Idle,
                    onSave = { savedShown = it },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.privacySettings_leaderboardBody)).assertIsDisplayed()
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOff().performClick()
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOn()
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saveButton)).performClick()
        assertEquals(true, savedShown)
    }

    @Test
    fun defaultsShown_whenNoExplicitChoice() {
        // Default-shown / opt-out: no explicit choice renders the "shown" toggle ON,
        // and Save persists that shown choice (leaderboardOptOut = false).
        var savedShown: Boolean? = null
        composeTestRule.setContent {
            KccTheme {
                LeaderboardVisibilitySection(
                    visibility = LeaderboardVisibilityState.DefaultShown,
                    saveStatus = LeaderboardVisibilitySaveStatus.Idle,
                    onSave = { savedShown = it },
                )
            }
        }
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOn()
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saveButton)).performClick()
        assertEquals(true, savedShown)
    }

    @Test
    fun unknownState_disablesSave_soAReadErrorCannotOverwrite() {
        // Unknown = still loading or a read error. The toggle shows the default-shown
        // value, but Save must be DISABLED so a transient error can never persist the
        // default over a member who has explicitly opted out.
        var savedShown: Boolean? = null
        composeTestRule.setContent {
            KccTheme {
                LeaderboardVisibilitySection(
                    visibility = LeaderboardVisibilityState.Unknown,
                    saveStatus = LeaderboardVisibilitySaveStatus.Idle,
                    onSave = { savedShown = it },
                )
            }
        }
        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOn()
        composeTestRule
            .onNodeWithText(str(R.string.privacySettings_saveButton))
            .assertIsNotEnabled()
        assertEquals(null, savedShown)
    }

    @Test
    fun savedStatus_showsConfirmation() {
        composeTestRule.setContent {
            KccTheme {
                LeaderboardVisibilitySection(
                    visibility = LeaderboardVisibilityState.Chosen(optOut = false),
                    saveStatus = LeaderboardVisibilitySaveStatus.Saved,
                    onSave = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.privacySettings_saved)).assertIsDisplayed()
    }
}
