package com.kungsbackacarcommunity.app.drives

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
