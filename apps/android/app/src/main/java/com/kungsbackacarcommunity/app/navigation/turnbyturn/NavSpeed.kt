package com.kungsbackacarcommunity.app.navigation.turnbyturn

import kotlin.math.roundToInt

/**
 * Pure (Android-free, Mapbox-free) core for the turn-by-turn navigation view's
 * SPEED readout — the driver's current speed and, when the map data has one, the
 * posted legal speed limit for the road they are on.
 *
 * Same split as [NavProgress]: the SDK glue that produces these numbers lives in
 * the token-gated `src/nav` source set and cannot run in CI, so the conversion
 * and the "is this limit trustworthy" rules are kept here in the always-compiled
 * `main` set and unit-tested without a device, a token, or the SDK.
 *
 * ## Units
 * Everything here is km/h. The app has NO user-facing unit preference — every
 * other speed it renders is hard km/h too (see
 * `com.kungsbackacarcommunity.app.drives.DriveFormatters.formatSpeed`), which
 * matches its Swedish audience — so this deliberately converts to km/h rather
 * than inventing a preference. If a unit setting is ever added, [KMH_LABEL] and
 * [postedLimitKmh] are the two places it lands.
 *
 * ## No gamification
 * This model exposes the two numbers and nothing else. There is deliberately no
 * "over the limit" flag, no margin, no streak and no severity: a speeding alert
 * or score is an explicit product NO, and the cheapest way to keep it out is for
 * the data layer never to compute it in the first place.
 */

/**
 * A snapshot of the speed readout.
 *
 * @param currentKmh the driver's current speed in whole km/h. Never null — a
 *   missing/!valid GPS speed reads as 0, which is what a stationary car shows
 *   anyway, so the readout never blanks mid-drive.
 * @param postedLimitKmh the posted legal limit for the current road in whole
 *   km/h, or NULL when the SDK has no limit for this location. Null is the
 *   COMMON case, not an error: Mapbox speed-limit coverage is patchy and thins
 *   out fast on smaller Swedish roads. A null must hide the limit entirely —
 *   showing a stale or guessed number is worse than showing none.
 */
data class NavSpeedInfo(
    val currentKmh: Int,
    val postedLimitKmh: Int?,
)

/** Conversion + presentation helpers for [NavSpeedInfo]. */
object NavSpeedFormat {
    /**
     * The km/h unit label.
     *
     * Not a string resource on purpose, matching the existing convention for
     * numeric-adjacent units in `drives/SavedDrive.kt`: "km/h" is identical in
     * Swedish and English, so a translated pair would be two copies of the same
     * literal drifting apart.
     */
    const val KMH_LABEL: String = "km/h"

    /** Exact m/s → km/h factor. */
    private const val MPS_TO_KMH: Double = 3.6

    /** Exact mph → km/h factor. */
    private const val MPH_TO_KMH: Double = 1.609344

    /** [com.mapbox.navigation.base.speed.model.SpeedUnit] names, passed as strings. */
    private const val UNIT_KMH = "KILOMETERS_PER_HOUR"
    private const val UNIT_MPH = "MILES_PER_HOUR"
    private const val UNIT_MPS = "METERS_PER_SECOND"

    /**
     * The GPS ground speed in m/s → whole km/h.
     *
     * Null (no speed in the fix) and negative values (some providers use a
     * negative sentinel for "unknown") both read as 0 rather than propagating a
     * null the UI would have to special-case, and rather than rendering a
     * nonsense negative speed.
     */
    fun currentKmhFromMetersPerSecond(metersPerSecond: Double?): Int {
        val mps = metersPerSecond ?: return 0
        if (!mps.isFinite() || mps <= 0.0) return 0
        return (mps * MPS_TO_KMH).roundToInt()
    }

    /**
     * The posted limit in whole km/h, or null when there is nothing trustworthy
     * to show.
     *
     * Returns null — i.e. shows NO limit — for every uncertain case, which is
     * the whole point of this function:
     * - [speed] is null (the SDK has no limit for this road; the common case),
     * - [speed] is <= 0 (an "unlimited"/unknown sentinel, e.g. a German
     *   derestricted stretch — there is no number to show),
     * - [unitName] is null or is a unit we do not recognise (a future SDK enum
     *   value would otherwise be silently mis-scaled, and a limit off by a
     *   factor of 1.6 is exactly the "wrong limit" this must never show).
     *
     * @param speed the raw posted limit as the SDK reports it, in [unitName].
     * @param unitName the SDK's `SpeedUnit` enum NAME (pass `unit.name`), kept a
     *   plain String so this file stays free of the token-gated SDK types.
     */
    fun postedLimitKmh(
        speed: Int?,
        unitName: String?,
    ): Int? {
        val raw = speed ?: return null
        if (raw <= 0) return null
        return when (unitName) {
            UNIT_KMH -> raw
            UNIT_MPH -> (raw * MPH_TO_KMH).roundToInt()
            UNIT_MPS -> (raw * MPS_TO_KMH).roundToInt()
            else -> null
        }
    }
}
