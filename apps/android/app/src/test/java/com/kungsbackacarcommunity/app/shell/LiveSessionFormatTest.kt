package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSessionFormatTest {
    @Test
    fun underAnHour_rendersMinutesAndSeconds() {
        assertEquals("0:00", LiveSessionFormat.elapsedLabel(0L))
        assertEquals("0:07", LiveSessionFormat.elapsedLabel(7_000L))
        assertEquals("12:34", LiveSessionFormat.elapsedLabel((12 * 60 + 34) * 1000L))
        assertEquals("59:59", LiveSessionFormat.elapsedLabel((59 * 60 + 59) * 1000L))
    }

    @Test
    fun anHourOrMore_rendersHoursAndZeroPaddedMinutes() {
        assertEquals("1h 00m", LiveSessionFormat.elapsedLabel(60 * 60 * 1000L))
        assertEquals("1h 04m", LiveSessionFormat.elapsedLabel((60 + 4) * 60 * 1000L))
        assertEquals("5h 59m", LiveSessionFormat.elapsedLabel((5 * 60 + 59) * 60 * 1000L))
    }

    @Test
    fun subSecondAndNegative_areFlooredAtZero() {
        assertEquals("0:00", LiveSessionFormat.elapsedLabel(999L))
        assertEquals("0:00", LiveSessionFormat.elapsedLabel(-5_000L))
    }
}
