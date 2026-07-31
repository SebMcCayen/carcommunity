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
            makeId = "volvo",
            modelId = "240",
            modelYear = 1988,
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = "B230",
        )

    @Test
    fun `a complete form is valid and maps to an input of catalogue ids`() {
        assertNull(VehicleValidation.validate(valid(), year))
        val input = VehicleValidation.toInput(valid(), year)
        assertNotNull(input)
        // IDS, not display text: the backend derives the text from the same
        // catalogue, and the ids are what community aggregation counts.
        assertEquals("volvo", input!!.makeId)
        assertEquals("240", input.modelId)
        assertEquals(1988, input.modelYear)
        assertEquals(VehiclePowertrain.PETROL, input.powertrain)
        assertEquals("B230", input.engineDescription)
    }

    @Test
    fun `missing make, model, year, and powertrain each fail`() {
        assertEquals(VehicleFieldError.MAKE_REQUIRED, VehicleValidation.validate(valid().copy(makeId = null), year))
        assertEquals(VehicleFieldError.MODEL_REQUIRED, VehicleValidation.validate(valid().copy(modelId = null), year))
        assertEquals(VehicleFieldError.MODEL_YEAR_REQUIRED, VehicleValidation.validate(valid().copy(modelYear = null), year))
        assertEquals(VehicleFieldError.POWERTRAIN_REQUIRED, VehicleValidation.validate(valid().copy(powertrain = null), year))
    }

    @Test
    fun `an id that is not in the catalogue is rejected`() {
        // The form can only produce catalogue ids, but a restored process state or
        // a future catalogue shrink must not sneak an unknown id past validation
        // and into an aggregate.
        assertEquals(
            VehicleFieldError.MAKE_REQUIRED,
            VehicleValidation.validate(valid().copy(makeId = "wolvo"), year),
        )
        assertEquals(
            VehicleFieldError.MODEL_REQUIRED,
            VehicleValidation.validate(valid().copy(modelId = "241"), year),
        )
    }

    @Test
    fun `a model from a different manufacturer is rejected`() {
        // Model ids are unique only WITHIN a manufacturer, so a stale selection
        // left over from switching brand must not pass as a "Mazda MGB".
        assertEquals(
            VehicleFieldError.MODEL_REQUIRED,
            VehicleValidation.validate(valid().copy(makeId = "mazda", modelId = "mgb"), year),
        )
        assertNull(VehicleValidation.validate(valid().copy(makeId = "mg", modelId = "mgb"), year))
    }

    @Test
    fun `year out of the offered window is invalid`() {
        assertEquals(VehicleFieldError.MODEL_YEAR_INVALID, VehicleValidation.validate(valid().copy(modelYear = 1800), year))
        assertEquals(VehicleFieldError.MODEL_YEAR_INVALID, VehicleValidation.validate(valid().copy(modelYear = 3000), year))
        // Boundaries: the catalogue floor and the NEXT model year are both offered.
        assertNull(VehicleValidation.validate(valid().copy(modelYear = VehicleValidation.MIN_MODEL_YEAR), year))
        assertNull(VehicleValidation.validate(valid().copy(modelYear = year + 1), year))
        // One past the next model year is not: nobody owns a car two years out.
        assertEquals(
            VehicleFieldError.MODEL_YEAR_INVALID,
            VehicleValidation.validate(valid().copy(modelYear = year + 2), year),
        )
    }

    @Test
    fun `an over-length engine description is rejected`() {
        val longEngine = "x".repeat(VehicleValidation.ENGINE_DESCRIPTION_MAX_LENGTH + 1)
        assertEquals(
            VehicleFieldError.ENGINE_DESCRIPTION_TOO_LONG,
            VehicleValidation.validate(valid().copy(engineDescription = longEngine), year),
        )
    }

    // --- the "Other / not listed" escape hatch -----------------------------

    @Test
    fun `Other is a valid selection at both levels`() {
        // The escape hatch: a rare import or a brand nobody listed must still be
        // addable, WITHOUT a free-text field. A real brand plus an "other" model
        // is the useful case — it says which brand needs a new model added.
        assertNull(VehicleValidation.validate(valid().copy(modelId = VehicleCatalogue.OTHER_ID), year))
        assertNull(
            VehicleValidation.validate(
                valid().copy(makeId = VehicleCatalogue.OTHER_ID, modelId = VehicleCatalogue.OTHER_ID),
                year,
            ),
        )
    }

    @Test
    fun `an unknown make cannot claim a known model`() {
        // "other / 240" would be a nonsense pair in the aggregate.
        assertEquals(
            VehicleFieldError.MODEL_REQUIRED,
            VehicleValidation.validate(valid().copy(makeId = VehicleCatalogue.OTHER_ID, modelId = "240"), year),
        )
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
    fun `plate uppercasing is locale-independent under the Turkish locale`() {
        // Turkish upper-cases 'i' to dotted 'İ' under a LOCALE-SENSITIVE
        // uppercase. We pin Locale.ROOT (matching the backend's locale-
        // independent JS String.toUpperCase()) so 'i' -> 'I' and the canonical
        // plate is stable across device locales.
        //
        // NOTE for future reviewers: Kotlin's NO-ARG String.uppercase() is
        // already locale-invariant — the stdlib defines it as
        // toUpperCase(Locale.ROOT), unlike the deprecated toUpperCase(). The
        // assertions below pin BOTH so nobody "fixes" this back and forth: the
        // explicit Locale.ROOT is for readability/intent, not a behaviour change.
        // (Contrast the genuinely locale-sensitive default-locale bug in #516.)
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals("ABI123", VehicleValidation.normaliseRegistrationPlate("abi123"))
            val input = VehicleValidation.toInput(valid().copy(registrationPlate = "abi 123"), year)
            assertEquals("ABI 123", input!!.registrationPlate)
            // The no-arg form under tr-TR: proves it is Locale.ROOT-based, while
            // the explicitly locale-sensitive form is what would break.
            assertEquals("ABI123", "abi123".uppercase())
            assertEquals("ABİ123", "abi123".uppercase(java.util.Locale.getDefault()))
        } finally {
            java.util.Locale.setDefault(previous)
        }
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

    // --- legacy (pre-catalogue) vehicles ----------------------------------

    @Test
    fun `a legacy vehicle carries its saved text but selects nothing`() {
        // Editing a car created before the catalogue: the form opens EMPTY (we
        // refuse to guess which entry "Wolwo 245" meant, because a wrong guess
        // both mislabels the car and corrupts the counts) and shows the saved
        // text, so the owner sees what they are replacing.
        val legacy = VehicleForm(legacyMake = "Wolwo", legacyModel = "245", modelYear = 1985)
        assertNull(legacy.makeId)
        assertEquals(VehicleFieldError.MAKE_REQUIRED, VehicleValidation.validate(legacy, year))
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
