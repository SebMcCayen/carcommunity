package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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
 *   the UI immediately [autoSave]s it. To make stopping feel INSTANT (#798), the
 *   Keep/Delete summary opens straight away over the client-side estimate while
 *   the `drives-save` callable runs in the BACKGROUND on the process-scoped
 *   [uploadScope] with bounded retry on transient faults (#800); the drive is
 *   still never lost by a missed Save. [keep] resolves instantly (the save carries
 *   on fire-and-forget); [delete] waits the background save out before removing the
 *   ride.
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
    /**
     * Total attempts the BACKGROUND live-session save makes before giving up on a
     * transient fault (1 initial + retries). Mirrors [RouteUploadRunner]; injected
     * so tests can pin the retry count. Production uses [DEFAULT_MAX_SAVE_ATTEMPTS].
     */
    private val maxSaveAttempts: Int = DEFAULT_MAX_SAVE_ATTEMPTS,
    /** Backoff before background-save retry N (0-based). Coarse — this is background. */
    private val saveBackoffMillis: (attempt: Int) -> Long = ::defaultSaveBackoffMillis,
    /** Injected so tests drive the backoff deterministically (no real delays). */
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    /**
     * Storage path of the car being driven (the live session's denormalized cover
     * photo), recorded on the saved drive so History can show a round car photo.
     * Null on a manual recording or when the sharer has no car.
     */
    private val carImagePath: String? = null,
) {
    private val stateFlow = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = stateFlow.asStateFlow()

    /**
     * The active recording. Mutated on the MAIN thread while recording (start /
     * addFix / stop) and read there for the summary preview; the background save
     * coroutine additionally READS it (to build the payload + snapshot the route)
     * and, on an early-Keep finalization, WRITES it to null. `@Volatile` so those
     * cross-thread accesses see a consistent reference. The [DriveRecorder] itself
     * is only STRUCTURALLY changed (addPoint) while recording — stopped before the
     * background save runs — so the background reads never race a mutation.
     */
    @Volatile
    private var recorder: DriveRecorder? = null

    /**
     * Handle to the background route upload kicked off after a successful save,
     * so the live-session [delete] can JOIN it before removing the drive.
     * `drives-delete` deletes the whole `rideRoutes/{uid}/{rideId}/` prefix and
     * then the doc; a slow upload that completed AFTER that delete would recreate
     * `route.bin` orphaned. Joining first makes delete wait the upload out so the
     * blobs it removes are the final ones. Null when nothing is (or was)
     * uploading (config-less build, summary-only save, or before the first save).
     *
     * `@Volatile`: with the background live save, [startRouteUpload] now runs from
     * the save coroutine (on [uploadScope], typically Dispatchers.IO) while
     * [delete] reads and joins this from the main thread — so the write must be
     * visible cross-thread, exactly like [saveJob] / [savedResult], or delete could
     * observe a stale null and skip joining an in-flight upload (the race this join
     * exists to prevent).
     */
    @Volatile
    private var uploadJob: Job? = null

    /**
     * The BACKGROUND live-session save started by [autoSave], so [delete] can JOIN
     * it before removing the drive (it must know the created rideId, and must not
     * race a save that would recreate a just-deleted drive). Runs on the
     * process-scoped [uploadScope] so dismissing the summary — or an Activity
     * recreation — cannot cancel it and lose the drive. `@Volatile`: written from
     * the background save coroutine, read from the main thread ([delete]).
     */
    @Volatile
    private var saveJob: Job? = null

    /**
     * The result of the successful background save (rideId + route path), or null
     * until it lands / if it ultimately failed. [delete] reads the rideId off this
     * after joining [saveJob]; the background completion reads the route path to
     * kick the route upload. `@Volatile` for the same cross-thread reason as
     * [saveJob].
     */
    @Volatile
    private var savedResult: DriveSaveResult? = null

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
        recorder = DriveRecorder(sourceSessionId, started, carImagePath = carImagePath)
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
     * Live-session end: show the Keep/Delete summary IMMEDIATELY (over the
     * client-side estimate) and persist the drive in the BACKGROUND, so stopping
     * feels instant and a drive can never be lost by a missed Save (#798). Unlike
     * the manual [save] (which blocks in [RecordingState.Saving] until the callable
     * returns), this transitions straight to [RecordingState.SavedPendingChoice]
     * with `savePending = true` and runs `drives-save` fire-and-forget on the
     * process-scoped [uploadScope] with bounded retry on TRANSIENT faults (#800).
     *
     * Because the save runs in the background:
     * - it survives dismissing the summary AND an Activity recreation (the scope
     *   outlives the composition), so it is never cancelled mid-flight and the
     *   drive is not lost — the reason the old composition-scoped save needed a
     *   cancellation→restore dance that this no longer has;
     * - the HEAVY work — building the callable payload and snapshotting the route,
     *   each mapping/copying up to ~20k points — runs INSIDE the background
     *   coroutine, not on the caller (UI) thread, so the summary is emitted first
     *   and never blocked behind that build (the whole point of the "immediate"
     *   summary in #798). Only the cheap values (title / endedAt / elapsed / point
     *   count) are captured up front; the [recorder] reference is captured so the
     *   build is unaffected if [this.recorder] is later cleared, and the recorder
     *   is only mutated on the main thread while RECORDING (stopped by now), so the
     *   background reads are safe.
     *
     * Valid from [RecordingState.PromptSave] (the state [stop] raises) or a prior
     * [RecordingState.Failed] (a manual retry after the background save gave up) —
     * the same two states the manual [save] accepts. Idempotent from
     * [RecordingState.SavedPendingChoice] (a re-fire after a recreation is a no-op
     * — the surviving background job is still running / done).
     */
    fun autoSave(title: String?) {
        val recorder = recorder ?: return
        val resumable =
            stateFlow.value is RecordingState.PromptSave ||
                stateFlow.value is RecordingState.Failed
        if (!resumable) return

        // Both resumable states are only reachable via stop(), so the captured
        // stop moment is always present; fall back defensively. These are all cheap
        // reads; the up-to-20k-point payload build + snapshot are deferred below.
        val endedAt = stoppedAtMillis ?: clock()
        val elapsedMillis = recorder.elapsedMillis(endedAt)
        val pointCount = recorder.pointCount

        // The summary opens at once over the local estimate; building the payload,
        // snapshotting the route, and the save are all the background job's work.
        stateFlow.value =
            RecordingState.SavedPendingChoice(elapsedMillis = elapsedMillis, savePending = true)
        savedResult = null
        saveJob =
            uploadScope.launch {
                try {
                    // Build the payload HERE (off the UI thread). The payload is
                    // idempotent per sourceSessionId, so a retry re-sends the exact
                    // same request (and stored duration).
                    val request = recorder.buildSaveRequest(title, endedAt)
                    val result = saveWithRetry(request)
                    savedResult = result
                    // Snapshot the route (a copy of up to ~20k points) ONLY once we
                    // know there is somewhere to upload it — an uploader exists AND
                    // the save returned a route path. This skips the copy on a
                    // summary-only save (no path) and a config-less build (no
                    // uploader), rather than taking it up front and discarding it.
                    val pointsForUpload =
                        if (routeUploadRunner != null && result.routePath != null) {
                            recorder.snapshot()
                        } else {
                            emptyList()
                        }
                    onBackgroundSaveSucceeded(result, pointsForUpload)
                } catch (cancellation: CancellationException) {
                    // Scope teardown (sign-out / account switch cancels uploadScope):
                    // the holder that owns the scope also drops the recording, so
                    // leave the state to it and preserve cooperative cancellation.
                    throw cancellation
                } catch (failure: Exception) {
                    onBackgroundSaveFailed(failure, elapsedMillis, pointCount)
                }
            }
    }

    /**
     * Runs `drives-save`, retrying up to [maxSaveAttempts] with backoff on
     * TRANSIENT faults only ([RecordingState.isTransientSaveCode]). The save is
     * idempotent per `sourceSessionId`, so re-issuing the same request is safe. A
     * permanent refusal (member gate), a malformed payload, or an unclassified
     * error is rethrown at once — retrying it would only loop.
     */
    private suspend fun saveWithRetry(request: Map<String, Any?>): DriveSaveResult {
        var attempt = 0
        while (true) {
            try {
                return repository.saveDrive(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                attempt++
                val code = (failure as? DriveSaveException)?.code
                if (attempt >= maxSaveAttempts || !RecordingState.isTransientSaveCode(code)) {
                    throw failure
                }
                delayFn(saveBackoffMillis(attempt - 1))
            }
        }
    }

    /**
     * The background save landed. Drop the inline "saving…" indicator if the user
     * is still deciding, then upload the route — but ONLY while the drive is meant
     * to live (still choosing, or Kept). A [delete] in flight has already moved off
     * [RecordingState.SavedPendingChoice] and joins this job to remove the ride, so
     * starting an upload here would just race that delete.
     */
    private fun onBackgroundSaveSucceeded(result: DriveSaveResult, pointsForUpload: List<RecordedPoint>) {
        // Settle the summary in place: clear the "saving…" indicator if the user is
        // still deciding, or FINALIZE an early Keep now that the save is confirmed
        // (KeptPendingSave → the terminal Kept — the only place an early Keep is
        // allowed to become terminal, so a save that had failed could never have
        // finalized it). updateAndGet so the resulting state is read atomically.
        val next =
            stateFlow.updateAndGet { current ->
                when (current) {
                    is RecordingState.SavedPendingChoice -> current.copy(savePending = false)
                    is RecordingState.KeptPendingSave -> RecordingState.Kept
                    else -> current
                }
            }
        // An early Keep just finalized to terminal Kept → release the recorder,
        // mirroring the normal keep() cleanup (only ever reached AFTER the save
        // definitively succeeded, so this can never lose a drive). The upload uses
        // its own pre-taken [pointsForUpload], so releasing the recorder here is safe.
        if (next is RecordingState.Kept) recorder = null
        // Upload the route while the drive is meant to live (still choosing, or
        // kept). A delete in flight has moved off these states and joins this job to
        // remove the ride, so an upload started here would just race that delete.
        when (next) {
            is RecordingState.SavedPendingChoice, RecordingState.Kept ->
                uploadJob = startRouteUpload(result, pointsForUpload)
            else -> Unit
        }
    }

    /**
     * The background save gave up (transient retries exhausted, or a permanent /
     * unclassified fault). Surface it as [RecordingState.Failed] — reusing the
     * summary's retry/close prompt — but ONLY while the user is still deciding.
     * Once they have committed the choice stands: Keep trusts the (now failed)
     * save, and a Delete has nothing to remove. Mirrors the manual [save]'s Failed
     * mapping so the code is carried for the retry gate and the auto error report.
     */
    private fun onBackgroundSaveFailed(failure: Exception, elapsedMillis: Long, pointCount: Int) {
        stateFlow.update { current ->
            // Surface the failure while the user is still deciding (SavedPendingChoice)
            // OR after an EARLY Keep (KeptPendingSave) — the latter is the critical
            // never-lose-a-drive path: a drive kept before the save landed must NOT be
            // silently dropped when the save then gives up; it re-raises the retry
            // prompt instead. Once a delete has committed (Deleting/Deleted) the choice
            // stands.
            if (current is RecordingState.SavedPendingChoice || current is RecordingState.KeptPendingSave) {
                RecordingState.Failed(
                    pointCount = pointCount,
                    elapsedMillis = elapsedMillis,
                    code = (failure as? DriveSaveException)?.code,
                )
            } else {
                current
            }
        }
    }

    /**
     * KEEP the drive. When the background save has ALREADY landed
     * ([RecordingState.SavedPendingChoice.savePending] false) this resolves to the
     * terminal [RecordingState.Kept] instantly and releases the recorder — the
     * common, fast case.
     *
     * When the save is STILL in flight, keeping must NOT finalize yet: going
     * terminal (which lets the host release everything) while a save could still
     * fail would silently lose the drive — the never-lose-a-drive failure #798
     * guards against. So an early Keep parks in [RecordingState.KeptPendingSave],
     * and the background save resolves it — success → [RecordingState.Kept]
     * ([onBackgroundSaveSucceeded]); a definitive failure → [RecordingState.Failed]
     * ([onBackgroundSaveFailed]), re-raising the retry prompt so the drive can still
     * be saved. The recorder is kept until then so a retry can rebuild the payload.
     *
     * Done via [updateAndGet] so the whole decision is atomic against the
     * background save's concurrent state transition (both use the same StateFlow),
     * and the recorder is released only when the FINAL state is the terminal Kept.
     * Valid only from [RecordingState.SavedPendingChoice].
     */
    fun keep() {
        val next =
            stateFlow.updateAndGet { current ->
                when {
                    current is RecordingState.SavedPendingChoice && current.savePending ->
                        RecordingState.KeptPendingSave(elapsedMillis = current.elapsedMillis)
                    current is RecordingState.SavedPendingChoice ->
                        RecordingState.Kept
                    else -> current
                }
            }
        // Release the recorder ONLY on the terminal Kept (the save had already
        // landed). An early Keep parked in KeptPendingSave keeps the recorder so a
        // post-failure retry can rebuild the payload.
        if (next is RecordingState.Kept) recorder = null
    }

    /**
     * DELETE the drive again via the `drives-delete` callable (the
     * SavedPendingChoice's Delete). WAITS for the pending work to settle first, in
     * order, so the delete can never race the writers that would resurrect it:
     *  1. the background SAVE ([saveJob].join()) — Delete may be tapped while the
     *     save is still in flight, and it must know the created rideId AND make sure
     *     no save lands AFTER the doc is deleted;
     *  2. the route UPLOAD ([uploadJob].join()) — so a slow upload cannot recreate
     *     `route.bin` after `drives-delete` cleared the whole
     *     `rideRoutes/{uid}/{rideId}/` prefix and then the doc.
     *
     * If the background save ultimately FAILED (no [savedResult]), nothing was ever
     * persisted, so there is nothing to remove and this resolves straight to
     * [RecordingState.Deleted]. On a delete-callable failure → back to
     * [RecordingState.SavedPendingChoice] with `deleteFailed` set — the drive stays
     * safely saved and the choice still stands. Valid only from
     * [RecordingState.SavedPendingChoice]; cancellation restores it too.
     */
    suspend fun delete() {
        val current = stateFlow.value as? RecordingState.SavedPendingChoice ?: return
        stateFlow.value = RecordingState.Deleting
        try {
            // Wait the background save out first so we know the rideId and no save
            // can create the drive after we delete it; then let the route upload
            // finish so it cannot recreate route.bin after the prefix is cleared.
            saveJob?.join()
            uploadJob?.join()
            // A failed background save persisted nothing — nothing to delete.
            savedResult?.let { repository.deleteDrive(it.rideId) }
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
        savedResult = null
        stateFlow.value = RecordingState.Discarded
    }

    /** Resets to [RecordingState.Idle] so a fresh recording can begin. */
    fun reset() {
        recorder = null
        stoppedAtMillis = null
        savedResult = null
        stateFlow.value = RecordingState.Idle
    }

    companion object {
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

        /**
         * Total background-save attempts before giving up on a transient fault
         * (1 initial + 2 retries). Same shape as [RouteUploadRunner]; the save is
         * idempotent per `sourceSessionId`, so the retries are safe.
         */
        const val DEFAULT_MAX_SAVE_ATTEMPTS = 3

        /** Backoff before background-save retry N (0-based). Coarse — background. */
        private val SAVE_BACKOFF_MILLIS = longArrayOf(1_000L, 4_000L)

        fun defaultSaveBackoffMillis(attempt: Int): Long =
            SAVE_BACKOFF_MILLIS.getOrElse(attempt) { SAVE_BACKOFF_MILLIS.last() }
    }
}
