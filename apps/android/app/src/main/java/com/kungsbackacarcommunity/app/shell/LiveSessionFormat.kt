package com.kungsbackacarcommunity.app.shell

import java.util.Locale

/**
 * Pure, Android-free formatting for the map's live-session bar, kept apart from
 * the Composable so the elapsed-time rendering is JVM-unit-testable.
 *
 * The bar has room for numbers and an icon, not labels, so the output is
 * deliberately terse: a running clock that stays inside the bar at any duration a
 * live session can reach (capped at 6 h — see
 * [com.kungsbackacarcommunity.app.live.LiveLocation.LIVE_SESSION_MAX_MS]).
 */
object LiveSessionFormat {
    /**
     * Elapsed session time as a compact clock:
     * - under an hour → `M:SS` (e.g. `0:07`, `12:34`), and
     * - an hour or more → `Hh MMm` (e.g. `1h 04m`, `5h 59m`).
     *
     * Negative input (a clock skew) is floored at zero rather than rendering a
     * nonsensical "-1:59". Uses [Locale.ROOT] so the digits never pick up
     * locale-specific grouping.
     */
    fun elapsedLabel(elapsedMillis: Long): String {
        val totalSeconds = (elapsedMillis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%dh %02dm", hours, minutes)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}
