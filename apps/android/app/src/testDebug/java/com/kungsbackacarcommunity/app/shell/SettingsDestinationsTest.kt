package com.kungsbackacarcommunity.app.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.design.LocalThemeController
import com.kungsbackacarcommunity.app.design.ThemeController
import com.kungsbackacarcommunity.app.design.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDestinationsTest {
    @get:Rule val compose = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun render(available: Boolean, onDestination: (String) -> Unit) {
        compose.setContent {
            val controller = object : ThemeController {
                override val preference = ThemePreference.SYSTEM
                override fun setPreference(preference: ThemePreference) = Unit
            }
            CompositionLocalProvider(LocalThemeController provides controller) {
                KccTheme {
                    SettingsScreen(
                        onManageSubscription = if (available) ({ onDestination("membership") }) else null,
                        onSavedPlaces = { onDestination("places") },
                        onNotificationSettings = if (available) ({ onDestination("notifications") }) else null,
                        onBlockedUsers = if (available) ({ onDestination("blocking") }) else null,
                        onPartnerStats = if (available) ({ onDestination("privacy") }) else null,
                        onFeedback = if (available) ({ onDestination("feedback") }) else null,
                        onDeleteAccount = if (available) ({ onDestination("delete") }) else null,
                        onWhatsNew = { onDestination("updates") },
                    )
                }
            }
        }
    }

    @Test
    fun unavailableDestinations_areHidden_andLocalDestinationsRemainReachable() {
        val calls = mutableListOf<String>()
        render(false) { calls += it }
        listOf(
            R.string.settingsMenu_manageSubscription,
            R.string.settingsMenu_notificationSettings,
            R.string.settings_blockedUsers,
            R.string.settingsMenu_partnerStats,
            R.string.settingsMenu_feedback,
            R.string.settingsMenu_deleteAccount,
        ).forEach {
            compose.onNodeWithText(str(it)).assertDoesNotExist()
        }
        compose.onNodeWithText(str(R.string.settingsMenu_savedPlaces)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.settingsMenu_whatsNew)).performScrollTo()
            .assertIsDisplayed().performClick()
        assertEquals(listOf("places", "updates"), calls)
    }

    @Test
    fun availableDestinations_preserveTheirCallbacksThroughScrolling() {
        val calls = mutableListOf<String>()
        render(true) { calls += it }
        listOf(
            R.string.settingsMenu_manageSubscription,
            R.string.settingsMenu_notificationSettings,
            R.string.settings_blockedUsers,
            R.string.settingsMenu_partnerStats,
            R.string.settingsMenu_feedback,
            R.string.settingsMenu_deleteAccount,
        ).forEach { compose.onNodeWithText(str(it)).performScrollTo().performClick() }
        assertEquals(listOf("membership", "notifications", "blocking", "privacy", "feedback", "delete"), calls)
    }
}
