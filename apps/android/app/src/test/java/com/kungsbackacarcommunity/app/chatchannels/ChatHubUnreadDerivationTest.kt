package com.kungsbackacarcommunity.app.chatchannels

import com.kungsbackacarcommunity.app.dm.DmConversation
import com.kungsbackacarcommunity.app.dm.DmConversationsState
import com.kungsbackacarcommunity.app.dm.DmUser
import com.kungsbackacarcommunity.app.dm.anyUnread as dmAnyUnread
import com.kungsbackacarcommunity.app.notifications.AppNotification
import com.kungsbackacarcommunity.app.notifications.NotificationCategory
import com.kungsbackacarcommunity.app.notifications.NotificationsState
import com.kungsbackacarcommunity.app.notifications.anyUnread as notificationsAnyUnread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-type "any unread" derivations behind the red-dot indicators: the map
 * chat bubble's aggregate dot and the Friends / Notifications tab dots all reduce
 * a section's existing listener snapshot to a single boolean. These are the pure
 * reductions; the wiring that OR-s them for the bubble lives in AuthenticatedApp.
 */
class ChatHubUnreadDerivationTest {

    private fun dmConversation(unread: Int) =
        DmConversation(
            conversationId = "me__other$unread",
            otherUser = DmUser("other$unread", "Other", null),
            lastMessage = null,
            unreadCount = unread,
            lastMessageAtMillis = 1_000L,
        )

    private fun notification(id: String, read: Boolean) =
        AppNotification(
            id = id,
            category = NotificationCategory.SYSTEM_NOTICE,
            title = "Title $id",
            previewText = null,
            body = null,
            isRead = read,
            createdAtMillis = 1_000L,
        )

    // --- DM inbox ---

    @Test
    fun dmLoadedWithAnUnreadConversationIsUnread() {
        val state = DmConversationsState.Loaded(listOf(dmConversation(0), dmConversation(2)))
        assertTrue(state.dmAnyUnread())
    }

    @Test
    fun dmLoadedAllReadIsNotUnread() {
        val state = DmConversationsState.Loaded(listOf(dmConversation(0), dmConversation(0)))
        assertFalse(state.dmAnyUnread())
    }

    @Test
    fun dmEmptyInboxIsNotUnread() {
        assertFalse(DmConversationsState.Loaded(emptyList()).dmAnyUnread())
    }

    @Test
    fun dmLoadingAndErrorAreNotUnread() {
        // A dot is a positive claim; a not-yet-loaded (or failed) inbox must not
        // light one on a guess.
        assertFalse(DmConversationsState.Loading.dmAnyUnread())
        assertFalse(DmConversationsState.Error("FAILED_PRECONDITION").dmAnyUnread())
    }

    // --- Notifications inbox ---

    @Test
    fun notificationsLoadedWithAnUnreadItemIsUnread() {
        val state =
            NotificationsState.Loaded(
                listOf(notification("a", read = true), notification("b", read = false)),
            )
        assertTrue(state.notificationsAnyUnread())
    }

    @Test
    fun notificationsLoadedAllReadIsNotUnread() {
        val state =
            NotificationsState.Loaded(
                listOf(notification("a", read = true), notification("b", read = true)),
            )
        assertFalse(state.notificationsAnyUnread())
    }

    @Test
    fun notificationsEmptyInboxIsNotUnread() {
        assertFalse(NotificationsState.Loaded(emptyList()).notificationsAnyUnread())
    }

    @Test
    fun notificationsLoadingAndErrorAreNotUnread() {
        assertFalse(NotificationsState.Loading.notificationsAnyUnread())
        assertFalse(NotificationsState.Error.notificationsAnyUnread())
    }
}
