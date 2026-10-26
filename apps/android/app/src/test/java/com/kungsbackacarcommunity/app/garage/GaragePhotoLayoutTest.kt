package com.kungsbackacarcommunity.app.garage

import org.junit.Assert.assertEquals
import org.junit.Test

class GaragePhotoLayoutTest {
    @Test fun `narrow effective width keeps the previous compact ratio`() {
        assertEquals(2.5f, garagePhotoAspectRatio(320f, 1f), 0f)
        assertEquals(2.5f, garagePhotoAspectRatio(359f, 1f), 0f)
        assertEquals(2.5f, garagePhotoAspectRatio(430f, 1.5f), 0f)
    }

    @Test fun `larger reading width permits the photographic ratio`() {
        assertEquals(16f / 9f, garagePhotoAspectRatio(360f, 1f), 0f)
        assertEquals(16f / 9f, garagePhotoAspectRatio(430f, 1f), 0f)
    }
}
