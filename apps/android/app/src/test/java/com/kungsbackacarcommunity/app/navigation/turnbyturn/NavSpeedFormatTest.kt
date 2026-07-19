package com.kungsbackacarcommunity.app.navigation.turnbyturn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the navigation speed readout's pure core.
 *
 * These matter more than usual because the SDK glue that feeds [NavSpeedFormat]
 * lives in the token-gated `src/nav` set and CANNOT be exercised in CI — so the
 * two rules that make the feature safe (km/h conversion, and "no trustworthy
 * limit ⇒ show nothing") are pinned here, where they run on every build.
 */
class NavSpeedFormatTest {
    @Test
    fun `current speed converts m per s to whole km per h`() {
        assertEquals(36, NavSpeedFormat.currentKmhFromMetersPerSecond(10.0))
        // 25 m/s = 90 km/h — a Swedish motorway cruise.
        assertEquals(90, NavSpeedFormat.currentKmhFromMetersPerSecond(25.0))
        // Rounds to nearest, not truncated: 13.9 m/s = 50.04 km/h.
        assertEquals(50, NavSpeedFormat.currentKmhFromMetersPerSecond(13.9))
    }

    @Test
    fun `current speed reads as zero when the fix has no usable speed`() {
        // A fix with no speed at all, and the negative "unknown" sentinel some
        // providers use, both show a stationary 0 rather than blanking the
        // readout or rendering a negative speed.
        assertEquals(0, NavSpeedFormat.currentKmhFromMetersPerSecond(null))
        assertEquals(0, NavSpeedFormat.currentKmhFromMetersPerSecond(-1.0))
        assertEquals(0, NavSpeedFormat.currentKmhFromMetersPerSecond(0.0))
        assertEquals(0, NavSpeedFormat.currentKmhFromMetersPerSecond(Double.NaN))
    }

    @Test
    fun `posted limit in km per h passes through unchanged`() {
        assertEquals(50, NavSpeedFormat.postedLimitKmh(50, "KILOMETERS_PER_HOUR"))
        assertEquals(120, NavSpeedFormat.postedLimitKmh(120, "KILOMETERS_PER_HOUR"))
    }

    @Test
    fun `posted limit in mph converts to km per h`() {
        // 55 mph = 88.5 km/h.
        assertEquals(89, NavSpeedFormat.postedLimitKmh(55, "MILES_PER_HOUR"))
        // 30 mph = 48.28 km/h — rounds down, not up to the 50 it looks like.
        assertEquals(48, NavSpeedFormat.postedLimitKmh(30, "MILES_PER_HOUR"))
    }

    @Test
    fun `posted limit in m per s converts to km per h`() {
        assertEquals(36, NavSpeedFormat.postedLimitKmh(10, "METERS_PER_SECOND"))
    }

    @Test
    fun `absent posted limit shows nothing`() {
        // The COMMON case: Mapbox has no limit for this road. Must be null so the
        // UI hides the sign rather than showing a stale or guessed number.
        assertNull(NavSpeedFormat.postedLimitKmh(null, "KILOMETERS_PER_HOUR"))
    }

    @Test
    fun `non-positive posted limit shows nothing`() {
        // Zero/negative is the SDK's "unlimited / unknown" sentinel — there is no
        // number to display, so display none.
        assertNull(NavSpeedFormat.postedLimitKmh(0, "KILOMETERS_PER_HOUR"))
        assertNull(NavSpeedFormat.postedLimitKmh(-1, "KILOMETERS_PER_HOUR"))
    }

    @Test
    fun `unknown or missing unit shows nothing rather than a mis-scaled limit`() {
        // A future SDK enum value must NOT be treated as km/h: silently showing a
        // mph number as km/h is the "wrong limit" failure this guards against.
        assertNull(NavSpeedFormat.postedLimitKmh(50, "KNOTS"))
        assertNull(NavSpeedFormat.postedLimitKmh(50, null))
    }
}
