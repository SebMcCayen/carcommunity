package com.kungsbackacarcommunity.app.shell

import android.content.Context

/**
 * Pure default / decode logic for the map-layers popup toggles that a user
 * flips on the map-first home (and the navigation screen's own layers control):
 *
 * - **Traffic alerts** — the Trafikverket + crowd-sourced incidents layer
 *   ([incidentsDefault]).
 * - **Traffic** — the Mapbox congestion overlay ([trafficCongestionDefault]).
 * - **Night mode** — the day/night light-preset override
 *   ([nightModeFromStored]); `null` means "follow the resolved app theme", which
 *   is the first-run behaviour.
 * - **3D buildings** — the tilted 3D vs. flat 2D camera ([threeDDefault]).
 *
 * Kept as a pure object — no Android types touch these constants / parsers — so
 * the "unset means the app default" and crash-safe-parse decisions are
 * JVM-unit-testable off-device, exactly like [MapZoomPreference.fromStored] and
 * [MapCompassMode.fromStoredName]. The [MapLayersPreferenceStore] below is the
 * thin `SharedPreferences` wrapper over these.
 *
 * The DEFAULTS reproduce the app's original out-of-the-box map exactly (so a
 * user who has never touched the popup sees no change): the traffic-alerts layer
 * on, the Mapbox congestion overlay off, the map following the app theme, and 3D
 * buildings on. They mirror the surface's own field defaults
 * ([MapSurface] implementations seed `trafficEnabled = false`, `is3d = true`).
 */
object MapLayersPreferences {
    /** Traffic-alerts (incidents) layer shown out of the box. */
    const val incidentsDefault: Boolean = true

    /** Mapbox congestion overlay off out of the box. */
    const val trafficCongestionDefault: Boolean = false

    /** 3D buildings / tilted camera on out of the box. */
    const val threeDDefault: Boolean = true

    /**
     * Decodes a persisted night-mode name. A `null` (nothing stored — the user
     * has never overridden day/night) stays `null` = "follow the resolved app
     * theme". An unknown / corrupt name (enum renamed by an app update, a
     * hand-edited prefs file) ALSO falls back to `null` rather than throwing the
     * way `MapMode.valueOf` would — this parse runs during map start-up, so a
     * throw here would be a launch crash. A recognised name round-trips.
     */
    fun nightModeFromStored(name: String?): MapMode? =
        if (name == null) null else MapMode.entries.find { it.name == name }

    /** Encodes the override for storage; `null` (follow theme) has no stored name. */
    fun nightModeToStored(mode: MapMode?): String? = mode?.name
}

/**
 * Device-local persistence for the map-layers popup toggles ([MapLayersPreferences]).
 *
 * `SharedPreferences`, no Firebase — mirroring [CompassModePreferenceStore],
 * [MapZoomPreferenceStore] and [com.kungsbackacarcommunity.app.design.ThemePreferenceStore].
 * Deliberately device-local and NOT account state: "this phone shows traffic /
 * 3D / a night map" is a per-device view preference (a passenger's phone or a
 * daytime commute may want different layers than the driver's night run), it must
 * work regardless of sign-in, and syncing it would need a rules/Firestore change
 * for no user-visible benefit.
 *
 * Plain cached read/write (like [CompassModePreferenceStore], not a `StateFlow`):
 * the shell seeds its hoisted states / the surface from the `read*` methods on
 * start-up and calls the `write*` methods on each toggle. Each writer is a no-op
 * when the value is unchanged, so seeding a state from a read and writing it
 * straight back on the first composition costs no disk I/O — only a genuine
 * toggle touches `SharedPreferences`.
 */
class MapLayersPreferenceStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Values currently on disk, cached so each writer can skip a redundant put.
    private var incidents: Boolean =
        prefs.getBoolean(KEY_INCIDENTS, MapLayersPreferences.incidentsDefault)
    private var trafficCongestion: Boolean =
        prefs.getBoolean(KEY_TRAFFIC, MapLayersPreferences.trafficCongestionDefault)
    private var threeD: Boolean =
        prefs.getBoolean(KEY_3D, MapLayersPreferences.threeDDefault)
    private var nightMode: MapMode? =
        MapLayersPreferences.nightModeFromStored(prefs.getString(KEY_NIGHT, null))

    /** The traffic-alerts layer's stored state, or its default when unset. */
    fun readIncidents(): Boolean = incidents

    /** Records the traffic-alerts layer choice so it survives a restart. */
    fun writeIncidents(enabled: Boolean) {
        if (enabled == incidents) return
        incidents = enabled
        prefs.edit().putBoolean(KEY_INCIDENTS, enabled).apply()
    }

    /** The Mapbox congestion overlay's stored state, or its default when unset. */
    fun readTrafficCongestion(): Boolean = trafficCongestion

    /** Records the congestion-overlay choice so it survives a restart. */
    fun writeTrafficCongestion(enabled: Boolean) {
        if (enabled == trafficCongestion) return
        trafficCongestion = enabled
        prefs.edit().putBoolean(KEY_TRAFFIC, enabled).apply()
    }

    /** The 3D-buildings toggle's stored state, or its default when unset. */
    fun read3d(): Boolean = threeD

    /** Records the 3D-buildings choice so it survives a restart. */
    fun write3d(enabled: Boolean) {
        if (enabled == threeD) return
        threeD = enabled
        prefs.edit().putBoolean(KEY_3D, enabled).apply()
    }

    /**
     * The stored day/night override, or `null` (follow the app theme) when the
     * user has never overridden it. Crash-safe on an unknown/corrupt value via
     * [MapLayersPreferences.nightModeFromStored].
     */
    fun readNightMode(): MapMode? = nightMode

    /**
     * Records the day/night override so it survives a restart. `null` (back to
     * "follow the app theme") clears the stored name. A no-op when unchanged.
     */
    fun writeNightMode(mode: MapMode?) {
        if (mode == nightMode) return
        nightMode = mode
        val name = MapLayersPreferences.nightModeToStored(mode)
        prefs.edit().apply {
            if (name == null) remove(KEY_NIGHT) else putString(KEY_NIGHT, name)
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "map_layers_preference"
        const val KEY_INCIDENTS = "incidents_layer"
        const val KEY_TRAFFIC = "traffic_congestion"
        const val KEY_3D = "buildings_3d"
        const val KEY_NIGHT = "night_mode"
    }
}
