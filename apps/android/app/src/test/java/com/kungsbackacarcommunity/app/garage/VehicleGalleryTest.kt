package com.kungsbackacarcommunity.app.garage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the car-detail photo gallery ([VehicleGallery]). */
class VehicleGalleryTest {

    private fun vehicle(imagePath: String?, photoPaths: List<String> = emptyList()) =
        Vehicle(
            id = "v1",
            make = "Volvo",
            model = "240",
            modelYear = 1988,
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = "B230",
            imagePath = imagePath,
            photoPaths = photoPaths,
        )

    @Test
    fun `photoPaths uses the real array when present, cover first`() {
        val paths =
            VehicleGallery.photoPaths(
                vehicle(
                    imagePath = "vehicleImages/u/v1/a.jpg",
                    photoPaths = listOf("vehicleImages/u/v1/a.jpg", "vehicleImages/u/v1/b.jpg"),
                ),
            )
        assertEquals(listOf("vehicleImages/u/v1/a.jpg", "vehicleImages/u/v1/b.jpg"), paths)
    }

    @Test
    fun `photoPaths falls back to imagePath for a legacy single-photo car`() {
        // No photoPaths array (legacy doc) → the single imagePath still shows.
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
        assertEquals(
            listOf("vehicleImages/u/v1/a.jpg"),
            VehicleGallery.photoPaths(
                vehicle(imagePath = null, photoPaths = listOf("  ", "vehicleImages/u/v1/a.jpg")),
            ),
        )
    }

    @Test
    fun `isCover is true only for the first photo`() {
        val paths = listOf("a", "b", "c")
        assertTrue(VehicleGallery.isCover(paths, "a"))
        assertFalse(VehicleGallery.isCover(paths, "b"))
        assertFalse(VehicleGallery.isCover(emptyList(), "a"))
    }

    @Test
    fun `moveToCover puts the chosen photo first and keeps the rest in order`() {
        assertEquals(
            listOf("c", "a", "b"),
            VehicleGallery.moveToCover(listOf("a", "b", "c"), "c"),
        )
        // Already the cover → unchanged (still a valid permutation).
        assertEquals(listOf("a", "b"), VehicleGallery.moveToCover(listOf("a", "b"), "a"))
        // A path not in the gallery leaves the order untouched.
        assertEquals(listOf("a", "b"), VehicleGallery.moveToCover(listOf("a", "b"), "z"))
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
