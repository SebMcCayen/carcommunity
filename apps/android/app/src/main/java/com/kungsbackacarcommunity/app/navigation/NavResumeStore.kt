package com.kungsbackacarcommunity.app.navigation

import android.content.Context

/**
 * Durably remembers the navigation currently in progress so it can be offered
 * for resume after a process death or cold start.
 *
 * Device-local by design (SharedPreferences, like [com.kungsbackacarcommunity.app.update.AppUpdateDismissalStore]):
 * an in-flight route on this phone is not account state worth syncing, and a
 * network round-trip here would make resume depend on connectivity at launch.
 *
 * The record is written when navigation starts, refreshed each start (so the
 * staleness clock tracks the latest session), and cleared on a user-confirmed
 * exit — so a clean exit can never later offer to resume. All fields are written
 * and read together: a record missing any field is treated as no record, so a
 * half-written state can only ever suppress a resume prompt, never fabricate one.
 */
class NavResumeStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The persisted in-progress navigation, or null when there is none. */
    fun read(): ActiveNavigation? {
        if (!prefs.contains(KEY_LNG) ||
            !prefs.contains(KEY_LAT) ||
            !prefs.contains(KEY_STARTED_AT)
        ) {
            return null
        }
        return ActiveNavigation(
            destination = LatLng(
                // Stored as raw IEEE-754 bits: SharedPreferences has no Double,
                // and bits round-trip a coordinate exactly where a String parse
                // could drift.
                longitude = Double.fromBits(prefs.getLong(KEY_LNG, 0L)),
                latitude = Double.fromBits(prefs.getLong(KEY_LAT, 0L)),
            ),
            label = prefs.getString(KEY_LABEL, "").orEmpty(),
            startedAtMillis = prefs.getLong(KEY_STARTED_AT, 0L),
        )
    }

    /** Records [nav] as the navigation in progress, replacing any earlier one. */
    fun save(nav: ActiveNavigation) {
        prefs
            .edit()
            .putLong(KEY_LNG, nav.destination.longitude.toRawBits())
            .putLong(KEY_LAT, nav.destination.latitude.toRawBits())
            .putString(KEY_LABEL, nav.label)
            .putLong(KEY_STARTED_AT, nav.startedAtMillis)
            .apply()
    }

    /** Forgets any in-progress navigation (a confirmed exit, or a declined/stale resume). */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "nav_resume"
        const val KEY_LNG = "destinationLongitudeBits"
        const val KEY_LAT = "destinationLatitudeBits"
        const val KEY_LABEL = "destinationLabel"
        const val KEY_STARTED_AT = "startedAtMillis"
    }
}
