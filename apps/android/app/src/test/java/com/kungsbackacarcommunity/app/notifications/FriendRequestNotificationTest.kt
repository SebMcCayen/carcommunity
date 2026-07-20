package com.kungsbackacarcommunity.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Notifications.pendingFriendRequestId] — which inbox rows may be answered.
 *
 * This is the whole correctness story for accept/decline in the inbox, and it
 * is deliberately pure so the STALE case is testable at all: a request answered
 * on another device leaves no trace on the notification (the inbox item is an
 * immutable record and is never rewritten), so the only honest signal is its
 * absence from the live pending list. Driving that through Firestore would need
 * the emulator and two devices; here it is a map lookup.
 */
class FriendRequestNotificationTest {

    private val requesterUid = "uid-requester"
    private val requestId = "req-abc123"

    /** The notification the backend writes for a NEW incoming request. */
    private fun incomingRequestNotice(
        relatedEntityId: String? = requesterUid,
        actionType: NotificationActionType = NotificationActionType.OPEN_NOTIFICATIONS,
        category: NotificationCategory = NotificationCategory.FRIEND_REQUEST,
    ) = AppNotification(
        id = "n1",
        category = category,
        title = "Ny vänförfrågan",
        previewText = "Gt86_swe vill bli din vän.",
        body = null,
        isRead = false,
        createdAtMillis = 0L,
        actionType = actionType,
        relatedEntityId = relatedEntityId,
    )

    @Test
    fun `a still-pending request resolves to its request id`() {
        assertEquals(
            requestId,
            Notifications.pendingFriendRequestId(
                incomingRequestNotice(),
                mapOf(requesterUid to requestId),
            ),
        )
    }

    @Test
    fun `the resolved id is the REQUEST id, never the requester uid`() {
        // friendRequests ids are a SHA-256 over the ordered uid pair, so the
        // client cannot derive one; passing the uid to friend-respondRequest
        // would fail with not-found for every single request.
        val resolved =
            Notifications.pendingFriendRequestId(
                incomingRequestNotice(),
                mapOf(requesterUid to requestId),
            )
        assertEquals(requestId, resolved)
        assertNotEquals(requesterUid, resolved)
    }

    @Test
    fun `a request already answered elsewhere is not actionable`() {
        // Accepted or declined on the profile screen or another device: gone
        // from the pending list, so the row must offer no buttons at all —
        // rather than showing an Accept that fails when tapped.
        assertNull(
            Notifications.pendingFriendRequestId(incomingRequestNotice(), emptyMap()),
        )
    }

    @Test
    fun `the request-accepted receipt is never actionable`() {
        // Same FRIEND_REQUEST category, opposite meaning: "X accepted your
        // request". Written with open_profile. Critically, this row is tested
        // against a pending map that DOES contain the other party — the state
        // you reach after unfriending and being re-requested — so only the
        // actionType gate can keep the old receipt from growing buttons.
        assertNull(
            Notifications.pendingFriendRequestId(
                incomingRequestNotice(actionType = NotificationActionType.OPEN_PROFILE),
                mapOf(requesterUid to requestId),
            ),
        )
    }

    @Test
    fun `other categories are never actionable`() {
        assertNull(
            Notifications.pendingFriendRequestId(
                incomingRequestNotice(category = NotificationCategory.EVENT_REMINDER),
                mapOf(requesterUid to requestId),
            ),
        )
    }

    @Test
    fun `a missing or blank related entity id is not actionable`() {
        val pending = mapOf(requesterUid to requestId, "" to "req-blank")
        assertNull(Notifications.pendingFriendRequestId(incomingRequestNotice(null), pending))
        assertNull(Notifications.pendingFriendRequestId(incomingRequestNotice("   "), pending))
    }

    @Test
    fun `a different requester's pending request is not borrowed`() {
        assertNull(
            Notifications.pendingFriendRequestId(
                incomingRequestNotice(),
                mapOf("someone-else" to "req-other"),
            ),
        )
    }

    @Test
    fun `action type parses wire values and defaults to none`() {
        assertEquals(
            NotificationActionType.OPEN_NOTIFICATIONS,
            NotificationActionType.fromWire("open_notifications"),
        )
        assertEquals(
            NotificationActionType.OPEN_PROFILE,
            NotificationActionType.fromWire("open_profile"),
        )
        assertEquals(NotificationActionType.NONE, NotificationActionType.fromWire("mystery"))
        assertEquals(NotificationActionType.NONE, NotificationActionType.fromWire(null))
    }
}
