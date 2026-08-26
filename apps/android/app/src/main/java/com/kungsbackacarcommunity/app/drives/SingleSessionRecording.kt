package com.kungsbackacarcommunity.app.drives

import com.kungsbackacarcommunity.app.location.CurrentSpeed
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped owner of the drive recording that runs alongside a Single
 * (solo live-sharing) session.
 *
 * ## Why process-scoped rather than composition- or Activity-scoped
 *
 * The recording's lifetime is the LIVE SESSION's, and a live session already
 * outlives the Activity: it lives in the backend (RTDB `liveLocation/{uid}`) and
 * is fed by [com.kungsbackacarcommunity.app.location.LocationSharingService], a
 * foreground service driven by the
 * [com.kungsbackacarcommunity.app.location.BackgroundLocationController] `object`
 * — the same process-singleton idiom this holder follows, for the same lifetime.
 *
 * Holding the recording in composition (`remember`) instead meant an Activity
 * recreation (rotation — the manifest does not lock orientation) dropped the
 * coordinator AND any pending save/discard prompt, silently discarding an
 * unsaved drive and defeating the forced-choice rule that a finished session
 * must always be explicitly saved or discarded. Keeping it here means:
 * - rotating mid-recording keeps recording (and every accumulated point);
 * - rotating while the prompt is open keeps the prompt and its data (elapsed
 *   time, the captured stop moment, and the points behind the summary), so the
 *   user still has to choose;
 * - leaving and re-entering the app during a session resumes the SAME recording
 *   rather than starting a second one.
 *
 * The points are deliberately NOT put in `rememberSaveable`/`savedInstanceState`:
 * a recording holds up to [DriveRecorder.MAX_ROUTE_POINTS] (20k) points, which
 * is far past the Bundle transaction limit (TransactionTooLargeException).
 *
 * State is exposed as [StateFlow]s so the UI observes it and re-attaches after a
 * recreation. All entry points are idempotent, so the UI can drive them from an
 * effect that re-runs on every recomposition.
 *
 * Not thread-safe by contract: driven from the main thread (a Compose effect),
 * matching [DriveRecordingCoordinator]/[DriveRecorder].
 */
object SingleSessionRecording {
    private val activeState = MutableStateFlow<DriveRecordingCoordinator?>(null)

    /** The current session's coordinator, or null when nothing is recording. */
    val active: StateFlow<DriveRecordingCoordinator?> = activeState.asStateFlow()

    private val promptPendingState = MutableStateFlow(false)

    /**
     * Whether a finished recording's end-of-session summary is showing. The live
     * UI auto-saves the drive from this state and then asks Keep or Delete.
     * Survives Activity recreation so the summary cannot be lost.
     */
    val promptPending: StateFlow<Boolean> = promptPendingState.asStateFlow()

    private val stopReasonState = MutableStateFlow(SavePromptReason.Default)

    /**
     * Why the pending summary was raised, so the dialog can explain a session that
     * ended for a reason the member did not cause — chiefly the convoy ending
     * under them. Captured in [stop] alongside [promptPending] and, like it,
     * survives Activity recreation and is cleared by [clear] for the next session.
     */
    val stopReason: StateFlow<SavePromptReason> = stopReasonState.asStateFlow()

    private var locationController: DriveLocationSource? = null

    /**
     * The uid whose session this recording belongs to; see [clearIfNotOwnedBy].
     * Null exactly when nothing is recording.
     */
    private var ownerUid: String? = null

    /**
     * The scope background route uploads run on, and the uid they are attributed
     * to. ONE scope PER SIGNED-IN uid, reused across every session that uid
     * records (see [start]), so all of a user's uploads share a single
     * cancellation handle. It is passed to each session's coordinator so the
     * upload is PROCESS-scoped (survives Activity recreation and the composition
     * that triggered the save) — the same lifetime the recording itself has, and
     * the reason the upload cannot live in `remember`.
     *
     * It deliberately OUTLIVES a normal [clear]: the fire-and-forget upload is
     * kicked off the instant the drive reaches "Saved", which is the very state
     * that triggers [clear], so cancelling it there would lose the route with no
     * retry — the exact half-state this whole writer avoids. Instead it is
     * cancelled ONLY by [clearIfNotOwnedBy] when the signed-in user stops being
     * [uploadOwnerUid] (sign-out or account switch): every upload started under
     * user A's auth is cancelled TOGETHER and none continues — or retries — under
     * user B's. Kept alongside [ownerUid] rather than folded into it because
     * [clear] nulls [ownerUid] while this must persist until the upload's owner
     * actually leaves.
     */
    private var uploadScope: CoroutineScope? = null
    private var uploadOwnerUid: String? = null

    /**
     * Begins recording for a newly-started session owned by [uid]. A no-op when
     * a recording is already in flight (including one still awaiting its
     * save/discard choice), so an effect that re-runs after a config change
     * resumes rather than restarting and losing the accumulated points.
     *
     * [controllerFactory] supplies the GPS source (null when Play services /
     * the location permission are unavailable — the session then yields a
     * duration-only summary); it is a parameter so this holder stays free of
     * Android types and JVM-unit-testable.
     *
     * [routeUploadRunner] uploads the recorded `route.bin` after the save
     * succeeds (null in a config-less build — the drive saves without a route
     * file). Passed through to the coordinator, which runs it in the background.
     *
     * [sessionId] is the LIVE-SESSION id (liveLocation/{uid}/session.id) this
     * recording belongs to, used as the drive's `sourceSessionId`. Keying the
     * ride on it (rather than a throwaway UUID) is what lets the server-side
     * convoy finalize (functions live.cleanupExpired) and this client save
     * DEDUPE onto the SAME ride document `rides/{uid}_{sessionId}`: whoever saves
     * first wins, and the other is an idempotent no-op. So a convoy member whose
     * app is alive at session end still gets the RICH route drive, while a member
     * whose app was backgrounded/killed gets the server's duration-only baseline
     * instead of losing the run entirely. Null (no live session id to hand) falls
     * back to a random id — no cross-save dedupe, but never a crash.
     */
    fun start(
        uid: String,
        repository: DrivesRepository,
        routeUploadRunner: RouteUploadRunner? = null,
        sessionId: String? = null,
        // Storage path of the car being driven — the live session's denormalized
        // cover photo — recorded on the saved drive so History shows a round car
        // photo. Null when the sharer has no car.
        carImagePath: String? = null,
        // Garage-vehicle id of the car being driven, recorded on the saved drive so
        // it links back to the exact vehicle. Null when the sharer has no car.
        vehicleId: String? = null,
        // The other members of the convoy this session belongs to, recorded on the
        // saved drive so History can show who you drove with. Empty when this is a
        // solo session (not a convoy drive). Captured here at session start, like
        // the car photo, and denormalized onto the ride document.
        convoyMembers: List<ConvoyDriveMember> = emptyList(),
        // Persists the in-flight recording to disk so a process kill mid-drive does
        // not lose it — a relaunched-but-still-live session RESUMES the same drive
        // (#849). Null in a config-less / CI build; the recording is then
        // memory-only, exactly as before.
        journal: DriveRecordingJournal? = null,
        // Low-noise lifecycle log (start / resume / milestone / stop) so a
        // recurrence of the "drive vanished on restart" report is diagnosable
        // (#849 follow-up). Defaults to the no-op sink.
        log: DriveRecordingLog = NoopDriveRecordingLog,
        controllerFactory: () -> DriveLocationSource?,
    ) {
        val existing = activeState.value
        if (existing != null) {
            // A recording is already in flight (an effect re-run after a config
            // change, or on a dependency the effect keys on — e.g. the location
            // permission — changing). Do NOT restart it; but if the GPS source
            // never attached at the original start, RETRY attaching it now so the
            // live speed + distance recover WITHIN the session (#994).
            //
            // The GPS source is null exactly when [controllerFactory] returned null
            // at start — the production factory (DriveLocationController.createIfPermitted)
            // returns null while ACCESS_FINE_LOCATION is not yet granted (or Play
            // services aren't ready). Before this retry the source was wired ONCE and
            // never again, so a permission granted just after the session began left
            // the readouts dead ("-" speed, "0 m" distance) for the whole session and
            // only a process restart re-ran start() from scratch and fixed it. The
            // caller re-runs this whenever location access changes, so re-consulting
            // the factory here attaches the stream the moment the grant lands.
            //
            // Gated on the coordinator still RECORDING so a stream is never re-opened
            // once the session has stopped and is awaiting its save/discard prompt.
            if (locationController == null &&
                existing.state.value is RecordingState.Recording
            ) {
                attachLocationController(existing, controllerFactory)
            }
            return
        }
        // REUSE this uid's existing upload scope across sessions so every upload
        // the user starts in one sign-in shares ONE cancellation handle: a later
        // sign-out / account switch then cancels them ALL together (a prior
        // drive's upload can still be retrying when the next drive is recorded).
        // A live scope here always belongs to this same uid — a different owner
        // would already have been cancelled + nulled by clearIfNotOwnedBy on the
        // auth change — so only mint a fresh scope for a new owner (or when none
        // is live). Process-scoped + supervisor-jobbed: one upload's failure
        // can't cancel another, and the scope outlives the composition.
        val scope =
            uploadScope?.takeIf { uploadOwnerUid == uid && it.isActive }
                ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator =
            DriveRecordingCoordinator(
                repository,
                // The live-session id keys the ride so a server finalize and this
                // client save land on ONE document; only fall back to a random id
                // when there is no session id to key on.
                sessionId ?: ("single-" + UUID.randomUUID().toString()),
                routeUploadRunner = routeUploadRunner,
                uploadScope = scope,
                carImagePath = carImagePath,
                vehicleId = vehicleId,
                convoyMembers = convoyMembers,
                journal = journal,
                log = log,
            )
        ownerUid = uid
        uploadScope = scope
        uploadOwnerUid = uid
        // start() BEFORE publishing: it loads the resumed journal and fixes
        // resumedRoutePoints, so the LaunchedEffect(activeRecording) that reads
        // them for the breadcrumb restore can never observe an empty pre-start list.
        coordinator.start()
        activeState.value = coordinator
        attachLocationController(coordinator, controllerFactory)
    }

    /**
     * Wires the GPS source into [coordinator]: consults [controllerFactory] for a
     * source (null when the fine-location permission is not yet granted / Play
     * services aren't ready) and, if one is returned AND it actually starts
     * streaming, streams every fix into the recorder ([DriveRecordingCoordinator.addFix])
     * and publishes its speed to the live bar's readout ([CurrentSpeed]).
     *
     * A no-op source result leaves [locationController] null so a later [start]
     * re-run RETRIES the attach — the recovery path for a permission granted after
     * the session began (#994). Idempotent per attached source: called both on a
     * fresh start and on the retry, it only ever holds one running stream.
     */
    private fun attachLocationController(
        coordinator: DriveRecordingCoordinator,
        controllerFactory: () -> DriveLocationSource?,
    ) {
        // A new session (or a recovery re-attach) must never open on the PREVIOUS
        // session's speed, so the readout starts blank and the first fix fills it.
        CurrentSpeed.clear()
        val controller = controllerFactory() ?: return
        // Only KEEP the source if it actually started streaming: start() returns
        // false when the runtime permission is (still) absent. Leaving
        // locationController null in that case lets the next retry try again rather
        // than pinning a source that delivers nothing for the whole session.
        val started =
            controller.start { latitude, longitude, timestampMs, speedMps ->
                coordinator.addFix(latitude, longitude, timestampMs)
                // Speed goes to the live-session bar's readout ONLY. It is passed to
                // the holder rather than the recorder deliberately: nothing about a
                // saved drive derives from it, and the drive payload carries no speed.
                // Stamped with the DEVICE clock, not the fix's own GPS `timestampMs`,
                // because the reader ages it against System.currentTimeMillis.
                CurrentSpeed.onFix(speedMps, System.currentTimeMillis())
            }
        locationController = if (started) controller else null
    }

    /**
     * Ends recording for a finished session and raises the end-of-session summary
     * ([RecordingState.PromptSave]); the live UI then auto-saves the drive from
     * that state and asks Keep or Delete. Releases the GPS source here —
     * deterministically at session end rather than when a composable happens to
     * leave composition, so a mere Activity recreation can never kill a live
     * recording, and a real session end always stops the updates. Idempotent:
     * safe to call repeatedly (and after a recreation, when the summary is
     * already pending).
     *
     * [reason] explains WHY the session ended, so the summary can render
     * convoy-specific copy when the convoy ended under the member. It is recorded
     * only when this call actually RAISES the prompt (the coordinator transitions
     * to [RecordingState.PromptSave]); an idempotent re-call after a recreation
     * finds the coordinator already past that state and leaves the first reason —
     * and the pending flag — untouched.
     */
    fun stop(reason: SavePromptReason = SavePromptReason.Default) {
        // Unconditionally, and BEFORE the "nothing recording" bail-out: the
        // readout must go blank when a session ends however it ended, including
        // the case where no recording was ever started (no drives backend / the
        // member gate) but a previous session left a number behind.
        CurrentSpeed.clear()
        val coordinator = activeState.value ?: return
        locationController?.stop()
        locationController = null
        coordinator.stop()
        // Only capture the reason on the FIRST call that actually raises the prompt.
        // coordinator.stop() no-ops unless it was Recording, so a re-call (effect
        // re-run / recreation) leaves it in PromptSave with promptPending already
        // set — guard on that so the first reason stands and a later call (which may
        // recompute a different reason, e.g. after the self-stop marker was cleared)
        // can never overwrite it.
        if (coordinator.state.value is RecordingState.PromptSave && !promptPendingState.value) {
            stopReasonState.value = reason
            promptPendingState.value = true
        }
    }

    /**
     * Releases everything once the drive has been saved or discarded, so the
     * next session starts clean. Also stops the GPS source defensively.
     */
    fun clear() {
        locationController?.stop()
        locationController = null
        CurrentSpeed.clear()
        ownerUid = null
        activeState.value = null
        promptPendingState.value = false
        stopReasonState.value = SavePromptReason.Default
    }

    /**
     * Tears the recording down when the signed-in user goes away — sign-out
     * ([signedInUid] null) or a switch to a different account — and does
     * NOTHING while it still belongs to [signedInUid].
     *
     * This is the counterpart to being process-scoped. Because the holder
     * deliberately outlives the composition, something has to stop it on a
     * GENUINE teardown, or the fused-location updates and an orphaned recording
     * would run on with no UI left to resolve them. Keying on the signed-in uid
     * discriminates that from a config change without inspecting composition
     * lifecycles: a rotation re-runs the caller with the SAME uid (no-op, the
     * recording and any pending prompt survive), while a sign-out flips it to
     * null (tear down). Sign-out swaps the authed screen out WITHOUT recreating
     * the Activity, so the two are never confusable.
     *
     * An unsaved drive awaiting the save/discard prompt is DROPPED here, and
     * that is deliberate rather than incidental: the user is leaving and there
     * is no UI left to ask them, and — decisively — the drive is the departing
     * uid's. Carrying it across a sign-out would let the next signed-in user's
     * session resolve it, filing another person's GPS trace to the wrong
     * account. Dropping it is required for correctness, not merely tidiness.
     * This never fires on rotation, so it cannot silently lose a drive there.
     */
    fun clearIfNotOwnedBy(signedInUid: String?) {
        // Cancel a still-running background route upload the moment its owner is
        // no longer the signed-in user — even after the recording itself was
        // already released at "Saved" (ownerUid null) while the upload runs on.
        // This is the ONLY place the upload scope is cancelled: a same-uid
        // rotation re-runs this with the SAME uid (uploadOwner == signedInUid, no
        // cancel), while a real sign-out (null) or account switch (a different
        // uid) cancels it, so an upload started under user A's auth can never
        // continue — or retry — under user B's. A benign backgrounding never
        // calls this at all.
        val uploadOwner = uploadOwnerUid
        if (uploadOwner != null && uploadOwner != signedInUid) {
            uploadScope?.cancel()
            uploadScope = null
            uploadOwnerUid = null
        }
        val owner = ownerUid ?: return
        if (owner == signedInUid) return
        clear()
    }
}
