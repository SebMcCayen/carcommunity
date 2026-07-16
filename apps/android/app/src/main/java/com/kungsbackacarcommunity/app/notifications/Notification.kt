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

/** A durable inbox item. */
data class AppNotification(
    val id: String,
    val category: NotificationCategory,
    val title: String,
    val previewText: String?,
    val body: String?,
    val isRead: Boolean,
    val createdAtMillis: Long?,
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

    /** Newest first; items without a timestamp sort last. */
    fun sortedForInbox(items: List<AppNotification>): List<AppNotification> =
        items.sortedWith(compareByDescending { it.createdAtMillis ?: Long.MIN_VALUE })
}
