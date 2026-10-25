package com.kungsbackacarcommunity.app.notifications

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationSettingsRedesignTest {
    @get:Rule val compose = createComposeRule()

    private fun screen(onToggle: (String, NotificationChannel, Boolean) -> Unit) {
        compose.setContent {
            KccTheme {
                NotificationSettingsScreen(
                    state = NotificationSettingsState.Loaded(NotificationPreferences.ALL_ENABLED),
                    pushPermission = PushPermissionStatus.GRANTED,
                    saveStatus = NotificationSettingsSaveStatus.Idle,
                    onToggle = onToggle,
                    onOpenSystemSettings = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun categoryChannels_remainIndependentAndLabelledSwitches() {
        val changes = mutableListOf<Triple<String, NotificationChannel, Boolean>>()
        screen { category, channel, enabled -> changes += Triple(category, channel, enabled) }
        compose
            .onNodeWithTag("notification_direct_message_in_app")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        compose
            .onNodeWithTag("notification_direct_message_push")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        assertEquals(
            listOf(
                Triple("direct_message", NotificationChannel.IN_APP, false),
                Triple("direct_message", NotificationChannel.PUSH, false),
            ),
            changes,
        )
    }

    @Test
    fun essentialChannels_areVisibleLockedOnAndCannotSubmitChanges() {
        var changes = 0
        screen { _, _, _ -> changes++ }
        NotificationCategories.ESSENTIAL.forEach { category ->
            listOf("in_app", "push").forEach { channel ->
                compose
                    .onNodeWithTag("notification_${category}_$channel")
                    .performScrollTo()
                    .assertIsOn()
                    .assertIsNotEnabled()
            }
        }
        assertEquals(0, changes)
    }
}
