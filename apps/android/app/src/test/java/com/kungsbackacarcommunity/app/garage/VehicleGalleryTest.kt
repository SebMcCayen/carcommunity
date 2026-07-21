package com.kungsbackacarcommunity.app.garage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the car-detail photo gallery ([VehicleGallery]). */
class VehicleGalleryTest {

    private fun vehicle(imagePath: String?) =
        Vehicle(
            id = "v1",
            make = "Volvo",
            model = "240",
            modelYear = 1988,
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = "B230",
            imagePath = imagePath,
        )

    @Test
    fun `photoPaths yields the single image when present`() {
        val paths = VehicleGallery.photoPaths(vehicle("vehicleImages/u/v1/a.jpg"))
        assertEquals(listOf("vehicleImages/u/v1/a.jpg"), paths)
    }

    @Test
    fun `photoPaths is empty when the car has no photo`() {
        assertEquals(emptyList<String>(), VehicleGallery.photoPaths(vehicle(null)))
    }

    @Test
    fun `photoPaths drops a blank path`() {
        assertEquals(emptyList<String>(), VehicleGallery.photoPaths(vehicle("   ")))
    }

    @Test
    fun `clampIndex keeps an in-range index`() {
        assertEquals(2, VehicleGallery.clampIndex(2, count = 4))
    }

    @Test
    fun `clampIndex pulls a too-large index back to the last photo`() {
        assertEquals(2, VehicleGallery.clampIndex(9, count = 3))
    }

    @Test
    fun `clampIndex floors a negative index at zero`() {
        assertEquals(0, VehicleGallery.clampIndex(-1, count = 3))
    }

    @Test
    fun `clampIndex is zero for an empty gallery`() {
        assertEquals(0, VehicleGallery.clampIndex(4, count = 0))
    }

    @Test
    fun `hasMultiple is true only above one photo`() {
        assertFalse(VehicleGallery.hasMultiple(0))
        assertFalse(VehicleGallery.hasMultiple(1))
        assertTrue(VehicleGallery.hasMultiple(2))
    }
}
