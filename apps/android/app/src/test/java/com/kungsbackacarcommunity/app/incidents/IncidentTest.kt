package com.kungsbackacarcommunity.app.incidents

import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    private val confirmError: Throwable? = null,
    private val confirmResult: IncidentConfirmResult = IncidentConfirmResult(1, false),
) : IncidentRepository {
    val reported = mutableListOf<Triple<IncidentType, LatLng, String?>>()
    val confirmed = mutableListOf<String>()
    var listNearbyCalls = 0
        private set

    /** The centre of the most recent [listNearby], so tests can pin WHERE the poll queried. */
    var lastCenter: LatLng? = null
        private set

    override suspend fun report(type: IncidentType, location: LatLng, note: String?): Incident {
        reportError?.let { throw it }
        reported += Triple(type, location, note)
        // Mirrors the real callable, which answers with the created incident.
        return Incident(
            id = "reported-${reported.size}",
            type = type,
            longitude = location.longitude,
            latitude = location.latitude,
            note = note,
            source = "user",
        )
    }

    override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<Incident> {
        listNearbyCalls += 1
        lastCenter = center
        return nearby
    }

    override suspend fun remove(incidentId: String) = Unit

    override suspend fun confirm(incidentId: String): IncidentConfirmResult {
        confirmed += incidentId
        confirmError?.let { throw it }
        return confirmResult
    }
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

    /**
     * `incidents-report` answers with the created incident view itself (an
     * unwrapped row), which the reporter's own marker is drawn from.
     */
    @Test
    fun `parses the report payload into the created incident`() {
        val data: Map<String, Any?> =
            mapOf(
                "id" to "new1",
                "type" to "police",
                "latitude" to 57.4874,
                "longitude" to 12.0757,
                "note" to "Kontroll",
                "source" to "user",
            )
        val incident = IncidentResponseParser.parseIncident(data)
        assertEquals("new1", incident?.id)
        assertEquals(IncidentType.POLICE, incident?.type)
        assertEquals(57.4874, incident?.latitude ?: 0.0, 1e-9)
        assertEquals(12.0757, incident?.longitude ?: 0.0, 1e-9)
        assertEquals("Kontroll", incident?.note)
    }

    @Test
    fun `report payload that is null or malformed parses to null`() {
        assertNull(IncidentResponseParser.parseIncident(null))
        assertNull(IncidentResponseParser.parseIncident(emptyMap()))
        // Missing coordinate.
        assertNull(IncidentResponseParser.parseIncident(mapOf("id" to "x", "type" to "police")))
        // Unknown type.
        assertNull(
            IncidentResponseParser.parseIncident(
                mapOf("id" to "x", "type" to "meteor", "latitude" to 1.0, "longitude" to 2.0),
            ),
        )
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
        // On success it refreshed, publishing the nearby list...
        assertTrue(controller.nearbyIncidents.value.any { it.id == "x" })
        // ...AND the reporter's own incident is on it (see the promise test below).
        assertEquals(2, controller.nearbyIncidents.value.size)
    }

    /**
     * The promise the success message makes — "Thanks, your report is on the map"
     * — must be kept by the code that returns Success.
     *
     * This is the shape of the bug: the write succeeds (no index, no extra read
     * permission needed), the follow-up `listNearby` that used to be the ONLY
     * thing putting the pin there fails, `refresh` swallows that failure by
     * design, and the user is told their report is on a map that never shows it.
     * Success must mean the marker is present, whatever the fetch did.
     */
    @Test
    fun `report puts the reporters own incident on the map even when the nearby fetch fails`() =
        runTest {
            val repo =
                object : IncidentRepository {
                    override suspend fun report(
                        type: IncidentType,
                        location: LatLng,
                        note: String?,
                    ) = Incident("mine", type, location.longitude, location.latitude)

                    // Every way listNearby can fail on a real device (missing
                    // composite index, permission, network) looks like this.
                    override suspend fun listNearby(
                        center: LatLng,
                        radiusMeters: Double,
                    ): List<Incident> = throw IllegalStateException("FAILED_PRECONDITION: index")

                    override suspend fun remove(incidentId: String) = Unit

                    override suspend fun confirm(incidentId: String) =
                        IncidentConfirmResult(0, false)
                }
            val controller = IncidentReportController(repo) { here }

            val outcome = controller.report(IncidentType.ACCIDENT)

            assertSame(ReportOutcome.Success, outcome)
            val markers = controller.nearbyIncidents.value
            assertEquals("the reported incident must be on the map", 1, markers.size)
            assertEquals("mine", markers[0].id)
            assertEquals(IncidentType.ACCIDENT, markers[0].type)
            assertEquals(here.latitude, markers[0].latitude, 1e-9)
            assertEquals(here.longitude, markers[0].longitude, 1e-9)
        }

    /**
     * The optimistic add is id-keyed, so a refresh that ALSO returns the fresh
     * report (the usual case — the write is committed by the time it runs) must
     * not leave two markers stacked on the same spot.
     */
    @Test
    fun `report does not duplicate its own incident when the fetch already returns it`() = runTest {
        val mine = Incident("mine", IncidentType.POLICE, here.longitude, here.latitude)
        val repo =
            object : IncidentRepository {
                override suspend fun report(type: IncidentType, location: LatLng, note: String?) =
                    mine

                override suspend fun listNearby(
                    center: LatLng,
                    radiusMeters: Double,
                ): List<Incident> = listOf(mine, Incident("other", IncidentType.HAZARD, 12.1, 57.6))

                override suspend fun remove(incidentId: String) = Unit

                override suspend fun confirm(incidentId: String) = IncidentConfirmResult(0, false)
            }
        val controller = IncidentReportController(repo) { here }

        controller.report(IncidentType.POLICE)

        val markers = controller.nearbyIncidents.value
        assertEquals(2, markers.size)
        assertEquals(1, markers.count { it.id == "mine" })
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
                override suspend fun report(type: IncidentType, location: LatLng, note: String?) =
                    Incident("reported", type, location.longitude, location.latitude)
                override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<Incident> {
                    if (fail) throw IllegalStateException("network")
                    return seeded
                }
                override suspend fun remove(incidentId: String) = Unit
                override suspend fun confirm(incidentId: String) = IncidentConfirmResult(0, false)
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
                override suspend fun report(type: IncidentType, location: LatLng, note: String?) =
                    Incident("reported", type, location.longitude, location.latitude)
                override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<Incident> =
                    throw CancellationException("cancelled")
                override suspend fun remove(incidentId: String) = Unit
                override suspend fun confirm(incidentId: String) = IncidentConfirmResult(0, false)
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

    /**
     * THE FIX for "an incident someone else reports isn't visible to me": the
     * shared layer must keep refreshing, not fetch once and go stale. Every user
     * but the reporter learns of a report only through listNearby, so a report
     * made while they are already on the map must still surface. Here the fix is
     * available from the first pass, so after the immediate acquisition the poll
     * must fire again on each interval.
     */
    @Test
    fun `pollNearby keeps refreshing on a cadence so later reports surface`() = runTest {
        val fake = FakeIncidentRepository(nearby = listOf(Incident("x", IncidentType.HAZARD, 1.0, 2.0)))
        val controller = IncidentReportController(fake) { here }

        backgroundScope.launch {
            controller.pollNearby(
                pollIntervalMs = 30_000L,
                initialRetryMs = 3_000L,
                initialAttempts = 5,
            )
        }

        // Phase 1 acquires the fix on the first pass (no backoff consumed).
        runCurrent()
        assertEquals(1, fake.listNearbyCalls)

        // Steady state: one more fetch per interval — the map stays live.
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(2, fake.listNearbyCalls)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(3, fake.listNearbyCalls)
    }

    /**
     * On a cold open the fused last-known location is frequently null, so the
     * first passes no-op; the poll must retry on a short backoff until a fix
     * arrives (no listNearby yet), THEN settle into the live cadence — rather
     * than firing the callable blindly or giving up.
     */
    @Test
    fun `pollNearby retries until a fix arrives, then polls live`() = runTest {
        val fake = FakeIncidentRepository(nearby = listOf(Incident("x", IncidentType.HAZARD, 1.0, 2.0)))
        // No fix for the first two passes, then a real fix.
        var fixCalls = 0
        val controller =
            IncidentReportController(fake) {
                fixCalls += 1
                if (fixCalls <= 2) null else here
            }

        backgroundScope.launch {
            controller.pollNearby(
                pollIntervalMs = 30_000L,
                initialRetryMs = 3_000L,
                initialAttempts = 5,
            )
        }

        // Pass 1: no fix → no fetch, waiting on the short retry backoff.
        runCurrent()
        assertEquals(0, fake.listNearbyCalls)

        // Pass 2: still no fix.
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(0, fake.listNearbyCalls)

        // Pass 3: fix arrives → the layer finally populates.
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(1, fake.listNearbyCalls)

        // ...and now it is on the live cadence, not the short retry one.
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(2, fake.listNearbyCalls)
    }

    /**
     * THE FIX for "I see no incidents at all": the live poll queries around the
     * centre [pollNearby]'s [centerProvider] yields — the map wires it to the
     * CAMERA centre — NOT only around a GPS fix. So even when the GPS provider
     * never yields a fix (permission denied, indoors, emulator), the shared
     * layer still populates around the visible map area, and it queries at
     * exactly that centre.
     */
    @Test
    fun `pollNearby queries the provided centre even when the GPS provider never yields`() = runTest {
        val fake = FakeIncidentRepository(nearby = listOf(Incident("x", IncidentType.HAZARD, 1.0, 2.0)))
        // GPS never resolves — the OLD behaviour would have polled nothing at all.
        val controller = IncidentReportController(fake) { null }
        // The map camera centre (here: the Kungsbacka default the map opens on).
        val cameraCentre = LatLng(longitude = 12.0757, latitude = 57.4874)

        backgroundScope.launch {
            controller.pollNearby(
                pollIntervalMs = 30_000L,
                initialRetryMs = 3_000L,
                initialAttempts = 5,
                centerProvider = { cameraCentre },
            )
        }

        // Phase 1 acquires the camera centre immediately — a fetch happens
        // despite the absent GPS fix — and it queries AT that centre.
        runCurrent()
        assertEquals(1, fake.listNearbyCalls)
        assertEquals(cameraCentre, fake.lastCenter)

        // Steady state keeps the layer live around the camera.
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(2, fake.listNearbyCalls)
        assertEquals(cameraCentre, fake.lastCenter)
    }

    /**
     * The map wires [pollNearby]'s centre to "camera, else GPS", so the query
     * follows the camera as the user pans — a pan to a new area re-queries
     * around the new centre on the next tick, not the old one.
     */
    @Test
    fun `pollNearby follows the provided centre as it moves`() = runTest {
        val fake = FakeIncidentRepository(nearby = listOf(Incident("x", IncidentType.HAZARD, 1.0, 2.0)))
        val controller = IncidentReportController(fake) { null }
        val kungsbacka = LatLng(longitude = 12.0757, latitude = 57.4874)
        val goteborg = LatLng(longitude = 11.9746, latitude = 57.7089)
        var centre = kungsbacka

        backgroundScope.launch {
            controller.pollNearby(
                pollIntervalMs = 30_000L,
                initialRetryMs = 3_000L,
                initialAttempts = 5,
                centerProvider = { centre },
            )
        }

        runCurrent()
        assertEquals(kungsbacka, fake.lastCenter)

        // User pans north; the next tick queries around the NEW centre.
        centre = goteborg
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(goteborg, fake.lastCenter)
    }

    /**
     * A zero/negative interval would make the poll a `delay(0)` busy loop that
     * hammers the backend and drains the battery, so misuse must fail fast
     * rather than ship a hot loop.
     */
    @Test
    fun `pollNearby rejects a non-positive poll interval`() = runTest {
        val controller = IncidentReportController(FakeIncidentRepository()) { here }
        val zero = catchCancellation { controller.pollNearby(pollIntervalMs = 0L) }
        assertTrue("zero interval must be rejected", zero is IllegalArgumentException)
        val negative = catchCancellation { controller.pollNearby(pollIntervalMs = -1L) }
        assertTrue("negative interval must be rejected", negative is IllegalArgumentException)
    }

    @Test
    fun `pollNearby rejects a non-positive initial retry`() = runTest {
        val controller = IncidentReportController(FakeIncidentRepository()) { here }
        val thrown = catchCancellation { controller.pollNearby(initialRetryMs = 0L) }
        assertTrue("zero retry must be rejected", thrown is IllegalArgumentException)
    }

    /**
     * A transient fetch failure mid-poll must NOT blank the shared map or kill
     * the loop: the previous markers stay, and the next tick recovers.
     */
    @Test
    fun `pollNearby survives a fetch failure and keeps the previous markers`() = runTest {
        val seeded = listOf(Incident("seed", IncidentType.ROADWORK, 12.0, 57.5))
        val repo =
            object : IncidentRepository {
                var calls = 0
                override suspend fun report(type: IncidentType, location: LatLng, note: String?) =
                    Incident("reported", type, location.longitude, location.latitude)
                override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<Incident> {
                    calls += 1
                    if (calls == 2) throw IllegalStateException("network")
                    return seeded
                }
                override suspend fun remove(incidentId: String) = Unit
                override suspend fun confirm(incidentId: String) = IncidentConfirmResult(0, false)
            }
        val controller = IncidentReportController(repo) { here }

        backgroundScope.launch {
            controller.pollNearby(
                pollIntervalMs = 30_000L,
                initialRetryMs = 3_000L,
                initialAttempts = 5,
            )
        }

        // Pass 1 populates the layer.
        runCurrent()
        assertEquals(seeded, controller.nearbyIncidents.value)

        // Pass 2 throws — the layer must keep the previous markers, not blank.
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(seeded, controller.nearbyIncidents.value)

        // Pass 3 proves the loop survived the throw and is still polling.
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(3, repo.calls)
        assertEquals(seeded, controller.nearbyIncidents.value)
    }

    /**
     * Pins the WIRE value, which is a cross-language contract: the importer in
     * `functions/src/incidents/trafikverket.ts` writes the string `trafikverket`
     * into Firestore, and nothing links the two languages at compile time. This
     * has to stay a literal — asserting the constant against itself would prove
     * nothing — so it is pinned once here and the constant is used everywhere
     * else, including the assertions below.
     */
    @Test
    fun `Trafikverket source constant matches the value the importer writes`() {
        assertEquals("trafikverket", INCIDENT_SOURCE_TRAFIKVERKET)
    }

    @Test
    fun `Trafikverket attribution shows only when imported data is loaded`() {
        // Member reports rely on Incident.source's default rather than repeating
        // the literal, so this test cannot drift from the model.
        val userReport = Incident("u1", IncidentType.HAZARD, 12.07, 57.48)
        val imported =
            Incident(
                "t1",
                IncidentType.ROADWORK,
                12.07,
                57.48,
                source = INCIDENT_SOURCE_TRAFIKVERKET,
            )
        // Sweden with imported roadwork on the map: we owe the credit.
        assertTrue(hasTrafikverketData(listOf(userReport, imported)))
        // Abroad (or a quiet Swedish area): the Sweden-only importer contributes
        // nothing, so no Trafikverket data is on screen and no credit is shown.
        assertFalse(hasTrafikverketData(emptyList()))
        assertFalse(hasTrafikverketData(listOf(userReport)))
    }

    // ---- confirm ----------------------------------------------------------

    @Test
    fun `confirm bumps the shared count for the confirmed incident only`() = runTest {
        val seeded =
            listOf(
                Incident("theirs", IncidentType.HAZARD, 12.0, 57.5, confirmationCount = 0),
                Incident("other", IncidentType.POLICE, 12.1, 57.6, confirmationCount = 2),
            )
        val fake =
            FakeIncidentRepository(
                nearby = seeded,
                confirmResult = IncidentConfirmResult(1, false),
            )
        val controller = IncidentReportController(fake) { here }
        controller.refresh(here)

        val outcome = controller.confirm("theirs")

        assertTrue(outcome is ConfirmOutcome.Success)
        outcome as ConfirmOutcome.Success
        assertEquals(1, outcome.confirmationCount)
        assertFalse(outcome.alreadyConfirmed)
        assertEquals(listOf("theirs"), fake.confirmed)
        // Only the confirmed incident's count moved on the shared layer.
        assertEquals(
            1,
            controller.nearbyIncidents.value.first { it.id == "theirs" }.confirmationCount,
        )
        assertEquals(
            2,
            controller.nearbyIncidents.value.first { it.id == "other" }.confirmationCount,
        )
    }

    @Test
    fun `a repeat confirm reads as alreadyConfirmed with the unchanged count`() = runTest {
        val seeded = listOf(Incident("theirs", IncidentType.HAZARD, 12.0, 57.5, confirmationCount = 3))
        val fake =
            FakeIncidentRepository(
                nearby = seeded,
                confirmResult = IncidentConfirmResult(3, true),
            )
        val controller = IncidentReportController(fake) { here }
        controller.refresh(here)

        val outcome = controller.confirm("theirs")

        assertTrue(outcome is ConfirmOutcome.Success)
        outcome as ConfirmOutcome.Success
        assertTrue(outcome.alreadyConfirmed)
        assertEquals(3, outcome.confirmationCount)
    }

    @Test
    fun `a rejected confirm leaves the count untouched`() = runTest {
        // The backend rejects e.g. confirming your own report; the local count
        // must not move on a call the server refused.
        val seeded = listOf(Incident("mine", IncidentType.HAZARD, 12.0, 57.5, confirmationCount = 0))
        val fake =
            FakeIncidentRepository(
                nearby = seeded,
                confirmError = IllegalStateException("permission-denied"),
            )
        val controller = IncidentReportController(fake) { here }
        controller.refresh(here)

        val outcome = controller.confirm("mine")

        assertTrue(outcome is ConfirmOutcome.Failed)
        assertEquals(
            0,
            controller.nearbyIncidents.value.first { it.id == "mine" }.confirmationCount,
        )
    }

    @Test
    fun `confirm propagates cancellation instead of reporting Failed`() = runTest {
        val fake = FakeIncidentRepository(confirmError = CancellationException("cancelled"))
        val controller = IncidentReportController(fake) { here }

        val thrown = catchCancellation { controller.confirm("x") }
        assertTrue("cancellation must propagate, not become Failed", thrown is CancellationException)
    }
}
