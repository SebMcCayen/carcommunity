package com.kungsbackacarcommunity.app.clubs

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ClubLocationCatalogTest {
    private val source by lazy {
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .flatMap { dir -> sequenceOf(
                File(dir, "src/main/assets/$CLUB_LOCATION_ASSET"),
                File(dir, "app/src/main/assets/$CLUB_LOCATION_ASSET"),
                File(dir, "apps/android/app/src/main/assets/$CLUB_LOCATION_ASSET"),
            ) }.first { it.isFile }.readText(Charsets.UTF_8)
    }
    private val catalog by lazy { ClubLocationCatalog.parse(source) }

    @Test fun `all current SCB localities and county memberships are bundled`() {
        assertEquals(21, catalog.counties.size)
        val current = catalog.counties.flatMap { catalog.localities(it.code) }.filter { it.partOfCode == null }
        assertEquals(2017, current.map { it.code }.toSet().size)
        assertEquals(2030, current.size)
        assertEquals(13, current.groupBy { it.code }.count { it.value.size > 1 })
        assertEquals(setOf("13", "14"), current.filter { it.name == "Göteborg" }.map { it.countyCode }.toSet())
        assertEquals(setOf("12", "13"), current.filter { it.name == "Båstad" }.map { it.countyCode }.toSet())
        assertEquals(setOf("03", "21"), current.filter { it.name == "Skutskär" }.map { it.countyCode }.toSet())
    }

    @Test fun `Halland includes Asa and the official former Kungsbacka locality`() {
        assertEquals("Åsa", catalog.locality("13", "1384TB126")?.name)
        val kungsbacka = catalog.locality("13", "1384TC117")!!
        assertEquals("Kungsbacka", kungsbacka.name)
        assertEquals("1480TC108", kungsbacka.partOfCode)
        assertEquals("Göteborg", catalog.locality("13", kungsbacka.partOfCode)?.name)
        val merged = catalog.counties.flatMap { catalog.localities(it.code) }.filter { it.partOfCode != null }
        assertEquals(32, merged.size)
        merged.forEach { assertNotNull(catalog.locality(it.countyCode, it.partOfCode)) }
        assertEquals("Kungsbacka, Hallands län", catalog.wireLocation(ClubLocationSelection("13", kungsbacka.code)))
    }

    @Test fun `county changes clear city and stale or mismatched choices cannot produce a payload`() {
        val halland = ClubLocationSelection().chooseCounty(catalog, "13")
            .chooseLocality(catalog, "13", "1384TB126")
        assertEquals("Åsa, Hallands län", catalog.wireLocation(halland))
        assertEquals(halland, halland.chooseCounty(catalog, "13"))
        val changed = halland.chooseCounty(catalog, "14")
        assertNull(changed.localityCode)
        assertNull(catalog.wireLocation(changed))
        assertNull(catalog.wireLocation(changed.chooseLocality(catalog, "13", "1384TB126")))
        assertNull(catalog.wireLocation(ClubLocationSelection("14", "1384TB126")))
        // Even a locality present in both counties must not accept an old county's callback.
        assertNull(catalog.wireLocation(changed.chooseLocality(catalog, "13", "1480TC108")))
        assertEquals("Göteborg, Västra Götalands län", catalog.wireLocation(changed.chooseLocality(catalog, "14", "1480TC108")))
        assertEquals(ClubLocationSelection(), halland.chooseCounty(catalog, "00"))
        assertNull(catalog.wireLocation(ClubLocationSelection(null, "1384TB126")))
        assertNull(catalog.wireLocation(ClubLocationSelection("13", "invented")))
    }

    @Test fun `search folds accents casing and whitespace while staying in selected county`() {
        val result = catalog.localities("13", "  aSA ")
        assertTrue(result.any { it.name == "Åsa" })
        assertTrue(result.all { it.countyCode == "13" })
        assertEquals(result, catalog.localities("13", "A\u030asa"))
        assertTrue(catalog.localities("13", "Åsa Kungsbacka").any { it.code == "1384TB126" })
        assertTrue(catalog.localities("13", "Åsa Stockholm").isEmpty())
        assertTrue(catalog.localities(null, "Åsa").isEmpty())
        val duplicates = catalog.localities("24").filter { it.name == "Ersmark" }
        assertEquals(2, duplicates.size)
        assertEquals(2, duplicates.map { it.municipalities }.toSet().size)
    }

    @Test fun `every selectable pair fits string wire contract without exposing ids`() {
        catalog.counties.forEach { county ->
            catalog.localities(county.code).forEach { place ->
                val fields = clubPresentationFields(" Test club ", " A description of the club ",
                    catalog.wireLocation(ClubLocationSelection(county.code, place.code)), closed = true)!!
                assertEquals(setOf("name", "description", "location", "visibility"), fields.keys)
                assertEquals("${place.name}, ${county.name}", fields["location"])
                assertEquals("closed", fields["visibility"])
                assertTrue((fields["location"] as String).length in 2..80)
            }
        }
        assertNull(clubPresentationFields("Test club", "A description", catalog.wireLocation(ClubLocationSelection("13")), false))
        assertNull(clubPresentationFields("ab", "A description", "Åsa, Hallands län", false))
        assertNull(clubPresentationFields("Test club", "short", "Åsa, Hallands län", false))
        assertNull(clubPresentationFields("Test club", "A description", "x".repeat(81), false))
    }

    @Test fun `unrelated edits preserve ambiguous legacy location strings`() {
        listOf("Kungsbacka", "Göteborg / Kungsbacka", "Hemtrakten", "Kungsbacka, Hallands län").forEach { legacy ->
            assertEquals(legacy, clubPresentationFields("Updated name", "Updated description", legacy, false)!!["location"])
        }
    }

    @Test fun `corrupt incomplete duplicate or unresolved catalog fails closed`() {
        assertTrue(runCatching { ClubLocationCatalog.parse("not a catalog") }.isFailure)
        assertTrue(runCatching { ClubLocationCatalog.parse(source.lineSequence().take(40).joinToString("\n")) }.isFailure)
        val existing = source.lineSequence().first { it.startsWith("L\t") }
        assertTrue(runCatching { ClubLocationCatalog.parse("$source$existing\n") }.isFailure)
        assertTrue(runCatching { ClubLocationCatalog.parse(source.replace(existing, existing.replaceFirst("L\t", "L\t99"))) }.isFailure)
        val alias = source.lineSequence().first { it.startsWith("A\t") }
        assertTrue(runCatching { ClubLocationCatalog.parse(source.replace(alias, alias.substringBeforeLast('\t') + "\t9999TC999")) }.isFailure)
    }
}
