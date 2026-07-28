package com.kungsbackacarcommunity.app.shell

import com.kungsbackacarcommunity.app.incidents.Incident
import com.kungsbackacarcommunity.app.incidents.IncidentClearFix
import com.kungsbackacarcommunity.app.incidents.IncidentClearResult
import com.kungsbackacarcommunity.app.incidents.IncidentConfirmResult
import com.kungsbackacarcommunity.app.incidents.IncidentReportController
import com.kungsbackacarcommunity.app.incidents.IncidentRepository
import com.kungsbackacarcommunity.app.incidents.IncidentType
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers when the incident details sheet CLOSES after its remove action.
 *
 * [com.kungsbackacarcommunity.app.incidents.IncidentRemoveTest] already pins the
 * data half — a rejected removal leaves the incident in `nearbyIncidents`. This
 * file pins the UI half: whether the sheet is still there to show it.
 *
 * The sheet renders while `incidentTap` resolves to a loaded incident, so
 * consuming the tap closes it. The action used to consume unconditionally, and
 * before the backend had even answered — so a removal the backend rejected
 * dismissed the sheet exactly like a successful one, telling the user it worked
 * and taking away the incident they needed to try again on.
 *
 * The load-bearing test here is the FAILURE one. Asserting that a SUCCESSFUL
 * removal closes the sheet passes against the old unconditional-consume code
 * too, so on its own it proves nothing.
 */
class IncidentRemoveActionTest {

    private class FakeRepository(
        private val removeError: Throwable? = null,
        private val seeded: List<Incident> = emptyList(),
    ) : IncidentRepository {
        override suspend fun report(type: IncidentType, location: LatLng, note: String?): Incident =
            Incident(id = "new", type = type, longitude = 0.0, latitude = 0.0)

        override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<Incident> = seeded

        override suspend fun remove(incidentId: String) {
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

    private suspend fun controllerWith(repository: IncidentRepository): IncidentReportController =
        IncidentReportController(
            repository = repository,
            locationProvider = { LatLng(57.0, 12.0) },
        ).also { it.refresh(LatLng(57.0, 12.0)) }

    /**
     * The host's rule for whether the sheet is on screen: the tapped id has to
     * still resolve to a loaded incident.
     */
    private fun openSheetIncident(
        surface: MapSurface,
        controller: IncidentReportController,
    ): Incident? =
        surface.incidentTap.value?.let { id ->
            controller.nearbyIncidents.value.firstOrNull { it.id == id }
        }

    @Test
    fun `a rejected removal leaves the sheet open so the user can retry`() = runTest {
        val seeded = listOf(incidentAt("mine"))
        val controller =
            controllerWith(
                FakeRepository(
                    removeError = IllegalStateException("permission-denied"),
                    seeded = seeded,
                ),
            )
        val surface = StubMapSurface(autoLoad = false)
        surface.emitIncidentTap("mine")

        val removed = runIncidentRemoval(controller, surface, "mine")

        assertFalse(removed)
        assertNotNull(
            "a rejected removal closed the details sheet, so the failure looked like " +
                "a success and the incident was no longer there to retry",
            openSheetIncident(surface, controller),
        )
        assertEquals("mine", openSheetIncident(surface, controller)?.id)
    }

    @Test
    fun `a rejected removal raises no navigate prompt while leaving the sheet up`() = runTest {
        // Keeping the sheet open must not leak the still-live tap into the
        // "navigate here?" channel.
        val controller =
            controllerWith(
                FakeRepository(
                    removeError = IllegalStateException("permission-denied"),
                    seeded = listOf(incidentAt("mine")),
                ),
            )
        val surface = StubMapSurface(autoLoad = false)
        surface.emitIncidentTap("mine")

        runIncidentRemoval(controller, surface, "mine")

        assertNull(surface.placeRequest.value)
    }

    @Test
    fun `an accepted removal closes the sheet`() = runTest {
        val seeded = listOf(incidentAt("mine"))
        val controller = controllerWith(FakeRepository(seeded = seeded))
        val surface = StubMapSurface(autoLoad = false)
        surface.emitIncidentTap("mine")

        val removed = runIncidentRemoval(controller, surface, "mine")

        assertTrue(removed)
        assertNull(
            "the sheet stayed open after the incident was removed from the map",
            openSheetIncident(surface, controller),
        )
    }

    @Test
    fun `an accepted removal of one incident leaves another one's sheet resolvable`() = runTest {
        // The action must close the sheet for the incident it removed, not the
        // tap channel in general.
        val seeded = listOf(incidentAt("mine"), incidentAt("theirs"))
        val controller = controllerWith(FakeRepository(seeded = seeded))
        val surface = StubMapSurface(autoLoad = false)

        runIncidentRemoval(controller, surface, "mine")
        surface.emitIncidentTap("theirs")

        assertEquals("theirs", openSheetIncident(surface, controller)?.id)
    }

    @Test
    fun `a late removal does not close a sheet the user opened for a different incident`() =
        runTest {
            // The interleaving the test above does NOT cover, because it emits
            // the second tap only after the removal has already finished:
            //
            //   1. tap A, press remove — the call goes out;
            //   2. dismiss the sheet while it is still in flight;
            //   3. tap B — a new sheet opens;
            //   4. A's removal comes back successful.
            //
            // Step 4 must not close B's sheet. The tap channel is a single
            // slot, so an unconditional consume on success clears whatever is
            // in it — which by then is B, an incident nobody asked to remove
            // and which is still on the map.
            val seeded = listOf(incidentAt("mine"), incidentAt("theirs"))
            val gate = CompletableDeferred<Unit>()
            val repository =
                object : IncidentRepository {
                    override suspend fun report(
                        type: IncidentType,
                        location: LatLng,
                        note: String?,
                    ): Incident = Incident(id = "new", type = type, longitude = 0.0, latitude = 0.0)

                    override suspend fun listNearby(
                        center: LatLng,
                        radiusMeters: Double,
                    ): List<Incident> = seeded

                    // Holds the removal open so the taps below land in the
                    // middle of it, rather than before or after.
                    override suspend fun remove(incidentId: String) = gate.await()

                    override suspend fun confirm(incidentId: String) = IncidentConfirmResult(0, false)

                    override suspend fun reportCleared(
                        incidentId: String,
                        fix: IncidentClearFix,
                    ) = IncidentClearResult(0, 0, false, removed = false, alreadyVoted = false)
                }
            val controller = controllerWith(repository)
            val surface = StubMapSurface(autoLoad = false)

            surface.emitIncidentTap("mine")
            val removal = async { runIncidentRemoval(controller, surface, "mine") }
            runCurrent()

            // The user dismisses, then opens a different incident, all while
            // the first removal is still out.
            surface.consumeIncidentTap()
            surface.emitIncidentTap("theirs")

            gate.complete(Unit)
            assertTrue("the removal should have been accepted", removal.await())

            assertEquals(
                "a late removal of another incident closed the sheet the user is looking at",
                "theirs",
                openSheetIncident(surface, controller)?.id,
            )
        }
}
