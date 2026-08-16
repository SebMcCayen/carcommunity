package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the #849 FOLLOW-UP: after a killed-and-relaunched drive resumes, the
 * coordinator exposes the rehydrated route ([DriveRecordingCoordinator.resumedRoutePoints])
 * so the host can redraw the on-screen tail, and the lifecycle log
 * ([DriveRecordingLog]) records start / resume / milestone / stop so a recurrence
 * of "the in-progress drive vanished after a restart" is diagnosable.
 *
 * The earlier data round-trip (points survive disk, the recorder resumes) is
 * covered by [DriveRecordingJournalTest]; this file adds the redraw + telemetry
 * seams on top.
 */
class DriveRecordingRestoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val session = "sess-restore"

    private fun journal(): DriveRecordingJournal =
        DriveRecordingJournal(tempFolder.newFolder("journals"))

    private fun coordinator(
        j: DriveRecordingJournal,
        log: DriveRecordingLog,
    ): DriveRecordingCoordinator =
        DriveRecordingCoordinator(
            repository = FakeRepository(),
            sourceSessionId = session,
            journal = j,
            log = log,
        )

    // --- resumedRoutePoints (the map-redraw seam) ------------------------

    @Test
    fun `a fresh start exposes no resumed route and logs a start`() {
        val log = RecordingLogSpy()
        val c = coordinator(journal(), log)
        c.start()

        assertTrue(c.resumedRoutePoints.isEmpty())
        assertEquals(listOf("started:$session"), log.events)
    }

    @Test
    fun `a resumed start exposes the persisted route and logs a resume`() {
        val j = journal()
        // A drive was recorded then the process was killed: a journal is on disk.
        j.begin(session, startedAtMillis = 1_000L)
        val persisted =
            listOf(
                RecordedPoint(57.0, 12.0, 1_100L),
                RecordedPoint(57.001, 12.0, 1_200L),
                RecordedPoint(57.002, 12.0, 1_300L),
            )
        j.appendPoints(session, persisted)

        // Relaunch: a fresh coordinator for the SAME live session starts.
        val log = RecordingLogSpy()
        val c = coordinator(j, log)
        c.start()

        // The resumed route is exposed for the host to re-seed the map tail…
        assertEquals(persisted, c.resumedRoutePoints)
        // …and the resume — with its restored point count — is logged.
        assertEquals(listOf("resumed:$session:3"), log.events)
    }

    // --- lifecycle logging ----------------------------------------------

    @Test
    fun `a point milestone is logged every interval, not per fix`() {
        val log = RecordingLogSpy()
        val c = coordinator(journal(), log)
        c.start()

        // Feed exactly MILESTONE_INTERVAL accepted fixes with strictly increasing
        // timestamps (the recorder drops out-of-order fixes).
        val interval = DriveRecordingLog.MILESTONE_INTERVAL
        for (i in 1..interval) {
            c.addFix(57.0 + i * 0.0001, 12.0, 1_000L + i)
        }

        // One start + exactly one milestone at the interval boundary — never one
        // event per fix.
        assertEquals(
            listOf("started:$session", "milestone:$session:$interval"),
            log.events,
        )
    }

    @Test
    fun `stop logs the total point count`() {
        val log = RecordingLogSpy()
        val c = coordinator(journal(), log)
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        c.addFix(57.001, 12.0, 2_000L)
        c.stop()

        assertEquals(listOf("started:$session", "stopped:$session:2"), log.events)
    }

    /** Captures every [DriveRecordingLog] call as a flat string for assertion. */
    private class RecordingLogSpy : DriveRecordingLog {
        val events = mutableListOf<String>()

        override fun started(sourceSessionId: String, startedAtMillis: Long) {
            events += "started:$sourceSessionId"
        }

        override fun resumed(sourceSessionId: String, restoredPoints: Int, startedAtMillis: Long) {
            events += "resumed:$sourceSessionId:$restoredPoints"
        }

        override fun milestone(sourceSessionId: String, pointCount: Int) {
            events += "milestone:$sourceSessionId:$pointCount"
        }

        override fun stopped(sourceSessionId: String, totalPoints: Int) {
            events += "stopped:$sourceSessionId:$totalPoints"
        }

        override fun restoredToMap(sourceSessionId: String, points: Int) {
            events += "restoredToMap:$sourceSessionId:$points"
        }
    }

    /** Minimal repository — the save/restore telemetry tests never save. */
    private class FakeRepository : DrivesRepository {
        override fun observeDrives(uid: String) = throw UnsupportedOperationException()

        override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult =
            DriveSaveResult(rideId = "ride", routePath = null, alreadySaved = false)

        override suspend fun deleteDrive(rideId: String) = Unit
    }
}
