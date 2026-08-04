package com.kungsbackacarcommunity.app.map

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's preferred RESTING map zoom — "how far away the focus is" when using
 * the map as usual.
 *
 * ## What this controls (and what it does not)
 * This is the zoom the map settles at when it opens on the user and when it
 * re-centres on the user while browsing (my-location control, compass, the
 * 10-second idle-return). In the surface those two sites are the first-fix camera
 * and [com.kungsbackacarcommunity.app.shell.MapboxMapSurface]'s `easeToUser`,
 * both of which framed the user at [MapMarkers.OWN_MARKER_ZOOM] before this
 * preference existed — which is why [DEFAULT_ZOOM] is exactly that value, so an
 * untouched slider reproduces the old behaviour to the decimal.
 *
 * It deliberately does NOT touch the ACTIVE drive-follow framing: while the map
 * is following a moving puck it only re-centres (leaving the zoom the user is
 * already at), and the convoy-fit / route-preview cameras own their own zoom.
 * Those paths are left alone; only the resting/browsing zoom is a preference.
 *
 * Higher = CLOSER (more zoomed in), lower = FARTHER away, matching how the slider
 * reads left (farther) to right (closer). Pure Kotlin — no Android or Mapbox
 * imports on the maths — so [clamp]/[snap]/[fromStored] are JVM-unit-testable and
 * the value can be reasoned about off-device.
 */
object MapZoomPreference {
    /**
     * Farthest resting zoom the slider allows — a district / town-overview frame.
     * Below Mapbox's mid-teens "streets" band on purpose: this is as far out as
     * "using the map as usual" should ever rest, not a whole-country survey.
     */
    const val MIN_ZOOM: Double = 12.0

    /**
     * Closest resting zoom the slider allows — building / street-detail level.
     * Above the old fixed [MapMarkers.OWN_MARKER_ZOOM] so a user who wants the map
     * tighter than the previous default can have it, without reaching the point
     * where the basemap runs out of detail.
     */
    const val MAX_ZOOM: Double = 18.0

    /**
     * The resting zoom applied when the preference is unset — the app's original
     * fixed own-marker zoom, so first open / recenter behave EXACTLY as before
     * until the user moves the slider. Sourced from [MapMarkers.OWN_MARKER_ZOOM]
     * so the two can never drift.
     */
    val DEFAULT_ZOOM: Double = MapMarkers.OWN_MARKER_ZOOM

    /** Slider granularity (Mapbox zoom levels per notch). */
    const val STEP: Double = 0.5

    /**
     * Discrete intermediate stops for a Compose `Slider` spanning
     * [[MIN_ZOOM], [MAX_ZOOM]] at [STEP]-sized notches — i.e. the count of ticks
     * BETWEEN the two ends (`Slider`'s `steps` excludes the endpoints). With the
     * 12–18 / 0.5 spread this is 11.
     */
    val sliderSteps: Int = (((MAX_ZOOM - MIN_ZOOM) / STEP).toInt() - 1).coerceAtLeast(0)

    /** Confines [zoom] to the valid resting range. */
    fun clamp(zoom: Double): Double = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /**
     * Clamps [zoom] to range AND rounds it to the nearest [STEP] notch, so the
     * stored/applied value is always one the slider can actually represent (no
     * drift from float arithmetic).
     */
    fun snap(zoom: Double): Double {
        val notches = Math.round((clamp(zoom) - MIN_ZOOM) / STEP).toDouble()
        return clamp(MIN_ZOOM + notches * STEP)
    }

    /**
     * Decodes a persisted raw value into a usable resting zoom. A null (nothing
     * stored yet) or non-finite value falls back to [DEFAULT_ZOOM] rather than
     * throwing or trusting a corrupt/hand-edited number; anything else is snapped
     * into the valid range. This is the ONE place "unset means the old default"
     * lives, kept pure so it is unit-testable without a `SharedPreferences`.
     */
    fun fromStored(raw: Float?): Double =
        if (raw == null || !raw.isFinite()) DEFAULT_ZOOM else snap(raw.toDouble())
}

/**
 * Device-local persistence for the resting map zoom ([MapZoomPreference]).
 *
 * SharedPreferences, no Firebase — mirroring
 * [com.kungsbackacarcommunity.app.design.ThemePreferenceStore] and
 * [com.kungsbackacarcommunity.app.welcome.WelcomeStore]. Deliberately
 * device-local and NOT account state: "this phone likes the map this close"
 * belongs to the phone (a phone mount an arm's length away wants it tighter than
 * one in the hand), it must work before sign-in, and syncing it would need a
 * rules/Firestore change for no user-visible benefit.
 *
 * Exposes a [StateFlow] rather than a getter so a change applies live: collectors
 * (the map surface, the slider) re-read on the spot with no app restart. The flow
 * is seeded from disk on construction, so the choice survives process death.
 */
class MapZoomPreferenceStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(readStored())

    /** The current resting zoom; emits again on every [set]. */
    val browsingZoom: StateFlow<Double> = state.asStateFlow()

    /** Records the user's choice (snapped to range) and applies it to collectors. */
    fun set(zoom: Double) {
        val snapped = MapZoomPreference.snap(zoom)
        prefs.edit().putFloat(KEY_ZOOM, snapped.toFloat()).apply()
        state.value = snapped
    }

    private fun readStored(): Double {
        val raw = if (prefs.contains(KEY_ZOOM)) prefs.getFloat(KEY_ZOOM, DEFAULT_RAW) else null
        return MapZoomPreference.fromStored(raw)
    }

    private companion object {
        const val PREFS_NAME = "app_map_zoom_preference"
        const val KEY_ZOOM = "browsing_zoom"

        // Only reached when the key is present, so its exact value is never used;
        // getFloat still demands a default argument.
        const val DEFAULT_RAW = 0f
    }
}

/**
 * Read/write access to the resting-map-zoom preference for the map-layers popup,
 * which sits several levels down inside the shell rather than beside the activity
 * that owns the store.
 *
 * A CompositionLocal rather than parameters threaded through AuthenticatedApp,
 * MapHome and MapLayersPopup — exactly the pattern
 * [com.kungsbackacarcommunity.app.design.LocalThemeController] uses for the theme
 * setting, and for the same reason: this keeps the shell's already very wide
 * parameter lists out of it. Tests / previews get the no-op default below.
 */
interface MapZoomController {
    val browsingZoom: Double

    fun setBrowsingZoom(zoom: Double)
}

/**
 * Defaults to a no-op controller reporting [MapZoomPreference.DEFAULT_ZOOM], so
 * previews and any composable rendered outside the activity's provider still
 * render with the app's original resting zoom.
 */
val LocalMapZoomController = staticCompositionLocalOf<MapZoomController> {
    object : MapZoomController {
        override val browsingZoom = MapZoomPreference.DEFAULT_ZOOM

        override fun setBrowsingZoom(zoom: Double) = Unit
    }
}
