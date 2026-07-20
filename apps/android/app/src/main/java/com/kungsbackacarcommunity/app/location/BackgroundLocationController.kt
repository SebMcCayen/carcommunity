package com.kungsbackacarcommunity.app.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Thin start/stop facade for [LocationSharingService].
 *
 * The live-location UI calls [start] with the signed-in uid when the user begins
 * sharing, and [stop] when they stop/hide. Starting is a no-op-safe
 * [ContextCompat.startForegroundService]; the service itself decides whether it
 * can actually publish (it stops itself when Firebase is unavailable, the
 * location permission is absent, or the session is not active).
 *
 * ### The service is NOT stopped on navigation
 * Only an actual END of sharing calls [stop]. Closing the live-location screen,
 * switching tabs or backgrounding the app deliberately leave the service
 * running — that is the entire point of it. The service's own session observer
 * is what ends it (manual stop, expiry, sign-out, remote end), so a UI that
 * forgets to call [stop] cannot leave background location running.
 */
object BackgroundLocationController {
    fun start(context: Context, uid: String) {
        if (uid.isBlank()) return
        val intent =
            Intent(context, LocationSharingService::class.java).apply {
                putExtra(LocationSharingService.EXTRA_UID, uid)
            }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LocationSharingService::class.java))
    }
}
