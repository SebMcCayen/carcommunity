package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map-layers popup default / persistence DECISION logic, pure Kotlin so it
 * runs on the JVM. The `SharedPreferences` plumbing ([MapLayersPreferenceStore])
 * is a thin wrapper over these functions — `getBoolean(key, default)` for the
 * three switches and [MapLayersPreferences.nightModeFromStored] for the day/night
 * override — exactly as [CompassModePreferenceTest] documents for the compass
 * store. To prove the wrapper's "write -> restart -> read back" contract without
 * an Android `SharedPreferences` (there is no Robolectric on this source set), the
 * lifecycle tests below drive the SAME pure encode/decode through an in-memory map
 * that models `SharedPreferences` key-absent-vs-present semantics.
 */
class MapLayersPreferenceTest {

    // ---- defaults: an untouched popup reproduces the app's original map --------

    /**
     * First run (nothing stored) leaves the map exactly as it shipped: the
     * traffic-alerts layer on, the Mapbox congestion overlay off, 3D buildings on,
     * and the day/night override unset (follow the app theme). These must match the
     * surface's own field defaults ([StubMapSurface] / the real surface seed
     * `trafficEnabled = false`, `is3d = true`), so persisting adds no visible change
     * for a user who never opens the popup.
     */
    @Test
    fun `first-run defaults reproduce the original map`() {
        assertTrue(MapLayersPreferences.incidentsDefault)
        assertFalse(MapLayersPreferences.trafficCongestionDefault)
        assertTrue(MapLayersPreferences.threeDDefault)
        assertNull(MapLayersPreferences.nightModeFromStored(null))
    }

    // ---- night-mode override decode -------------------------------------------

    /** An unset override (never overridden) means "follow the app theme" = null. */
    @Test
    fun `unset night-mode override follows the app theme`() {
        assertNull(MapLayersPreferences.nightModeFromStored(null))
        assertNull(MapLayersPreferences.nightModeToStored(null))
    }

    /** Every stored MapMode round-trips through encode -> decode. */
    @Test
    fun `night-mode override round-trips`() {
        MapMode.entries.forEach { mode ->
            assertEquals(
                mode,
                MapLayersPreferences.nightModeFromStored(MapLayersPreferences.nightModeToStored(mode)),
            )
        }
    }

    /**
     * A value this build no longer knows (enum renamed by an update, a hand-edited
     * prefs file) or an empty string falls back to null = "follow the app theme"
     * rather than throwing the way `MapMode.valueOf` would — this parse runs during
     * map start-up, so a throw would be a launch crash.
     */
    @Test
    fun `unknown or empty night-mode names fall back to following the theme`() {
        assertNull(MapLayersPreferences.nightModeFromStored(""))
        assertNull(MapLayersPreferences.nightModeFromStored("Dusk"))
        // Case-sensitive, like the compass / theme parsers: a lowercased name is unknown.
        assertNull(MapLayersPreferences.nightModeFromStored("night"))
    }

    // ---- full write -> restart -> read-back lifecycle --------------------------
    //
    // Models SharedPreferences with a plain map: absent key = default (first run),
    // present key = the stored value (survives the "restart"). The store's readers
    // are exactly `getBoolean(key, default)` and `nightModeFromStored(getString)`,
    // so decoding from the map is the same computation the real store performs after
    // a cold start.

    private fun readIncidents(prefs: Map<String, Any?>): Boolean =
        prefs[KEY_INCIDENTS] as? Boolean ?: MapLayersPreferences.incidentsDefault

    private fun readTraffic(prefs: Map<String, Any?>): Boolean =
        prefs[KEY_TRAFFIC] as? Boolean ?: MapLayersPreferences.trafficCongestionDefault

    private fun read3d(prefs: Map<String, Any?>): Boolean =
        prefs[KEY_3D] as? Boolean ?: MapLayersPreferences.threeDDefault

    private fun readNight(prefs: Map<String, Any?>): MapMode? =
        MapLayersPreferences.nightModeFromStored(prefs[KEY_NIGHT] as? String)

    @Test
    fun `empty store returns defaults on first run`() {
        val prefs = emptyMap<String, Any?>()
        assertTrue(readIncidents(prefs))
        assertFalse(readTraffic(prefs))
        assertTrue(read3d(prefs))
        assertNull(readNight(prefs))
    }

    @Test
    fun `configured non-default values survive a restart`() {
        // The user flips every toggle away from its default and picks Night mode.
        val disk = mutableMapOf<String, Any?>()
        disk[KEY_INCIDENTS] = false
        disk[KEY_TRAFFIC] = true
        disk[KEY_3D] = false
        disk[KEY_NIGHT] = MapLayersPreferences.nightModeToStored(MapMode.Night)

        // "Restart": a fresh read from the same disk returns exactly what was set,
        // NOT the defaults.
        assertFalse(readIncidents(disk))
        assertTrue(readTraffic(disk))
        assertFalse(read3d(disk))
        assertEquals(MapMode.Night, readNight(disk))
    }

    @Test
    fun `clearing the night-mode override returns to following the theme`() {
        val disk = mutableMapOf<String, Any?>(
            KEY_NIGHT to MapLayersPreferences.nightModeToStored(MapMode.Day),
        )
        assertEquals(MapMode.Day, readNight(disk))

        // Encoding null (follow the app theme again) removes the stored name, so a
        // later read falls back to null.
        if (MapLayersPreferences.nightModeToStored(null) == null) disk.remove(KEY_NIGHT)
        assertNull(readNight(disk))
    }

    private companion object {
        const val KEY_INCIDENTS = "incidents_layer"
        const val KEY_TRAFFIC = "traffic_congestion"
        const val KEY_3D = "buildings_3d"
        const val KEY_NIGHT = "night_mode"
    }
}
