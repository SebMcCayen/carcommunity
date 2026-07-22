package com.kungsbackacarcommunity.app.drives

import com.kungsbackacarcommunity.app.media.MediaUploader
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRecordingTest {

    // ---------------------------------------------------------------------
    // DriveRecorder — point accumulation, ordering, cap.
    // ---------------------------------------------------------------------

    @Test
    fun `accumulates points in arrival order`() {
        val recorder = DriveRecorder(sourceSessionId = "s1", startedAtMillis = 1_000L)
        recorder.addPoint(RecordedPoint(57.0, 12.0, 1_000L))
        recorder.addPoint(RecordedPoint(57.1, 12.1, 2_000L))
        assertEquals(2, recorder.pointCount)
        assertEquals(listOf(1_000L, 2_000L), recorder.snapshot().map { it.timestampMs })
    }

    @Test
    fun `drops out-of-order fixes so timestamps stay non-decreasing`() {
        val recorder = DriveRecorder(sourceSessionId = "s1", startedAtMillis = 0L)
        recorder.addPoint(RecordedPoint(57.0, 12.0, 5_000L))
        recorder.addPoint(RecordedPoint(57.1, 12.1, 4_000L)) // earlier — dropped
        recorder.addPoint(RecordedPoint(57.2, 12.2, 6_000L))
        assertEquals(listOf(5_000L, 6_000L), recorder.snapshot().map { it.timestampMs })
    }

    @Test
    fun `caps at MAX_ROUTE_POINTS and stops accepting without crashing`() {
        val recorder = DriveRecorder(sourceSessionId = "s1", startedAtMillis = 0L)
        repeat(DriveRecorder.MAX_ROUTE_POINTS + 50) { i ->
            recorder.addPoint(RecordedPoint(57.0, 12.0, i.toLong()))
        }
        assertEquals(DriveRecorder.MAX_ROUTE_POINTS, recorder.pointCount)
        assertTrue(recorder.isFull)
    }

    @Test
    fun `running distance matches the bulk total and starts at zero`() {
        val recorder = DriveRecorder(sourceSessionId = "s1", startedAtMillis = 0L)
        assertEquals(0.0, recorder.distanceMetres, 0.0)

        val points =
            listOf(
                RecordedPoint(57.0000, 12.0000, 0L),
                RecordedPoint(57.0010, 12.0000, 10_000L),
                RecordedPoint(57.0020, 12.0000, 20_000L),
            )
        points.forEach { recorder.addPoint(it) }

        // The incremental accumulator must equal a bulk recompute over the same
        // points (single source of truth via DriveSummary.segmentDistanceMetres).
        assertEquals(
            DriveSummary.totalDistanceMetres(points),
            recorder.distanceMetres,
            0.0001,
        )
        assertTrue("expected a positive distance", recorder.distanceMetres > 0.0)
    }

    @Test
    fun `running distance excludes implausible GPS jumps like the bulk total`() {
        val recorder = DriveRecorder(sourceSessionId = "s1", startedAtMillis = 0L)
        // A ~1 km hop in 1 ms implies an absurd speed — the backend jump filter
        // drops it, so the running total must ignore it too.
        recorder.addPoint(RecordedPoint(57.0000, 12.0000, 0L))
        recorder.addPoint(RecordedPoint(57.0100, 12.0000, 1L))
        assertEquals(0.0, recorder.distanceMetres, 0.0001)
    }

    // ---------------------------------------------------------------------
    // buildSaveRequest — exact payload shape.
    // ---------------------------------------------------------------------

    @Test
    fun `buildSaveRequest emits exact callable payload keys`() {
        val recorder = DriveRecorder(sourceSessionId = "sess-1", startedAtMillis = 0L)
        recorder.addPoint(RecordedPoint(57.0, 12.0, 0L))
        recorder.addPoint(RecordedPoint(57.5, 12.5, 60_000L))

        val request = recorder.buildSaveRequest(title = "  Morning loop  ", endedAtMillis = 60_000L)

        assertEquals("1970-01-01T00:00:00Z", request["startedAt"])
        assertEquals("1970-01-01T00:01:00Z", request["endedAt"])
        assertEquals("Morning loop", request["title"]) // trimmed
        assertEquals("sess-1", request["sourceSessionId"])

        @Suppress("UNCHECKED_CAST")
        val points = request["routePoints"] as List<Map<String, Any?>>
        assertEquals(2, points.size)
        assertEquals(setOf("latitude", "longitude", "timestampMs"), points[0].keys)
        assertEquals(57.0, points[0]["latitude"])
        assertEquals(12.0, points[0]["longitude"])
        assertEquals(0L, points[0]["timestampMs"])
    }

    @Test
    fun `buildSaveRequest omits blank title and empty routePoints`() {
        val recorder = DriveRecorder(sourceSessionId = "sess-2", startedAtMillis = 0L)
        val request = recorder.buildSaveRequest(title = "   ", endedAtMillis = 1_000L)
        assertFalse(request.containsKey("title"))
        assertFalse(request.containsKey("routePoints"))
        assertEquals("sess-2", request["sourceSessionId"])
    }

    @Test
    fun `buildSaveRequest uses the last fix time as endedAt when points exist`() {
        val recorder = DriveRecorder(sourceSessionId = "sess-end", startedAtMillis = 0L)
        recorder.addPoint(RecordedPoint(57.0, 12.0, 0L))
        recorder.addPoint(RecordedPoint(57.5, 12.5, 60_000L))

        // Wall clock runs ahead of the last GPS fix (Location.time vs
        // System.currentTimeMillis skew); endedAt must follow the last fix.
        val request = recorder.buildSaveRequest(title = null, endedAtMillis = 95_000L)
        assertEquals("1970-01-01T00:01:00Z", request["endedAt"])
    }

    @Test
    fun `buildSaveRequest clamps endedAt up to the last fix when the wall clock lags`() {
        val recorder = DriveRecorder(sourceSessionId = "sess-clamp", startedAtMillis = 0L)
        recorder.addPoint(RecordedPoint(57.0, 12.0, 60_000L))

        // Even if the caller passes an earlier stop moment, endedAt never
        // precedes the last accepted point.
        val request = recorder.buildSaveRequest(title = null, endedAtMillis = 10_000L)
        assertEquals("1970-01-01T00:01:00Z", request["endedAt"])
    }

    @Test
    fun `buildSaveRequest falls back to the wall clock for summary-only saves`() {
        val recorder = DriveRecorder(sourceSessionId = "sess-summary", startedAtMillis = 0L)
        // No points accumulated — endedAt comes straight from the wall clock.
        val request = recorder.buildSaveRequest(title = null, endedAtMillis = 42_000L)
        assertFalse(request.containsKey("routePoints"))
        assertEquals("1970-01-01T00:00:42Z", request["endedAt"])
    }

    @Test
    fun `buildSaveRequest yields endedAt strictly after startedAt on an instant stop`() {
        // User stops in the same millisecond the recording started (no points):
        // endedAt would equal startedAt, which the backend guard rejects
        // (endedAt <= startedAt). It must be floored to startedAt + 1ms.
        val recorder = DriveRecorder(sourceSessionId = "sess-instant", startedAtMillis = 1_000L)
        val request = recorder.buildSaveRequest(title = null, endedAtMillis = 1_000L)
        assertFalse(request.containsKey("routePoints"))
        assertEquals("1970-01-01T00:00:01Z", request["startedAt"])
        // startedAt + 1ms — sub-second precision so the ISO string carries millis.
        assertEquals("1970-01-01T00:00:01.001Z", request["endedAt"])
    }

    @Test
    fun `buildSaveRequest yields endedAt after startedAt when the first fix timestamp is stale`() {
        // The first accepted fix carries a timestamp at/behind startedAt (clock
        // skew), so lastPointMillis never advances past startedAtMillis. Deriving
        // endedAt from the last fix alone would produce endedAt <= startedAt; the
        // startedAt + 1ms floor keeps it strictly after.
        val recorder = DriveRecorder(sourceSessionId = "sess-stale", startedAtMillis = 5_000L)
        recorder.addPoint(RecordedPoint(57.0, 12.0, 5_000L)) // same millis as start
        val request = recorder.buildSaveRequest(title = null, endedAtMillis = 5_000L)
        assertEquals("1970-01-01T00:00:05Z", request["startedAt"])
        assertEquals("1970-01-01T00:00:05.001Z", request["endedAt"])
    }

    @Test
    fun `buildSaveRequest caps the title at the backend max length`() {
        val recorder = DriveRecorder(sourceSessionId = "sess-3", startedAtMillis = 0L)
        val long = "x".repeat(DriveRecorder.DRIVE_TITLE_MAX_LENGTH + 25)
        val request = recorder.buildSaveRequest(title = long, endedAtMillis = 1_000L)
        assertEquals(DriveRecorder.DRIVE_TITLE_MAX_LENGTH, (request["title"] as String).length)
    }

    // ---------------------------------------------------------------------
    // DriveRecordingCoordinator — state transitions + save/discard/failure.
    // ---------------------------------------------------------------------

    private fun coordinator(shouldFail: Boolean): DriveRecordingCoordinator {
        var now = 0L
        return DriveRecordingCoordinator(
            repository = RecordingFakeRepository(shouldFail),
            sourceSessionId = "sess",
            clock = { now += 1_000L; now },
        )
    }

    @Test
    fun `start moves Idle to Recording`() {
        val c = coordinator(shouldFail = false)
        assertEquals(RecordingState.Idle, c.state.value)
        c.start()
        assertTrue(c.state.value is RecordingState.Recording)
    }

    @Test
    fun `addFix updates the live counters only while recording`() {
        val c = coordinator(shouldFail = false)
        c.addFix(57.0, 12.0, 1_000L) // ignored before start
        assertEquals(RecordingState.Idle, c.state.value)
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        assertEquals(1, (c.state.value as RecordingState.Recording).pointCount)
    }

    @Test
    fun `stop opens the save prompt`() {
        val c = coordinator(shouldFail = false)
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        c.stop()
        assertTrue(c.state.value is RecordingState.PromptSave)
    }

    @Test
    fun `save transitions to Saved on success`() = runTest {
        val c = coordinator(shouldFail = false)
        c.start()
        c.stop()
        c.save(title = "Trip")
        assertEquals(RecordingState.Saved, c.state.value)
    }

    @Test
    fun `save transitions to Failed and is retryable`() = runTest {
        val c = coordinator(shouldFail = true)
        c.start()
        c.stop()
        c.save(title = null)
        assertTrue(c.state.value is RecordingState.Failed)
    }

    // ---------------------------------------------------------------------
    // Regression (v0.8.0 "Could not save the drive"): the save failure's code
    // must survive into the state, so the prompt can tell a permanent member-gate
    // refusal from a retryable fault and the auto error report files a code.
    // ---------------------------------------------------------------------

    @Test
    fun `save carries the callable status code into Failed`() = runTest {
        val c =
            DriveRecordingCoordinator(
                repository = FailingRepository(DriveSaveException(code = "PERMISSION_DENIED")),
                sourceSessionId = "sess",
                clock = { 1_000L },
            )
        c.start()
        c.stop()
        c.save(title = null)
        val failed = c.state.value as RecordingState.Failed
        assertEquals("PERMISSION_DENIED", failed.code)
    }

    @Test
    fun `a member-gate refusal is a permanent refusal, not a retryable fault`() = runTest {
        val c =
            DriveRecordingCoordinator(
                repository = FailingRepository(DriveSaveException(code = "PERMISSION_DENIED")),
                sourceSessionId = "sess",
                clock = { 1_000L },
            )
        c.start()
        c.stop()
        c.save(title = null)
        assertTrue((c.state.value as RecordingState.Failed).isPermanentRefusal)
    }

    @Test
    fun `a transient fault is not a permanent refusal`() = runTest {
        val c =
            DriveRecordingCoordinator(
                repository = FailingRepository(DriveSaveException(code = "UNAVAILABLE")),
                sourceSessionId = "sess",
                clock = { 1_000L },
            )
        c.start()
        c.stop()
        c.save(title = null)
        val failed = c.state.value as RecordingState.Failed
        assertEquals("UNAVAILABLE", failed.code)
        assertFalse(failed.isPermanentRefusal)
    }

    @Test
    fun `a non-callable failure yields no code and is not a permanent refusal`() = runTest {
        val c =
            DriveRecordingCoordinator(
                repository = FailingRepository(IllegalStateException("socket closed")),
                sourceSessionId = "sess",
                clock = { 1_000L },
            )
        c.start()
        c.stop()
        c.save(title = null)
        val failed = c.state.value as RecordingState.Failed
        assertNull(failed.code)
        assertFalse(failed.isPermanentRefusal)
    }

    // ---------------------------------------------------------------------
    // DriveRecordingGate — the v0.8.0 root cause. A live session must only
    // record a drive the backend would actually accept.
    // ---------------------------------------------------------------------

    @Test
    fun `a non-member live session records nothing, since drives-save would refuse it`() {
        assertFalse(
            DriveRecordingGate.shouldRecord(
                hasDrivesBackend = true,
                canShareLive = true,
                passesMemberGate = false,
            ),
        )
    }

    @Test
    fun `a member live session records`() {
        assertTrue(
            DriveRecordingGate.shouldRecord(
                hasDrivesBackend = true,
                canShareLive = true,
                passesMemberGate = true,
            ),
        )
    }

    @Test
    fun `no drives backend records nothing even for a member`() {
        assertFalse(
            DriveRecordingGate.shouldRecord(
                hasDrivesBackend = false,
                canShareLive = true,
                passesMemberGate = true,
            ),
        )
    }

    @Test
    fun `no live-share reach records nothing`() {
        assertFalse(
            DriveRecordingGate.shouldRecord(
                hasDrivesBackend = true,
                canShareLive = false,
                passesMemberGate = true,
            ),
        )
    }

    @Test
    fun `discard stores nothing and clears the recorder`() {
        val c = coordinator(shouldFail = false)
        c.start()
        c.stop()
        c.discard()
        assertEquals(RecordingState.Discarded, c.state.value)
    }

    @Test
    fun `discard forwards no request to the repository`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false)
        val c = DriveRecordingCoordinator(repo, "sess")
        c.start()
        c.stop()
        c.discard()
        assertNull(repo.lastRequest)
    }

    @Test
    fun `save on cancellation restores the prompt and rethrows`() = runTest {
        val repo = CancellingFakeRepository()
        val c = DriveRecordingCoordinator(repo, "sess")
        c.start()
        c.stop()
        var rethrown = false
        try {
            c.save(title = "Trip")
        } catch (cancellation: CancellationException) {
            rethrown = true
        }
        assertTrue("CancellationException must propagate", rethrown)
        // Cancellation is not a save failure: the prompt is restored, not Failed.
        assertTrue(c.state.value is RecordingState.PromptSave)
    }

    @Test
    fun `successful save releases the recorder so a repeat save is a no-op`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false)
        val c = DriveRecordingCoordinator(repo, "sess")
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        c.stop()
        c.save(title = "Trip")
        assertEquals(RecordingState.Saved, c.state.value)

        // Recorder was released on success; a second save can't run and the
        // terminal state is preserved for the UI.
        repo.lastRequest = null
        c.save(title = "Again")
        assertNull(repo.lastRequest)
        assertEquals(RecordingState.Saved, c.state.value)
    }

    @Test
    fun `summary-only save sends the stop moment as endedAt, not the save time`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false)
        // Explicit clock so the gap between stopping and saving is unambiguous.
        var now = 0L
        val c =
            DriveRecordingCoordinator(
                repository = repo,
                sourceSessionId = "sess",
                clock = { now },
            )
        now = 1_000L
        c.start()
        // No fixes: a summary-only save, where the backend derives the stored
        // duration straight from endedAt.
        now = 5_000L
        c.stop()
        // The user sits on the prompt for a while before saving.
        now = 90_000L
        c.save(title = null)

        // endedAt must be the STOP moment (5s), not the save moment (90s) —
        // otherwise History would store an 89s drive instead of a 4s one.
        assertEquals(
            Instant.ofEpochMilli(5_000L).toString(),
            repo.lastRequest?.get("endedAt"),
        )
        assertEquals(
            Instant.ofEpochMilli(1_000L).toString(),
            repo.lastRequest?.get("startedAt"),
        )
    }

    @Test
    fun `retrying a failed save reuses the original stop moment`() = runTest {
        val repo = RetryFakeRepository()
        var now = 0L
        val c =
            DriveRecordingCoordinator(
                repository = repo,
                sourceSessionId = "sess",
                clock = { now },
            )
        now = 1_000L
        c.start()
        now = 5_000L
        c.stop()

        now = 20_000L
        c.save(title = null)
        assertTrue(c.state.value is RecordingState.Failed)
        val firstEndedAt = repo.lastRequest?.get("endedAt")

        // Retry much later: the stored end time must not drift.
        now = 300_000L
        c.save(title = null)
        assertEquals(RecordingState.Saved, c.state.value)
        assertEquals(Instant.ofEpochMilli(5_000L).toString(), firstEndedAt)
        assertEquals(firstEndedAt, repo.lastRequest?.get("endedAt"))
    }

    @Test
    fun `discard releases the recorder so a subsequent save is a no-op`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false)
        val c = DriveRecordingCoordinator(repo, "sess")
        c.start()
        c.stop()
        c.discard()
        c.save(title = "Trip")
        assertNull(repo.lastRequest)
        assertEquals(RecordingState.Discarded, c.state.value)
    }

    // ---------------------------------------------------------------------
    // Live auto-save → keep / delete. A finished LIVE session AUTO-saves the
    // drive (so it can never be lost by a missed Save), then the summary asks
    // KEEP (it stays) or DELETE (removed again via drives-delete).
    // ---------------------------------------------------------------------

    @Test
    fun `autoSave persists the drive and lands in SavedPendingChoice with the rideId`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false)
        val c = DriveRecordingCoordinator(repo, "sess")
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        c.stop()
        c.autoSave(title = null)
        val state = c.state.value as RecordingState.SavedPendingChoice
        assertEquals("ride", state.rideId)
        assertNotNull("the drive must reach the repository", repo.lastRequest)
    }

    @Test
    fun `autoSave keeps the recorder so the summary preview still resolves`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false)
        val c = DriveRecordingCoordinator(repo, "sess")
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        c.addFix(57.001, 12.0, 2_000L)
        c.stop()
        c.autoSave(title = null)
        // Unlike the manual save(), the recorder is NOT released here — the
        // Keep/Delete summary still shows the client-side distance/speed estimate.
        assertTrue(c.state.value is RecordingState.SavedPendingChoice)
        assertEquals(2, c.recordedPoints().size)
    }

    @Test
    fun `autoSave is only valid from the prompt or a prior failure`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false)
        val c = DriveRecordingCoordinator(repo, "sess")
        // No stop() first: still Recording, so autoSave is a no-op and nothing saves.
        c.start()
        c.autoSave(title = null)
        assertTrue(c.state.value is RecordingState.Recording)
        assertNull(repo.lastRequest)
    }

    @Test
    fun `keep resolves to Kept and deletes nothing`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false)
        val c = DriveRecordingCoordinator(repo, "sess")
        c.start()
        c.stop()
        c.autoSave(title = null)
        c.keep()
        assertEquals(RecordingState.Kept, c.state.value)
        assertTrue("keep must not delete", repo.deletedRideIds.isEmpty())
        // Recorder released on keep.
        assertEquals(0, c.recordedPoints().size)
    }

    @Test
    fun `delete removes the auto-saved ride and lands in Deleted`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false)
        val c = DriveRecordingCoordinator(repo, "sess")
        c.start()
        c.stop()
        c.autoSave(title = null)
        val rideId = (c.state.value as RecordingState.SavedPendingChoice).rideId
        c.delete()
        assertEquals(RecordingState.Deleted, c.state.value)
        assertEquals(listOf(rideId), repo.deletedRideIds)
    }

    @Test
    fun `delete failure returns to SavedPendingChoice with deleteFailed, then a retry deletes`() =
        runTest {
            val repo = RecordingFakeRepository(shouldFail = false).apply { deleteShouldFail = true }
            val c = DriveRecordingCoordinator(repo, "sess")
            c.start()
            c.stop()
            c.autoSave(title = null)
            c.delete()
            val failed = c.state.value as RecordingState.SavedPendingChoice
            assertTrue("the drive stays saved and the choice stands", failed.deleteFailed)
            assertTrue(repo.deletedRideIds.isEmpty())

            // Fault cleared: a retry deletes.
            repo.deleteShouldFail = false
            c.delete()
            assertEquals(RecordingState.Deleted, c.state.value)
            assertEquals(listOf("ride"), repo.deletedRideIds)
        }

    @Test
    fun `an auto-save failure is retryable and the retry lands in SavedPendingChoice`() = runTest {
        // First save fails, the retry succeeds — the live retry must land in
        // SavedPendingChoice (NOT the manual save()'s terminal Saved).
        val repo = RetryFakeRepository()
        val c = DriveRecordingCoordinator(repo, "sess")
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        c.stop()
        c.autoSave(title = null)
        assertTrue(c.state.value is RecordingState.Failed)
        c.autoSave(title = null)
        assertTrue(c.state.value is RecordingState.SavedPendingChoice)
    }

    @Test
    fun `delete waits for the in-flight route upload before removing the drive`() = runBlocking {
        // drives-delete removes the whole rideRoutes/{uid}/{rideId}/ prefix then
        // the doc, so a route upload that finished AFTER the delete would orphan
        // route.bin. delete() must JOIN the upload first.
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val order = CopyOnWriteArrayList<String>()
        val uploader =
            object : MediaUploader {
                override suspend fun upload(path: String, bytes: ByteArray, contentType: String): String {
                    uploadStarted.complete(Unit)
                    releaseUpload.await()
                    order.add("upload")
                    return path
                }
            }
        val repo =
            object : DrivesRepository {
                override fun observeDrives(uid: String) = throw UnsupportedOperationException()

                override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult =
                    DriveSaveResult(
                        rideId = "ride",
                        routePath = "rideRoutes/uid/ride/route.bin",
                        alreadySaved = false,
                    )

                override suspend fun deleteDrive(rideId: String) {
                    order.add("delete:$rideId")
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val c =
            DriveRecordingCoordinator(
                repository = repo,
                sourceSessionId = "sess",
                routeUploadRunner = RouteUploadRunner(uploader, delayFn = {}),
                uploadScope = scope,
            )
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        c.addFix(57.001, 12.0, 2_000L)
        c.stop()
        c.autoSave(title = null)
        withTimeout(5_000) { uploadStarted.await() }

        // Delete in the background — it must block on the upload join.
        val deleteJob = launch { c.delete() }
        // Bound how long a wrongly-early delete has to surface; the passing path
        // (delete blocks on the upload join) never runs drives-delete here.
        delay(50L)
        assertTrue("delete must not run drives-delete until the upload settles", order.isEmpty())

        releaseUpload.complete(Unit)
        withTimeout(5_000) { deleteJob.join() }
        assertEquals(listOf("upload", "delete:ride"), order.toList())
        assertEquals(RecordingState.Deleted, c.state.value)
        scope.cancel()
    }

    // ---------------------------------------------------------------------
    // Route upload wiring: on a successful save the recorded route is uploaded
    // to the path the callable returned, in the background, using the SAME fixes.
    // ---------------------------------------------------------------------

    @Test
    fun `successful save uploads the recorded route to the returned path`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false) // routePath = rideRoutes/uid/ride/route.bin
        val uploader = CapturingUploader()
        val c =
            DriveRecordingCoordinator(
                repository = repo,
                sourceSessionId = "sess",
                routeUploadRunner = RouteUploadRunner(uploader, delayFn = {}),
                uploadScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            )
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        c.addFix(57.001, 12.0, 2_000L)
        c.stop()
        c.save(title = "Trip")
        advanceUntilIdle()

        assertEquals(RecordingState.Saved, c.state.value)
        assertEquals("rideRoutes/uid/ride/route.bin", uploader.lastPath)
        // The uploaded bytes decode back to the two fixes the backend priced.
        val decoded = RouteCodec.decode(uploader.lastBytes)
        assertEquals(2, decoded?.size)
    }

    @Test
    fun `save still succeeds and skips upload when there are no route points`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false)
        val uploader = CapturingUploader()
        val c =
            DriveRecordingCoordinator(
                repository = repo,
                sourceSessionId = "sess",
                routeUploadRunner = RouteUploadRunner(uploader, delayFn = {}),
                uploadScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            )
        c.start()
        // Summary-only: no fixes, so there is no route.bin to upload.
        c.stop()
        c.save(title = null)
        advanceUntilIdle()

        assertEquals(RecordingState.Saved, c.state.value)
        assertEquals(0, uploader.attempts)
    }

    @Test
    fun `a failed upload does not affect the completed save`() = runTest {
        val repo = RecordingFakeRepository(shouldFail = false)
        // Uploader always fails; the drive doc already exists, so Saved must stand.
        val uploader = CapturingUploader(alwaysFail = true)
        val c =
            DriveRecordingCoordinator(
                repository = repo,
                sourceSessionId = "sess",
                routeUploadRunner = RouteUploadRunner(uploader, delayFn = {}),
                uploadScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            )
        c.start()
        c.addFix(57.0, 12.0, 1_000L)
        c.addFix(57.001, 12.0, 2_000L)
        c.stop()
        c.save(title = "Trip")
        advanceUntilIdle()

        // Save is complete despite the route upload exhausting its retries.
        assertEquals(RecordingState.Saved, c.state.value)
        assertEquals(RouteUploadRunner.DEFAULT_MAX_ATTEMPTS, uploader.attempts)
    }
}

/** Fake [MediaUploader] for coordinator wiring tests. */
private class CapturingUploader(private val alwaysFail: Boolean = false) : MediaUploader {
    var attempts = 0
    var lastPath: String? = null
    var lastBytes: ByteArray? = null

    override suspend fun upload(path: String, bytes: ByteArray, contentType: String): String {
        attempts++
        if (alwaysFail) throw IllegalStateException("upload failed")
        lastPath = path
        lastBytes = bytes
        return path
    }
}

private class CancellingFakeRepository : DrivesRepository {
    override fun observeDrives(uid: String) = throw UnsupportedOperationException()

    override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult =
        throw CancellationException("scope cancelled")

    override suspend fun deleteDrive(rideId: String) = throw UnsupportedOperationException()
}

/**
 * Records every request and fails only the FIRST save, so a retry succeeds —
 * lets a test compare the payload across the failed attempt and the retry.
 */
private class RetryFakeRepository : DrivesRepository {
    var lastRequest: Map<String, Any?>? = null
    private var attempts = 0

    override fun observeDrives(uid: String) = throw UnsupportedOperationException()

    override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult {
        lastRequest = request
        attempts++
        if (attempts == 1) throw IllegalStateException("save failed")
        return DriveSaveResult(rideId = "ride", routePath = null, alreadySaved = false)
    }

    override suspend fun deleteDrive(rideId: String) = throw UnsupportedOperationException()
}

/** Repository whose save always fails with [failure], to pin failure mapping. */
private class FailingRepository(private val failure: Exception) : DrivesRepository {
    override fun observeDrives(uid: String) = throw UnsupportedOperationException()

    override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult = throw failure

    override suspend fun deleteDrive(rideId: String) = throw UnsupportedOperationException()
}

private class RecordingFakeRepository(private val shouldFail: Boolean) : DrivesRepository {
    var lastRequest: Map<String, Any?>? = null

    /** Every rideId passed to [deleteDrive], so the auto-save flow can assert it. */
    val deletedRideIds = mutableListOf<String>()

    /** When true, [deleteDrive] throws — pins the delete-failure branch. */
    var deleteShouldFail = false

    override fun observeDrives(uid: String) = throw UnsupportedOperationException()

    override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult {
        if (shouldFail) throw IllegalStateException("save failed")
        lastRequest = request
        return DriveSaveResult(
            rideId = "ride",
            routePath = "rideRoutes/uid/ride/route.bin",
            alreadySaved = false,
        )
    }

    override suspend fun deleteDrive(rideId: String) {
        if (deleteShouldFail) throw IllegalStateException("delete failed")
        deletedRideIds += rideId
    }
}
