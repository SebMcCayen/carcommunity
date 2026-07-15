package com.kungsbackacarcommunity.app.incidents

import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A recording fake [IncidentRepository] for controller tests. */
private class FakeIncidentRepository(
    private val nearby: List<Incident> = emptyList(),
    private val reportError: Throwable? = null,
) : IncidentRepository {
    val reported = mutableListOf<Triple<IncidentType, LatLng, String?>>()
    var listNearbyCalls = 0
        private set

    override suspend fun report(type: IncidentType, location: LatLng, note: String?) {
        reportError?.let { throw it }
        reported += Triple(type, location, note)
    }

    override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<Incident> {
        listNearbyCalls += 1
        return nearby
    }

    override suspend fun remove(incidentId: String) = Unit
}

class IncidentTypeTest {
    @Test
    fun `wire values match the backend enum`() {
        assertEquals("accident", IncidentType.ACCIDENT.wire)
        assertEquals("roadwork", IncidentType.ROADWORK.wire)
        assertEquals("hazard", IncidentType.HAZARD.wire)
        assertEquals("police", IncidentType.POLICE.wire)
        assertEquals("road_closed", IncidentType.ROAD_CLOSED.wire)
    }

    @Test
    fun `fromWire round-trips and rejects unknowns`() {
        for (type in IncidentType.entries) {
            assertEquals(type, IncidentType.fromWire(type.wire))
        }
        assertNull(IncidentType.fromWire("nope"))
        assertNull(IncidentType.fromWire(null))
    }
}

class IncidentResponseParserTest {
    @Test
    fun `parses well-formed rows and drops malformed ones`() {
        val data =
            mapOf(
                "incidents" to
                    listOf(
                        mapOf(
                            "id" to "a",
                            "type" to "roadwork",
                            "latitude" to 57.5,
                            "longitude" to 12.0,
                            "note" to "Vägarbete",
                            "source" to "user",
                        ),
                        // Unknown type → dropped.
                        mapOf("id" to "b", "type" to "meteor", "latitude" to 1.0, "longitude" to 2.0),
                        // Missing coordinate → dropped.
                        mapOf("id" to "c", "type" to "police", "latitude" to 1.0),
                        // Trafikverket source preserved.
                        mapOf(
                            "id" to "d",
                            "type" to "accident",
                            "latitude" to 59.3,
                            "longitude" to 18.0,
                            "source" to "trafikverket",
                        ),
                    ),
            )
        val incidents = IncidentResponseParser.parseListNearby(data)
        assertEquals(2, incidents.size)
        assertEquals(IncidentType.ROADWORK, incidents[0].type)
        assertEquals("Vägarbete", incidents[0].note)
        assertEquals("trafikverket", incidents[1].source)
    }

    @Test
    fun `returns empty for null or shapeless payloads`() {
        assertTrue(IncidentResponseParser.parseListNearby(null).isEmpty())
        assertTrue(IncidentResponseParser.parseListNearby(mapOf("incidents" to "nope")).isEmpty())
        assertTrue(IncidentResponseParser.parseListNearby(emptyMap()).isEmpty())
    }
}

class IncidentPaletteTest {
    @Test
    fun `every type has a distinct opaque colour`() {
        val colors = IncidentType.entries.map { IncidentPalette.colorArgb(it) }
        assertEquals(colors.size, colors.toSet().size) // all distinct
        colors.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) } // fully opaque
    }
}

class IncidentReportControllerTest {
    private val here = LatLng(longitude = 12.0757, latitude = 57.4874)

    @Test
    fun `report sends at the current location and refreshes the nearby list`() = runTest {
        val fake =
            FakeIncidentRepository(
                nearby = listOf(Incident("x", IncidentType.HAZARD, 12.0, 57.5)),
            )
        val controller = IncidentReportController(fake) { here }

        val outcome = controller.report(IncidentType.ACCIDENT, note = "krock")

        assertSame(ReportOutcome.Success, outcome)
        assertEquals(1, fake.reported.size)
        assertEquals(IncidentType.ACCIDENT, fake.reported[0].first)
        assertEquals(here, fake.reported[0].second)
        assertEquals("krock", fake.reported[0].third)
        // On success it refreshed, publishing the nearby list.
        assertEquals(1, controller.nearbyIncidents.value.size)
    }

    @Test
    fun `report returns NoLocation and sends nothing when there is no fix`() = runTest {
        val fake = FakeIncidentRepository()
        val controller = IncidentReportController(fake) { null }

        val outcome = controller.report(IncidentType.POLICE)

        assertSame(ReportOutcome.NoLocation, outcome)
        assertTrue(fake.reported.isEmpty())
        assertEquals(0, fake.listNearbyCalls)
    }

    @Test
    fun `report surfaces a backend failure`() = runTest {
        val fake = FakeIncidentRepository(reportError = IllegalStateException("boom"))
        val controller = IncidentReportController(fake) { here }

        val outcome = controller.report(IncidentType.ROADWORK)

        assertTrue(outcome is ReportOutcome.Failed)
        assertTrue(controller.nearbyIncidents.value.isEmpty())
    }

    @Test
    fun `refresh keeps the previous list when the fetch fails`() = runTest {
        val seeded = listOf(Incident("seed", IncidentType.ROADWORK, 12.0, 57.5))
        val repo =
            object : IncidentRepository {
                var fail = false
                override suspend fun report(type: IncidentType, location: LatLng, note: String?) = Unit
                override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<Incident> {
                    if (fail) throw IllegalStateException("network")
                    return seeded
                }
                override suspend fun remove(incidentId: String) = Unit
            }
        val controller = IncidentReportController(repo) { here }

        // 1) A successful refresh publishes the seeded list.
        controller.refresh(here)
        assertEquals(seeded, controller.nearbyIncidents.value)

        // 2) The next fetch fails...
        repo.fail = true
        controller.refresh(here)

        // 3) ...and the previously-loaded list is retained, not cleared/emptied.
        assertEquals(seeded, controller.nearbyIncidents.value)
        assertEquals(1, controller.nearbyIncidents.value.size)
    }

    @Test
    fun `report propagates cancellation from the location provider instead of NoLocation`() = runTest {
        val fake = FakeIncidentRepository()
        val controller = IncidentReportController(fake) { throw CancellationException("cancelled") }

        val thrown = catchCancellation { controller.report(IncidentType.HAZARD) }
        assertTrue("cancellation must propagate, not become NoLocation", thrown is CancellationException)
        assertTrue(fake.reported.isEmpty())
    }

    @Test
    fun `report propagates cancellation from the backend instead of failing`() = runTest {
        val fake = FakeIncidentRepository(reportError = CancellationException("cancelled"))
        val controller = IncidentReportController(fake) { here }

        val thrown = catchCancellation { controller.report(IncidentType.HAZARD) }
        assertTrue("cancellation must propagate, not become Failed", thrown is CancellationException)
    }

    @Test
    fun `refresh propagates cancellation instead of retaining the previous list`() = runTest {
        val repo =
            object : IncidentRepository {
                override suspend fun report(type: IncidentType, location: LatLng, note: String?) = Unit
                override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<Incident> =
                    throw CancellationException("cancelled")
                override suspend fun remove(incidentId: String) = Unit
            }
        val controller = IncidentReportController(repo) { here }

        val thrown = catchCancellation { controller.refresh(here) }
        assertTrue("cancellation must propagate", thrown is CancellationException)
    }

    /** Runs [block], returning the thrown [Throwable] (or null) without letting it cancel the test. */
    private suspend fun catchCancellation(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (t: Throwable) {
            t
        }

    @Test
    fun `refreshAroundCurrent is a no-op and returns false without a fix`() = runTest {
        val fake = FakeIncidentRepository(nearby = listOf(Incident("x", IncidentType.HAZARD, 1.0, 2.0)))
        val controller = IncidentReportController(fake) { null }
        // false signals "no fix yet" so the shell retry loop keeps trying.
        assertFalse(controller.refreshAroundCurrent())
        assertEquals(0, fake.listNearbyCalls)
        assertTrue(controller.nearbyIncidents.value.isEmpty())
    }

    @Test
    fun `refreshAroundCurrent refreshes and returns true with a fix`() = runTest {
        val seeded = listOf(Incident("x", IncidentType.HAZARD, 1.0, 2.0))
        val fake = FakeIncidentRepository(nearby = seeded)
        val controller = IncidentReportController(fake) { here }
        // true signals a fix was available, so the shell retry loop stops even
        // when the fetched list is empty (an area with no active incidents).
        assertTrue(controller.refreshAroundCurrent())
        assertEquals(1, fake.listNearbyCalls)
        assertEquals(seeded, controller.nearbyIncidents.value)
    }
}
