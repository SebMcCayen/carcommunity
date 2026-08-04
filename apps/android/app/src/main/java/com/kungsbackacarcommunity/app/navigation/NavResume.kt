package com.kungsbackacarcommunity.app.navigation

/**
 * Pure (Android-free, Mapbox-free) core for keeping turn-by-turn navigation
 * alive across an interruption and offering to resume it.
 *
 * Turn-by-turn navigation is in-app Compose state (a non-null destination in
 * [com.kungsbackacarcommunity.app.AuthenticatedApp] renders the full-screen nav
 * view — there is no foreground service). Two things flow from that:
 *
 *  - A backgrounded app whose activity is later recreated, or a process death,
 *    used to drop the destination and end navigation the moment the user came
 *    back from, say, replying to a text. Carrying the destination in saved
 *    instance state fixes the recreation case; a durable record ([ActiveNavigation]
 *    persisted by [NavResumeStore]) fixes the cold-start case by letting the app
 *    offer to continue.
 *
 * This file is the JVM-unit-testable decision layer: what a back press should do
 * while navigating, and whether a persisted navigation is still worth resuming.
 * The Android glue (SharedPreferences, the dialogs) lives beside it.
 */

/**
 * A navigation the user had running, persisted so it can survive process death
 * and be offered back on the next launch.
 *
 * @param destination the turn-by-turn target (Mapbox lng-first [LatLng]).
 * @param label the human-readable destination name shown in the resume prompt.
 * @param startedAtMillis wall-clock time navigation was (re)started, for the
 *   staleness cap — a navigation begun hours ago is not one to silently resume.
 */
data class ActiveNavigation(
    val destination: LatLng,
    val label: String,
    val startedAtMillis: Long,
)

/**
 * The pure decisions behind the nav-robustness feature. No Android, no clock of
 * its own — everything it needs is a parameter, so the whole policy is
 * unit-testable.
 */
object NavResumePolicy {

    /**
     * How old a persisted navigation may be and still be offered for resume.
     *
     * Two hours: long enough that a genuine mid-drive interruption (a stop for
     * fuel, a long phone call) is still resumable, short enough that yesterday's
     * finished trip never resurfaces as a "continue navigation?" prompt. A record
     * older than this is treated as stale and cleared rather than offered.
     */
    const val RESUME_MAX_AGE_MILLIS: Long = 2L * 60 * 60 * 1000

    /**
     * Whether the Android BACK key should raise the "exit navigation?" confirm
     * instead of leaving immediately. Only while navigating — back must behave
     * normally everywhere else, so this is exactly [navigating].
     */
    fun shouldConfirmBackExit(navigating: Boolean): Boolean = navigating

    /**
     * Whether to offer to resume [persisted] on this launch.
     *
     * Offered only when there IS a record, navigation is NOT already running
     * (a live session never needs resuming), the saved point is a sendable
     * coordinate, and the record is neither stale nor from the future (a
     * backwards clock jump must not make an old record look fresh).
     *
     * A confirmed exit clears the record before this is ever asked, so a clean
     * exit yields null here and no prompt; an interruption leaves the record in
     * place, so this returns true and the prompt appears.
     */
    fun shouldOfferResume(
        persisted: ActiveNavigation?,
        nowMillis: Long,
        currentlyNavigating: Boolean,
        maxAgeMillis: Long = RESUME_MAX_AGE_MILLIS,
    ): Boolean {
        if (persisted == null) return false
        if (currentlyNavigating) return false
        if (!isValidWgs84Coordinate(persisted.destination)) return false
        val age = nowMillis - persisted.startedAtMillis
        return age in 0..maxAgeMillis
    }
}
