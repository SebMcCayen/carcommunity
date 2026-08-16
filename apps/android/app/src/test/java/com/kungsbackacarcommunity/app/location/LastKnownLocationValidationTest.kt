package com.kungsbackacarcommunity.app.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinate-validation guard behind [LastKnownLocationStore.read]: a stored
 * pair is only handed to a map camera when it is finite and in range. Corrupt or
 * absurd values (NaN, ±Infinity, out-of-range lat/lng) are rejected so `read()`
 * reports NO cache and the Kungsbacka fallback opens the map instead of crashing
 * `setCamera(...)`.
 */
class LastKnownLocationValidationTest {

    @Test
    fun `a normal Kungsbacka coordinate is valid`() {
        assertTrue(isValidCoordinate(latitude = 57.4874, longitude = 12.0757))
    }

    @Test
    fun `the extremes of the WGS84 ranges are valid`() {
        assertTrue(isValidCoordinate(latitude = 90.0, longitude = 180.0))
        assertTrue(isValidCoordinate(latitude = -90.0, longitude = -180.0))
        assertTrue(isValidCoordinate(latitude = 0.0, longitude = 0.0))
    }

    @Test
    fun `NaN is rejected`() {
        assertFalse(isValidCoordinate(latitude = Double.NaN, longitude = 12.0))
        assertFalse(isValidCoordinate(latitude = 57.0, longitude = Double.NaN))
    }

    @Test
    fun `infinities are rejected`() {
        assertFalse(isValidCoordinate(latitude = Double.POSITIVE_INFINITY, longitude = 12.0))
        assertFalse(isValidCoordinate(latitude = 57.0, longitude = Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `out-of-range coordinates are rejected`() {
        assertFalse(isValidCoordinate(latitude = 91.0, longitude = 12.0)) // lat past the pole
        assertFalse(isValidCoordinate(latitude = -90.001, longitude = 12.0))
        assertFalse(isValidCoordinate(latitude = 57.0, longitude = 180.5)) // lng past the antimeridian
        assertFalse(isValidCoordinate(latitude = 57.0, longitude = -200.0))
    }
}
