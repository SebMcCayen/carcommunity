package com.kungsbackacarcommunity.app.redesign

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kungsbackacarcommunity.app.design.LocalThemeController
import com.kungsbackacarcommunity.app.design.ThemeController
import com.kungsbackacarcommunity.app.design.ThemePreference
import com.kungsbackacarcommunity.app.shell.SettingsScreen
import com.kungsbackacarcommunity.app.subscription.PurchaseFlowStatus
import com.kungsbackacarcommunity.app.subscription.SubscriptionScreen

/**
 * Screenshot entry points for MAIN's themed emulator harness. These are the production screens,
 * with local state and inert navigation/billing callbacks. No route, Firebase initializer, billing
 * client, API call or remote asset is used. MAIN owns the visible fixture caption, theme, locale
 * and capture dimensions.
 */
@Composable
fun Wave2SettingsPreview(screen: String) {
    when (screen) {
        "settings" -> {
            var selectedTheme by remember { mutableStateOf(ThemePreference.SYSTEM) }
            val controller =
                object : ThemeController {
                    override val preference: ThemePreference = selectedTheme

                    override fun setPreference(preference: ThemePreference) {
                        // The preference lives only in this test composition.
                        selectedTheme = preference
                    }
                }
            CompositionLocalProvider(LocalThemeController provides controller) {
                SettingsScreen(
                    onManageSubscription = {},
                    onSavedPlaces = {},
                    onNotificationSettings = {},
                    onBlockedUsers = {},
                    onPartnerStats = {},
                    onFeedback = {},
                    onDeleteAccount = {},
                    onWhatsNew = {},
                )
            }
        }
        "membership" ->
            SubscriptionScreen(
                isActiveMember = false,
                currentTier = null,
                status = PurchaseFlowStatus.Idle,
                canSubscribe = true,
                canChangePlan = false,
                canManageSubscription = false,
                onSubscribe = {},
                onManageSubscription = {},
                onRestore = {},
                onBack = {},
            )
        else -> error("Unknown settings screenshot case: $screen")
    }
}
