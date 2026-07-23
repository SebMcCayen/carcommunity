package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates a single drive recording lifecycle (Phase 12 slice 12, write
 * side): start → record → stop → persist via the `drives-save` callable. Pure
 * Kotlin so it is unit-testable with a fake repository; the screen owns location
 * plumbing and feeds fixes in.
 *
 * Two end flows share this one coordinator:
 * - the MANUAL recorder ([RecordDriveScreen]): [stop] opens an EXPLICIT prompt
 *   and a drive is persisted only when [save] is called ([RecordingState.Saved]);
 *   [discard] stores nothing. This is the product's explicit-save rule.
 * - the LIVE session ([SingleSessionRecording]): [stop] opens the same prompt but
 *   the UI immediately [autoSave]s it, so the drive is persisted the instant the
 *   session ends and can never be lost by a missed Save; the summary then asks
 *   KEEP ([keep]) or DELETE ([delete]) over the already-saved ride.
 */
class DriveRecordingCoordinator(
    private val repository: DrivesRepository,
    private val sourceSessionId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Uploads the recorded route to `route.bin` after the save. Null in a
     * config-less / CI build (no Cloud Storage) — the drive still saves, it just
     * has no route file, exactly as it did before any uploader existed.
     */
    private val routeUploadRunner: RouteUploadRunner? = null,
    /**
     * Scope the background route upload runs on. In production
     * [SingleSessionRecording] injects a PER-SESSION process-scoped scope that it
     * owns and cancels on sign-out / account switch, so an upload started under
     * one user's auth can never continue under another's, while still outliving
     * the composition (dismissing the save prompt or navigating away the instant
     * "Saved" appears cannot cancel an in-flight upload and silently lose the
     * route). Defaults to a shared process-scoped [processUploadScope] for tests
     * / direct construction; injectable so tests can drive it deterministically.
     */
    private val uploadScope: CoroutineScope = processUploadScope,
) {
    private val stateFlow = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = stateFlow.asStateFlow()

    private var recorder: DriveRecorder? = null

    /**
     * Handle to the background route upload kicked off after a successful save,
     * so the live-session [delete] can JOIN it before removing the drive.
     * `drives-delete` deletes the whole `rideRoutes/{uid}/{rideId}/` prefix and
     * then the doc; a slow upload that completed AFTER that delete would recreate
     * `route.bin` orphaned. Joining first makes delete wait the upload out so the
     * blobs it removes are the final ones. Null when nothing is (or was)
     * uploading (config-less build, summary-only save, or before the first save).
     */
    private var uploadJob: Job? = null

    /**
     * The wall-clock moment recording actually STOPPED, captured once in [stop].
     *
     * This — not the moment the user finally taps Save — is what
     * [DriveRecorder.buildSaveRequest] documents as `endedAtMillis`, and it is
     * also the basis for the elapsed time reported in every post-stop state. It
     * matters most for summary-only saves (no route points), where the backend
     * derives the stored duration straight from `endedAt`: re-reading the clock
     * at save time would inflate the duration by however long the user sat on
     * the prompt, and drift further on every retry. Capturing it once keeps the
     * summary the user sees identical to what History stores, and makes retries
     * idempotent in the stored duration as well as the sourceSessionId.
     */
    private var stoppedAtMillis: Long? = null

    /**
     * The wall-clock moment recording STARTED, captured once in [start] and held
     * until [start] runs again. This is the basis the map's live-session bar ticks
     * its elapsed time from (now − start), which — unlike [RecordingState.Recording.elapsedMillis],
     * frozen at the last accepted fix — keeps advancing once per second even while
     * the car (and the GPS stream) is stationary. Null before the first start.
     */
    var startedAtMillis: Long? = null
        private set

    /** Begins a recording. No-op if one is already active or resolved. */
    fun start() {
        if (recorder != null) return
        val started = clock()
        recorder = DriveRecorder(sourceSessionId, started)
        startedAtMillis = started
        stoppedAtMillis = null
        stateFlow.value = RecordingState.Recording(pointCount = 0, elapsedMillis = 0L)
    }

    /**
     * Adds a fix while recording. Ignored unless actively recording. Updates
     * the live counters so the screen reflects point count + elapsed time.
     */
    fun addFix(latitude: Double, longitude: Double, timestampMs: Long) {
        val recorder = recorder ?: return
        if (stateFlow.value !is RecordingState.Recording) return
        recorder.addPoint(RecordedPoint(latitude, longitude, timestampMs))
        stateFlow.value =
            RecordingState.Recording(
                pointCount = recorder.pointCount,
                elapsedMillis = recorder.elapsedMillis(clock()),
                distanceMeters = recorder.distanceMetres,
            )
    }

    /** Stops recording and opens the explicit save/discard prompt. */
    fun stop() {
        val recorder = recorder ?: return
        if (stateFlow.value !is RecordingState.Recording) return
        // Freeze the stop moment: every later state and the save payload's
        // endedAt derive from THIS instant, never from a re-read of the clock.
        val stoppedAt = clock()
        stoppedAtMillis = stoppedAt
        stateFlow.value =
            RecordingState.PromptSave(
                pointCount = recorder.pointCount,
                elapsedMillis = recorder.elapsedMillis(stoppedAt),
            )
    }

    /**
     * Explicitly saves the recorded drive via `drives-save`. Only valid from
     * the prompt or a prior failure. On success → [RecordingState.Saved]; on
     * failure → [RecordingState.Failed], carrying the callable status code so
     * the caller can tell a retryable fault from a permanent refusal
     * ([RecordingState.Failed.isPermanentRefusal] — the member gate). A retry is
     * still ACCEPTED from either, so the decision of whether to offer one is the
     * UI's.
     *
     * The payload's `endedAt` is the captured [stoppedAtMillis], so a retry
     * sends the exact same end time as the first attempt (and the stored
     * duration matches the summary the user was shown).
     */
    suspend fun save(title: String?) {
        val recorder = recorder ?: return
        val resumable =
            stateFlow.value is RecordingState.PromptSave ||
                stateFlow.value is RecordingState.Failed
        if (!resumable) return

        // Both resumable states are only reachable via stop(), so the captured
        // stop moment is always present; fall back defensively.
        val endedAt = stoppedAtMillis ?: clock()
        stateFlow.value = RecordingState.Saving
        try {
            val result = repository.saveDrive(recorder.buildSaveRequest(title, endedAt))
            // Snapshot the fixes for the background upload only now that the save
            // succeeded, and only when an upload can actually run: snapshot()
            // copies up to ~20k points, so this skips that copy on a failed save,
            // on a summary-only / no-route save, and in a config-less build (no
            // uploader). Taken BEFORE the recorder is released just below, so it
            // is the exact set of fixes the backend just priced its stats from
            // (replay + top-speed then match the summary).
            val points =
                if (routeUploadRunner != null && result.routePath != null) {
                    recorder.snapshot()
                } else {
                    emptyList()
                }
            // Release the recorder (and its up-to-20k points) now that the save
            // succeeded; the UI renders the terminal state from RecordingState.
            this.recorder = null
            stoppedAtMillis = null
            stateFlow.value = RecordingState.Saved
            uploadJob = startRouteUpload(result, points)
        } catch (cancellation: CancellationException) {
            // Cancellation (navigation away / scope cancellation) is not a save
            // failure; restore the prompt so a retry is possible if the scope
            // survives, then rethrow to preserve cooperative cancellation.
            stateFlow.value =
                RecordingState.PromptSave(
                    pointCount = recorder.pointCount,
                    elapsedMillis = recorder.elapsedMillis(endedAt),
                )
            throw cancellation
        } catch (failure: Exception) {
            stateFlow.value =
                RecordingState.Failed(
                    pointCount = recorder.pointCount,
                    elapsedMillis = recorder.elapsedMillis(endedAt),
                    // Carry the callable status so the prompt can distinguish a
                    // permanent refusal (the member gate) from a retryable fault,
                    // and so the auto error report files a stable code.
                    code = (failure as? DriveSaveException)?.code,
                )
        }
    }

    /**
     * Live-session end: AUTO-SAVE the just-finished recording, so a drive can
     * never be lost by the user missing an explicit Save. Unlike the manual
     * [save] (which lands in the terminal [RecordingState.Saved]), this lands in
     * [RecordingState.SavedPendingChoice] carrying the created rideId, so the
     * end-of-session summary can offer KEEP or DELETE over an already-persisted
     * drive rather than the forced Save/Discard.
     *
     * Valid from [RecordingState.PromptSave] (the state [stop] raises) or a prior
     * [RecordingState.Failed] (a retry) — the same two states the manual [save]
     * accepts — so the caller can auto-trigger it the moment the prompt opens and
     * retry it after a transient failure. The recorder is deliberately NOT
     * released here (the summary still shows the client-side distance/speed
     * estimate, and a retry needs the points); [keep] / [delete] / [discard]
     * release it. On failure → [RecordingState.Failed]; cancellation restores
     * [RecordingState.PromptSave] so the auto-trigger re-fires after a recreation.
     */
    suspend fun autoSave(title: String?) {
        val recorder = recorder ?: return
        val resumable =
            stateFlow.value is RecordingState.PromptSave ||
                stateFlow.value is RecordingState.Failed
        if (!resumable) return

        val endedAt = stoppedAtMillis ?: clock()
        stateFlow.value = RecordingState.Saving
        try {
            val result = repository.saveDrive(recorder.buildSaveRequest(title, endedAt))
            // Snapshot only when an upload can actually run (skips the ~20k-point
            // copy on a summary-only / config-less save). Taken before any release.
            val points =
                if (routeUploadRunner != null && result.routePath != null) {
                    recorder.snapshot()
                } else {
                    emptyList()
                }
            uploadJob = startRouteUpload(result, points)
            stateFlow.value =
                RecordingState.SavedPendingChoice(
                    rideId = result.rideId,
                    elapsedMillis = recorder.elapsedMillis(endedAt),
                )
        } catch (cancellation: CancellationException) {
            // A recreation cancels the composition-scoped auto-save; this is not a
            // failure. Restore the prompt (recorder intact) so the auto-trigger
            // re-fires and the drive is still saved, then rethrow.
            stateFlow.value =
                RecordingState.PromptSave(
                    pointCount = recorder.pointCount,
                    elapsedMillis = recorder.elapsedMillis(endedAt),
                )
            throw cancellation
        } catch (failure: Exception) {
            stateFlow.value =
                RecordingState.Failed(
                    pointCount = recorder.pointCount,
                    elapsedMillis = recorder.elapsedMillis(endedAt),
                    code = (failure as? DriveSaveException)?.code,
                )
        }
    }

    /**
     * KEEP the auto-saved drive: it is already persisted, so this simply resolves
     * the prompt to the terminal [RecordingState.Kept] and releases the recorder.
     * Valid only from [RecordingState.SavedPendingChoice].
     */
    fun keep() {
        if (stateFlow.value !is RecordingState.SavedPendingChoice) return
        recorder = null
        stateFlow.value = RecordingState.Kept
    }

    /**
     * DELETE the just-auto-saved drive again via the `drives-delete` callable
     * (the SavedPendingChoice's Delete). WAITS for the background route upload to
     * settle first ([uploadJob].join()) so the delete removes the just-uploaded
     * `route.bin`/preview rather than racing a slow upload that would recreate
     * them after the doc is gone (`drives-delete` clears the whole
     * `rideRoutes/{uid}/{rideId}/` prefix then the doc). On success → the terminal
     * [RecordingState.Deleted]; on failure → back to
     * [RecordingState.SavedPendingChoice] with `deleteFailed` set — the drive
     * stays safely saved and the choice still stands. Valid only from
     * [RecordingState.SavedPendingChoice]; cancellation restores it too.
     */
    suspend fun delete() {
        val current = stateFlow.value as? RecordingState.SavedPendingChoice ?: return
        stateFlow.value = RecordingState.Deleting
        try {
            // Let the route upload finish before removing the blobs, so it cannot
            // recreate route.bin after drives-delete cleared the prefix.
            uploadJob?.join()
            repository.deleteDrive(current.rideId)
            recorder = null
            uploadJob = null
            stateFlow.value = RecordingState.Deleted
        } catch (cancellation: CancellationException) {
            // Cancellation (navigation away / scope teardown) is NOT a delete
            // failure. Restore the prior choice unchanged — no error line, the
            // drive is still saved — and rethrow to preserve cooperative
            // cancellation. (Caught before the generic Exception below, which
            // would otherwise swallow it and wrongly show a delete error.)
            stateFlow.value = current
            throw cancellation
        } catch (failure: Exception) {
            stateFlow.value = current.copy(deleteFailed = true)
        }
    }

    /**
     * Kicks off the route-file upload AFTER a successful save, on the
     * process-scoped [uploadScope] so it survives the prompt leaving the screen,
     * returning its [Job] so the live-session [delete] can join it. Runs as a
     * fire-and-forget SECOND step, deliberately decoupled from the save's success:
     * the drive doc already exists and the UI already shows the terminal / choice
     * state, so the upload must never block or reverse that. The runner retries
     * transient failures internally; if it still fails the drive keeps its (empty)
     * route reference and the reader degrades to "route unavailable" — a tolerated
     * state, not a broken save. Returns null (no job) when there is no uploader
     * (config-less build), no route path (defensive), or no points (a summary-only
     * save has nothing to upload).
     */
    private fun startRouteUpload(result: DriveSaveResult, points: List<RecordedPoint>): Job? {
        val runner = routeUploadRunner ?: return null
        val routePath = result.routePath ?: return null
        if (points.isEmpty()) return null
        return uploadScope.launch { runner.upload(routePath, points) }
    }

    /**
     * Snapshot of the accumulated fixes, used only to compute the client-side
     * [DriveSummary] preview shown in the end-of-session save prompt. Empty once
     * the recorder has been released (after a successful save / discard / reset).
     * Never used for persistence — the save payload is built by the recorder.
     */
    fun recordedPoints(): List<RecordedPoint> = recorder?.snapshot() ?: emptyList()

    /** Explicitly discards the recording — nothing is stored. */
    fun discard() {
        recorder = null
        stoppedAtMillis = null
        stateFlow.value = RecordingState.Discarded
    }

    /** Resets to [RecordingState.Idle] so a fresh recording can begin. */
    fun reset() {
        recorder = null
        stoppedAtMillis = null
        stateFlow.value = RecordingState.Idle
    }

    private companion object {
        /**
         * Fallback scope for background route uploads when none is injected
         * (tests / direct construction): process-scoped and supervisor-jobbed so
         * one upload's failure can't cancel another, and so the upload outlives
         * the composition that triggered the save. The save prompt is dismissed
         * the moment "Saved" appears (SingleSessionRecording clears the
         * recording), which would cancel a composition-scoped upload mid-flight
         * and lose the route with no retry — the exact half-state this avoids.
         * IO dispatcher: the actual work is a network putBytes.
         *
         * Production does NOT use this default: [SingleSessionRecording] injects
         * a per-session scope it can cancel on sign-out / account switch, so this
         * shared process-wide scope is never the one carrying a real user's
         * upload across an auth change.
         */
        private val processUploadScope: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
