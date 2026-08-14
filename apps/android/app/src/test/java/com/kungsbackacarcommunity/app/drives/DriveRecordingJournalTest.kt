package com.kungsbackacarcommunity.app.drives

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the crash-resilient recording journal (#849): points round-trip through
 * disk, a truncated final record is tolerated, and the coordinator RESUMES a
 * persisted drive on restart and CLEARS the journal once the drive is safe.
 */
class DriveRecordingJournalTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun journal(): DriveRecordingJournal = DriveRecordingJournal(tempFolder.newFolder("journals"))

    private val session = "sess-abc"

    // --- Pure journal round-trip -----------------------------------------

    @Test
    fun `begin then append round-trips the header start and every point`() {
        val j = journal()
        j.begin(session, startedAtMillis = 1_000L)
        j.appendPoints(session, listOf(RecordedPoint(57.0, 12.0, 1_100L)))
        j.appendPoints(session, listOf(RecordedPoint(57.001, 12.001, 1_200L)))

        val snapshot = j.load(session)!!
        assertEquals(1_000L, snapshot.startedAtMillis)
        assertEquals(
            listOf(
                RecordedPoint(57.0, 12.0, 1_100L),
                RecordedPoint(57.001, 12.001, 1_200L),
            ),
            snapshot.points,
        )
    }

    @Test
    fun `load returns null when there is no journal`() {
        assertNull(journal().load("never-started"))
    }

    @Test
    fun `a truncated final record is skipped, keeping every complete point`() {
        val dir = tempFolder.newFolder("journals")
        val j = DriveRecordingJournal(dir)
        j.begin(session, startedAtMillis = 500L)
        j.appendPoints(session, listOf(RecordedPoint(57.0, 12.0, 600L)))
        // Simulate a process kill MID-write: a partial trailing line with no newline.
        val file = dir.listFiles()!!.single { it.name.endsWith(".journal") }
        file.appendText("P,57.1,12.")

        val snapshot = j.load(session)!!
        assertEquals(500L, snapshot.startedAtMillis)
        assertEquals(listOf(RecordedPoint(57.0, 12.0, 600L)), snapshot.points)
    }

    @Test
    fun `appending after a truncated last line keeps every whole record intact`() {
        val dir = tempFolder.newFolder("journals")
        val j = DriveRecordingJournal(dir)
        j.begin(session, startedAtMillis = 500L)
        j.appendPoints(session, listOf(RecordedPoint(57.0, 12.0, 600L)))
        // A process kill mid-write leaves a truncated partial line (no newline).
        val file = dir.listFiles()!!.single { it.name.endsWith(".journal") }
        file.appendText("P,57.1,12.")

        // The session resumes and keeps recording: more fixes are appended. The
        // truncated partial must NOT fuse with the first new point into one corrupt
        // record — every WHOLE record must still parse, losing only the partial.
        j.appendPoints(
            session,
            listOf(
                RecordedPoint(57.2, 12.2, 700L),
                RecordedPoint(57.3, 12.3, 800L),
            ),
        )

        val snapshot = j.load(session)!!
        assertEquals(500L, snapshot.startedAtMillis)
        assertEquals(
            listOf(
                RecordedPoint(57.0, 12.0, 600L),
                RecordedPoint(57.2, 12.2, 700L),
                RecordedPoint(57.3, 12.3, 800L),
            ),
            snapshot.points,
        )
    }

    @Test
    fun `load returns null when the header is unreadable`() {
        val dir = tempFolder.newFolder("journals")
        val j = DriveRecordingJournal(dir)
        j.begin(session, startedAtMillis = 500L)
        // Corrupt the header (first line) — nothing to resume.
        val file = dir.listFiles()!!.single { it.name.endsWith(".journal") }
        file.writeText("garbage\nP,57.0,12.0,600\n")

        assertNull(j.load(session))
    }

    @Test
    fun `clear removes the journal`() {
        val j = journal()
        j.begin(session, startedAtMillis = 1L)
        j.appendPoints(session, listOf(RecordedPoint(57.0, 12.0, 2L)))
        j.clear(session)
        assertNull(j.load(session))
    }

    @Test
    fun `begin prunes a stale journal from a different session`() {
        val j = journal()
        j.begin("old-session", startedAtMillis = 1L)
        j.appendPoints("old-session", listOf(RecordedPoint(57.0, 12.0, 2L)))
        // A NEW session starting means the old one is long gone — it must be pruned.
        j.begin("new-session", startedAtMillis = 10L)
        assertNull("the stale journal must be pruned", j.load("old-session"))
    }

    // --- Coordinator resume + clear --------------------------------------

    private fun resumeCoordinator(
        scope: CoroutineScope,
        repo: DrivesRepository,
        journal: DriveRecordingJournal,
    ): DriveRecordingCoordinator =
        DriveRecordingCoordinator(
            repository = repo,
            sourceSessionId = session,
            uploadScope = scope,
            delayFn = {},
            journal = journal,
        )

    @Test
    fun `a restarted session resumes the persisted drive instead of starting empty`() = runTest {
        val j = journal()
        // A drive was recorded, then the process was killed: a journal is on disk.
        j.begin(session, startedAtMillis = 1_000L)
        j.appendPoints(
            session,
            listOf(
                RecordedPoint(57.0, 12.0, 1_100L),
                RecordedPoint(57.001, 12.0, 1_200L),
            ),
        )

        // Relaunch: a fresh coordinator for the SAME live session starts.
        val repo = JournalFakeRepository()
        val c = resumeCoordinator(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), repo, j)
        c.start()

        // It resumed: the persisted points are back, and the original start moment
        // is preserved (not reset to "now").
        assertTrue(c.state.value is RecordingState.Recording)
        assertEquals(2, c.recordedPoints().size)
        assertEquals(1_000L, c.startedAtMillis)
    }

    @Test
    fun `a fresh session with no journal starts empty`() {
        val repo = JournalFakeRepository()
        val c =
            DriveRecordingCoordinator(
                repository = repo,
                sourceSessionId = "brand-new",
                journal = journal(),
            )
        c.start()
        assertEquals(0, c.recordedPoints().size)
    }

    /**
     * Minimal repository whose save/delete simply succeed. No route path is
     * returned (there is no uploader wired here), so no route upload runs — the
     * journal is cleared purely on the SAVE/DELETE succeeding, which is what these
     * tests assert.
     */
    private class JournalFakeRepository : DrivesRepository {
        override fun observeDrives(uid: String) = throw UnsupportedOperationException()

        override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult =
            DriveSaveResult(rideId = "ride", routePath = null, alreadySaved = false)

        override suspend fun deleteDrive(rideId: String) = Unit
    }

    @Test
    fun `the journal is cleared once the background save succeeds`() = runTest {
        val j = journal()
        val repo = JournalFakeRepository()
        val c = resumeCoordinator(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), repo, j)
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        c.addFix(57.001, 12.0, 2_000L)
        c.stop()
        // stop() force-flushes the batch, so the drive is on disk before the save.
        assertTrue(j.load(session)!!.points.isNotEmpty())

        c.autoSave(title = null)
        advanceUntilIdle()

        // Persisted server-side → the journal is gone, so a relaunch won't resume
        // an already-saved drive.
        assertNull(j.load(session))
    }

    @Test
    fun `the journal is cleared when the drive is deleted`() = runTest {
        val j = journal()
        val repo = JournalFakeRepository()
        val c = resumeCoordinator(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), repo, j)
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        c.stop()
        c.autoSave(title = null)
        advanceUntilIdle()
        assertFalse((c.state.value as RecordingState.SavedPendingChoice).savePending)

        c.delete()
        advanceUntilIdle()
        assertEquals(RecordingState.Deleted, c.state.value)
        assertNull(j.load(session))
    }
}
