package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.live.LiveCoordinate
import java.time.Instant

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
