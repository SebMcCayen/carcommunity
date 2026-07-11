package com.kungsbackacarcommunity.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineCodecTest {
    @Test
    fun `empty string decodes to no points`() {
        assertTrue(PolylineCodec.decode("").isEmpty())
    }

    @Test
    fun `decodes the canonical precision-5 example to lng, lat points`() {
        // Google's reference polyline for
        // (38.5,-120.2), (40.7,-120.95), (43.252,-126.453).
        val points = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 1e5)
        assertEquals(3, points.size)
        assertEquals(-120.2, points[0].longitude, 1e-5)
        assertEquals(38.5, points[0].latitude, 1e-5)
        assertEquals(-120.95, points[1].longitude, 1e-5)
        assertEquals(40.7, points[1].latitude, 1e-5)
        assertEquals(-126.453, points[2].longitude, 1e-5)
        assertEquals(43.252, points[2].latitude, 1e-5)
    }
}
