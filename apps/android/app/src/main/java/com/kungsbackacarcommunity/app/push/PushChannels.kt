package com.kungsbackacarcommunity.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.kungsbackacarcommunity.app.R

/**
 * Creates the app's notification channels (Phase 12 slice 21, push portion).
 *
 * Called from KccApplication.onCreate BEFORE the Firebase guard — channel
 * creation is pure Android (no Firebase), so it is safe in config-less
 * CI/validation builds — and defensively again before posting from
 * [KccMessagingService]. createNotificationChannel is idempotent; names and
 * descriptions are localized resources and refresh on every call.
 */
object PushChannels {

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        PushChannel.entries.forEach { channel ->
            manager.createNotificationChannel(
                NotificationChannel(channel.id, context.getString(channel.nameRes()), channel.importance()).apply {
                    description = context.getString(channel.descriptionRes())
                },
            )
        }
    }

    private fun PushChannel.nameRes(): Int =
        when (this) {
            PushChannel.EVENTS -> R.string.notifications_channelEventsName
            PushChannel.ACCOUNT -> R.string.notifications_channelAccountName
            PushChannel.GENERAL -> R.string.notifications_channelGeneralName
        }

    private fun PushChannel.descriptionRes(): Int =
        when (this) {
            PushChannel.EVENTS -> R.string.notifications_channelEventsDescription
            PushChannel.ACCOUNT -> R.string.notifications_channelAccountDescription
            PushChannel.GENERAL -> R.string.notifications_channelGeneralDescription
        }

    private fun PushChannel.importance(): Int =
        when (this) {
            // Essential account notices must be seen (legacy invariant).
            PushChannel.ACCOUNT -> NotificationManager.IMPORTANCE_HIGH
            PushChannel.EVENTS -> NotificationManager.IMPORTANCE_DEFAULT
            PushChannel.GENERAL -> NotificationManager.IMPORTANCE_DEFAULT
        }
}
