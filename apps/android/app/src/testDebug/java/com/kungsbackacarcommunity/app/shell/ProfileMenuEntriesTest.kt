package com.kungsbackacarcommunity.app.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileMenuEntriesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accountOrderAndActionsIncludeStandaloneInbox() {
        var entries: List<HubEntry> = emptyList()
        val opened = mutableListOf<ShellRoute>()
        var signedOut = 0
        compose.setContent {
            KccTheme {
                entries = profileMenuEntries(true, true, true, true, true,
                    onOpenRoute = { opened += it }, onSignOut = { signedOut++ })
            }
        }
        compose.runOnIdle {
            val routes = listOf(ShellRoute.Profile, ShellRoute.Friends, ShellRoute.MyDrives,
                ShellRoute.PartnerApplication, ShellRoute.Notifications, ShellRoute.Settings)
            assertEquals(routes.map { it.name } + "sign_out", entries.map { it.id })
            assertEquals(listOf(ShellRoute.Notifications.name), entries.filter { it.hasUnread }.map { it.id })
            entries.forEach { it.onClick!!.invoke() }
            assertEquals(routes, opened)
            assertEquals(1, signedOut)
            val withPlaces = withSavedPlacesEntry(entries, HubEntry("Saved places", Icons.Filled.Place, {}, id = "saved_places"))
            assertEquals(listOf("Profile", "Friends", "MyDrives", "saved_places", "PartnerApplication", "Notifications", "Settings", "sign_out"), withPlaces.map { it.id })
        }
    }

    @Test fun unavailableDestinationsDoNotGetAnUnreadOrClickableEntry() {
        var entries: List<HubEntry> = emptyList()
        compose.setContent {
            KccTheme { entries = profileMenuEntries(false, false, false, false, true, {}, {}) }
        }
        compose.runOnIdle {
            assertEquals(listOf("MyDrives", "Settings", "sign_out"), entries.filter { it.onClick != null }.map { it.id })
            assertFalse(entries.any { it.hasUnread })
        }
    }
}
