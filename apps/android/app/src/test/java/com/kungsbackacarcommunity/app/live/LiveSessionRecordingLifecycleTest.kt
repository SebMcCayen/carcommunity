package com.kungsbackacarcommunity.app.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind "rotating the phone must not prompt to save the session".
 *
 * The shell binds the single-session drive recording to the observed live-share
 * state. An Activity recreation (rotation; the manifest does not lock
 * orientation) restarts the composition and re-subscribes the own-session flow,
 * so for a frame or more the collector reads its not-yet-loaded placeholder —
 * indistinguishable BY VALUE from "no session" — before the RTDB listener
 * re-emits the still-live session. Treating that transient not-sharing read as a
 * session end stopped the recording and raised its save/discard prompt on every
 * rotate.
 *
 * [LiveSessionRecordingLifecycle.shouldStopRecording] withholds the stop until
 * the flow has actually emitted ([LiveSessionLoad.observed]); these tests pin
 * exactly that — the config-change case is a no-op while every genuine end still
 * stops.
 */
class LiveSessionRecordingLifecycleTest {

    private fun session(): LiveSessionInfo =
        LiveSessionInfo(
            sessionId = "s1",
            status = LiveSessionStatus.ACTIVE,
            duration = LiveSessionDuration.SIX_HOURS,
            expiresAtMillis = Long.MAX_VALUE,
        )

    // --- The load wrapper distinguishes "not emitted" from "emitted null". ---

    @Test
    fun loadingHasNotObservedAndCarriesNoSession() {
        val loading: LiveSessionLoad = LiveSessionLoad.Loading
        assertFalse(loading.observed)
        assertFalse("Loading must not surface a session", loading.sessionOrNull != null)
    }

    @Test
    fun loadedNullIsObservedButHasNoSession() {
        val loaded: LiveSessionLoad = LiveSessionLoad.Loaded(null)
        assertTrue(loaded.observed)
        assertFalse(loaded.sessionOrNull != null)
    }

    @Test
    fun loadedSessionIsObservedAndSurfacesTheSession() {
        val loaded: LiveSessionLoad = LiveSessionLoad.Loaded(session())
        assertTrue(loaded.observed)
        assertTrue(loaded.sessionOrNull != null)
    }

    // --- The stop decision. ---

    /**
     * The rotation case: the flow has not re-emitted yet, so sharing reads false
     * off the placeholder. The recording must NOT stop — this is the whole bug.
     */
    @Test
    fun aNotYetLoadedNotSharingReadDoesNotStop_theRotationCase() {
        assertFalse(
            LiveSessionRecordingLifecycle.shouldStopRecording(
                sharing = false,
                sessionObserved = false,
                convoyActive = false,
            ),
        )
    }

    /**
     * A genuine end: the flow has emitted the ended/expired session, so
     * not-sharing is real and the recording stops (and auto-saves).
     */
    @Test
    fun anObservedNotSharingStateStops_theRealEndCase() {
        assertTrue(
            LiveSessionRecordingLifecycle.shouldStopRecording(
                sharing = false,
                sessionObserved = true,
                convoyActive = false,
            ),
        )
    }

    /** While sharing, nothing stops — loaded or not, convoy or not. */
    @Test
    fun sharingNeverStops() {
        assertFalse(
            LiveSessionRecordingLifecycle.shouldStopRecording(
                sharing = true,
                sessionObserved = true,
                convoyActive = false,
            ),
        )
        assertFalse(
            LiveSessionRecordingLifecycle.shouldStopRecording(
                sharing = true,
                sessionObserved = false,
                convoyActive = true,
            ),
        )
    }

    // --- The convoy rotation case (regression of the #729 class). ---

    /**
     * A convoy member's auto-started session re-syncs after a rotation, so the
     * own-session flow can re-emit a transient `Loaded(null)`: the flow HAS
     * emitted ([sessionObserved] true) yet the session reads not-sharing. The
     * [sessionObserved] guard alone would stop and raise the save prompt here —
     * the exact regression. The still-active convoy (#726) proves the session
     * cannot really have ended, so the stop is withheld.
     */
    @Test
    fun anActiveConvoyWithholdsAnObservedNotSharingRead_theConvoyRotationCase() {
        assertFalse(
            LiveSessionRecordingLifecycle.shouldStopRecording(
                sharing = false,
                sessionObserved = true,
                convoyActive = true,
            ),
        )
    }

    /**
     * Once the convoy is no longer active — the owner ended it, or the caller
     * left — an observed not-sharing state is a real end again and stops (and
     * saves the member's run). This is what keeps the convoy path from silently
     * dropping a finished drive.
     */
    @Test
    fun endingTheConvoyReleasesTheGuardAndStops() {
        assertTrue(
            LiveSessionRecordingLifecycle.shouldStopRecording(
                sharing = false,
                sessionObserved = true,
                convoyActive = false,
            ),
        )
    }

    /**
     * Belt and braces: an active convoy on the not-yet-loaded placeholder is
     * still withheld — a config change is a no-op whether or not the convoy
     * snapshot has caught up.
     */
    @Test
    fun anActiveConvoyOnTheUnloadedPlaceholderStillDoesNotStop() {
        assertFalse(
            LiveSessionRecordingLifecycle.shouldStopRecording(
                sharing = false,
                sessionObserved = false,
                convoyActive = true,
            ),
        )
    }
}
