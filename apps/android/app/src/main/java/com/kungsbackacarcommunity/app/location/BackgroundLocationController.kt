package com.kungsbackacarcommunity.app.location

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

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
 *
 * ### Reliable start when the session begins while backgrounded
 * A foreground-service start is refused with
 * [android.app.ForegroundServiceStartNotAllowedException] (API 31+) when the app
 * is in the background. The manual Start paths always run in the foreground, but
 * a session-bound start can legitimately fire while backgrounded — e.g. a convoy
 * member whose app is off screen when the owner activates the group. Rather than
 * silently give up (which left nothing publishing/recording until the user next
 * foregrounded the app), [start] records a PENDING start and a
 * [ProcessLifecycleOwner] observer retries it the moment the app returns to the
 * foreground. [stop] cancels any pending retry, so a stopped session never
 * re-starts the service. A location-typed FGS grants location-while-running
 * without ACCESS_BACKGROUND_LOCATION, so no extra permission is involved.
 */
object BackgroundLocationController {

    /**
     * uid of a start that was refused because the app was backgrounded, awaiting
     * a retry on the next foreground. `null` means nothing is pending. Written and
     * read on the main thread (start paths + the lifecycle observer); volatile is
     * defensive against any off-main caller of [start].
     */
    @Volatile
    private var pendingStartUid: String? = null

    /**
     * Application context captured on first start, so the foreground retry has a
     * context to start the service with without leaking an Activity.
     */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var observerRegistered = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Retries a pending start when the app returns to the foreground. Registered
     * lazily on the first refused start; safe to keep registered for the process
     * lifetime because it no-ops whenever nothing is pending.
     */
    private val foregroundObserver =
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val uid = pendingStartUid ?: return
                val context = appContext ?: return
                // Now in the foreground: this start is allowed and, on success,
                // clears the pending flag. If it still fails it stays pending and
                // is retried on the next foreground.
                start(context, uid)
            }
        }

    fun start(context: Context, uid: String) {
        if (uid.isBlank()) return
        appContext = context.applicationContext
        val intent =
            Intent(context, LocationSharingService::class.java).apply {
                putExtra(LocationSharingService.EXTRA_UID, uid)
            }
        try {
            ContextCompat.startForegroundService(context, intent)
            // A successful start (whether the original foreground start or a
            // foreground retry) clears any pending retry. Starting an already-
            // running service just re-delivers onStartCommand, which the service
            // handles idempotently, so this is safe if it was already up.
            pendingStartUid = null
        } catch (e: IllegalStateException) {
            // The `is` type check is the only framework-specific bit; the SDK
            // gating that turns it into the swallow-vs-rethrow decision lives in
            // the pure, unit-tested [ForegroundStartDecision].
            val isSpecificRefusalType =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
            if (ForegroundStartDecision.isBackgroundStartRefusal(
                    sdkInt = Build.VERSION.SDK_INT,
                    isSpecificRefusalType = isSpecificRefusalType,
                )
            ) {
                // Backgrounded-start refusal: record the uid and arm the
                // foreground retry instead of silently giving up.
                pendingStartUid = uid
                ensureForegroundObserver()
            } else {
                // Modern device, and NOT the background-start refusal — a real
                // fault. Rethrow so it is not hidden.
                throw e
            }
        }
    }

    fun stop(context: Context) {
        // Cancel any pending retry FIRST so a session that is stopped before it
        // ever got to foreground can never re-start the service.
        pendingStartUid = null
        context.stopService(Intent(context, LocationSharingService::class.java))
    }

    /**
     * Registers [foregroundObserver] exactly once. `addObserver` must run on the
     * main thread, so it is posted there; the guard flips before the post so
     * concurrent refused starts cannot double-register.
     */
    private fun ensureForegroundObserver() {
        if (observerRegistered) return
        observerRegistered = true
        mainHandler.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        }
    }
}

/**
 * Pure decision for [BackgroundLocationController]: was a caught
 * [IllegalStateException] the OS refusing a foreground-service start because the
 * app is in the background — the recoverable case we record as pending and retry
 * on the next foreground?
 *
 * Framework-free (the caller supplies the one framework fact —
 * [isSpecificRefusalType] — as a boolean) so the whole truth table is
 * unit-testable without a device.
 */
internal object ForegroundStartDecision {
    /**
     * @param sdkInt the running [Build.VERSION.SDK_INT].
     * @param isSpecificRefusalType whether the caught exception is the
     *   [android.app.ForegroundServiceStartNotAllowedException] the platform
     *   raises for a backgrounded start (only meaningful on API 31+).
     *
     * - API 31+: only the specific refusal type is recoverable; any other ISE is
     *   a genuine fault and returns false so the caller rethrows it.
     * - API 26-30: there is no dedicated type and the background-start
     *   restriction is the only ISE `startForegroundService` raises, so every ISE
     *   is treated as the recoverable refusal.
     */
    fun isBackgroundStartRefusal(sdkInt: Int, isSpecificRefusalType: Boolean): Boolean =
        sdkInt < Build.VERSION_CODES.S || isSpecificRefusalType
}
