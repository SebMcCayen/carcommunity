package com.kungsbackacarcommunity.app.garage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off-Compose tests for the vehicle catalogue: the packed-data parse, the
 * cascading make -> model filter, the year window, the "Other / not listed"
 * escape hatch, and the legacy rendering path.
 *
 * The data itself is generated from contracts/vehicles/vehicle-catalogue.json and
 * diffed by CI, so this file checks the LOGIC around it plus a few anchors that
 * would catch a truncated or mis-parsed mirror.
 */
class VehicleCatalogueTest {

    @Test
    fun `the packed data parses into a real catalogue`() {
        val makes = VehicleCatalogue.makes
        assertTrue("expected a substantial catalogue, got ${makes.size}", makes.size > 50)
        assertTrue(makes.sumOf { it.models.size } > 500)
        // Every entry is fully populated — a mis-parsed line would show up as a
        // blank name or an empty model list rather than an exception.
        assertTrue(makes.all { it.id.isNotBlank() && it.name.isNotBlank() && it.models.isNotEmpty() })
        assertTrue(makes.all { m -> m.models.all { it.id.isNotBlank() && it.name.isNotBlank() } })
    }

    @Test
    fun `common Swedish brands come first and the rest are alphabetical`() {
        val makes = VehicleCatalogue.makes
        val firstNonCommon = makes.indexOfFirst { !it.common }
        assertTrue(firstNonCommon > 0)
        // No `common` brand may appear after the alphabetical block starts, or the
        // opening screenful stops being the useful one.
        assertTrue(makes.drop(firstNonCommon).none { it.common })
        val rest = makes.drop(firstNonCommon).map { it.name.lowercase() }
        assertEquals(rest.sorted(), rest)
        // Volvo first: this is a Swedish car community.
        assertEquals("volvo", makes.first().id)
    }

    @Test
    fun `Toyota GT86 and NIO ET5 Touring are selectable`() {
        // Regression guard for the two models added in catalogue v1.1.0. GT86
        // (first-gen, 2012-2020) is a distinct entry from the existing GR86.
        assertEquals("GT86", VehicleCatalogue.modelName("toyota", "gt86"))
        assertEquals("GR86", VehicleCatalogue.modelName("toyota", "gr86"))
        // NIO already existed as a manufacturer; ET5 Touring is a new model on it.
        assertNotNull(VehicleCatalogue.make("nio"))
        assertEquals("ET5 Touring", VehicleCatalogue.modelName("nio", "et5-touring"))
    }

    @Test
    fun `lookups resolve display names`() {
        assertEquals("Volvo", VehicleCatalogue.makeName("volvo"))
        assertEquals("240", VehicleCatalogue.modelName("volvo", "240"))
        assertNotNull(VehicleCatalogue.make("saab"))
        assertNull(VehicleCatalogue.makeName("not-a-brand"))
        assertNull(VehicleCatalogue.modelName("volvo", "not-a-volvo"))
    }

    @Test
    fun `the model list cascades from the manufacturer`() {
        val volvoModels = VehicleCatalogue.modelOptions("volvo").filterNot { it.isOther }
        val saabModels = VehicleCatalogue.modelOptions("saab").filterNot { it.isOther }
        assertTrue(volvoModels.any { it.id == "240" })
        // The whole point of cascading: a Volvo model is not offered under Saab.
        assertFalse(saabModels.any { it.id == "240" })
        assertTrue(saabModels.any { it.id == "9-3" })
    }

    @Test
    fun `no manufacturer selected means no models to choose from`() {
        // The model selector is disabled in this state; if it were ever opened it
        // must offer the escape hatch and nothing else, never a flat list of every
        // model in the catalogue.
        assertEquals(listOf(VehicleCatalogue.OTHER_ID), VehicleCatalogue.modelOptions(null).map { it.id })
    }

    @Test
    fun `a model id from another manufacturer is not selectable`() {
        // Model ids repeat across brands (`3` is both a Mazda and an MG), so a
        // selection left over from switching manufacturer has to be rejected.
        assertTrue(VehicleCatalogue.isSelectableModel("mg", "mgb"))
        assertFalse(VehicleCatalogue.isSelectableModel("mazda", "mgb"))
    }

    // --- the "Other / not listed" escape hatch ------------------------------

    @Test
    fun `Other is offered last at both levels and is never real data`() {
        val makeOptions = VehicleCatalogue.makeOptions()
        assertTrue(makeOptions.last().isOther)
        assertEquals(1, makeOptions.count { it.isOther })
        // It is synthesised, not part of the contract data.
        assertTrue(VehicleCatalogue.makes.none { it.id == VehicleCatalogue.OTHER_ID })
        assertTrue(VehicleCatalogue.makes.none { m -> m.models.any { it.id == VehicleCatalogue.OTHER_ID } })
        assertTrue(VehicleCatalogue.modelOptions("volvo").last().isOther)
    }

    @Test
    fun `Other carries no catalogue name so the UI can localize it`() {
        // Real makes/models are proper nouns and are never translated; "Other /
        // not listed" is a UI string, so the option deliberately has no name.
        assertEquals("", VehicleCatalogue.makeOptions().last().name)
    }

    @Test
    fun `Other survives every search so the escape hatch can never be filtered away`() {
        val options = VehicleCatalogue.makeOptions()
        val noMatches = VehicleCatalogue.filter(options, "zzzzzzzz")
        // The moment a member most needs "not listed" is when their brand returns
        // nothing at all.
        assertEquals(listOf(VehicleCatalogue.OTHER_ID), noMatches.map { it.id })
    }

    // --- search -------------------------------------------------------------

    @Test
    fun `search is case- and diacritic-insensitive`() {
        val options = VehicleCatalogue.makeOptions()
        // A Swedish keyboard reaches ë and é awkwardly; demanding them would hide
        // the entry entirely.
        assertTrue(VehicleCatalogue.filter(options, "citroen").any { it.id == "citroen" })
        assertTrue(VehicleCatalogue.filter(options, "CITROËN").any { it.id == "citroen" })
        assertTrue(VehicleCatalogue.filter(options, "skoda").any { it.id == "skoda" })
        val renault = VehicleCatalogue.modelOptions("renault")
        assertTrue(VehicleCatalogue.filter(renault, "megane").any { it.id == "megane" })
    }

    @Test
    fun `search still matches under the Turkish locale`() {
        // Turkish lower-cases 'I' to the DOTLESS 'ı' under a locale-SENSITIVE
        // fold, which would make "INFINITI" fold to "ınfınıtı" and stop matching
        // the catalogue's "Infiniti" on every Turkish-locale device.
        //
        // Kotlin's no-arg String.lowercase() is already locale-invariant (the
        // stdlib defines it as toLowerCase(Locale.ROOT)), unlike Java's
        // deprecated toLowerCase() — so VehicleCatalogue.fold() needs no Locale
        // argument. This pins BOTH forms so nobody "fixes" it back and forth,
        // the same way VehicleValidationTest pins the plate's uppercase().
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val options = VehicleCatalogue.makeOptions()
            assertTrue(VehicleCatalogue.filter(options, "INFINITI").any { it.id == "infiniti" })
            assertTrue(VehicleCatalogue.filter(options, "infiniti").any { it.id == "infiniti" })
            // The locale-invariant form is what fold() uses; the locale-sensitive
            // form is what would have broken it.
            assertEquals("infiniti", "INFINITI".lowercase())
            assertEquals("ınfınıtı", "INFINITI".lowercase(java.util.Locale.getDefault()))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `an empty query returns everything unchanged`() {
        val options = VehicleCatalogue.makeOptions()
        assertEquals(options, VehicleCatalogue.filter(options, "   "))
    }

    // --- year window --------------------------------------------------------

    @Test
    fun `years run newest first from the next model year down to the floor`() {
        val years = VehicleCatalogue.modelYears(2026)
        assertEquals(2027, years.first())
        assertEquals(VehicleCatalogue.MIN_MODEL_YEAR, years.last())
        assertEquals(2027 - VehicleCatalogue.MIN_MODEL_YEAR + 1, years.size)
    }

    @Test
    fun `only years inside the window are offered`() {
        assertTrue(VehicleCatalogue.isOfferedYear(2027, 2026))
        assertTrue(VehicleCatalogue.isOfferedYear(VehicleCatalogue.MIN_MODEL_YEAR, 2026))
        // Never future-dated beyond the next model year.
        assertFalse(VehicleCatalogue.isOfferedYear(2028, 2026))
        assertFalse(VehicleCatalogue.isOfferedYear(VehicleCatalogue.MIN_MODEL_YEAR - 1, 2026))
    }
}

/**
 * How a vehicle's make/model is RENDERED — including the two cases that decide
 * whether the migration was safe: a pre-catalogue vehicle and an "Other" one.
 */
class VehicleDisplayTest {

    private val other = "Other / not listed"

    private fun vehicle(
        make: String,
        model: String,
        makeId: String? = null,
        modelId: String? = null,
        year: Int = 1988,
    ) = Vehicle(
        id = "v1",
        make = make,
        model = model,
        makeId = makeId,
        modelId = modelId,
        modelYear = year,
        powertrain = VehiclePowertrain.PETROL,
        engineDescription = null,
    )

    @Test
    fun `a catalogue vehicle renders the catalogue name`() {
        // Not the stored text: renaming a label in the contract must propagate
        // without rewriting a single document.
        val v = vehicle(make = "stale text", model = "stale", makeId = "volvo", modelId = "240")
        assertEquals("Volvo 240 (1988)", VehicleDisplay.headline(v, other))
    }

    @Test
    fun `a legacy vehicle renders its stored free text untouched`() {
        // The promise of the migration: no member's car silently changed or
        // disappeared when the catalogue arrived.
        val v = vehicle(make = "Wolwo", model = "245 Turbo")
        assertEquals("Wolwo 245 Turbo (1988)", VehicleDisplay.headline(v, other))
        assertEquals("Wolwo", VehicleDisplay.makeLabel(null, "Wolwo", other))
    }

    @Test
    fun `an id this build does not know falls back to the stored text`() {
        // Forward compatibility: a vehicle written by a NEWER client against a
        // newer catalogue must still render, not show a blank or an id.
        val v = vehicle(make = "Brandnew", model = "Model Z", makeId = "brandnew", modelId = "model-z")
        assertEquals("Brandnew Model Z (1988)", VehicleDisplay.headline(v, other))
    }

    @Test
    fun `an Other model under a real make shows the localized label`() {
        val v = vehicle(make = "Volvo", model = "Duett", makeId = "volvo", modelId = VehicleCatalogue.OTHER_ID)
        assertEquals("Volvo $other (1988)", VehicleDisplay.headline(v, other))
    }

    @Test
    fun `a fully unlisted car shows the label once, not twice`() {
        val v =
            vehicle(
                make = "Other make",
                model = "Other model",
                makeId = VehicleCatalogue.OTHER_ID,
                modelId = VehicleCatalogue.OTHER_ID,
                year = 1998,
            )
        assertEquals("$other (1998)", VehicleDisplay.headline(v, other))
    }
}
