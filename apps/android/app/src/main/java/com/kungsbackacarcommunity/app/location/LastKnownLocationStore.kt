package com.kungsbackacarcommunity.app.location

import android.content.Context

/** The last GPS position we saw, enough to open a map camera where the user is. */
data class LastKnownLocation(val latitude: Double, val longitude: Double)

/**
 * Whether a stored lat/lng is a coordinate safe to hand to a map camera: finite
 * (no NaN/±Infinity) and within the WGS84 ranges. Anything else — corruption, a
 * bad restore, a prior bug — is treated as no cache at all, because feeding a
 * non-finite or absurd value into `setCamera(...)` can crash. Pure, so it is
 * unit-tested directly (the SharedPreferences half is not JVM-testable here).
 */
internal fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
    latitude.isFinite() &&
        longitude.isFinite() &&
        latitude in -90.0..90.0 &&
        longitude in -180.0..180.0

/**
 * A tiny, last-write-wins cache of the most recent GPS fix, backed by
 * SharedPreferences so it survives process death.
 *
 * Its whole purpose is to let a map open AT the user before the first live fix
 * arrives, instead of flying in from the world view. The live/location layer
 * writes to it on every fix (cheap `apply()`); a map reads it once at start-up.
 *
 * Only a coarse lat/lng is stored — no timestamp, no bearing, no uid, and it is
 * never sent anywhere. A stale entry is harmless: the map opens near where the
 * user last was and the first real fix immediately corrects it.
 */
class LastKnownLocationStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The last stored fix, or null if none has been recorded — OR if what is
     * stored is not a valid coordinate.
     *
     * A stored pair can be garbage from corruption, a bad restore, or a prior
     * bug: NaN, ±Infinity, or an out-of-range lat/lng. Callers feed the result
     * straight into `setCamera(...)`, which can crash on a non-finite or absurd
     * value, so an invalid entry is treated as a MISSING cache (returns null) and
     * the Kungsbacka fallback takes over instead.
     */
    fun read(): LastKnownLocation? {
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LNG)) return null
        val lat = prefs.getFloat(KEY_LAT, Float.NaN).toDouble()
        val lng = prefs.getFloat(KEY_LNG, Float.NaN).toDouble()
        if (!isValidCoordinate(lat, lng)) return null
        return LastKnownLocation(latitude = lat, longitude = lng)
    }

    // Range/finiteness validation lives in the top-level [isValidCoordinate] so
    // it can be unit-tested without a Context/SharedPreferences.

    /**
     * Record the latest fix. Last-write-wins; called on a high-frequency location
     * callback, so it uses `apply()` (async, off the caller's thread) and stores
     * floats — a few metres of precision is plenty for an opening camera.
     */
    fun save(latitude: Double, longitude: Double) {
        prefs.edit()
            .putFloat(KEY_LAT, latitude.toFloat())
            .putFloat(KEY_LNG, longitude.toFloat())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "last_known_location"
        const val KEY_LAT = "lat"
        const val KEY_LNG = "lng"
    }
}
