package com.kungsbackacarcommunity.app.garage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- registration plate (deliberately-public field) --------------------

    @Test
    fun `plate is trimmed, whitespace-collapsed and uppercased`() {
        assertEquals("ABC 123", VehicleValidation.normaliseRegistrationPlate("  abc   123  "))
        assertEquals("ABC123", VehicleValidation.normaliseRegistrationPlate("abc123"))
    }

    @Test
    fun `blank plate normalises to null so the field clears`() {
        assertNull(VehicleValidation.normaliseRegistrationPlate(""))
        assertNull(VehicleValidation.normaliseRegistrationPlate("   "))
        val input = VehicleValidation.toInput(valid().copy(registrationPlate = "  "), year)
        assertNull(input!!.registrationPlate)
    }

    @Test
    fun `a valid plate round-trips into the input, normalised`() {
        val input = VehicleValidation.toInput(valid().copy(registrationPlate = " abc 12 "), year)
        assertEquals("ABC 12", input!!.registrationPlate)
    }

    @Test
    fun `an over-length plate is rejected, checked against the normalised value`() {
        val tooLong = "a".repeat(VehicleValidation.REGISTRATION_PLATE_MAX_LENGTH + 1)
        assertEquals(
            VehicleFieldError.REGISTRATION_PLATE_TOO_LONG,
            VehicleValidation.validate(valid().copy(registrationPlate = tooLong), year),
        )
        // Exactly at the cap is fine, and padding/whitespace does not count.
        assertNull(
            VehicleValidation.validate(
                valid().copy(registrationPlate = "a".repeat(VehicleValidation.REGISTRATION_PLATE_MAX_LENGTH)),
                year,
            ),
        )
        assertNull(VehicleValidation.validate(valid().copy(registrationPlate = "   ABC 123   "), year))
    }

    @Test
    fun `powertrain parses wire values`() {
        assertEquals(VehiclePowertrain.PLUG_IN_HYBRID, VehiclePowertrain.fromWire("plug_in_hybrid"))
        assertEquals(VehiclePowertrain.ELECTRIC, VehiclePowertrain.fromWire("electric"))
        assertNull(VehiclePowertrain.fromWire("steam"))
    }

    // --- the offered four --------------------------------------------------

    @Test
    fun `the form offers exactly Petrol Diesel Hybrid Electric in that order`() {
        // Pins the SPECIFIC set and order Seb asked for, not just a count: this
        // list is what VehicleFormScreen renders.
        assertEquals(
            listOf(
                VehiclePowertrain.PETROL,
                VehiclePowertrain.DIESEL,
                VehiclePowertrain.HYBRID,
                VehiclePowertrain.ELECTRIC,
            ),
            VehiclePowertrain.selectable(),
        )
    }

    @Test
    fun `the four offered powertrains carry the wire values the backend accepts`() {
        assertEquals(
            listOf("petrol", "diesel", "hybrid", "electric"),
            VehiclePowertrain.selectable().map { it.wire },
        )
    }

    @Test
    fun `retired powertrains are never offered`() {
        assertFalse(VehiclePowertrain.PLUG_IN_HYBRID.isSelectable)
        assertFalse(VehiclePowertrain.OTHER.isSelectable)
        assertFalse(VehiclePowertrain.selectable().contains(VehiclePowertrain.PLUG_IN_HYBRID))
        assertFalse(VehiclePowertrain.selectable().contains(VehiclePowertrain.OTHER))
    }

    // --- backward compatibility --------------------------------------------

    @Test
    fun `retired wire values still parse so existing vehicles keep loading`() {
        // Load-bearing: FirebaseGarageRepository.toVehicle drops the WHOLE
        // vehicle when fromWire returns null, so if these ever stopped parsing,
        // every pre-existing plug-in-hybrid / other car would vanish from its
        // owner's garage — silently, with no error anywhere.
        assertEquals(VehiclePowertrain.PLUG_IN_HYBRID, VehiclePowertrain.fromWire("plug_in_hybrid"))
        assertEquals(VehiclePowertrain.OTHER, VehiclePowertrain.fromWire("other"))
    }

    @Test
    fun `a vehicle keeping a retired powertrain still validates and round-trips it`() {
        // Editing (say) a plug-in hybrid's mileage must not force a powertrain
        // change, and must not rewrite the stored value to something else.
        val form = valid().copy(powertrain = VehiclePowertrain.PLUG_IN_HYBRID)
        assertNull(VehicleValidation.validate(form, year))
        val input = VehicleValidation.toInput(form, year)
        assertEquals(VehiclePowertrain.PLUG_IN_HYBRID, input!!.powertrain)
        assertEquals("plug_in_hybrid", input.powertrain.wire)
    }
}
