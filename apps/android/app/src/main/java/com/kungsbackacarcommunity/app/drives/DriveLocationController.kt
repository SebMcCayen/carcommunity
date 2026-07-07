package com.kungsbackacarcommunity.app.drives

import android.content.Context
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kungsbackacarcommunity.app.location.BackgroundLocation

/**
 * In-screen (foreground) GPS source for drive recording (Phase 12 slice 12,
 * write side). Wraps a [FusedLocationProviderClient] and streams fixes to a
 * callback while the record screen is active. Reuses the fused-location
 * cadence constants from slice 6's [BackgroundLocation].
 *
 * Foreground-only for this slice: updates run only while the screen holds this
 * controller. Continuing a recording in the background (screen off / app
 * backgrounded) via the existing [com.kungsbackacarcommunity.app.location.LocationSharingService]
 * foreground service is a documented follow-up.
 *
 * Guarded like the other Firebase/location entry points: [createIfAvailable]
 * returns null when Google Play services location is unavailable so a
 * config-less CI build (no device, no services) never crashes at class-load or
 * start. Real-device GPS and the runtime location permission grant cannot be
 * exercised in this environment.
 */
class DriveLocationController private constructor(
    private val fusedClient: FusedLocationProviderClient,
) {
    private var callback: LocationCallback? = null

    /**
     * Starts fused-location updates, invoking [onFix] on the main looper for
     * each sample. A missing runtime location permission surfaces as a
     * [SecurityException] from requestLocationUpdates; the caller requests the
     * permission before starting, and this returns false if it is still
     * absent so the screen can show its permission hint.
     */
    fun start(onFix: (latitude: Double, longitude: Double, timestampMs: Long) -> Unit): Boolean {
        if (callback != null) return true
        val cb =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val fix = result.lastLocation ?: return
                    onFix(fix.latitude, fix.longitude, fix.time)
                }
            }
        val request =
            LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                BackgroundLocation.UPDATE_INTERVAL_MS,
            )
                .setMinUpdateIntervalMillis(BackgroundLocation.MIN_UPDATE_INTERVAL_MS)
                .build()
        return try {
            fusedClient.requestLocationUpdates(request, cb, Looper.getMainLooper())
            callback = cb
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Stops updates. Safe to call repeatedly. */
    fun stop() {
        callback?.let { fusedClient.removeLocationUpdates(it) }
        callback = null
    }

    companion object {
        fun createIfAvailable(context: Context): DriveLocationController? =
            try {
                DriveLocationController(
                    LocationServices.getFusedLocationProviderClient(context.applicationContext),
                )
            } catch (_: Exception) {
                // Play services location unavailable (e.g. config-less CI image).
                null
            }
    }
}
