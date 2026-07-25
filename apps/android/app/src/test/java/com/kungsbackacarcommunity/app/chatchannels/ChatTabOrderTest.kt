package com.kungsbackacarcommunity.app.chatchannels

import com.kungsbackacarcommunity.app.push.PushTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the invariants the chat-hub swipe pager relies on:
 *
 * 1. [ChatTab.ordinal] is the pager page index, so the enum's declaration order IS
 *    the left-to-right swipe order (and must match the tab row's build order).
 *    Reordering the enum silently reorders the swipe and the tabs together — this
 *    test makes that a deliberate, visible change.
 * 2. `ChatTab.entries[tab.ordinal] == tab` round-trips, since the pager converts a
 *    page index back to a tab that way in the swipe→tab sync.
 * 3. [chatHubLandingTab] maps a push deep-link's target to the right landing tab,
 *    degrading anything the hub does not host (and a null link) to Community.
 */
class ChatTabOrderTest {

    @Test
    fun tabOrder_isCommunityConvoysFriendsNotifications() {
        assertEquals(
            listOf(
                ChatTab.Community,
                ChatTab.Convoys,
                ChatTab.Friends,
                ChatTab.Notifications,
            ),
            ChatTab.entries,
        )
    }

    @Test
    fun ordinal_roundTripsThroughEntries() {
        ChatTab.entries.forEach { tab ->
            assertEquals(tab, ChatTab.entries[tab.ordinal])
        }
    }

    @Test
    fun landingTab_mapsEachHostedTarget() {
        assertEquals(ChatTab.Community, chatHubLandingTab(PushTarget.COMMUNITY_CHAT))
        assertEquals(ChatTab.Convoys, chatHubLandingTab(PushTarget.CONVOY_CHAT))
        assertEquals(ChatTab.Friends, chatHubLandingTab(PushTarget.FRIENDS))
        assertEquals(ChatTab.Notifications, chatHubLandingTab(PushTarget.NOTIFICATIONS))
    }

    @Test
    fun landingTab_unhostedOrNullTargetDegradesToCommunity() {
        assertEquals(ChatTab.Community, chatHubLandingTab(null))
        // Targets the hub does not host as tabs must not jump the hub off its
        // default section.
        assertEquals(ChatTab.Community, chatHubLandingTab(PushTarget.DM))
        assertEquals(ChatTab.Community, chatHubLandingTab(PushTarget.CONVOYS))
        assertEquals(ChatTab.Community, chatHubLandingTab(PushTarget.EVENT))
        assertEquals(ChatTab.Community, chatHubLandingTab(PushTarget.SUBSCRIPTION))
    }
}
