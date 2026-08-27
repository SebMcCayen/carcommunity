package com.kungsbackacarcommunity.app.drives

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kungsbackacarcommunity.app.location.BackgroundLocation

/**
 * The GPS source contract the drive recorders drive: begin streaming fixes to a
 * callback ([start], returning whether updates were actually requested) and stop
 * ([stop]). [DriveLocationController] is the sole production implementation — it
 * wraps a [FusedLocationProviderClient] and therefore cannot be built off-device
 * — so this interface exists purely as the seam a unit test can substitute a fake
 * fix stream for (see SingleSessionRecordingTest), which the concrete controller
 * could never allow.
 */
interface DriveLocationSource {
    /**
     * Starts fix updates, invoking [onFix] per sample; returns whether updates
     * were actually requested (false when the runtime location permission is
     * absent, so the caller can drive its permission UI / retry).
     */
    fun start(
        onFix: (latitude: Double, longitude: Double, timestampMs: Long, speedMps: Double?) -> Unit,
    ): Boolean

    /** Stops updates. Safe to call repeatedly. */
    fun stop()
}

/**
 * In-screen (foreground) GPS source for drive recording (Phase 12 slice 12,
 * write side). Wraps a [FusedLocationProviderClient] and streams fixes to a
 * callback while the record screen is active. Reuses the fused-location
 * cadence constants from slice 6's [BackgroundLocation].
 *
 * This controller is the in-screen GPS source: its updates run only while the
 * screen holds it. Recording is NOT limited to the foreground, though — while
 * the location-typed [com.kungsbackacarcommunity.app.location.LocationSharingService]
 * foreground service is running, recording continues with the screen off / the
 * app backgrounded, and that service's start is now retried on the next
 * foreground if it was first requested while the app was backgrounded (see
 * [com.kungsbackacarcommunity.app.location.BackgroundLocationController]).
 *
 * Guarded like the other Firebase/location entry points, via two factories with
 * deliberately different contracts — pick by whether you request the runtime
 * permission yourself:
 * - [createIfAvailable] guards ONLY on Google Play services. It does NOT check
 *   the runtime location permission, so it can hand back a controller whose
 *   [start] then fails. For callers that create the controller up front and
 *   request the permission afterwards (they read [start]'s result to drive their
 *   own permission UI).
 * - [createIfPermitted] additionally requires ACCESS_FINE_LOCATION to already be
 *   granted, returning null when it is not. For callers that need "null means no
 *   fixes will arrive" to be true and deterministic.
 *
 * Either way a config-less CI build (no device, no services) never crashes at
 * class-load or start. Real-device GPS and the runtime permission grant cannot
 * be exercised in this environment.
 */
class DriveLocationController private constructor(
    private val fusedClient: FusedLocationProviderClient,
) : DriveLocationSource {
    private var callback: LocationCallback? = null

    /**
     * Starts fused-location updates, invoking [onFix] on the main looper for
     * each sample. A missing runtime location permission surfaces as a
     * [SecurityException] from requestLocationUpdates; the caller requests the
     * permission before starting, and this returns false if it is still
     * absent so the screen can show its permission hint.
     *
     * `speedMps` is the platform's own ground speed for the fix, in metres per
     * second, and is null when the fix does not carry one (`hasSpeed()` false —
     * a first fix, or a provider that could not derive it). It is passed through
     * rather than derived from successive positions on purpose: the platform
     * value comes from the GNSS Doppler shift where available and is far steadier
     * than a position delta. It feeds only the live-session bar's readout — it is
     * never recorded into the drive (see [DriveRecorder], which stores positions
     * and timestamps and nothing else).
     */
    override fun start(
        onFix: (latitude: Double, longitude: Double, timestampMs: Long, speedMps: Double?) -> Unit,
    ): Boolean {
        if (callback != null) return true
        val cb =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val fix = result.lastLocation ?: return
                    onFix(
                        fix.latitude,
                        fix.longitude,
                        fix.time,
                        if (fix.hasSpeed()) fix.speed.toDouble() else null,
                    )
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
    override fun stop() {
        callback?.let { fusedClient.removeLocationUpdates(it) }
        callback = null
    }

    companion object {
        /**
         * Guards ONLY on Google Play services location being available; returns
         * null when it is not.
         *
         * Does NOT check the runtime location permission — without it this still
         * returns a controller, whose [start] then returns false. That suits a
         * caller which creates the controller before requesting the permission
         * and uses [start]'s result to drive its own permission UI (see
         * [RecordDriveScreen]). If you instead need "null means no fixes will
         * arrive", use [createIfPermitted].
         */
        fun createIfAvailable(context: Context): DriveLocationController? =
            try {
                DriveLocationController(
                    LocationServices.getFusedLocationProviderClient(context.applicationContext),
                )
            } catch (_: Exception) {
                // Play services location unavailable (e.g. config-less CI image).
                null
            }

        /**
         * Like [createIfAvailable], but ALSO returns null unless
         * ACCESS_FINE_LOCATION is already granted — so a null result reliably
         * means "no fixes will arrive", and a non-null one means [start] can
         * actually deliver them.
         *
         * Fine (not coarse) is required deliberately, matching the rest of the
         * app: ACCESS_FINE_LOCATION is the only location permission this app
         * ever checks or requests (the map home and [RecordDriveScreen] both
         * request exactly it; ACCESS_COARSE_LOCATION is declared in the manifest
         * but never requested). It is also what this controller's own
         * [Priority.PRIORITY_HIGH_ACCURACY] request needs: coarse fixes are
         * city-block accurate, which would turn a drive's distance and average
         * speed into noise — an honest duration-only summary is better than a
         * fabricated distance.
         *
         * Permission can be granted or revoked at any time, so call this when a
         * session actually STARTS rather than caching the result — a user who
         * grants the permission and starts a new session then gets a real
         * controller.
         */
        fun createIfPermitted(context: Context): DriveLocationController? {
            val granted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return null
            return createIfAvailable(context)
        }
    }
}
