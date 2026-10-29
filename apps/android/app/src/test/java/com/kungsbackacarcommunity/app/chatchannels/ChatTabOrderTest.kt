package com.kungsbackacarcommunity.app.chatchannels

import com.kungsbackacarcommunity.app.push.PushTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** Visible message destinations and migration of the old inbox tab. */
class ChatTabOrderTest {

    @Test
    fun visibleOrder_isCommunityConvoysFriendsClub() {
        assertEquals(
            listOf(
                ChatTab.Community,
                ChatTab.Convoys,
                ChatTab.Friends,
                ChatTab.Club,
            ),
            ChatTab.visibleEntries,
        )
    }

    @Test
    fun visiblePage_roundTripsAndRetiredInboxFallsBackToCommunity() {
        ChatTab.visibleEntries.forEach { tab ->
            assertEquals(tab, ChatTab.visibleEntries[chatHubPage(tab)])
        }
        assertEquals(0, chatHubPage(ChatTab.Notifications))
        assertEquals(3, chatHubPage(ChatTab.Club))
        assertEquals(ChatTab.Notifications, enumValueOf<ChatTab>("Notifications"))
    }

    @Test
    fun landingTab_mapsEachHostedTarget() {
        assertEquals(ChatTab.Community, chatHubLandingTab(PushTarget.COMMUNITY_CHAT))
        assertEquals(ChatTab.Convoys, chatHubLandingTab(PushTarget.CONVOY_CHAT))
        assertEquals(ChatTab.Friends, chatHubLandingTab(PushTarget.FRIENDS))
        assertEquals(ChatTab.Community, chatHubLandingTab(PushTarget.NOTIFICATIONS))
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
