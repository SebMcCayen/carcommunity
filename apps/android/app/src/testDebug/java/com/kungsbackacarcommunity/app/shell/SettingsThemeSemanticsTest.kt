package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
import org.robolectric.annotation.Config

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
 * other three report not-selected. It guards against a refactor that swaps the
 * selectable Surface for a plain clickable one (which would drop `Selected` and
 * leave TalkBack announcing the role with no state).
 */
@RunWith(AndroidJUnit4::class)
class SettingsThemeSemanticsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private class FakeThemeController(initial: ThemePreference) : ThemeController {
        val state = mutableStateOf(initial)
        override val preference get() = state.value
        override fun setPreference(preference: ThemePreference) { state.value = preference }
    }

    private fun renderSettings(selected: ThemePreference, fontScale: Float = 1f) {
        val controller = FakeThemeController(selected)
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalThemeController provides controller,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                KccTheme(darkTheme = false) {
                    SettingsScreen(
                        onManageSubscription = null,
                        onSavedPlaces = {},
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
    @Config(qualifiers = "sv-rSE-w320dp-h640dp")
    fun theChosenRowIsAnnouncedSelectedAndTheOthersAreNot() {
        renderSettings(ThemePreference.LIGHT)
        composeTestRule.onNodeWithTag(SETTINGS_THEME_AUTOMATIC_TAG).assertTextContains(
            "Ljust 07:00–19:00, annars mörkt. Lokal tid, oavsett telefonens tema.",
        )

        composeTestRule.onNodeWithTag(SETTINGS_THEME_LIGHT_TAG).assertIsSelected()
        composeTestRule.onNodeWithTag(SETTINGS_THEME_AUTOMATIC_TAG).assertIsNotSelected()
        composeTestRule.onNodeWithTag(SETTINGS_THEME_SYSTEM_TAG).assertIsNotSelected()
        composeTestRule.onNodeWithTag(SETTINGS_THEME_DARK_TAG).assertIsNotSelected()
    }

    @Test
    fun theSelectedStateFollowsThePreference() {
        renderSettings(ThemePreference.DARK)

        composeTestRule.onNodeWithTag(SETTINGS_THEME_DARK_TAG).assertIsSelected()
        composeTestRule.onNodeWithTag(SETTINGS_THEME_AUTOMATIC_TAG).assertIsNotSelected()
        composeTestRule.onNodeWithTag(SETTINGS_THEME_SYSTEM_TAG).assertIsNotSelected()
        composeTestRule.onNodeWithTag(SETTINGS_THEME_LIGHT_TAG).assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun fourChoicesAreAccessibleAndSelectionUpdatesAtLargeFontSize() {
        renderSettings(ThemePreference.SYSTEM, fontScale = 2f)
        composeTestRule.onNodeWithTag(SETTINGS_THEME_AUTOMATIC_TAG).assertTextContains(
            "Light 07:00–19:00, dark otherwise. Local time, independent of your phone’s theme.",
        )
        val tags = listOf(SETTINGS_THEME_SYSTEM_TAG, SETTINGS_THEME_AUTOMATIC_TAG,
            SETTINGS_THEME_LIGHT_TAG, SETTINGS_THEME_DARK_TAG)
        tags.forEach { selected ->
            composeTestRule.onNodeWithTag(selected)
                .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .performClick().assertIsSelected()
            tags.filter { it != selected }.forEach { other ->
                composeTestRule.onNodeWithTag(other).assertIsNotSelected()
            }
        }
    }

}
