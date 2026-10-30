package com.kungsbackacarcommunity.app.push

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Framework cancellation, including legacy FCM tags and service protection. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SystemNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alerts", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    @Test fun dismissesAllAlertCategoriesAndTaggedNotificationsWithTheSameId() {
        PushChannels.ensureCreated(context)
        PushChannel.entries.forEachIndexed { index, channel ->
            manager.notify(index, alert(channel.id))
        }
        manager.notify("FCM-Notification:first", 42, alert())
        manager.notify("FCM-Notification:second", 42, alert())
        manager.notify(42, alert())
        assertEquals(7, manager.activeNotifications.size)

        SystemNotifications.dismissOnAppOpen(context)

        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun preservesOngoingForegroundServiceAndNoClearNotices() {
        manager.notify(1, alert())
        manager.notify(2, alert().apply { flags = flags or Notification.FLAG_ONGOING_EVENT })
        manager.notify(3, alert().apply { flags = flags or Notification.FLAG_FOREGROUND_SERVICE })
        manager.notify(4, alert().apply { flags = flags or Notification.FLAG_NO_CLEAR })

        SystemNotifications.dismissOnAppOpen(context)

        assertEquals(setOf(2, 3, 4), manager.activeNotifications.map { it.id }.toSet())
    }

    @Test fun preservesPersistentTaggedNoticeWhileClearingUntaggedAlertWithSameId() {
        manager.notify("live-session", 4201, alert().apply { flags = flags or Notification.FLAG_ONGOING_EVENT })
        manager.notify(4201, alert())

        SystemNotifications.dismissOnAppOpen(context)

        assertEquals(1, manager.activeNotifications.size)
        assertEquals("live-session", manager.activeNotifications.single().tag)
    }

    @Test fun repeatedOpeningWithNoAlertsIsHarmlessAndDoesNotChangeChannelPreferences() {
        val before = requireNotNull(manager.getNotificationChannel(CHANNEL))

        repeat(2) { SystemNotifications.dismissOnAppOpen(context) }

        assertTrue(manager.activeNotifications.isEmpty())
        val after = requireNotNull(manager.getNotificationChannel(CHANNEL))
        assertEquals(before.importance, after.importance)
        assertEquals(before.canShowBadge(), after.canShowBadge())
        assertEquals(before.sound, after.sound)
    }

    @Test fun clearingDeliveredAlertsDoesNotDisableFutureNotifications() {
        manager.notify(1, alert())
        SystemNotifications.dismissOnAppOpen(context)

        manager.notify(2, alert())
        assertEquals(listOf(2), manager.activeNotifications.map { it.id })

        SystemNotifications.dismissOnAppOpen(context)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun clearingNeedsNoNotificationPostingPermission() {
        manager.notify(1, alert())
        shadowOf(manager).setNotificationsEnabled(false)

        SystemNotifications.dismissOnAppOpen(context)

        assertTrue(manager.activeNotifications.isEmpty())
    }

    private fun alert(channel: String = CHANNEL): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Notification fixture")
            .setAutoCancel(true)
            .build()

    private companion object {
        const val CHANNEL = "system-notification-cleanup-test"
    }
}
