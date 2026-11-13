package com.kungsbackacarcommunity.app.design

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.time.ZonedDateTime
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Re-reads BOTH wall time and timezone on every event; never retains a default-zone Clock. */
internal class AutomaticThemeClock(
    context: Context,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val appContext = context.applicationContext

    fun isDarkNow(schedule: AutomaticThemeSchedule = AutomaticThemeSchedule.DEFAULT): Boolean =
        schedule.isDark(now().toLocalTime())

    fun updates(schedule: AutomaticThemeSchedule = AutomaticThemeSchedule.DEFAULT): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(isDarkNow(schedule))
            }
        }
        // These three protected broadcasts can only originate from the system.
        // TIME_TICK covers the chosen minute boundaries without polling or alarms.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        trySend(isDarkNow(schedule))
        awaitClose { appContext.unregisterReceiver(receiver) }
    }
}

/** Activity-root resolution shared by the app palette, system bars and automatic map style. */
@Composable
internal fun rememberResolvedDarkTheme(
    preference: ThemePreference,
    systemInDark: Boolean,
    lifecycle: Lifecycle,
    clock: AutomaticThemeClock? = null,
    schedule: AutomaticThemeSchedule = AutomaticThemeSchedule.DEFAULT,
): Boolean {
    if (preference != ThemePreference.AUTOMATIC) return preference.resolveDark(systemInDark)

    val context = LocalContext.current
    val liveClock = remember(context, clock) { clock ?: AutomaticThemeClock(context) }
    // key() also replaces the initial value, so an edited schedule applies in this frame.
    return key(liveClock, lifecycle, schedule) {
        val dark by produceState(liveClock.isDarkNow(schedule), liveClock, lifecycle, schedule) {
            // Cancels/unregisters in the background and samples immediately on foreground entry.
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                liveClock.updates(schedule).collect { value = it }
            }
        }
        dark
    }
}
