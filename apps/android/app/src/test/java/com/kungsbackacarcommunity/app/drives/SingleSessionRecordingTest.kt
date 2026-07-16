package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the config-change-survival contract of [SingleSessionRecording].
 *
 * The holder is process-scoped precisely so an Activity recreation (rotation)
 * cannot drop an in-flight recording or a pending save/discard prompt. The UI
 * drives it from a Compose effect that RE-RUNS after every recreation, so these
 * tests exercise that re-run directly: calling `start`/`stop` again must resume
 * the existing state rather than restart or clear it. That is the behaviour a
 * rotation actually produces, and it is what a `StateRestorationTester` UI test
 * would ultimately be asserting — pinned here without an instrumented device.
 */
class SingleSessionRecordingTest {

    private val repository = SingleSessionFakeRepository()

    // The holder is a process singleton: reset around every test.
    @Before fun setUp() = SingleSessionRecording.clear()

    @After fun tearDown() = SingleSessionRecording.clear()

    /** Stands in for the effect the UI runs whenever `isSharing` is true. */
    private fun onSharing() = SingleSessionRecording.start(repository) { null }

    @Test
    fun `start begins a recording`() {
        onSharing()
        assertNotNull(SingleSessionRecording.active.value)
        assertTrue(SingleSessionRecording.active.value?.state?.value is RecordingState.Recording)
        assertFalse(SingleSessionRecording.promptPending.value)
    }

    @Test
    fun `re-running start after a recreation keeps the same recording and its points`() {
        onSharing()
        val coordinator = SingleSessionRecording.active.value
        coordinator?.addFix(57.0, 12.0, 1_000L)
        coordinator?.addFix(57.001, 12.0, 2_000L)
        assertEquals(2, coordinator?.recordedPoints()?.size)

        // Rotation: the composition is recreated and the effect re-runs.
        onSharing()

        // Same coordinator, same points — nothing restarted, nothing lost.
        assertSame(coordinator, SingleSessionRecording.active.value)
        assertEquals(2, SingleSessionRecording.active.value?.recordedPoints()?.size)
        assertTrue(SingleSessionRecording.active.value?.state?.value is RecordingState.Recording)
    }

    @Test
    fun `stop raises the pending prompt`() {
        onSharing()
        SingleSessionRecording.active.value?.addFix(57.0, 12.0, 1_000L)
        SingleSessionRecording.stop()
        assertTrue(SingleSessionRecording.promptPending.value)
        assertTrue(SingleSessionRecording.active.value?.state?.value is RecordingState.PromptSave)
    }

    @Test
    fun `a recreation while the prompt is open keeps the prompt and its data`() {
        onSharing()
        val coordinator = SingleSessionRecording.active.value
        coordinator?.addFix(57.0, 12.0, 1_000L)
        coordinator?.addFix(57.001, 12.0, 2_000L)
        SingleSessionRecording.stop()
        val elapsed = (coordinator?.state?.value as RecordingState.PromptSave).elapsedMillis

        // Rotation with the summary dialog open: the effect re-runs with
        // isSharing still false, so stop() is called again.
        SingleSessionRecording.stop()

        // The user must still be asked, and the summary data is intact.
        assertTrue(SingleSessionRecording.promptPending.value)
        assertSame(coordinator, SingleSessionRecording.active.value)
        val state = SingleSessionRecording.active.value?.state?.value
        assertTrue(state is RecordingState.PromptSave)
        assertEquals(elapsed, (state as RecordingState.PromptSave).elapsedMillis)
        assertEquals(2, SingleSessionRecording.active.value?.recordedPoints()?.size)
    }

    @Test
    fun `a recreation while the prompt is open does not start a second recording`() {
        onSharing()
        val coordinator = SingleSessionRecording.active.value
        SingleSessionRecording.stop()

        // Even if isSharing briefly reads true again, the pending prompt must
        // not be replaced by a fresh recording (which would drop the drive).
        onSharing()

        assertSame(coordinator, SingleSessionRecording.active.value)
        assertTrue(SingleSessionRecording.promptPending.value)
    }

    @Test
    fun `saving from the prompt survives a recreation and then clears`() = runTest {
        onSharing()
        SingleSessionRecording.active.value?.addFix(57.0, 12.0, 1_000L)
        SingleSessionRecording.stop()
        SingleSessionRecording.stop() // recreation

        SingleSessionRecording.active.value?.save(title = null)
        assertEquals(RecordingState.Saved, SingleSessionRecording.active.value?.state?.value)
        assertNotNull("the drive must reach the repository", repository.lastRequest)

        // The UI clears on the terminal state.
        SingleSessionRecording.clear()
        assertNull(SingleSessionRecording.active.value)
        assertFalse(SingleSessionRecording.promptPending.value)
    }

    @Test
    fun `clear releases the recording so the next session starts fresh`() {
        onSharing()
        val first = SingleSessionRecording.active.value
        SingleSessionRecording.stop()
        SingleSessionRecording.clear()

        onSharing()
        val second = SingleSessionRecording.active.value
        assertNotNull(second)
        assertTrue("a new session must get a new coordinator", first !== second)
        assertFalse(SingleSessionRecording.promptPending.value)
    }

    @Test
    fun `stop before any start is a no-op`() {
        SingleSessionRecording.stop()
        assertNull(SingleSessionRecording.active.value)
        assertFalse(SingleSessionRecording.promptPending.value)
    }
}

private class SingleSessionFakeRepository : DrivesRepository {
    var lastRequest: Map<String, Any?>? = null

    override fun observeDrives(uid: String) = throw UnsupportedOperationException()

    override suspend fun saveDrive(request: Map<String, Any?>) {
        lastRequest = request
    }

    override suspend fun deleteDrive(rideId: String) = throw UnsupportedOperationException()
}
