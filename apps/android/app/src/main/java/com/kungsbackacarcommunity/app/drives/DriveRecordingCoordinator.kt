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

    /** Begins a recording. No-op if one is already active or resolved. */
    fun start() {
        if (recorder != null) return
        val started = clock()
        recorder = DriveRecorder(sourceSessionId, started)
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
        stateFlow.value =
            RecordingState.PromptSave(
                pointCount = recorder.pointCount,
                elapsedMillis = recorder.elapsedMillis(clock()),
            )
    }

    /**
     * Explicitly saves the recorded drive via `drives-save`. Only valid from
     * the prompt or a prior failure. On success → [RecordingState.Saved]; on
     * failure → [RecordingState.Failed] (retryable).
     */
    suspend fun save(title: String?) {
        val recorder = recorder ?: return
        val resumable =
            stateFlow.value is RecordingState.PromptSave ||
                stateFlow.value is RecordingState.Failed
        if (!resumable) return

        val endedAt = clock()
        stateFlow.value = RecordingState.Saving
        try {
            repository.saveDrive(recorder.buildSaveRequest(title, endedAt))
            // Release the recorder (and its up-to-20k points) now that the save
            // succeeded; the UI renders the terminal state from RecordingState.
            this.recorder = null
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

    /** Explicitly discards the recording — nothing is stored. */
    fun discard() {
        recorder = null
        stateFlow.value = RecordingState.Discarded
    }

    /** Resets to [RecordingState.Idle] so a fresh recording can begin. */
    fun reset() {
        recorder = null
        stateFlow.value = RecordingState.Idle
    }
}
