package com.kungsbackacarcommunity.app.drives

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

private class CancellingFakeRepository : DrivesRepository {
    override fun observeDrives(uid: String) = throw UnsupportedOperationException()

    override suspend fun saveDrive(request: Map<String, Any?>): Unit =
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

    override suspend fun saveDrive(request: Map<String, Any?>) {
        lastRequest = request
        attempts++
        if (attempts == 1) throw IllegalStateException("save failed")
    }

    override suspend fun deleteDrive(rideId: String) = throw UnsupportedOperationException()
}

/** Repository whose save always fails with [failure], to pin failure mapping. */
private class FailingRepository(private val failure: Exception) : DrivesRepository {
    override fun observeDrives(uid: String) = throw UnsupportedOperationException()

    override suspend fun saveDrive(request: Map<String, Any?>): Unit = throw failure

    override suspend fun deleteDrive(rideId: String) = throw UnsupportedOperationException()
}

private class RecordingFakeRepository(private val shouldFail: Boolean) : DrivesRepository {
    var lastRequest: Map<String, Any?>? = null

    override fun observeDrives(uid: String) = throw UnsupportedOperationException()

    override suspend fun saveDrive(request: Map<String, Any?>) {
        if (shouldFail) throw IllegalStateException("save failed")
        lastRequest = request
    }

    override suspend fun deleteDrive(rideId: String) = throw UnsupportedOperationException()
}
