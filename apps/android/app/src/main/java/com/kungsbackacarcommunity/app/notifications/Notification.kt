package com.kungsbackacarcommunity.app.notifications

/**
 * In-app notifications domain (Phase 12 slice 21, in-app portion).
 *
 * Mirrors the backend notifications-core contract: the notification
 * categories and the durable inbox item shape (notifications/{uid}/items).
 * Pure Kotlin — JVM-testable. Push delivery + FCM token registration await the
 * end-of-MVP Firebase console setup and are out of scope here.
 */

/** Notification category (notifications/{uid}/items/{id}.category). */
enum class NotificationCategory(val wire: String) {
    EVENT_REMINDER("event_reminder"),
    EVENT_UPDATED("event_updated"),
    EVENT_CANCELLED("event_cancelled"),
    ADMIN_MESSAGE("admin_message"),
    ACCOUNT_WARNING("account_warning"),
    ACCOUNT_SUSPENSION("account_suspension"),
    SUBSCRIPTION_STATUS("subscription_status"),
    SYSTEM_NOTICE("system_notice"),

    // Social categories: member-to-member activity. All optional — a user can
    // always silence other members (backend SOCIAL_NOTIFICATION_CATEGORIES).
    DIRECT_MESSAGE("direct_message"),
    COMMUNITY_CHAT("community_chat"),
    CONVOY_CHAT("convoy_chat"),
    FRIEND_REQUEST("friend_request"),
    CONVOY_INVITE("convoy_invite"),
    ;

    companion object {
        /** Unknown categories fall back to a neutral system notice for display. */
        fun fromWire(value: String?): NotificationCategory =
            values().firstOrNull { it.wire == value } ?: SYSTEM_NOTICE
    }
}

/**
 * What the backend says this item is FOR (notifications-core `actionType`).
 * Only the values the inbox actually branches on are modelled; everything else
 * (including a missing field) collapses to [NONE], which renders as a plain,
 * non-actionable row.
 *
 * This matters for FRIEND_REQUEST specifically, because that ONE category
 * carries two opposite meanings: "someone wants to be your friend" (actionable,
 * written with `open_notifications`) and "someone accepted you" (a receipt,
 * written with `open_profile`). Without this discriminator the inbox cannot
 * tell them apart — see [pendingFriendRequestId].
 */
enum class NotificationActionType(val wire: String) {
    NONE("none"),
    OPEN_NOTIFICATIONS("open_notifications"),
    OPEN_PROFILE("open_profile"),
    ;

    companion object {
        fun fromWire(value: String?): NotificationActionType =
            values().firstOrNull { it.wire == value } ?: NONE
    }
}

/**
 * A durable inbox item.
 *
 * [relatedEntityId] is the backend's "who/what is this about" pointer. For a
 * friend-request notice it is the OTHER member's uid (the requester) — NOT the
 * friendRequests document id, which is a SHA-256 over the ordered uid pair and
 * therefore cannot be reconstructed on the client. Acting on the request means
 * resolving that uid against the live pending list; see [pendingFriendRequestId].
 */
data class AppNotification(
    val id: String,
    val category: NotificationCategory,
    val title: String,
    val previewText: String?,
    val body: String?,
    val isRead: Boolean,
    val createdAtMillis: Long?,
    val actionType: NotificationActionType = NotificationActionType.NONE,
    val relatedEntityId: String? = null,
)

object Notifications {
    /**
     * Maximum inbox items the Firestore listener subscribes to (newest first
     * by createdAt). Keeps the snapshot bounded as the per-user collection
     * grows; older items simply fall off the inbox. Safe for the unread
     * affordances: [unreadCount] only gates the mark-all-read button — an
     * aggregate over the displayed items — while each row's unread label is
     * driven by that item's own [AppNotification.isRead], not by this count;
     * and markAllRead runs server-side over the full collection regardless.
     */
    const val INBOX_QUERY_LIMIT = 50L

    /** Number of unread items. */
    fun unreadCount(items: List<AppNotification>): Int = items.count { !it.isRead }

    /**
     * The rows the inbox should actually render: the server's items minus the
     * ones a delete has optimistically removed.
     *
     * Optimistic removal is a VIEW over the snapshot, never an edit of it. The
     * snapshot stays the single source of truth, so a delete that the server
     * refuses is undone by dropping its id from [pendingDeletedIds] — the row
     * reappears with its real contents, because it was never taken out of the
     * list it is derived from. Deleting an item that isn't there is a no-op,
     * so a stale id can only ever fail to match.
     */
    fun visibleItems(
        items: List<AppNotification>,
        pendingDeletedIds: Set<String>,
    ): List<AppNotification> =
        if (pendingDeletedIds.isEmpty()) items else items.filterNot { it.id in pendingDeletedIds }

    /**
     * The pending-delete set narrowed to ids the server still returns.
     *
     * Run against every snapshot. A delete that SUCCEEDED takes its item out of
     * the snapshot, and this is what then retires the id: the set holds only
     * ids that are still being hidden from something, so it cannot grow without
     * bound over a long-lived screen. An id whose call is still in flight is
     * kept, because its item is still in the snapshot.
     */
    fun prunePendingDeletes(
        pendingDeletedIds: Set<String>,
        items: List<AppNotification>,
    ): Set<String> {
        if (pendingDeletedIds.isEmpty()) return pendingDeletedIds
        val present = items.mapTo(HashSet()) { it.id }
        return pendingDeletedIds.filterTo(LinkedHashSet()) { it in present }
    }

    /** Newest first; items without a timestamp sort last. */
    fun sortedForInbox(items: List<AppNotification>): List<AppNotification> =
        items.sortedWith(compareByDescending { it.createdAtMillis ?: Long.MIN_VALUE })

    /**
     * The friendRequests id this inbox row can be accepted/declined against, or
     * null when the row carries no answerable request.
     *
     * [pendingRequestIdsByRequester] maps a REQUESTER uid to the id of their
     * still-pending incoming request, built from the live `friend-list`
     * snapshot. Deriving the actionability from that snapshot — rather than
     * from the notification alone — is the whole point: the notification is an
     * immutable historical record that is never rewritten when the request is
     * answered, so it is not, and cannot be, evidence that the request is still
     * open. Three cases fall out of this for free:
     *
     *  - ANSWERED ELSEWHERE (the profile screen, another device, or this
     *    screen a moment ago): the request has left the pending list, the
     *    lookup misses, and the row simply stops offering buttons. Tapping a
     *    button that no longer means anything is not possible, which is the
     *    stale case Seb's report is really about.
     *  - The "X accepted your request" RECEIPT: same category, but written with
     *    `open_profile`, so the actionType gate rejects it before the lookup.
     *    Without that gate an un-friend followed by a fresh request from the
     *    same member would grow Accept/Decline buttons on the old receipt.
     *  - A request the viewer SENT (outgoing): never in this map, which only
     *    ever holds incoming requests.
     *
     * Pure and total — no I/O, no exceptions — so the stale/fresh distinction
     * is unit-testable without Firestore or the emulator.
     */
    fun pendingFriendRequestId(
        item: AppNotification,
        pendingRequestIdsByRequester: Map<String, String>,
    ): String? {
        if (item.category != NotificationCategory.FRIEND_REQUEST) return null
        if (item.actionType != NotificationActionType.OPEN_NOTIFICATIONS) return null
        val requesterUid = item.relatedEntityId?.takeIf { it.isNotBlank() } ?: return null
        return pendingRequestIdsByRequester[requesterUid]
    }
}
