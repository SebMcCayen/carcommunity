package com.kungsbackacarcommunity.app.drives

import com.kungsbackacarcommunity.app.media.MediaUploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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

    // The holder is a process singleton: reset around every test. Use the
    // sign-out path (uid null) rather than clear() so a leftover background
    // upload scope from a prior test is cancelled too, not just the recording.
    @Before fun setUp() = SingleSessionRecording.clearIfNotOwnedBy(null)

    @After fun tearDown() = SingleSessionRecording.clearIfNotOwnedBy(null)

    /** Stands in for the effect the UI runs whenever `isSharing` is true. */
    private fun onSharing(uid: String = UID) = SingleSessionRecording.start(uid, repository) { null }

    private companion object {
        const val UID = "uid-owner"

        /**
         * Bound (ms) for a negative cross-thread assertion: how long a wrong
         * cancel dispatched to Dispatchers.IO gets to surface before we assert it
         * did not happen. Small — the passing path never cancels, so it is a
         * fixed cost, not a source of flakiness.
         */
        const val SETTLE_MS = 50L
    }

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

    // -------------------------------------------------------------------
    // Teardown vs config change. The holder is process-scoped on purpose, so a
    // GENUINE teardown (sign-out / account switch) must stop it, while a
    // config-change recreation (rotation) must not. clearIfNotOwnedBy keys that
    // on the signed-in uid, which MainActivity feeds from auth state.
    // -------------------------------------------------------------------

    @Test
    fun `sign-out clears an in-flight recording`() {
        onSharing()
        assertNotNull(SingleSessionRecording.active.value)

        // Signed-in uid becomes null: the authed screen is being torn down.
        SingleSessionRecording.clearIfNotOwnedBy(null)

        // The recording is released — no orphaned recording, and clear() stops
        // the fused-location source with it.
        assertNull(SingleSessionRecording.active.value)
        assertFalse(SingleSessionRecording.promptPending.value)
    }

    @Test
    fun `sign-out drops an unsaved drive waiting on the prompt, deliberately`() {
        onSharing()
        SingleSessionRecording.active.value?.addFix(57.0, 12.0, 1_000L)
        SingleSessionRecording.stop()
        assertTrue(SingleSessionRecording.promptPending.value)

        // The user signs out with the save/discard prompt still open. The drive
        // belongs to the departing uid, so it must NOT survive to be resolved by
        // whoever signs in next.
        SingleSessionRecording.clearIfNotOwnedBy(null)

        assertNull(SingleSessionRecording.active.value)
        assertFalse(SingleSessionRecording.promptPending.value)
        assertNull("nothing may be filed on sign-out", repository.lastRequest)
    }

    @Test
    fun `switching to a different account clears the previous user's recording`() {
        onSharing()
        SingleSessionRecording.active.value?.addFix(57.0, 12.0, 1_000L)

        // A different uid signs in: the recording is not theirs to resolve.
        SingleSessionRecording.clearIfNotOwnedBy("uid-someone-else")

        assertNull(SingleSessionRecording.active.value)
        assertFalse(SingleSessionRecording.promptPending.value)
    }

    @Test
    fun `a rotation with the same uid does not clear an in-flight recording`() {
        onSharing()
        val coordinator = SingleSessionRecording.active.value
        coordinator?.addFix(57.0, 12.0, 1_000L)

        // Activity recreated: the effect re-runs with the SAME signed-in uid.
        SingleSessionRecording.clearIfNotOwnedBy(UID)

        assertSame(coordinator, SingleSessionRecording.active.value)
        assertEquals(1, SingleSessionRecording.active.value?.recordedPoints()?.size)
        assertTrue(SingleSessionRecording.active.value?.state?.value is RecordingState.Recording)
    }

    @Test
    fun `a rotation with the same uid keeps a pending save prompt`() {
        onSharing()
        val coordinator = SingleSessionRecording.active.value
        coordinator?.addFix(57.0, 12.0, 1_000L)
        SingleSessionRecording.stop()

        // The exact regression this guards: rotation must never drop the prompt.
        SingleSessionRecording.clearIfNotOwnedBy(UID)

        assertTrue(SingleSessionRecording.promptPending.value)
        assertSame(coordinator, SingleSessionRecording.active.value)
        assertTrue(SingleSessionRecording.active.value?.state?.value is RecordingState.PromptSave)
    }

    @Test
    fun `clearIfNotOwnedBy is a no-op when nothing is recording`() {
        SingleSessionRecording.clearIfNotOwnedBy(null)
        SingleSessionRecording.clearIfNotOwnedBy(UID)
        assertNull(SingleSessionRecording.active.value)
        assertFalse(SingleSessionRecording.promptPending.value)
    }

    // -------------------------------------------------------------------
    // Background route upload lifetime. The upload is process-scoped so it
    // survives the composition (and the post-save clear() the "Saved" state
    // triggers), but it must be TORN DOWN on a real auth change: an upload
    // started under user A's auth must never continue — or retry — under user
    // B's. These drive a real, gated upload through the holder's own scope.
    // -------------------------------------------------------------------

    /** Drives a genuinely in-flight upload: it signals start, then suspends. */
    private class GatedUploader : MediaUploader {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun upload(path: String, bytes: ByteArray, contentType: String): String {
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (c: CancellationException) {
                cancelled.complete(Unit)
                throw c
            }
        }
    }

    /** Repository whose save returns a real routePath, so the upload launches. */
    private class UploadingFakeRepository : DrivesRepository {
        override fun observeDrives(uid: String) = throw UnsupportedOperationException()

        override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult =
            DriveSaveResult(
                rideId = "ride",
                routePath = "rideRoutes/$UID/ride/route.bin",
                alreadySaved = false,
            )

        override suspend fun deleteDrive(rideId: String) = throw UnsupportedOperationException()
    }

    /** Starts a session and drives it to a saved drive with an in-flight upload. */
    private suspend fun startWithInFlightUpload(uploader: GatedUploader) {
        val runner = RouteUploadRunner(uploader, delayFn = {})
        SingleSessionRecording.start(UID, UploadingFakeRepository(), routeUploadRunner = runner) {
            null
        }
        val coordinator = SingleSessionRecording.active.value
        coordinator?.addFix(57.0, 12.0, 1_000L)
        coordinator?.addFix(57.001, 12.0, 2_000L)
        SingleSessionRecording.stop()
        coordinator?.save(title = null)
        // Wait until the upload has actually entered the uploader on the scope.
        withTimeout(5_000) { uploader.started.await() }
    }

    @Test
    fun `an account switch cancels the in-flight route upload`() = runBlocking {
        val uploader = GatedUploader()
        startWithInFlightUpload(uploader)

        // The post-save clear() the "Saved" state triggers must NOT cancel the
        // same user's upload — that is the exact half-state this writer avoids.
        SingleSessionRecording.clear()
        // A real (short) settle is load-bearing here, not a hack: an erroneous
        // cancel would surface on Dispatchers.IO (a different thread pool), so
        // yield()/delay(0) on this thread can't observe it — we bound how long a
        // wrong cancel has to reveal itself. The passing path is timing-
        // independent (a correct clear() never cancels), so this cannot flake.
        delay(SETTLE_MS)
        assertFalse(
            "post-save clear() must not cancel the same user's upload",
            uploader.cancelled.isCompleted,
        )

        // A different account signs in: the upload started under UID must be
        // cancelled so it cannot continue or retry under the new user's auth.
        SingleSessionRecording.clearIfNotOwnedBy("uid-someone-else")
        withTimeout(5_000) { uploader.cancelled.await() }
        assertTrue(uploader.cancelled.isCompleted)
    }

    @Test
    fun `sign-out cancels the in-flight route upload`() = runBlocking {
        val uploader = GatedUploader()
        startWithInFlightUpload(uploader)

        // Sign-out (uid null) is an auth change too: cancel the upload.
        SingleSessionRecording.clearIfNotOwnedBy(null)
        withTimeout(5_000) { uploader.cancelled.await() }
        assertTrue(uploader.cancelled.isCompleted)
    }

    @Test
    fun `every upload started in one sign-in is cancelled together on sign-out`() = runBlocking {
        // Drive 1: saved, its upload still in flight (retrying) when the user
        // records a second drive in the SAME sign-in.
        val first = GatedUploader()
        startWithInFlightUpload(first)
        SingleSessionRecording.clear() // post-save clear; drive 1's upload lives on

        // Drive 2 in the same sign-in — must share drive 1's scope, not orphan it.
        val second = GatedUploader()
        startWithInFlightUpload(second)

        // A single sign-out must cancel BOTH uploads, not only the latest.
        SingleSessionRecording.clearIfNotOwnedBy(null)
        withTimeout(5_000) { first.cancelled.await() }
        withTimeout(5_000) { second.cancelled.await() }
        assertTrue(first.cancelled.isCompleted)
        assertTrue(second.cancelled.isCompleted)
    }

    @Test
    fun `a same-uid rotation does not cancel the in-flight route upload`() = runBlocking {
        val uploader = GatedUploader()
        startWithInFlightUpload(uploader)

        // Rotation re-runs the effect with the SAME signed-in uid: the upload
        // must keep running (only a genuine auth change tears it down).
        SingleSessionRecording.clearIfNotOwnedBy(UID)
        // Same rationale as above: bound the window for a wrong cross-thread
        // cancel to surface; a correct same-uid path never cancels, so no flake.
        delay(SETTLE_MS)
        assertFalse(uploader.cancelled.isCompleted)

        // Cleanup: a real sign-out cancels the scope so no thread leaks.
        SingleSessionRecording.clearIfNotOwnedBy(null)
        withTimeout(5_000) { uploader.cancelled.await() }
    }

    // -------------------------------------------------------------------
    // Location-permission behaviour. The permission CHECK itself lives in
    // DriveLocationController.createIfPermitted and needs the Android
    // framework (ContextCompat/PackageManager), which this JVM suite has no
    // seam for (the project has no Robolectric). What is pinned here is the
    // contract that check feeds: a null controller — which is exactly what
    // createIfPermitted returns without ACCESS_FINE_LOCATION — still records
    // the session and yields an honest duration-only summary rather than a
    // fabricated distance.
    // -------------------------------------------------------------------

    @Test
    fun `without permission the null controller still records a duration-only summary`() {
        // createIfPermitted returns null when ACCESS_FINE_LOCATION is missing.
        SingleSessionRecording.start(UID, repository) { null }

        // The session still records (so duration is real) and can be resolved.
        val coordinator = SingleSessionRecording.active.value
        assertNotNull(coordinator)
        assertTrue(coordinator?.state?.value is RecordingState.Recording)

        SingleSessionRecording.stop()
        assertTrue(SingleSessionRecording.promptPending.value)

        // No fixes could arrive, so the summary is duration-only: distance and
        // average speed are null (the dialog renders "—") rather than invented.
        assertEquals(0, coordinator?.recordedPoints()?.size)
        val elapsed = (coordinator?.state?.value as RecordingState.PromptSave).elapsedMillis
        val preview = DriveSummary.preview(coordinator.recordedPoints(), elapsed)
        assertNull(preview.distanceMeters)
        assertNull(preview.averageSpeedMetersPerSecond)
        assertEquals(DriveSummary.durationSeconds(elapsed), preview.durationSeconds)
    }

    @Test
    fun `with permission the delivered fixes reach the summary as distance and speed`() {
        // The mirror of the case above: once ACCESS_FINE_LOCATION is granted,
        // createIfPermitted yields a real controller and its fixes must land in
        // the summary. A real DriveLocationController can't be built off-device
        // (it wraps a FusedLocationProviderClient), so this feeds the coordinator
        // the fixes such a controller would deliver — the holder wires start()'s
        // controller callback straight to this same addFix path.
        SingleSessionRecording.start(UID, repository) { null }
        val coordinator = SingleSessionRecording.active.value
        coordinator?.addFix(57.0000, 12.0, 0L)
        coordinator?.addFix(57.0010, 12.0, 10_000L)
        SingleSessionRecording.stop()

        // The fixes reached the recorder behind the prompt.
        assertEquals(2, coordinator?.recordedPoints()?.size)
        assertTrue(coordinator?.state?.value is RecordingState.PromptSave)

        // The holder builds its coordinator on the real wall clock, which does
        // not advance within a unit test (elapsed ≈ 0 ⇒ a null average speed is
        // correct there), so pass a representative elapsed to show the fixes
        // materialise as a real distance AND speed rather than the em dashes of
        // the no-permission case above.
        val preview = DriveSummary.preview(coordinator!!.recordedPoints(), 10_000L)
        assertNotNull("distance must be estimated once fixes exist", preview.distanceMeters)
        assertTrue(preview.distanceMeters!! > 100.0)
        assertNotNull(preview.averageSpeedMetersPerSecond)
    }

    @Test
    fun `the controller factory is re-evaluated on each new session, never cached`() {
        // Permission can be granted between sessions, so the factory must be
        // consulted again at every start rather than reusing an earlier result.
        var factoryCalls = 0
        val factory = { factoryCalls++; null }

        SingleSessionRecording.start(UID, repository, controllerFactory = factory)
        assertEquals(1, factoryCalls)
        // A re-run within the SAME session must not re-consult it.
        SingleSessionRecording.start(UID, repository, controllerFactory = factory)
        assertEquals(1, factoryCalls)

        SingleSessionRecording.stop()
        SingleSessionRecording.clear()

        // A NEW session re-evaluates: a permission granted meanwhile now counts.
        SingleSessionRecording.start(UID, repository, controllerFactory = factory)
        assertEquals(2, factoryCalls)
    }
}

private class SingleSessionFakeRepository : DrivesRepository {
    var lastRequest: Map<String, Any?>? = null

    override fun observeDrives(uid: String) = throw UnsupportedOperationException()

    override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult {
        lastRequest = request
        return DriveSaveResult(rideId = "ride", routePath = null, alreadySaved = false)
    }

    override suspend fun deleteDrive(rideId: String) = throw UnsupportedOperationException()
}
