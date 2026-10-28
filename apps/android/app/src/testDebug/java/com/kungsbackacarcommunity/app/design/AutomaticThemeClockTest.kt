package com.kungsbackacarcommunity.app.design

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual activity-root hook, Android receiver and foreground lifecycle. */
@RunWith(AndroidJUnit4::class)
class AutomaticThemeClockTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private val owner = Owner()
    private var now = ZonedDateTime.parse("2026-10-27T06:59:00+01:00[Europe/Stockholm]")
    private val preference = mutableStateOf(ThemePreference.AUTOMATIC)
    private val osDark = mutableStateOf(false)
    private var renderedDark = false
    private var background = Color.Unspecified
    private var clockReads = 0

    private fun render() {
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        val clock = AutomaticThemeClock(compose.activity) { clockReads++; now }
        compose.setContent {
            val dark = rememberResolvedDarkTheme(preference.value, osDark.value, owner.lifecycle, clock)
            KccTheme(darkTheme = dark) {
                renderedDark = LocalKccDarkTheme.current
                background = MaterialTheme.colorScheme.background
                Text("themed")
            }
        }
        compose.waitForIdle()
    }

    private fun expectDark(expected: Boolean) {
        compose.runOnIdle {
            assertEquals(expected, renderedDark)
            assertEquals(if (expected) KccDarkColors.pageBackground else KccLightColors.pageBackground, background)
        }
    }

    private fun broadcast(action: String) {
        compose.activity.sendBroadcast(Intent(action))
        compose.waitForIdle()
    }

    @Test fun minuteTicksCrossBothBoundariesWithoutRestarting() {
        render()
        expectDark(true)
        now = now.withHour(7).withMinute(0)
        broadcast(Intent.ACTION_TIME_TICK)
        expectDark(false)
        now = now.withHour(19).withMinute(0)
        broadcast(Intent.ACTION_TIME_TICK)
        expectDark(true)
    }

    @Test fun manualClockAndTimezoneChangesResampleImmediately() {
        render()
        now = now.withHour(10)
        broadcast(Intent.ACTION_TIME_CHANGED)
        expectDark(false)
        now = now.withZoneSameInstant(java.time.ZoneId.of("America/New_York"))
        broadcast(Intent.ACTION_TIMEZONE_CHANGED)
        expectDark(true)
    }

    @Test fun productionClockRereadsTheDefaultTimezone() {
        val original = java.util.TimeZone.getDefault()
        try {
            val clock = AutomaticThemeClock(compose.activity)
            val hourUtc = java.time.Instant.now().atZone(java.time.ZoneOffset.UTC).hour
            // Choose offsets within +/-12 that put the current wall hour at 00 and 12.
            fun zoneForHour(hour: Int) = java.util.TimeZone.getTimeZone(
                java.time.ZoneOffset.ofHours(Math.floorMod(hour - hourUtc + 12, 24) - 12),
            )
            java.util.TimeZone.setDefault(zoneForHour(0))
            assertEquals(true, clock.isDarkNow())
            java.util.TimeZone.setDefault(zoneForHour(12))
            assertEquals(false, clock.isDarkNow())
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test fun backgroundStopsObservationAndForegroundResamplesWithoutABroadcast() {
        render()
        expectDark(true)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        val readsBefore = clockReads
        now = now.withHour(12)
        broadcast(Intent.ACTION_TIME_TICK)
        assertEquals(readsBefore, clockReads)
        expectDark(true)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        expectDark(false)
    }

    @Test fun leavingAutomaticDisposesClockAndKeepsExplicitChoicesThroughTimeChanges() {
        render()
        listOf(ThemePreference.LIGHT, ThemePreference.DARK, ThemePreference.SYSTEM).forEach { choice ->
            compose.runOnIdle { preference.value = choice; osDark.value = false }
            compose.waitForIdle()
            val readsBefore = clockReads
            now = now.withHour(19)
            broadcast(Intent.ACTION_TIME_CHANGED)
            broadcast(Intent.ACTION_TIMEZONE_CHANGED)
            assertEquals(readsBefore, clockReads)
            expectDark(choice == ThemePreference.DARK)
            compose.runOnIdle { osDark.value = true }
            compose.waitForIdle()
            expectDark(choice != ThemePreference.LIGHT)
        }
        compose.runOnIdle { preference.value = ThemePreference.AUTOMATIC }
        compose.waitForIdle()
        expectDark(true)
        now = now.withHour(12)
        broadcast(Intent.ACTION_TIME_TICK)
        expectDark(false) // Even with the OS still dark.
    }
}
