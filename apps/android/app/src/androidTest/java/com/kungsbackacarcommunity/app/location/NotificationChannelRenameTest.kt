package com.kungsbackacarcommunity.app.location

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.testutil.RetryRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether re-creating an existing [NotificationChannel] id updates its
 * user-visible name and description.
 *
 * This exists because it was raised in review that "NotificationChannel names
 * cannot be updated once the channel ID already exists on a device", which would
 * mean [LocationSharingService] needed a channel-id migration to rename its
 * channel. The platform contract is the opposite: `createNotificationChannel()`
 * on an existing id updates the name and description, and only the
 * user-owned behavioural settings (importance, sound, vibration) are frozen
 * after creation. Asserting it on a real device rather than trusting either
 * reading — the whole question is what the framework actually does.
 */
@RunWith(RetryRunner::class)
class NotificationChannelRenameTest {

    // A non-Compose instrumentation test: it drives NotificationManager directly.
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    @After
    fun removeChannel() {
        manager.deleteNotificationChannel(CHANNEL_ID)
    }

    @Test
    fun existingChannelId_recreated_updatesNameAndDescription() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Old name", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Old description" },
        )
        assertEquals("Old name", requireNotNull(manager.getNotificationChannel(CHANNEL_ID)).name)

        // Exactly what LocationSharingService.createNotificationChannel() does on
        // every onCreate, with strings that have since changed.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New name", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "New description" },
        )

        val updated =
            requireNotNull(manager.getNotificationChannel(CHANNEL_ID)) {
                "channel $CHANNEL_ID was not created"
            }
        assertEquals("New name", updated.name)
        assertEquals("New description", updated.description)
        // Importance is user-owned after creation and is NOT re-applied; the
        // service does not rely on changing it.
        assertEquals(NotificationManager.IMPORTANCE_LOW, updated.importance)
    }

    private companion object {
        // Deliberately not the service's own id, so the test cannot disturb a
        // real sharing notification's channel settings on the device.
        const val CHANNEL_ID = "test_channel_rename_probe"
    }
}
