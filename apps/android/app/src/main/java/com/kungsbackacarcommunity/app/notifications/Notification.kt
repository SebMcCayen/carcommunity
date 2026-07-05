package com.kungsbackacarcommunity.app.notifications

/**
 * In-app notifications domain (Phase 12 slice 21, in-app portion).
 *
 * Mirrors the backend notifications-core contract: the 8 notification
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
    /** Number of unread items. */
    fun unreadCount(items: List<AppNotification>): Int = items.count { !it.isRead }

    /** Newest first; items without a timestamp sort last. */
    fun sortedForInbox(items: List<AppNotification>): List<AppNotification> =
        items.sortedWith(compareByDescending { it.createdAtMillis ?: Long.MIN_VALUE })
}
