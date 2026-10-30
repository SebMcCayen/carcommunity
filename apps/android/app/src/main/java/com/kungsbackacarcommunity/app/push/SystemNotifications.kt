package com.kungsbackacarcommunity.app.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/**
 * Dismisses delivered alerts when the member opens or returns to the app.
 *
 * Launcher notification badges follow active system notifications. Cancel only
 * dismissible alerts, preserving live-location/other ongoing service notices
 * and their actions. This is entirely device-local: the durable notification
 * inbox, its unread state and notification-channel preferences are untouched.
 */
object SystemNotifications {
    fun dismissOnAppOpen(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Snapshot once, rather than subscribing to or continually clearing new
        // deliveries while the app is open.
        val delivered = manager.activeNotifications
        delivered.forEach { item ->
            val persistentFlags =
                Notification.FLAG_ONGOING_EVENT or
                    Notification.FLAG_FOREGROUND_SERVICE or
                    Notification.FLAG_NO_CLEAR
            if (item.notification.flags and persistentFlags == 0) {
                // SDK/legacy notifications can have tags, and several tags may
                // share the same numeric id. Cancelling only by id misses them.
                manager.cancel(item.tag, item.id)
            }
        }
    }
}
