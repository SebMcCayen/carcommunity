package com.kungsbackacarcommunity.app.garage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleValidationTest {

    private val year = 2026

    private fun valid() =
        VehicleForm(
            make = "Volvo",
            model = "240",
            modelYear = "1988",
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = "B230",
        )

    @Test
    fun `a complete form is valid and maps to an input`() {
        assertNull(VehicleValidation.validate(valid(), year))
        val input = VehicleValidation.toInput(valid(), year)
        assertNotNull(input)
        assertEquals("Volvo", input!!.make)
        assertEquals(1988, input.modelYear)
        assertEquals(VehiclePowertrain.PETROL, input.powertrain)
        assertEquals("B230", input.engineDescription)
    }

    @Test
    fun `blank make, model, year, and missing powertrain each fail`() {
        assertEquals(VehicleFieldError.MAKE_REQUIRED, VehicleValidation.validate(valid().copy(make = "  "), year))
        assertEquals(VehicleFieldError.MODEL_REQUIRED, VehicleValidation.validate(valid().copy(model = ""), year))
        assertEquals(VehicleFieldError.MODEL_YEAR_REQUIRED, VehicleValidation.validate(valid().copy(modelYear = ""), year))
        assertEquals(VehicleFieldError.POWERTRAIN_REQUIRED, VehicleValidation.validate(valid().copy(powertrain = null), year))
    }

    @Test
    fun `year out of bounds or non-numeric is invalid`() {
        assertEquals(VehicleFieldError.MODEL_YEAR_INVALID, VehicleValidation.validate(valid().copy(modelYear = "1800"), year))
        assertEquals(VehicleFieldError.MODEL_YEAR_INVALID, VehicleValidation.validate(valid().copy(modelYear = "3000"), year))
        assertEquals(VehicleFieldError.MODEL_YEAR_INVALID, VehicleValidation.validate(valid().copy(modelYear = "nope"), year))
        // Boundary: currentYear + 2 is allowed.
        assertNull(VehicleValidation.validate(valid().copy(modelYear = "2028"), year))
    }

    @Test
    fun `over-length make, model and engine description are rejected`() {
        val longName = "x".repeat(VehicleValidation.MAKE_MODEL_MAX_LENGTH + 1)
        assertEquals(VehicleFieldError.MAKE_TOO_LONG, VehicleValidation.validate(valid().copy(make = longName), year))
        assertEquals(VehicleFieldError.MODEL_TOO_LONG, VehicleValidation.validate(valid().copy(model = longName), year))
        val longEngine = "x".repeat(VehicleValidation.ENGINE_DESCRIPTION_MAX_LENGTH + 1)
        assertEquals(
            VehicleFieldError.ENGINE_DESCRIPTION_TOO_LONG,
            VehicleValidation.validate(valid().copy(engineDescription = longEngine), year),
        )
        // Exactly at the bound is fine.
        assertNull(VehicleValidation.validate(valid().copy(make = "x".repeat(VehicleValidation.MAKE_MODEL_MAX_LENGTH)), year))
    }

    @Test
    fun `blank engine description maps to null`() {
        val input = VehicleValidation.toInput(valid().copy(engineDescription = "   "), year)
        assertNull(input!!.engineDescription)
    }

    @Test
    fun `powertrain parses wire values`() {
        assertEquals(VehiclePowertrain.PLUG_IN_HYBRID, VehiclePowertrain.fromWire("plug_in_hybrid"))
        assertEquals(VehiclePowertrain.ELECTRIC, VehiclePowertrain.fromWire("electric"))
        assertNull(VehiclePowertrain.fromWire("steam"))
    }
}
