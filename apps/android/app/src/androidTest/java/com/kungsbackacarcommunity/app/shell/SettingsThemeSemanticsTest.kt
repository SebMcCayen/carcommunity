package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.design.LocalThemeController
import com.kungsbackacarcommunity.app.design.ThemeController
import com.kungsbackacarcommunity.app.design.ThemePreference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TalkBack accessibility of the Appearance single-choice set.
 *
 * Each [ThemeOptionRow] is built on the selectable `Surface` overload
 * (`selected = …, onClick = …`), which applies `Modifier.selectable` — that
 * modifier's node ALWAYS publishes [androidx.compose.ui.semantics.SemanticsProperties.Selected]
 * on the row, alongside the explicit `role = Role.RadioButton`. So the row
 * announces both its role AND its checked state without any extra semantics;
 * the child `RadioButton(onClick = null)` is decoration whose merged semantics
 * add nothing the row does not already expose.
 *
 * This pins that invariant: exactly the chosen row reports `selected`, and the
 * other two report not-selected. It guards against a refactor that swaps the
 * selectable Surface for a plain clickable one (which would drop `Selected` and
 * leave TalkBack announcing the role with no state).
 */
@RunWith(AndroidJUnit4::class)
class SettingsThemeSemanticsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private class FakeThemeController(override val preference: ThemePreference) : ThemeController {
        override fun setPreference(preference: ThemePreference) = Unit
    }

    private fun renderSettings(selected: ThemePreference) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalThemeController provides FakeThemeController(selected)) {
                KccTheme(darkTheme = false) {
                    SettingsScreen(
                        onManageSubscription = null,
                        onNotificationSettings = null,
                        onBlockedUsers = null,
                        onPartnerStats = null,
                        onFeedback = null,
                        onDeleteAccount = null,
                        onWhatsNew = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun theChosenRowIsAnnouncedSelectedAndTheOthersAreNot() {
        renderSettings(ThemePreference.LIGHT)

        composeTestRule.onNodeWithTag(SETTINGS_THEME_LIGHT_TAG).assertIsSelected()
        composeTestRule.onNodeWithTag(SETTINGS_THEME_AUTOMATIC_TAG).assertIsNotSelected()
        composeTestRule.onNodeWithTag(SETTINGS_THEME_DARK_TAG).assertIsNotSelected()
    }

    @Test
    fun theSelectedStateFollowsThePreference() {
        renderSettings(ThemePreference.DARK)

        composeTestRule.onNodeWithTag(SETTINGS_THEME_DARK_TAG).assertIsSelected()
        composeTestRule.onNodeWithTag(SETTINGS_THEME_AUTOMATIC_TAG).assertIsNotSelected()
        composeTestRule.onNodeWithTag(SETTINGS_THEME_LIGHT_TAG).assertIsNotSelected()
    }
}
