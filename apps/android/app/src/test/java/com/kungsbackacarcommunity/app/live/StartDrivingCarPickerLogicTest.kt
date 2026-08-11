package com.kungsbackacarcommunity.app.live

import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehiclePowertrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for the "Start driving" picker's default preselection
 * ([defaultStartDrivingVehicleId]). This must mirror the server's fallback
 * (pickSessionVehicleData): main car → first car → none.
 */
class StartDrivingCarPickerLogicTest {

    private fun car(id: String, isMainCar: Boolean = false) =
        Vehicle(
            id = id,
            make = "Volvo",
            model = "240",
            modelYear = 1988,
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = null,
            isMainCar = isMainCar,
        )

    @Test
    fun `prefers the main car`() {
        val vehicles = listOf(car("a"), car("b", isMainCar = true), car("c"))
        assertEquals("b", defaultStartDrivingVehicleId(vehicles))
    }

    @Test
    fun `falls back to the first car when none is main`() {
        val vehicles = listOf(car("a"), car("b"), car("c"))
        assertEquals("a", defaultStartDrivingVehicleId(vehicles))
    }

    @Test
    fun `is null when there are no cars`() {
        assertNull(defaultStartDrivingVehicleId(emptyList()))
    }
}
