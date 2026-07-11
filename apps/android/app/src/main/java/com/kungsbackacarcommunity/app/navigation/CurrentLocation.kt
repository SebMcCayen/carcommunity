package com.kungsbackacarcommunity.app.navigation

import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * One-shot current-location source for route origins, wrapping the fused
 * location provider (already a dependency, see [com.kungsbackacarcommunity.app.drives.DriveLocationController]).
 *
 * Guarded end-to-end so the config-less/CI build and a permission-less device
 * both degrade to `null` rather than crashing: Play-services unavailability is
 * caught at construction time, and a missing runtime location permission
 * surfaces as a [SecurityException] that resolves to null. Returns the last
 * known fix when present, else a fresh current fix; null when neither is
 * available.
 *
 * On-device verification note: the actual GPS/permission path cannot be
 * exercised in CI (no device), so the happy path is verified on device.
 */
object CurrentLocation {
    suspend fun lastKnown(context: Context): LatLng? {
        val client =
            runCatching {
                LocationServices.getFusedLocationProviderClient(context.applicationContext)
            }.getOrNull() ?: return null

        val last =
            runCatchingCancellable {
                suspendCancellableCoroutine<LatLng?> { cont ->
                    client.lastLocation
                        .addOnSuccessListener { loc ->
                            if (!cont.isActive) return@addOnSuccessListener
                            cont.resume(loc?.let { LatLng(it.longitude, it.latitude) })
                        }
                        .addOnFailureListener {
                            if (!cont.isActive) return@addOnFailureListener
                            cont.resume(null)
                        }
                }
            }.getOrNull()
        if (last != null) return last

        // No cached fix — ask for a single current fix (may still be null if the
        // permission is absent; the SecurityException is swallowed by
        // runCatchingCancellable, which still rethrows CancellationException).
        return runCatchingCancellable {
            suspendCancellableCoroutine<LatLng?> { cont ->
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (!cont.isActive) return@addOnSuccessListener
                        cont.resume(loc?.let { LatLng(it.longitude, it.latitude) })
                    }
                    .addOnFailureListener {
                        if (!cont.isActive) return@addOnFailureListener
                        cont.resume(null)
                    }
            }
        }.getOrNull()
    }
}
