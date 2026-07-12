package com.kungsbackacarcommunity.app.navigation.turnbyturn

import com.kungsbackacarcommunity.app.navigation.NavFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure (Android-free, Mapbox-free) core for the turn-by-turn navigation view's
 * bottom progress bar.
 *
 * The heavy on-device navigation glue (the Mapbox Navigation SDK v3 session,
 * camera, maneuver banner and route rendering) lives in the token-gated
 * `src/nav` source set and cannot run in CI. This snapshot + its formatting are
 * deliberately kept here in the always-compiled `main` set so the ETA/arrival
 * logic is deterministic and unit-tested without a device, a token, or the SDK.
 */

/**
 * A snapshot of progress along the active route, distilled from the SDK's
 * `RouteProgress` to the two numbers the bottom bar shows: how far and how long
 * remain to the destination.
 */
data class NavProgress(
    val distanceRemainingMeters: Double,
    val durationRemainingSeconds: Double,
)

/**
 * Locale-aware formatting for the navigation progress bar. Reuses [NavFormat]
 * (the address-search formatter) for the distance/duration so the two features
 * render "4.5 km" / "12 min" identically, and adds a wall-clock arrival time.
 */
object NavProgressFormat {
    /**
     * The estimated arrival wall-clock time, e.g. "14:05". Computed from [now]
     * plus the remaining duration and rendered in 24-hour `HH:mm` for the given
     * [zone]. Negative durations are clamped to 0 (arrival = now).
     */
    fun arrivalClock(
        durationRemainingSeconds: Double,
        now: Instant,
        zone: ZoneId,
    ): String {
        val remainingMillis = (durationRemainingSeconds.coerceAtLeast(0.0) * 1000.0).toLong()
        val arrival = now.plusMillis(remainingMillis)
        return ARRIVAL_FORMATTER.withZone(zone).format(arrival)
    }

    /**
     * The "time · distance" remaining summary, e.g. "12 min · 4.5 km". The unit
     * labels are passed in (from string resources) so the numeric rounding stays
     * pure while the abbreviations remain localizable. The [template] is a
     * positional format string (from `R.string.turnByTurn_remaining`,
     * `"%1$s · %2$s"`) so the separator and the time/distance order are
     * localizable too: arg 1 is the duration, arg 2 the distance.
     */
    fun remaining(
        progress: NavProgress,
        template: String,
        metersLabel: String,
        kilometersLabel: String,
        minutesLabel: String,
        hoursLabel: String,
    ): String {
        val duration =
            NavFormat.formatDuration(progress.durationRemainingSeconds, minutesLabel, hoursLabel)
        val distance =
            NavFormat.formatDistance(progress.distanceRemainingMeters, metersLabel, kilometersLabel)
        return String.format(Locale.getDefault(), template, duration, distance)
    }

    private val ARRIVAL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
