package com.kungsbackacarcommunity.app.chatchannels

import com.kungsbackacarcommunity.app.dm.DmConversation
import com.kungsbackacarcommunity.app.dm.DmConversationsState
import com.kungsbackacarcommunity.app.dm.DmUser
import com.kungsbackacarcommunity.app.dm.anyUnread as dmAnyUnread
import com.kungsbackacarcommunity.app.notifications.AppNotification
import com.kungsbackacarcommunity.app.notifications.NotificationCategory
import com.kungsbackacarcommunity.app.notifications.Notifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-type "unread" derivations behind the red-dot indicators: the map chat
 * bubble's aggregate dot and the Friends / Notifications tab dots. Friends
 * reduces its inbox snapshot to a boolean; Notifications compares the newest
 * item's instant against the caller's last-SEEN marker (the exact mirror of the
 * community chat dot — clearing on open without marking rows read). These are the
 * pure reductions; the wiring that OR-s them for the bubble lives in
 * AuthenticatedApp.
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

    private fun notification(id: String, createdAtMillis: Long?) =
        AppNotification(
            id = id,
            category = NotificationCategory.SYSTEM_NOTICE,
            title = "Title $id",
            previewText = null,
            body = null,
            isRead = false,
            createdAtMillis = createdAtMillis,
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

    // --- Notifications inbox (last-seen-marker dot) ---

    @Test
    fun notificationNewerThanMarkerIsUnread() {
        // Newest item post-dates the last-seen marker -> the dot lights.
        assertTrue(Notifications.hasUnread(newestCreatedAtMillis = 2_000L, lastSeenAtMillis = 1_000L))
    }

    @Test
    fun notificationOlderThanOrEqualToMarkerIsNotUnread() {
        // Opened AFTER (or exactly when) the newest arrived -> the dot clears.
        assertFalse(Notifications.hasUnread(newestCreatedAtMillis = 1_000L, lastSeenAtMillis = 2_000L))
        assertFalse(Notifications.hasUnread(newestCreatedAtMillis = 1_000L, lastSeenAtMillis = 1_000L))
    }

    @Test
    fun notificationWithNoMarkerIsUnreadWhenAnItemExists() {
        // Never opened the inbox -> any notification is unseen.
        assertTrue(Notifications.hasUnread(newestCreatedAtMillis = 1_000L, lastSeenAtMillis = null))
    }

    @Test
    fun notificationEmptyInboxIsNotUnread() {
        // No newest instant at all (empty inbox, or none with a parseable createdAt).
        assertFalse(Notifications.hasUnread(newestCreatedAtMillis = null, lastSeenAtMillis = null))
        assertFalse(Notifications.hasUnread(newestCreatedAtMillis = null, lastSeenAtMillis = 1_000L))
    }

    @Test
    fun newestCreatedAtIsTheMaxIndependentOfOrder() {
        // Independent of client sort order, and items without a createdAt are skipped.
        assertEquals(
            3_000L,
            Notifications.newestCreatedAtMillis(
                listOf(
                    notification("a", createdAtMillis = 1_000L),
                    notification("b", createdAtMillis = 3_000L),
                    notification("c", createdAtMillis = null),
                    notification("d", createdAtMillis = 2_000L),
                ),
            ),
        )
        assertEquals(null, Notifications.newestCreatedAtMillis(emptyList()))
        assertEquals(
            null,
            Notifications.newestCreatedAtMillis(listOf(notification("x", createdAtMillis = null))),
        )
    }
}
