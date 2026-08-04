package com.kungsbackacarcommunity.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resting-zoom preference maths ("how far away the focus is"): the range
 * clamp, the notch snap, the stored-value decode, and the slider step count.
 *
 * Pure Kotlin, so it runs in the BLOCKING unit-test job — the SharedPreferences
 * wrapper ([MapZoomPreferenceStore]) and the on-map application (that this zoom
 * actually reaches the first-fix / recenter camera) are thin shells over this
 * logic, so pinning the logic here is what protects the behaviour off-device.
 */
class MapZoomPreferenceTest {

    @Test
    fun `default resting zoom is the app's original own-marker zoom`() {
        // The whole point of the default: an untouched slider must reproduce the
        // pre-preference framing to the decimal. If OWN_MARKER_ZOOM ever moves, the
        // default rides along with it.
        assertEquals(MapMarkers.OWN_MARKER_ZOOM, MapZoomPreference.DEFAULT_ZOOM, 1e-9)
    }

    @Test
    fun `the range brackets the default so it is always reachable`() {
        assertTrue(MapZoomPreference.MIN_ZOOM < MapZoomPreference.DEFAULT_ZOOM)
        assertTrue(MapZoomPreference.DEFAULT_ZOOM < MapZoomPreference.MAX_ZOOM)
    }

    @Test
    fun `clamp confines out-of-range values to the ends and leaves in-range alone`() {
        assertEquals(MapZoomPreference.MIN_ZOOM, MapZoomPreference.clamp(0.0), 1e-9)
        assertEquals(MapZoomPreference.MIN_ZOOM, MapZoomPreference.clamp(MapZoomPreference.MIN_ZOOM - 5.0), 1e-9)
        assertEquals(MapZoomPreference.MAX_ZOOM, MapZoomPreference.clamp(100.0), 1e-9)
        assertEquals(15.0, MapZoomPreference.clamp(15.0), 1e-9)
    }

    @Test
    fun `snap rounds to the nearest notch and stays in range`() {
        // 14.2 -> nearest 0.5 notch is 14.0; 14.3 -> 14.5.
        assertEquals(14.0, MapZoomPreference.snap(14.2), 1e-9)
        assertEquals(14.5, MapZoomPreference.snap(14.3), 1e-9)
        // Out of range still snaps to a valid end notch.
        assertEquals(MapZoomPreference.MIN_ZOOM, MapZoomPreference.snap(-1.0), 1e-9)
        assertEquals(MapZoomPreference.MAX_ZOOM, MapZoomPreference.snap(50.0), 1e-9)
    }

    @Test
    fun `a valid notch round-trips through snap unchanged`() {
        var z = MapZoomPreference.MIN_ZOOM
        while (z <= MapZoomPreference.MAX_ZOOM) {
            assertEquals("notch $z should be stable", z, MapZoomPreference.snap(z), 1e-9)
            z += MapZoomPreference.STEP
        }
    }

    @Test
    fun `fromStored maps unset and corrupt values to the default`() {
        // Unset (nothing persisted yet) is the "existing behaviour unchanged" case.
        assertEquals(MapZoomPreference.DEFAULT_ZOOM, MapZoomPreference.fromStored(null), 1e-9)
        assertEquals(MapZoomPreference.DEFAULT_ZOOM, MapZoomPreference.fromStored(Float.NaN), 1e-9)
        assertEquals(
            MapZoomPreference.DEFAULT_ZOOM,
            MapZoomPreference.fromStored(Float.POSITIVE_INFINITY),
            1e-9,
        )
    }

    @Test
    fun `fromStored snaps a genuine stored value into range`() {
        assertEquals(14.5, MapZoomPreference.fromStored(14.5f), 1e-9)
        // A hand-edited / stale out-of-range number is clamped, never trusted.
        assertEquals(MapZoomPreference.MAX_ZOOM, MapZoomPreference.fromStored(99f), 1e-9)
    }

    @Test
    fun `slider step count matches the range and granularity`() {
        // 12.0..18.0 at 0.5 = 13 stops, i.e. 11 ticks BETWEEN the two ends.
        assertEquals(11, MapZoomPreference.sliderSteps)
    }
}
