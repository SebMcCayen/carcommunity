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

        // No cached fix — ask for a single current fix.
        return currentFix(context)
    }

    /**
     * A FRESH current fix, never the passive last-known cache.
     *
     * [lastKnown] prefers `client.lastLocation`, which only ADVANCES while some
     * other component is actively requesting location updates; a slow poll that
     * reads it as the member moves keeps getting the same minutes-old coordinate
     * (its own KDoc warns the cache "may be minutes old"). A consumer that has to
     * track where the device is NOW — the Crown-Hunt map layer, which greys a
     * crown until the member is inside its collect radius and must recolour it the
     * moment they cross the ring — polls THIS instead, so `getCurrentLocation`
     * actually recomputes the position each pass rather than echoing a stale fix
     * and leaving every crown greyed forever.
     *
     * [Priority.PRIORITY_BALANCED_POWER_ACCURACY] rather than the high accuracy
     * the claim flow ([com.kungsbackacarcommunity.app.crownhunt.CrownLocation])
     * asks for: the collect ring is 75 m and the poll is deliberately slow, so a
     * coarse fresh fix is close enough to decide grey-vs-coloured, and the costly
     * high-accuracy source stays reserved for the moment a member is actually
     * standing on a crown trying to collect it.
     *
     * Guarded exactly like [lastKnown]: a missing runtime permission surfaces as a
     * `SecurityException` that [runCatchingCancellable] swallows to null (while
     * still rethrowing `CancellationException`), so a permission-less device
     * degrades to "no fix" rather than crashing.
     */
    suspend fun currentFix(context: Context): LatLng? {
        val client =
            runCatching {
                LocationServices.getFusedLocationProviderClient(context.applicationContext)
            }.getOrNull() ?: return null

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
