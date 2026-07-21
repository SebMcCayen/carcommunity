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
     * How far the device must have moved since the last SUBMITTED fix before a
     * new one is worth a network round-trip. At 50 km/h this is reached in
     * roughly a second, so on a moving convoy every fix at [UPDATE_INTERVAL_MS]
     * publishes and the convoy arrows/focus stay live; parked at a meet, GPS
     * jitter of a few metres stops generating callable traffic.
     */
    const val MOVEMENT_THRESHOLD_METERS = 15.0

    /**
     * Publish at least this often even when stationary, so viewers can tell a
     * parked friend from a dead phone and the marker never looks stale.
     *
     * The single biggest data/cost saver in live sharing: a parked phone used to
     * write a heartbeat every 30 s; at 3 min it writes 6× less while parked. A
     * moving phone is unaffected — movement past [MOVEMENT_THRESHOLD_METERS]
     * publishes at the ordinary fix cadence regardless of this interval.
     *
     * ### Reader-staleness reconciliation (the subtle part)
     * A viewer that treats an old marker as offline must tolerate a 3-min gap.
     * The convoy planner's freshness window
     * ([com.kungsbackacarcommunity.app.map.ConvoyArrowPlanner.STALE_AFTER_MS]) is
     * kept STRICTLY GREATER than this interval so a parked, still-alive member on
     * the 3-min heartbeat is never dropped as stale between heartbeats. The
     * server sweep's own silent-stale window (LATEST_STALE_MINUTES = 15 min) is
     * far larger, so it is unaffected. If this interval is ever raised, raise
     * STALE_AFTER_MS to stay above it (a unit test asserts the ordering).
     */
    const val STATIONARY_HEARTBEAT_MS = 3 * 60 * 1000L // 3 minutes

    /** How often the service re-checks the clock against the session expiry. */
    const val EXPIRY_TICK_MS = 15_000L

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Whether a freshly received fix is worth publishing, given the last one we
     * submitted. Movement OR the stationary heartbeat qualifies; the first fix
     * of a session always does.
     *
     * "Submitted", not "published": the caller records a sample when it
     * dispatches the publish, not when the backend confirms it. Gating on
     * confirmation would make a failing backend retry at the full fix cadence
     * for as long as the failure lasted — the opposite of a throttle.
     *
     * Pure so the battery/traffic policy is unit-testable — the fused-location
     * callback itself cannot be exercised without a device.
     */
    fun shouldPublish(
        lastSubmittedAtMillis: Long?,
        lastSubmittedLatitude: Double?,
        lastSubmittedLongitude: Double?,
        latitude: Double,
        longitude: Double,
        nowMillis: Long,
    ): Boolean {
        if (lastSubmittedAtMillis == null ||
            lastSubmittedLatitude == null ||
            lastSubmittedLongitude == null
        ) {
            return true
        }
        if (nowMillis - lastSubmittedAtMillis >= STATIONARY_HEARTBEAT_MS) return true
        val moved =
            distanceMeters(lastSubmittedLatitude, lastSubmittedLongitude, latitude, longitude)
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
