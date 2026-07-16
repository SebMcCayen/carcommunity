package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates a single drive recording lifecycle (Phase 12 slice 12, write
 * side): start → record → explicit save/discard prompt → save via the
 * `drives-save` callable. Pure Kotlin so it is unit-testable with a fake
 * repository; the screen owns location plumbing and feeds fixes in.
 *
 * The explicit-save product rule lives here: [stop] only opens the prompt; a
 * drive is persisted only when [save] is called, and [discard] stores nothing.
 */
class DriveRecordingCoordinator(
    private val repository: DrivesRepository,
    private val sourceSessionId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val stateFlow = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = stateFlow.asStateFlow()

    private var recorder: DriveRecorder? = null

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

    /** Begins a recording. No-op if one is already active or resolved. */
    fun start() {
        if (recorder != null) return
        val started = clock()
        recorder = DriveRecorder(sourceSessionId, started)
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
     * failure → [RecordingState.Failed] (retryable).
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
            repository.saveDrive(recorder.buildSaveRequest(title, endedAt))
            // Release the recorder (and its up-to-20k points) now that the save
            // succeeded; the UI renders the terminal state from RecordingState.
            this.recorder = null
            stoppedAtMillis = null
            stateFlow.value = RecordingState.Saved
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
                )
        }
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
}
