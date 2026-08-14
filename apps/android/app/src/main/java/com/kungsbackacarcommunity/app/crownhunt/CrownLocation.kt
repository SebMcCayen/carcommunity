package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import android.os.Build
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One-shot position source for Kronjakt claims — the same fused-provider
 * pattern as [com.kungsbackacarcommunity.app.navigation.CurrentLocation], but
 * carrying the extra fields a claim needs: speed, accuracy, the fix's own
 * timestamp, and `isMock`.
 *
 * Separate from `CurrentLocation` rather than widening it, because the two want
 * opposite things. A route origin wants the CHEAPEST usable coordinate, so
 * `CurrentLocation` happily returns a cached last-known fix that may be minutes
 * old. A claim is refused outright if its fix is more than 60 s old, and its
 * whole point is to describe where the member is RIGHT NOW — so this asks for a
 * current fix at high accuracy and never falls back to the cache.
 *
 * Guarded end to end: Play-services unavailability is caught at construction and
 * a missing runtime permission surfaces as a `SecurityException` that resolves
 * to null, so a config-less build and a permission-less device both degrade to
 * "no crown can be collected" rather than crashing.
 *
 * On-device verification note: the GPS/permission path cannot be exercised in CI
 * (no device, no fused provider), so the values this produces — particularly
 * `speed`, which some devices simply never populate — are verified on device.
 */
object CrownLocation {
    /**
     * A fresh fix, or null when none is available.
     *
     * [Priority.PRIORITY_HIGH_ACCURACY] by default rather than the balanced
     * priority the route origin uses: a claim is decided on a 75 m radius and a
     * 2 m/s speed ceiling, and a coarse network fix can be hundreds of metres out —
     * which would produce refusals that look arbitrary to a member standing
     * directly on top of a crown.
     *
     * Two power profiles, chosen by [highAccuracy]:
     *  - [highAccuracy] = true (the default): a high-accuracy fix for a
     *    position that has to be TRUSTWORTHY — a claim, a one-shot check-in, an
     *    incident clear. These are bounded by a single user action, so the cost is
     *    the user's own attention. This is why every caller that submits a fix to
     *    the server takes the default.
     *  - [highAccuracy] = false: [Priority.PRIORITY_BALANCED_POWER_ACCURACY], for
     *    warming state ahead of time where only rough TIMING matters and a later
     *    high-accuracy read will refine the position. The crown pre-warm uses this
     *    so keeping a Collect button warm never sits on high-power GPS, and it
     *    stops as soon as it has what it needs rather than polling on.
     */
    suspend fun currentFix(context: Context, highAccuracy: Boolean = true): CrownFix? {
        val client =
            runCatching {
                LocationServices.getFusedLocationProviderClient(context.applicationContext)
            }.getOrNull() ?: return null

        val priority =
            if (highAccuracy) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }
        return runCatching {
            suspendCancellableCoroutine { continuation ->
                client
                    .getCurrentLocation(priority, null)
                    .addOnSuccessListener { location ->
                        if (!continuation.isActive) return@addOnSuccessListener
                        continuation.resume(
                            location?.let {
                                CrownFix(
                                    latitude = it.latitude,
                                    longitude = it.longitude,
                                    // The FIX's timestamp, not "now". The server
                                    // checks freshness against this, and a fix
                                    // stamped with the moment we happened to read
                                    // it would hide exactly the staleness that
                                    // check exists to catch.
                                    recordedAtMillis = it.time,
                                    speedMetersPerSecond =
                                        if (it.hasSpeed()) it.speed.toDouble() else null,
                                    accuracyMeters =
                                        if (it.hasAccuracy()) it.accuracy.toDouble() else null,
                                    isMock = isMock(it),
                                )
                            },
                        )
                    }
                    .addOnFailureListener {
                        if (!continuation.isActive) return@addOnFailureListener
                        continuation.resume(null)
                    }
            }
        }.getOrNull()
    }

    /**
     * `Location.isMock` across API levels.
     *
     * Reported truthfully and never suppressed: the backend treats it as a
     * one-way signal (true is penalised, false and absent are identical), so an
     * honest client gives nothing away and a dishonest one gains nothing by
     * omitting it.
     */
    @Suppress("DEPRECATION")
    private fun isMock(location: android.location.Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            location.isFromMockProvider
        }
}
