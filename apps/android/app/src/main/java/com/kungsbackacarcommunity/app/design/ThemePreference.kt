package com.kungsbackacarcommunity.app.design

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import com.kungsbackacarcommunity.app.shell.MapLayersPreferenceStore
import com.kungsbackacarcommunity.app.shell.MapMode

/**
 * The user's app-wide light/dark choice.
 *
 * [SYSTEM] decodes the former default (following Android), migrated to the
 * automatic schedule by the store. [LIGHT] and [DARK] are explicit and
 * deliberately *sticky*: once chosen, nothing the system does — a scheduled
 * sunset->sunrise flip, battery-saver forcing dark, or the user toggling the
 * system theme — changes the app's appearance. That stickiness is the whole
 * point of the setting. [AUTOMATIC] follows the user's local-clock schedule.
 * [SYSTEM] is retained for decoding legacy values; Map layers offers the other three choices.
 */
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
    AUTOMATIC,
    ;

    /**
     * The dark/light decision this preference produces, given what the system
     * currently reports. The single place the app answers "are we dark?" —
     * [KccTheme], the OS bar tinting, and the map's day/night default all read
     * the result of this, so they cannot drift apart.
     */
    fun resolveDark(
        systemInDark: Boolean,
        localTime: LocalTime = LocalTime.now(),
        schedule: AutomaticThemeSchedule = AutomaticThemeSchedule.DEFAULT,
    ): Boolean =
        when (this) {
            SYSTEM -> systemInDark
            LIGHT -> false
            DARK -> true
            AUTOMATIC -> schedule.isDark(localTime)
        }

    companion object {
        /**
         * Parses a persisted value. Unknown/absent/corrupt names fall back to
         * [SYSTEM] rather than throwing: `valueOf` raises on an unrecognised
         * constant, which would crash startup after a rename or a hand-edited
         * prefs file. (Same defensive parse as MapHome's MapMode saver.)
         */
        fun fromStoredName(name: String?): ThemePreference =
            entries.find { it.name == name } ?: SYSTEM
    }
}

/**
 * Device-local persistence for [ThemePreference].
 *
 * SharedPreferences, no Firebase — mirroring
 * [com.kungsbackacarcommunity.app.welcome.WelcomeStore] and
 * [com.kungsbackacarcommunity.app.whatsnew.WhatsNewStore]. Deliberately
 * device-local and NOT account state: "this phone renders the app dark" belongs
 * to the phone (a bright car interior vs. a dim one), it must work before
 * sign-in, and syncing it would need a rules/Firestore change for no
 * user-visible benefit.
 *
 * Exposes a [StateFlow] rather than a getter so a change applies live: the
 * activity collects it, so picking a preference re-renders the theme on the
 * spot with no app restart. The flow is seeded from disk on construction, so
 * the choice also survives process death.
 */
class ThemePreferenceStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Upgrade once: the old explicit map choice wins, since it was the visible map style.
    // Otherwise keep explicit app choices and move system-following/default users to the schedule.
    private val state = MutableStateFlow(readUnifiedPreference(context))
    private val scheduleState = MutableStateFlow(
        AutomaticThemeSchedule.fromStoredMinutes(
            prefs.getInt(KEY_DAY_START, 7 * 60),
            prefs.getInt(KEY_NIGHT_START, 19 * 60),
        ),
    )

    /** The current preference; emits again on every [set]. */
    val preference: StateFlow<ThemePreference> = state.asStateFlow()
    val schedule: StateFlow<AutomaticThemeSchedule> = scheduleState.asStateFlow()

    private fun readUnifiedPreference(context: Context): ThemePreference {
        val previous = ThemePreference.fromStoredName(prefs.getString(KEY_MODE, null))
        if (prefs.getBoolean(KEY_UNIFIED, false)) {
            return if (previous == ThemePreference.SYSTEM) ThemePreference.AUTOMATIC else previous
        }
        val legacyLayers = MapLayersPreferenceStore(context)
        val unified = when (legacyLayers.readNightMode()) {
            MapMode.Day -> ThemePreference.LIGHT
            MapMode.Night -> ThemePreference.DARK
            null -> if (previous == ThemePreference.SYSTEM) ThemePreference.AUTOMATIC else previous
        }
        prefs.edit().putString(KEY_MODE, unified.name).putBoolean(KEY_UNIFIED, true).apply()
        legacyLayers.writeNightMode(null)
        return unified
    }

    /** Records the user's choice and applies it to every collector immediately. */
    fun set(preference: ThemePreference) {
        val choice = if (preference == ThemePreference.SYSTEM) ThemePreference.AUTOMATIC else preference
        prefs.edit().putString(KEY_MODE, choice.name).apply()
        state.value = choice
    }

    fun setSchedule(schedule: AutomaticThemeSchedule) {
        prefs.edit()
            .putInt(KEY_DAY_START, schedule.dayStart.toSecondOfDay() / 60)
            .putInt(KEY_NIGHT_START, schedule.nightStart.toSecondOfDay() / 60)
            .apply()
        scheduleState.value = schedule
    }

    private companion object {
        const val PREFS_NAME = "app_theme_preference"
        const val KEY_MODE = "mode"
        const val KEY_UNIFIED = "unified_map_theme"
        const val KEY_DAY_START = "day_start_minute"
        const val KEY_NIGHT_START = "night_start_minute"
    }
}

/**
 * Read/write access to the theme preference for screens far from the activity
 * that owns the store (the map-layers panel is nested inside the shell).
 *
 * A CompositionLocal rather than parameters threaded through AuthenticatedApp
 * and RouteHost: the theme is genuinely ambient — it already flows down as
 * [LocalKccDarkTheme] — and a single provider at the activity root keeps this
 * out of the shell's already very wide parameter lists. Tests provide a fake.
 */
interface ThemeController {
    val preference: ThemePreference
    val schedule: AutomaticThemeSchedule get() = AutomaticThemeSchedule.DEFAULT

    fun setPreference(preference: ThemePreference)
    fun setSchedule(schedule: AutomaticThemeSchedule) = Unit
}

/**
 * Defaults to a no-op controller reporting [ThemePreference.AUTOMATIC] so previews
 * and any composable rendered outside the activity's provider still render.
 */
val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    object : ThemeController {
        override val preference = ThemePreference.AUTOMATIC

        override fun setPreference(preference: ThemePreference) = Unit
    }
}
