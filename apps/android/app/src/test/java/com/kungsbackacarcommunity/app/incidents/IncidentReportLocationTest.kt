package com.kungsbackacarcommunity.app.incidents

import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A minimal recording fake so the reportAt tests don't depend on IncidentTest's private one. */
private class RecordingRepository(
    private val reportError: Throwable? = null,
) : IncidentRepository {
    val reported = mutableListOf<Triple<IncidentType, LatLng, String?>>()
    var listNearbyCalls = 0
        private set

    override suspend fun report(type: IncidentType, location: LatLng, note: String?): Incident {
        reportError?.let { throw it }
        reported += Triple(type, location, note)
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
        return emptyList()
    }

    override suspend fun remove(incidentId: String) = Unit

    override suspend fun confirm(incidentId: String): IncidentConfirmResult =
        IncidentConfirmResult(0, false)
}

class IsValidReportCoordinateTest {
    @Test
    fun `accepts a sane coordinate`() {
        assertTrue(isValidReportCoordinate(LatLng(longitude = 12.0757, latitude = 57.4874)))
    }

    @Test
    fun `accepts the WGS-84 extremes`() {
        assertTrue(isValidReportCoordinate(LatLng(longitude = -180.0, latitude = -90.0)))
        assertTrue(isValidReportCoordinate(LatLng(longitude = 180.0, latitude = 90.0)))
    }

    @Test
    fun `rejects out-of-range latitude`() {
        assertFalse(isValidReportCoordinate(LatLng(longitude = 0.0, latitude = 90.1)))
        assertFalse(isValidReportCoordinate(LatLng(longitude = 0.0, latitude = -90.1)))
    }

    @Test
    fun `rejects out-of-range longitude`() {
        assertFalse(isValidReportCoordinate(LatLng(longitude = 180.1, latitude = 0.0)))
        assertFalse(isValidReportCoordinate(LatLng(longitude = -180.1, latitude = 0.0)))
    }

    @Test
    fun `rejects non-finite coordinates`() {
        assertFalse(isValidReportCoordinate(LatLng(longitude = Double.NaN, latitude = 57.0)))
        assertFalse(isValidReportCoordinate(LatLng(longitude = 12.0, latitude = Double.NaN)))
        assertFalse(
            isValidReportCoordinate(LatLng(longitude = Double.POSITIVE_INFINITY, latitude = 0.0)),
        )
    }
}

class ReportLocationTest {
    @Test
    fun `Chosen carries the picked coordinate`() {
        val point = LatLng(longitude = 11.9746, latitude = 57.7089)
        val chosen = ReportLocation.Chosen(point)
        assertEquals(point, chosen.location)
    }
}

class IncidentReportAtTest {
    private val chosen = LatLng(longitude = 11.9746, latitude = 57.7089)

    @Test
    fun `reportAt sends at the chosen location without consulting the location provider`() =
        runTest {
            val repo = RecordingRepository()
            // A provider that MUST NOT be called: reportAt uses the explicit point.
            val controller =
                IncidentReportController(repo) { error("location provider must not be called") }

            val outcome = controller.reportAt(IncidentType.HAZARD, chosen, note = "gropar")

            assertSame(ReportOutcome.Success, outcome)
            assertEquals(1, repo.reported.size)
            assertEquals(IncidentType.HAZARD, repo.reported[0].first)
            assertEquals(chosen, repo.reported[0].second)
            assertEquals("gropar", repo.reported[0].third)
            // The reporter's own marker is on the map from the write alone.
            val markers = controller.nearbyIncidents.value
            assertEquals(1, markers.size)
            assertEquals(chosen.latitude, markers[0].latitude, 1e-9)
            assertEquals(chosen.longitude, markers[0].longitude, 1e-9)
        }

    @Test
    fun `reportAt rejects an invalid coordinate and sends nothing`() = runTest {
        val repo = RecordingRepository()
        val controller = IncidentReportController(repo) { null }

        val outcome =
            controller.reportAt(IncidentType.POLICE, LatLng(longitude = 999.0, latitude = 999.0))

        assertSame(ReportOutcome.NoLocation, outcome)
        assertTrue(repo.reported.isEmpty())
        assertEquals(0, repo.listNearbyCalls)
    }

    @Test
    fun `reportAt surfaces a backend failure`() = runTest {
        val repo = RecordingRepository(reportError = IllegalStateException("boom"))
        val controller = IncidentReportController(repo) { null }

        val outcome = controller.reportAt(IncidentType.ROADWORK, chosen)

        assertTrue(outcome is ReportOutcome.Failed)
        assertTrue(controller.nearbyIncidents.value.isEmpty())
    }
}
