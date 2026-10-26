package com.kungsbackacarcommunity.app.notifications

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h900dp")
class NotificationSettingsRedesignTest {
    @get:Rule val compose = createComposeRule()

    private fun screen(
        fontScale: Float = 1f,
        onToggle: (String, NotificationChannel, Boolean) -> Unit,
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale)
            ) {
                KccTheme {
                    Box(Modifier.width(if (fontScale > 1f) 320.dp else 420.dp)) {
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
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
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

    @Test
    fun largeText_everyChannelRemainsReachable_andLastEssentialStaysLocked() {
        screen(fontScale = 2f) { _, _, _ -> }
        NotificationCategories.ACTIVE.forEach { category ->
            listOf("in_app", "push").forEach { channel ->
                compose.onNodeWithTag("notification_${category}_$channel")
                    .performScrollTo().assertIsDisplayed()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            }
        }
        compose.onNodeWithTag("notification_${NotificationCategories.ESSENTIAL.last()}_push")
            .performScrollTo().assertIsOn().assertIsNotEnabled()
    }
}
