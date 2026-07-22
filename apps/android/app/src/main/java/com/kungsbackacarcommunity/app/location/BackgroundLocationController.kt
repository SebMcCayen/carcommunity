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
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: IllegalStateException) {
            // Android 12+ forbids starting a foreground service while the app is
            // in the background (ForegroundServiceStartNotAllowedException, a
            // subclass of IllegalStateException). The manual Start paths always
            // run in the foreground, but the session-bound start (a convoy
            // auto-started session — see AuthenticatedApp) can fire while the app
            // is backgrounded, e.g. when the convoy OWNER starts the group while
            // this member's app is not on screen. Swallow it rather than crash:
            // the member simply begins publishing the next time a start runs in
            // the foreground (opening the app / the map, or tapping Start). This
            // is defence in depth — dropping a foreground-service start can never
            // be worth taking the app down.
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LocationSharingService::class.java))
    }
}
