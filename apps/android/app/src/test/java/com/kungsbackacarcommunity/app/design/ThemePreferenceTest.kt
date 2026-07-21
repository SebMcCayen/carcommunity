package com.kungsbackacarcommunity.app.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme-preference decision logic: which light/dark scheme each setting
 * produces. Pure Kotlin, so it runs on the JVM — the Compose side (that this
 * decision actually reaches the rendered colours, and that it survives
 * navigation) is covered by ThemePreferenceUiTest.
 */
class ThemePreferenceTest {

    @Test
    fun `automatic follows the system in both directions`() {
        assertTrue(ThemePreference.SYSTEM.resolveDark(systemInDark = true))
        assertFalse(ThemePreference.SYSTEM.resolveDark(systemInDark = false))
    }

    /**
     * The bug Seb reported, as an assertion: with the device on dark (a
     * scheduled sunset->sunrise theme, battery saver, or just a manual system
     * setting), an explicit Light choice must still render light. If this ever
     * returns true the app is following the system behind the user's back again.
     */
    @Test
    fun `light stays light even while the system is dark`() {
        assertFalse(ThemePreference.LIGHT.resolveDark(systemInDark = true))
        assertFalse(ThemePreference.LIGHT.resolveDark(systemInDark = false))
    }

    @Test
    fun `dark stays dark even while the system is light`() {
        assertTrue(ThemePreference.DARK.resolveDark(systemInDark = false))
        assertTrue(ThemePreference.DARK.resolveDark(systemInDark = true))
    }

    /**
     * Only SYSTEM may change its answer when the system flips. LIGHT and DARK
     * are the "stop changing it automatically" half of the feature, so their
     * output must be independent of the input.
     */
    @Test
    fun `only automatic reacts to a system theme flip`() {
        ThemePreference.entries.forEach { preference ->
            val reacts = preference.resolveDark(true) != preference.resolveDark(false)
            assertEquals(
                "${preference.name} reacting to the system flip",
                preference == ThemePreference.SYSTEM,
                reacts,
            )
        }
    }

    @Test
    fun `stored names round-trip`() {
        ThemePreference.entries.forEach { preference ->
            assertEquals(preference, ThemePreference.fromStoredName(preference.name))
        }
    }

    /**
     * A missing key (first run) or a value this build no longer knows (enum
     * renamed by an update, hand-edited prefs) must fall back to the default
     * rather than throw the way `valueOf` would — this runs during activity
     * start-up, so a throw here is a launch crash.
     */
    @Test
    fun `unknown or absent stored names fall back to automatic`() {
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredName(null))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredName(""))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredName("SEPIA"))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredName("light"))
    }
}
