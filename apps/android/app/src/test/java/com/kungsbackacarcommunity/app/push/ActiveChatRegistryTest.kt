package com.kungsbackacarcommunity.app.push

import com.kungsbackacarcommunity.app.notifications.PushPermissionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveChatRegistryTest {

    @After
    fun tearDown() {
        // Process-scoped singleton — reset so tests can't leak into each other.
        ActiveChatRegistry.clear()
        PushNavigator.clear()
    }

    @Test
    fun `nothing is suppressed when no chat is open`() {
        assertFalse(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.DM, "a")))
        assertFalse(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.COMMUNITY_CHAT)))
    }

    @Test
    fun `an open DM suppresses only that conversation`() {
        ActiveChatRegistry.set(ActiveChat.Dm("alice"))
        assertTrue(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.DM, "alice")))
        // A DM from someone else must still notify.
        assertFalse(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.DM, "bob")))
        assertFalse(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.DM, null)))
    }

    @Test
    fun `an open convoy channel suppresses only that convoy`() {
        ActiveChatRegistry.set(ActiveChat.Convoy("convoy-1"))
        assertTrue(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.CONVOY_CHAT, "convoy-1")))
        assertFalse(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.CONVOY_CHAT, "convoy-2")))
    }

    @Test
    fun `the community channel suppresses community pushes`() {
        ActiveChatRegistry.set(ActiveChat.Community)
        assertTrue(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.COMMUNITY_CHAT)))
    }

    @Test
    fun `non-chat notifications are never suppressed by an open chat`() {
        // A friend request or convoy invite arriving while reading a chat is not
        // the thing being looked at — it still deserves a banner.
        ActiveChatRegistry.set(ActiveChat.Dm("alice"))
        assertFalse(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.FRIENDS, "bob")))
        assertFalse(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.CONVOYS)))
        assertFalse(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.EVENT, "e1")))
        assertFalse(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.NOTIFICATIONS)))
    }

    @Test
    fun `a cross-type push does not suppress`() {
        ActiveChatRegistry.set(ActiveChat.Community)
        assertFalse(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.DM, "alice")))
        assertFalse(ActiveChatRegistry.suppresses(PushDeepLink(PushTarget.CONVOY_CHAT, "c1")))
    }

    @Test
    fun `clearing a stale chat does not wipe the screen that replaced it`() {
        // Compose can compose the incoming screen before disposing the outgoing
        // one; a blind clear on dispose would leave nothing registered.
        val old = ActiveChat.Dm("alice")
        ActiveChatRegistry.set(old)
        ActiveChatRegistry.set(ActiveChat.Dm("bob"))
        ActiveChatRegistry.clear(old)
        assertEquals(ActiveChat.Dm("bob"), ActiveChatRegistry.active.value)
    }

    @Test
    fun `clearing the current chat empties the registry`() {
        val chat = ActiveChat.Convoy("c1")
        ActiveChatRegistry.set(chat)
        ActiveChatRegistry.clear(chat)
        assertNull(ActiveChatRegistry.active.value)
    }
}

class PushNavigatorTest {

    @After
    fun tearDown() {
        PushNavigator.clear()
    }

    @Test
    fun `a published link is consumed exactly once`() {
        val link = PushDeepLink(PushTarget.DM, "alice")
        PushNavigator.publish(link)
        assertEquals(link, PushNavigator.consume())
        // A recomposition must not replay the navigation.
        assertNull(PushNavigator.consume())
    }

    @Test
    fun `consuming an empty navigator is null`() {
        assertNull(PushNavigator.consume())
    }

    @Test
    fun `a newer tap replaces an unconsumed one`() {
        PushNavigator.publish(PushDeepLink(PushTarget.DM, "alice"))
        PushNavigator.publish(PushDeepLink(PushTarget.CONVOY_CHAT, "c1"))
        assertEquals(PushDeepLink(PushTarget.CONVOY_CHAT, "c1"), PushNavigator.consume())
    }

    @Test
    fun `clear drops a pending link so sign-out cannot navigate the next member`() {
        PushNavigator.publish(PushDeepLink(PushTarget.DM, "alice"))
        PushNavigator.clear()
        assertNull(PushNavigator.consume())
    }
}

class PushPermissionGateTest {

    @Test
    fun `asks once when the permission is required and not yet granted`() {
        assertTrue(
            PushPermissionGate.shouldRequest(
                runtimePermissionRequired = true,
                status = PushPermissionStatus.DENIED,
                alreadyAsked = false,
            ),
        )
    }

    @Test
    fun `never asks twice`() {
        // Android 13+ stops showing the dialog after repeated refusals; asking
        // again would be a no-op that we'd wrongly treat as a fresh prompt.
        assertFalse(
            PushPermissionGate.shouldRequest(
                runtimePermissionRequired = true,
                status = PushPermissionStatus.DENIED,
                alreadyAsked = true,
            ),
        )
    }

    @Test
    fun `does not ask when already granted`() {
        assertFalse(
            PushPermissionGate.shouldRequest(
                runtimePermissionRequired = true,
                status = PushPermissionStatus.GRANTED,
                alreadyAsked = false,
            ),
        )
    }

    @Test
    fun `does not ask below API 33 where there is no runtime permission`() {
        assertFalse(
            PushPermissionGate.shouldRequest(
                runtimePermissionRequired = false,
                status = PushPermissionStatus.DENIED,
                alreadyAsked = false,
            ),
        )
    }
}
