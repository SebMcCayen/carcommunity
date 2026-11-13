package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsThemeSemanticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun appearanceIsRemovedAndLanguageRemainsAvailable() {
        compose.setContent {
            KccTheme {
                SettingsScreen(onManageSubscription = null, onSavedPlaces = {},
                    onNotificationSettings = null, onBlockedUsers = null, onPartnerStats = null,
                    onFeedback = null, onDeleteAccount = null, onWhatsNew = {})
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.settingsMenu_appearanceSection)).assertDoesNotExist()
        listOf("system", "automatic", "light", "dark").forEach {
            compose.onNodeWithTag("settings_theme_$it").assertDoesNotExist()
        }
        compose.onNodeWithTag(SETTINGS_LANGUAGE_SWEDISH_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SETTINGS_LANGUAGE_ENGLISH_TAG).performScrollTo().assertIsDisplayed()
    }
}
