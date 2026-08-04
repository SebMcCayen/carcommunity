package com.kungsbackacarcommunity.app.shell

import android.content.Context

/**
 * Device-local persistence for the map's compass orientation ([MapCompassMode]):
 * the user's north-up vs. course-up choice for the map-first home.
 *
 * SharedPreferences, no Firebase — mirroring
 * [com.kungsbackacarcommunity.app.design.ThemePreferenceStore] and
 * [com.kungsbackacarcommunity.app.welcome.WelcomeStore]. Deliberately
 * device-local and NOT account state: "this phone shows the map course-up" is a
 * per-device view preference (a windscreen mount rotates with you; a passenger's
 * phone may not), it must work regardless of sign-in, and syncing it would need a
 * rules/Firestore change for no user-visible benefit.
 *
 * The DEFAULT, when nothing has been written yet (first run), is
 * [MapCompassMode.DEFAULT] = course-up: the map rotates with the direction of
 * travel out of the box. The moment the user taps the compass control the chosen
 * mode is written here and, being on disk, survives a cold restart / process
 * kill — after which [read] returns exactly what they picked instead of falling
 * back to the default.
 *
 * A plain read/write (not a StateFlow like ThemePreferenceStore): the shell seeds
 * a single hoisted state from [read] on start-up and calls [write] on each toggle,
 * so there is no second live collector that would need the flow.
 */
class CompassModePreferenceStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The stored mode, or [MapCompassMode.DEFAULT] (course-up) when the user has
     * never chosen one. Crash-safe on an unknown/corrupt value via
     * [MapCompassMode.fromStoredName].
     */
    fun read(): MapCompassMode = MapCompassMode.fromStoredName(prefs.getString(KEY_MODE, null))

    /** Records the user's chosen mode so it survives a restart. */
    fun write(mode: MapCompassMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object {
        const val PREFS_NAME = "map_compass_preference"
        const val KEY_MODE = "mode"
    }
}
