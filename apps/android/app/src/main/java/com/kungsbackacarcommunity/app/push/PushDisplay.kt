package com.kungsbackacarcommunity.app.push

import com.kungsbackacarcommunity.app.notifications.NotificationCategory

/**
 * Push display domain (Phase 12 slice 21, push portion). Pure Kotlin —
 * JVM-testable, no Android/Firebase imports.
 *
 * Maps an incoming FCM message to what the system notification should show.
 * The expected `data` keys are exactly those produced by `buildPushPayload` in
 * functions/src/notifications/notifications-core.ts: category, title,
 * notificationId, target, and the optional previewText + entityId.
 *
 * The backend sends DATA-ONLY messages (no `notification` block) so the client
 * owns display in every app state — which is what makes per-category channels
 * and the "don't notify me about the chat I'm reading" suppression possible.
 * The notification-block fallbacks below therefore only matter for a malformed
 * or legacy send. Unknown or missing keys degrade to a neutral system notice
 * rather than crashing.
 *
 * `previewText` is ABSENT when the member has lock-screen previews off — the
 * body simply becomes null and the notification shows the title alone. There is
 * nothing to suppress client-side: content that must not appear on the lock
 * screen is never sent.
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
    /** Where tapping this notification should navigate. */
    val deepLink: PushDeepLink,
)

object PushDisplay {

    // Data keys — buildPushPayload field names (notifications-core.ts).
    private const val KEY_CATEGORY = "category"
    private const val KEY_TITLE = "title"
    private const val KEY_PREVIEW_TEXT = "previewText"
    private const val KEY_BODY = "body"
    private const val KEY_TARGET = "target"
    private const val KEY_ENTITY_ID = "entityId"
    private const val KEY_NOTIFICATION_ID = "notificationId"

    /**
     * Whether a received push may be displayed at all. Signed-in AND
     * permission-granted are both required.
     *
     * The signed-in check is defence in depth for the shared-device case: the
     * token IS now unregistered on sign-out, but that is a network call which
     * can fail or race an in-flight send, so a signed-out shell must still
     * refuse to display what would be the previous member's DMs.
     */
    fun shouldDisplay(signedIn: Boolean, permissionGranted: Boolean): Boolean =
        signedIn && permissionGranted

    /** Channel for a category (unknown categories already fall back to SYSTEM_NOTICE). */
    fun channelFor(category: NotificationCategory): PushChannel =
        when (category) {
            NotificationCategory.EVENT_CREATED,
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
            // Somebody left the convoy / leadership moved / the convoy ended:
            // member-to-member activity, so it belongs on the social channel with
            // the rest of it rather than reading as a system announcement.
            NotificationCategory.CONVOY_UPDATE,
            // A nearby live sharer waved at you (live.sendWave): member-to-member
            // activity, so it rides the social channel with the rest.
            NotificationCategory.WAVE,
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
            deepLink = PushDeepLink(
                target = PushTarget.fromWire(firstNonBlank(data[KEY_TARGET])),
                entityId = firstNonBlank(data[KEY_ENTITY_ID]),
            ),
        )
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
