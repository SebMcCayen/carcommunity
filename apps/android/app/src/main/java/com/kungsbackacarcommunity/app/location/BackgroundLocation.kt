package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.live.LiveCoordinate
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin helpers for the background-location slice (Phase 12 slice 6).
 *
 * Deliberately free of Android framework imports so the coordinate mapping and
 * timing constants are JVM-unit-testable. The [LocationSharingService] adapts
 * each platform `Location` sample through [buildCoordinate] before publishing it
 * via the Firebase-free [LiveCoordinate] type.
 */
object BackgroundLocation {
    /** Requested cadence for fused-location updates while sharing. */
    const val UPDATE_INTERVAL_MS = 5_000L

    /** Fastest cadence we will accept updates at (throttles bursty fixes). */
    const val MIN_UPDATE_INTERVAL_MS = 2_000L

    /**
     * How far the device must have moved since the last PUBLISHED fix before a
     * new one is worth a network round-trip. At 50 km/h this is reached in
     * roughly a second, so on a moving convoy every fix at [UPDATE_INTERVAL_MS]
     * publishes and the convoy arrows/focus stay live; parked at a meet, GPS
     * jitter of a few metres stops generating callable traffic.
     */
    const val MOVEMENT_THRESHOLD_METERS = 15.0

    /**
     * Publish at least this often even when stationary, so viewers can tell a
     * parked friend from a dead phone and the marker never looks stale.
     */
    const val STATIONARY_HEARTBEAT_MS = 30_000L

    /** How often the service re-checks the clock against the session expiry. */
    const val EXPIRY_TICK_MS = 15_000L

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Whether a freshly received fix is worth publishing, given the last one we
     * actually published. Movement OR the stationary heartbeat qualifies; the
     * first fix of a session always does.
     *
     * Pure so the battery/traffic policy is unit-testable — the fused-location
     * callback itself cannot be exercised without a device.
     */
    fun shouldPublish(
        lastPublishedAtMillis: Long?,
        lastPublishedLatitude: Double?,
        lastPublishedLongitude: Double?,
        latitude: Double,
        longitude: Double,
        nowMillis: Long,
    ): Boolean {
        if (lastPublishedAtMillis == null ||
            lastPublishedLatitude == null ||
            lastPublishedLongitude == null
        ) {
            return true
        }
        if (nowMillis - lastPublishedAtMillis >= STATIONARY_HEARTBEAT_MS) return true
        val moved =
            distanceMeters(lastPublishedLatitude, lastPublishedLongitude, latitude, longitude)
        return moved >= MOVEMENT_THRESHOLD_METERS
    }

    /** Great-circle distance in metres between two WGS-84 coordinates. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Maps a single GPS fix to a publishable [LiveCoordinate].
     *
     * `recordedAtIso` is the fix timestamp formatted as an ISO-8601 instant
     * (`Instant.ofEpochMilli(timeMillis).toString()`), matching the wire shape
     * that live.updatePosition expects. Optional accuracy/bearing/speed fields
     * pass straight through when the platform provides them.
     */
    fun buildCoordinate(
        latitude: Double,
        longitude: Double,
        timeMillis: Long,
        accuracyMeters: Double? = null,
        bearingDegrees: Double? = null,
        speedMps: Double? = null,
    ): LiveCoordinate =
        LiveCoordinate(
            latitude = latitude,
            longitude = longitude,
            recordedAtIso = Instant.ofEpochMilli(timeMillis).toString(),
            accuracyMeters = accuracyMeters,
            headingDegrees = bearingDegrees,
            speedMetersPerSecond = speedMps,
        )
}
