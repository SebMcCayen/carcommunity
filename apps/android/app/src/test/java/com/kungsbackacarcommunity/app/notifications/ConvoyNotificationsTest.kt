package com.kungsbackacarcommunity.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things that were wrong on the device, pinned as pure logic:
 *  - a convoy-invite row resolved to NO destination, so tapping it did nothing;
 *  - a row kept looking actionable after its convoy had ended.
 */
class ConvoyNotificationsTest {
    private fun item(
        category: NotificationCategory,
        relatedEntityId: String? = null,
        isRead: Boolean = false,
    ) = AppNotification(
        id = "n1",
        category = category,
        title = "Konvoj-inbjudan",
        previewText = "Anna har bjudit in dig till en konvoj.",
        body = null,
        isRead = isRead,
        createdAtMillis = 1_000L,
        actionType = NotificationActionType.OPEN_NOTIFICATIONS,
        relatedEntityId = relatedEntityId,
    )

    private val liveInvite = mapOf("c1" to ConvoyFacts(ended = false, inviteOpen = true))
    private val answered = mapOf("c1" to ConvoyFacts(ended = false, inviteOpen = false))
    private val ended = mapOf("c1" to ConvoyFacts(ended = true, inviteOpen = false))

    // --- convoy id extraction ------------------------------------------

    @Test
    fun `takes the convoy id from a convoy invite`() {
        assertEquals(
            "c1",
            ConvoyNotifications.convoyId(item(NotificationCategory.CONVOY_INVITE, "c1")),
        )
    }

    @Test
    fun `blank and missing ids are not convoy ids`() {
        assertEquals(
            null,
            ConvoyNotifications.convoyId(item(NotificationCategory.CONVOY_INVITE, "  ")),
        )
        assertEquals(
            null,
            ConvoyNotifications.convoyId(item(NotificationCategory.CONVOY_INVITE, null)),
        )
    }

    @Test
    fun `a non-convoy category never yields a convoy id`() {
        // friend_request's relatedEntityId is a UID, not a convoy.
        assertEquals(
            null,
            ConvoyNotifications.convoyId(item(NotificationCategory.FRIEND_REQUEST, "some-uid")),
        )
    }

    // --- row state -----------------------------------------------------

    @Test
    fun `an open invite on a live convoy is actionable`() {
        assertEquals(
            ConvoyRowState.INVITE_OPEN,
            ConvoyNotifications.rowState(
                item(NotificationCategory.CONVOY_INVITE, "c1"),
                liveInvite,
            ),
        )
    }

    @Test
    fun `an invite already accepted or declined is not actionable`() {
        val state =
            ConvoyNotifications.rowState(item(NotificationCategory.CONVOY_INVITE, "c1"), answered)
        assertEquals(ConvoyRowState.INVITE_ANSWERED, state)
        assertFalse(state.isDead)
    }

    @Test
    fun `an ended convoy makes the row dead`() {
        val state =
            ConvoyNotifications.rowState(item(NotificationCategory.CONVOY_INVITE, "c1"), ended)
        assertEquals(ConvoyRowState.ENDED, state)
        assertTrue(state.isDead)
    }

    @Test
    fun `an ended convoy also kills its chat notification`() {
        assertEquals(
            ConvoyRowState.ENDED,
            ConvoyNotifications.rowState(item(NotificationCategory.CONVOY_CHAT, "c1"), ended),
        )
    }

    @Test
    fun `a live convoy chat row is never reported as an invite`() {
        // There is no invite on a chat notification, so it must not borrow the
        // invite states even when the viewer's own invite is open.
        assertEquals(
            ConvoyRowState.UNRESOLVED,
            ConvoyNotifications.rowState(item(NotificationCategory.CONVOY_CHAT, "c1"), liveInvite),
        )
    }

    @Test
    fun `an unknown convoy is UNRESOLVED, never ENDED`() {
        // The regression that must not happen: no facts must never be read as
        // "this convoy is over" and strike through a live invite.
        val state =
            ConvoyNotifications.rowState(
                item(NotificationCategory.CONVOY_INVITE, "not-in-the-list"),
                liveInvite,
            )
        assertEquals(ConvoyRowState.UNRESOLVED, state)
        assertFalse(state.isDead)
    }

    @Test
    fun `an empty facts map leaves every convoy row unresolved`() {
        assertEquals(
            ConvoyRowState.UNRESOLVED,
            ConvoyNotifications.rowState(
                item(NotificationCategory.CONVOY_INVITE, "c1"),
                emptyMap(),
            ),
        )
    }

    @Test
    fun `a non-convoy row has no convoy state`() {
        assertEquals(
            ConvoyRowState.NOT_CONVOY,
            ConvoyNotifications.rowState(
                item(NotificationCategory.EVENT_REMINDER, "e1"),
                liveInvite,
            ),
        )
    }

    // --- tap action (the "tapping does nothing" bug) --------------------

    @Test
    fun `a convoy invite with an id opens that invite`() {
        val action =
            ConvoyNotifications.tapAction(item(NotificationCategory.CONVOY_INVITE, "c1"), liveInvite)
        assertEquals(NotificationTapAction.OpenConvoyInvite("c1"), action)
        assertTrue(ConvoyNotifications.navigates(action))
    }

    @Test
    fun `a convoy invite with no id falls back to the convoy list`() {
        // The brief's explicit requirement: degrade to the list rather than do
        // nothing silently.
        val action =
            ConvoyNotifications.tapAction(item(NotificationCategory.CONVOY_INVITE, null), emptyMap())
        assertEquals(NotificationTapAction.OpenConvoyInvite(null), action)
        assertTrue(ConvoyNotifications.navigates(action))
    }

    @Test
    fun `a blank id is treated as no id, not as a convoy called space`() {
        assertEquals(
            NotificationTapAction.OpenConvoyInvite(null),
            ConvoyNotifications.tapAction(item(NotificationCategory.CONVOY_INVITE, "   "), emptyMap()),
        )
    }

    @Test
    fun `an unresolved invite still navigates`() {
        val action =
            ConvoyNotifications.tapAction(
                item(NotificationCategory.CONVOY_INVITE, "c1"),
                emptyMap(),
            )
        assertEquals(NotificationTapAction.OpenConvoyInvite("c1"), action)
    }

    @Test
    fun `an already answered invite still opens the convoy list`() {
        assertEquals(
            NotificationTapAction.OpenConvoyInvite("c1"),
            ConvoyNotifications.tapAction(item(NotificationCategory.CONVOY_INVITE, "c1"), answered),
        )
    }

    @Test
    fun `an ended convoy navigates nowhere`() {
        val action =
            ConvoyNotifications.tapAction(item(NotificationCategory.CONVOY_INVITE, "c1"), ended)
        assertEquals(NotificationTapAction.ConvoyEnded, action)
        assertFalse(ConvoyNotifications.navigates(action))
    }

    @Test
    fun `other categories keep their plain non-navigating row`() {
        for (category in
            listOf(
                NotificationCategory.EVENT_REMINDER,
                NotificationCategory.FRIEND_REQUEST,
                NotificationCategory.DIRECT_MESSAGE,
                NotificationCategory.ADMIN_MESSAGE,
                NotificationCategory.SYSTEM_NOTICE,
                NotificationCategory.SUBSCRIPTION_STATUS,
            )) {
            val action = ConvoyNotifications.tapAction(item(category, "x"), liveInvite)
            assertEquals(
                "$category should not navigate from the inbox",
                NotificationTapAction.None,
                action,
            )
            assertFalse(ConvoyNotifications.navigates(action))
        }
    }

    @Test
    fun `a convoy chat row resolves but does not navigate`() {
        val action =
            ConvoyNotifications.tapAction(item(NotificationCategory.CONVOY_CHAT, "c1"), liveInvite)
        assertEquals(NotificationTapAction.None, action)
        assertFalse(ConvoyNotifications.navigates(action))
    }

    @Test
    fun `read state does not change where a row goes`() {
        // Tapping marks read as it always did; opening is added to that, not
        // conditional on it — a row already read must still be openable.
        assertEquals(
            ConvoyNotifications.tapAction(
                item(NotificationCategory.CONVOY_INVITE, "c1", isRead = false),
                liveInvite,
            ),
            ConvoyNotifications.tapAction(
                item(NotificationCategory.CONVOY_INVITE, "c1", isRead = true),
                liveInvite,
            ),
        )
    }
}
