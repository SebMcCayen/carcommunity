package com.kungsbackacarcommunity.app.drives

import java.util.UUID
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
     * Whether a finished recording is waiting on the user's save/discard
     * choice. Survives Activity recreation so the prompt cannot be lost.
     */
    val promptPending: StateFlow<Boolean> = promptPendingState.asStateFlow()

    private var locationController: DriveLocationController? = null

    /**
     * Begins recording for a newly-started session. A no-op when a recording is
     * already in flight (including one still awaiting its save/discard choice),
     * so an effect that re-runs after a config change resumes rather than
     * restarting and losing the accumulated points.
     *
     * [controllerFactory] supplies the GPS source (null when Play services /
     * the location permission are unavailable — the session then yields a
     * duration-only summary); it is a parameter so this holder stays free of
     * Android types and JVM-unit-testable.
     */
    fun start(
        repository: DrivesRepository,
        controllerFactory: () -> DriveLocationController?,
    ) {
        if (activeState.value != null) return
        val coordinator =
            DriveRecordingCoordinator(repository, "single-" + UUID.randomUUID().toString())
        activeState.value = coordinator
        coordinator.start()
        val controller = controllerFactory()
        locationController = controller
        controller?.start { latitude, longitude, timestampMs ->
            coordinator.addFix(latitude, longitude, timestampMs)
        }
    }

    /**
     * Ends recording for a finished session and raises the save/discard prompt.
     * Releases the GPS source here — deterministically at session end rather
     * than when a composable happens to leave composition, so a mere Activity
     * recreation can never kill a live recording, and a real session end always
     * stops the updates. Idempotent: safe to call repeatedly (and after a
     * recreation, when the prompt is already pending).
     */
    fun stop() {
        val coordinator = activeState.value ?: return
        locationController?.stop()
        locationController = null
        coordinator.stop()
        if (coordinator.state.value is RecordingState.PromptSave) {
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
        activeState.value = null
        promptPendingState.value = false
    }
}
