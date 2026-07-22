package com.kungsbackacarcommunity.app.location

import android.content.Context
import android.content.Intent
import android.os.Build
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
        } catch (e: IllegalStateException) {
            // A foreground-service start is refused when the app is in the
            // background. The manual Start paths always run in the foreground,
            // but the session-bound start (a convoy auto-started session — see
            // AuthenticatedApp) can legitimately fire while backgrounded, e.g.
            // when the convoy OWNER starts the group while this member's app is
            // off screen. Swallow ONLY that background-start refusal rather than
            // crash — the member begins publishing the next time a start runs in
            // the foreground (opening the app / the map, or tapping Start) — and
            // RETHROW any other IllegalStateException so a genuine misconfig
            // still surfaces loudly instead of being hidden:
            //   - API 31+: the refusal is the specific
            //     ForegroundServiceStartNotAllowedException (a subclass of ISE),
            //     matched precisely so unrelated ISEs on modern devices propagate.
            //   - API 26-30: there is no dedicated type; the platform raises a
            //     bare IllegalStateException for the same background-start
            //     restriction, and it is the only ISE startForegroundService
            //     throws there, so it is swallowed too.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e !is android.app.ForegroundServiceStartNotAllowedException
            ) {
                // Modern device, and NOT the background-start refusal — a real
                // fault. Rethrow so it is not hidden. (On API 26-30 the class
                // reference is never reached, so its absence there is moot.)
                throw e
            }
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LocationSharingService::class.java))
    }
}
