package com.kungsbackacarcommunity.app.design

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's app-wide light/dark choice.
 *
 * [SYSTEM] is the default and reproduces the app's original behaviour (follow
 * the Android system Dark theme, live). [LIGHT] and [DARK] are explicit and
 * deliberately *sticky*: once chosen, nothing the system does — a scheduled
 * sunset->sunrise flip, battery-saver forcing dark, or the user toggling the
 * system theme — changes the app's appearance. That stickiness is the whole
 * point of the setting, so [resolveDark] ignores `systemInDark` for those two.
 */
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /**
     * The dark/light decision this preference produces, given what the system
     * currently reports. The single place the app answers "are we dark?" —
     * [KccTheme], the OS bar tinting, and the map's day/night default all read
     * the result of this, so they cannot drift apart.
     */
    fun resolveDark(systemInDark: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemInDark
            LIGHT -> false
            DARK -> true
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

    private val state = MutableStateFlow(ThemePreference.fromStoredName(prefs.getString(KEY_MODE, null)))

    /** The current preference; emits again on every [set]. */
    val preference: StateFlow<ThemePreference> = state.asStateFlow()

    /** Records the user's choice and applies it to every collector immediately. */
    fun set(preference: ThemePreference) {
        prefs.edit().putString(KEY_MODE, preference.name).apply()
        state.value = preference
    }

    private companion object {
        const val PREFS_NAME = "app_theme_preference"
        const val KEY_MODE = "mode"
    }
}

/**
 * Read/write access to the theme preference for screens far from the activity
 * that owns the store (Settings sits several levels inside the shell's route
 * host).
 *
 * A CompositionLocal rather than parameters threaded through AuthenticatedApp
 * and RouteHost: the theme is genuinely ambient — it already flows down as
 * [LocalKccDarkTheme] — and a single provider at the activity root keeps this
 * out of the shell's already very wide parameter lists. Tests provide a fake.
 */
interface ThemeController {
    val preference: ThemePreference

    fun setPreference(preference: ThemePreference)
}

/**
 * Defaults to a no-op controller reporting [ThemePreference.SYSTEM] so previews
 * and any composable rendered outside the activity's provider still render.
 */
val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    object : ThemeController {
        override val preference = ThemePreference.SYSTEM

        override fun setPreference(preference: ThemePreference) = Unit
    }
}
