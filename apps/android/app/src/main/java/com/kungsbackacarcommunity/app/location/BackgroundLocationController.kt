package com.kungsbackacarcommunity.app.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Thin start/stop facade for [LocationSharingService] (Phase 12 slice 6).
 *
 * The live-location UI calls [start] when the user begins sharing and [stop]
 * when they stop/hide or leave the screen, so the foreground service is never
 * dead code. Starting is a no-op-safe [ContextCompat.startForegroundService];
 * the service itself decides whether it can actually publish (it stops itself
 * when Firebase is unavailable or the location permission is absent).
 */
object BackgroundLocationController {
    fun start(context: Context) {
        val intent = Intent(context, LocationSharingService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LocationSharingService::class.java))
    }
}
