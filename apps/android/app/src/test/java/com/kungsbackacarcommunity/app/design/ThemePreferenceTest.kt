package com.kungsbackacarcommunity.app.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId

/**
 * The theme-preference decision logic: which light/dark scheme each setting
 * produces. Pure Kotlin, so it runs on the JVM — the Compose side (that this
 * decision actually reaches the rendered colours, and that it survives
 * navigation) is covered by ThemePreferenceUiTest.
 */
class ThemePreferenceTest {

    @Test
    fun `system follows the OS in both directions`() {
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
    fun `only system reacts to a system theme flip`() {
        ThemePreference.entries.forEach { preference ->
            val reacts = preference.resolveDark(true, LocalTime.NOON) != preference.resolveDark(false, LocalTime.NOON)
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
    fun `unknown or absent stored names fall back to system`() {
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredName(null))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredName(""))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredName("SEPIA"))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredName("light"))
    }
    @Test
    fun `automatic boundaries include 0700 day and 1900 night`() {
        listOf(
            LocalTime.MIDNIGHT to true,
            LocalTime.of(6, 59, 59, 999999999) to true,
            LocalTime.of(7, 0) to false,
            LocalTime.NOON to false,
            LocalTime.of(18, 59, 59, 999999999) to false,
            LocalTime.of(19, 0) to true,
            LocalTime.MAX to true,
        ).forEach { (time, expected) ->
            listOf(false, true).forEach { systemDark ->
                assertEquals("$time with OS dark=$systemDark", expected,
                    ThemePreference.AUTOMATIC.resolveDark(systemDark, time))
            }
        }
    }

    @Test
    fun `explicit and OS choices ignore the clock at every hour`() {
        (0..23).forEach { hour ->
            val time = LocalTime.of(hour, 0)
            listOf(false, true).forEach { systemDark ->
                assertFalse(ThemePreference.LIGHT.resolveDark(systemDark, time))
                assertTrue(ThemePreference.DARK.resolveDark(systemDark, time))
                assertEquals(systemDark, ThemePreference.SYSTEM.resolveDark(systemDark, time))
            }
        }
    }

    @Test
    fun `same instant follows current timezone including daylight saving offsets`() {
        val instant = Instant.parse("2026-10-27T06:30:00Z")
        fun darkIn(zone: String) = AutomaticThemeSchedule.isDark(instant.atZone(ZoneId.of(zone)).toLocalTime())
        assertFalse(darkIn("Europe/Stockholm")) // 07:30
        assertTrue(darkIn("America/New_York")) // 02:30
        assertTrue(darkIn("Pacific/Auckland")) // 19:30
        listOf("2026-03-29T05:00:00Z", "2026-10-25T06:00:00Z").forEach {
            assertFalse(AutomaticThemeSchedule.isDark(Instant.parse(it).atZone(ZoneId.of("Europe/Stockholm")).toLocalTime()))
        }
        listOf("2026-03-29T04:59:59Z", "2026-10-25T05:59:59Z").forEach {
            assertTrue(AutomaticThemeSchedule.isDark(Instant.parse(it).atZone(ZoneId.of("Europe/Stockholm")).toLocalTime()))
        }
    }

}
