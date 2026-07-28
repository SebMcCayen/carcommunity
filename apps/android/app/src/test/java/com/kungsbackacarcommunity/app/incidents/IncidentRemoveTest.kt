package com.kungsbackacarcommunity.app.incidents

import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [IncidentReportController.remove] — the action the detail sheet offers
 * on your OWN report.
 *
 * The behaviour worth pinning is the ORDER: the local list is only pruned after
 * the backend accepts. A removal the backend rejected (it refuses anything that
 * is not your own user-sourced report) must leave the marker on the map, because
 * the incident is still live for everyone else — hiding it locally would show
 * one driver a clear road that is not clear.
 */
class IncidentRemoveTest {

    private class RecordingRepository(
        var removeError: Throwable? = null,
        val seeded: List<Incident> = emptyList(),
    ) : IncidentRepository {
        val removed = mutableListOf<String>()

        override suspend fun report(type: IncidentType, location: LatLng, note: String?): Incident =
            Incident(id = "new", type = type, longitude = 0.0, latitude = 0.0)

        override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<Incident> = seeded

        override suspend fun remove(incidentId: String) {
            removed += incidentId
            removeError?.let { throw it }
        }

        override suspend fun confirm(incidentId: String) = IncidentConfirmResult(0, false)

        override suspend fun reportCleared(
            incidentId: String,
            fix: IncidentClearFix,
        ) = IncidentClearResult(0, 0, false, removed = false, alreadyVoted = false)
    }

    private fun incidentAt(id: String) =
        Incident(id = id, type = IncidentType.HAZARD, longitude = 12.0, latitude = 57.0)

    private suspend fun controllerWith(
        repository: IncidentRepository,
        seeded: List<Incident>,
    ): IncidentReportController {
        val controller =
            IncidentReportController(
                repository = repository,
                locationProvider = { LatLng(57.0, 12.0) },
            )
        // Populate nearbyIncidents through the public API rather than reaching
        // into the flow.
        controller.refresh(LatLng(57.0, 12.0))
        assertEquals(seeded.map { it.id }, controller.nearbyIncidents.value.map { it.id })
        return controller
    }

    @Test
    fun `a successful removal drops the incident from the map`() = runTest {
        val seeded = listOf(incidentAt("mine"), incidentAt("theirs"))
        val repository = RecordingRepository(seeded = seeded)
        val controller = controllerWith(repository, seeded)

        assertTrue(controller.remove("mine"))

        assertEquals(listOf("mine"), repository.removed)
        assertEquals(listOf("theirs"), controller.nearbyIncidents.value.map { it.id })
    }

    @Test
    fun `a rejected removal leaves the incident on the map`() = runTest {
        val seeded = listOf(incidentAt("mine"), incidentAt("theirs"))
        val repository =
            RecordingRepository(
                removeError = IllegalStateException("permission-denied"),
                seeded = seeded,
            )
        val controller = controllerWith(repository, seeded)

        assertFalse(controller.remove("mine"))

        // The call was attempted, and NOTHING was pruned locally: the incident is
        // still live for every other driver.
        assertEquals(listOf("mine"), repository.removed)
        assertEquals(listOf("mine", "theirs"), controller.nearbyIncidents.value.map { it.id })
    }

    @Test
    fun `removing an id that is not loaded is harmless`() = runTest {
        val seeded = listOf(incidentAt("theirs"))
        val repository = RecordingRepository(seeded = seeded)
        val controller = controllerWith(repository, seeded)

        assertTrue(controller.remove("ghost"))

        assertEquals(listOf("theirs"), controller.nearbyIncidents.value.map { it.id })
    }

    @Test
    fun `cancellation propagates rather than reading as a failed removal`() = runTest {
        val seeded = listOf(incidentAt("mine"))
        val repository =
            RecordingRepository(
                removeError = CancellationException("cancelled"),
                seeded = seeded,
            )
        val controller = controllerWith(repository, seeded)

        var cancelled = false
        try {
            controller.remove("mine")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("cancellation was swallowed into a false result", cancelled)
        // Structured concurrency honoured, and the list untouched.
        assertEquals(listOf("mine"), controller.nearbyIncidents.value.map { it.id })
    }
}
