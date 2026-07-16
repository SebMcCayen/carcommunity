package com.kungsbackacarcommunity.app.push

import com.kungsbackacarcommunity.app.notifications.NotificationCategory

/**
 * Push display domain (Phase 12 slice 21, push portion). Pure Kotlin —
 * JVM-testable, no Android/Firebase imports.
 *
 * Maps an incoming FCM message to what the system notification should show.
 * The expected `data` keys mirror the backend notification document built by
 * functions/src/notifications/notifications-core.ts `buildNotificationDocument`
 * (category, title, previewText, body, actionType, relatedEntityId) plus the
 * inbox `notificationId`. Actual FCM sends ship with the end-of-MVP Firebase
 * console setup (`sendPushNotification` is deliberately not implemented yet —
 * see functions/src/notifications/pushTokens.ts), so this mapping is the
 * client half of that contract: unknown or missing keys degrade gracefully to
 * a neutral system notice instead of crashing on a malformed message.
 */

/** Android notification channels, grouped from the backend categories. */
enum class PushChannel(val id: String) {
    /** event_reminder / event_updated / event_cancelled. */
    EVENTS("events"),

    /** Essential account notices + important admin messages. */
    ACCOUNT("account"),

    /** Member-to-member activity: DMs, chats, friend requests, convoy invites. */
    SOCIAL("social"),

    /** Subscription status, system notices, and anything unknown. */
    GENERAL("general"),
}

/** What a received push should display. */
data class PushDisplayModel(
    /** Null when the message carried no usable title — caller shows the app name. */
    val title: String?,
    val body: String?,
    val channelId: String,
    val category: NotificationCategory,
    /** Inbox item id (notifications/{uid}/items/{id}) when the sender included one. */
    val notificationId: String?,
    /** Deep-link hint (open_event, open_notifications, ...); navigation is a follow-up. */
    val actionType: String?,
    val relatedEntityId: String?,
)

object PushDisplay {

    // Data keys — buildNotificationDocument field names (notifications-core.ts).
    private const val KEY_CATEGORY = "category"
    private const val KEY_TITLE = "title"
    private const val KEY_PREVIEW_TEXT = "previewText"
    private const val KEY_BODY = "body"
    private const val KEY_ACTION_TYPE = "actionType"
    private const val KEY_RELATED_ENTITY_ID = "relatedEntityId"
    private const val KEY_NOTIFICATION_ID = "notificationId"

    /**
     * Whether a received push may be displayed at all. Tokens outlive
     * sign-out (unregister-on-sign-out is deferred — there is no
     * pre-sign-out hook yet), so on a shared device a signed-out shell can
     * still receive the previous user's pushes; displaying them would leak
     * that user's account/event details. Signed-in AND permission-granted
     * are both required.
     */
    fun shouldDisplay(signedIn: Boolean, permissionGranted: Boolean): Boolean =
        signedIn && permissionGranted

    /** Channel for a category (unknown categories already fall back to SYSTEM_NOTICE). */
    fun channelFor(category: NotificationCategory): PushChannel =
        when (category) {
            NotificationCategory.EVENT_REMINDER,
            NotificationCategory.EVENT_UPDATED,
            NotificationCategory.EVENT_CANCELLED,
            -> PushChannel.EVENTS

            NotificationCategory.ACCOUNT_WARNING,
            NotificationCategory.ACCOUNT_SUSPENSION,
            NotificationCategory.ADMIN_MESSAGE,
            -> PushChannel.ACCOUNT

            NotificationCategory.DIRECT_MESSAGE,
            NotificationCategory.COMMUNITY_CHAT,
            NotificationCategory.CONVOY_CHAT,
            NotificationCategory.FRIEND_REQUEST,
            NotificationCategory.CONVOY_INVITE,
            -> PushChannel.SOCIAL

            NotificationCategory.SUBSCRIPTION_STATUS,
            NotificationCategory.SYSTEM_NOTICE,
            -> PushChannel.GENERAL
        }

    /**
     * Builds the display model from an FCM message. `data` keys win over the
     * optional notification block (the backend sends data messages so display
     * stays consistent whether the app is foregrounded or not); blank values
     * count as missing.
     */
    fun fromMessage(
        data: Map<String, String>,
        notificationTitle: String? = null,
        notificationBody: String? = null,
    ): PushDisplayModel {
        val category = NotificationCategory.fromWire(data[KEY_CATEGORY])
        return PushDisplayModel(
            title = firstNonBlank(data[KEY_TITLE], notificationTitle),
            body = firstNonBlank(data[KEY_PREVIEW_TEXT], data[KEY_BODY], notificationBody),
            channelId = channelFor(category).id,
            category = category,
            notificationId = firstNonBlank(data[KEY_NOTIFICATION_ID]),
            actionType = firstNonBlank(data[KEY_ACTION_TYPE]),
            relatedEntityId = firstNonBlank(data[KEY_RELATED_ENTITY_ID]),
        )
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
