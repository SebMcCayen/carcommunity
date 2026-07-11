package com.kungsbackacarcommunity.app.navigation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class NavigationFormatTest {
    private lateinit var previous: Locale

    @Before
    fun pinLocale() {
        // Distance formatting uses the default locale's decimal separator; pin
        // US so "%.1f" is a dot for deterministic assertions.
        previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previous)
    }

    @Test
    fun `distance under a kilometre rounds to nearest ten metres`() {
        assertEquals("120 m", NavFormat.formatDistance(123.0, "m", "km"))
        assertEquals("0 m", NavFormat.formatDistance(4.0, "m", "km"))
        assertEquals("990 m", NavFormat.formatDistance(994.0, "m", "km"))
    }

    @Test
    fun `distance of a kilometre or more shows one decimal`() {
        assertEquals("1.0 km", NavFormat.formatDistance(1000.0, "m", "km"))
        assertEquals("4.5 km", NavFormat.formatDistance(4523.0, "m", "km"))
    }

    @Test
    fun `negative distance clamps to zero`() {
        assertEquals("0 m", NavFormat.formatDistance(-50.0, "m", "km"))
    }

    @Test
    fun `duration under an hour shows whole minutes`() {
        assertEquals("12 min", NavFormat.formatDuration(12 * 60.0, "min", "h"))
        assertEquals("1 min", NavFormat.formatDuration(29.0, "min", "h")) // never "0 min"
        assertEquals("59 min", NavFormat.formatDuration(59 * 60.0, "min", "h"))
    }

    @Test
    fun `duration of an hour or more shows hours and minutes`() {
        assertEquals("1 h", NavFormat.formatDuration(60 * 60.0, "min", "h"))
        assertEquals("1 h 5 min", NavFormat.formatDuration(65 * 60.0, "min", "h"))
        assertEquals("2 h 30 min", NavFormat.formatDuration(150 * 60.0, "min", "h"))
    }
}
