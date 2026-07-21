package com.kungsbackacarcommunity.app.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for [AppActiveNotificationPolicy.shouldPost]: the notice appears
 * only when notifications are permitted AND no live-location session is running.
 */
class AppActiveNotificationPolicyTest {

    @Test
    fun posts_whenPermitted_andNoLiveShare() {
        assertTrue(
            AppActiveNotificationPolicy.shouldPost(
                notificationsPermitted = true,
                liveShareActive = false,
            ),
        )
    }

    @Test
    fun suppressed_whenNotPermitted() {
        // Android 13+ without POST_NOTIFICATIONS: notify() would no-op anyway,
        // and we must not prompt from here.
        assertFalse(
            AppActiveNotificationPolicy.shouldPost(
                notificationsPermitted = false,
                liveShareActive = false,
            ),
        )
    }

    @Test
    fun suppressed_whileLiveShareOwnsTheShade() {
        // The live-location foreground service (#495) already shows an ongoing
        // notification — do not compete with a second one.
        assertFalse(
            AppActiveNotificationPolicy.shouldPost(
                notificationsPermitted = true,
                liveShareActive = true,
            ),
        )
    }

    @Test
    fun suppressed_whenNeitherConditionHolds() {
        assertFalse(
            AppActiveNotificationPolicy.shouldPost(
                notificationsPermitted = false,
                liveShareActive = true,
            ),
        )
    }
}
