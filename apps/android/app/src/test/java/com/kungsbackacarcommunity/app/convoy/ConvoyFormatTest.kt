package com.kungsbackacarcommunity.app.convoy

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure summary formatting (locale-neutral). */
class ConvoyFormatTest {

    @Test
    fun `duration formats seconds, minutes and hours`() {
        assertEquals("0s", ConvoyFormat.duration(0))
        assertEquals("45s", ConvoyFormat.duration(45))
        assertEquals("3m 20s", ConvoyFormat.duration(200))
        assertEquals("1h 01m", ConvoyFormat.duration(3661))
    }

    @Test
    fun `negative duration clamps to zero`() {
        assertEquals("0s", ConvoyFormat.duration(-10))
    }

    @Test
    fun `distance switches from metres to kilometres at 1000m`() {
        assertEquals("540 m", ConvoyFormat.distance(540.0))
        assertEquals("1.0 km", ConvoyFormat.distance(1000.0))
        assertEquals("12.3 km", ConvoyFormat.distance(12345.0))
    }
}
