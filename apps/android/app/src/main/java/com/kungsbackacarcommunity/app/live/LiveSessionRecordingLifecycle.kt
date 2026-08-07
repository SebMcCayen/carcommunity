package com.kungsbackacarcommunity.app.live

/**
 * Load state of the observed own-session flow ([LiveLocationRepository.observeOwnSession]).
 *
 * ## Why a load state exists at all
 *
 * The shell subscribes to the flow with a Compose collector that starts from an
 * `initial` value BEFORE the flow has actually emitted. That placeholder is
 * indistinguishable, by value alone, from "there is genuinely no session": both
 * are a null [LiveSessionInfo]. It usually does not matter — until an Activity
 * recreation (rotation; the manifest does not lock orientation) restarts the
 * composition and re-subscribes the flow. For one or more frames the collector
 * is back on its placeholder null while the RTDB listener re-attaches and
 * re-emits the STILL-LIVE session, so anything that reads "no session → the
 * session ended" fires spuriously on every rotation.
 *
 * That is exactly what stopped a live drive recording and raised its
 * save/discard prompt on rotation: the recording-lifecycle effect treated the
 * transient placeholder null as a real session end. [SingleSessionRecording] is
 * process-scoped so the recording ITSELF survives the recreation, but the effect
 * calling `stop()` from the recreated composition defeated that.
 *
 * Distinguishing [Loading] (not emitted yet) from [Loaded] (emitted — the value,
 * null or not, is the real answer) is the fix: a stop decision is only allowed
 * once the flow is [Loaded]. See [LiveSessionRecordingLifecycle.shouldStopRecording].
 */
sealed interface LiveSessionLoad {
    /**
     * The flow has not emitted since it was (re)subscribed — the collector is on
     * its placeholder. True on a fresh composition and immediately after every
     * Activity recreation, until the first real emission lands.
     */
    data object Loading : LiveSessionLoad

    /**
     * The flow has emitted; [session] is the real current session (or null when
     * the user genuinely is not sharing).
     */
    data class Loaded(val session: LiveSessionInfo?) : LiveSessionLoad

    /** The session if one has actually been observed, else null. */
    val sessionOrNull: LiveSessionInfo?
        get() = (this as? Loaded)?.session

    /** Whether the flow has emitted at least once since (re)subscription. */
    val observed: Boolean
        get() = this is Loaded
}

/**
 * The pure rule deciding whether a NOT-sharing observation should stop the
 * single-session drive recording (and raise its save/discard prompt).
 *
 * Kept off the Compose effect and expressed over plain booleans so it is
 * JVM-unit-testable and cannot drift: the whole point is that a config change
 * must not be mistaken for a session end.
 */
object LiveSessionRecordingLifecycle {
    /**
     * True only when the observed-session flow has actually reported a genuine
     * not-sharing state, and not a config-change re-sync artifact.
     *
     * The [sessionObserved] guard is what makes a plain SOLO rotation a no-op:
     * right after an Activity recreation the flow has not re-emitted yet
     * ([sessionObserved] false), so even though [sharing] reads false on the
     * placeholder, the recording is NOT stopped and no save prompt fires.
     *
     * The [convoyActive]+[sessionPresent] guard closes the CONVOY rotation gap. A
     * convoy member's live session is auto-started server-side and re-synced (not
     * locally authored), and its node read is gated on convoy membership, so after
     * a rotation the own-session flow can re-emit a transient `Loaded(null)` while
     * the listener re-attaches — the flow HAS emitted ([sessionObserved] true) and
     * carries NO session ([sessionPresent] false), so [sharing] reads false and
     * the [sessionObserved] guard alone treats it as a real end and stops on it.
     * #726's invariant ("an active convoy implies an ongoing live session") is the
     * disambiguator: while a convoy is still active a MISSING session is a re-sync
     * transient, never a real end, so the stop is withheld. The caller latches
     * [convoyActive] across the config change (the convoy snapshot is itself
     * transiently Loading right after a recreation), so the guard holds through the
     * whole rotation window.
     *
     * Crucially the convoy guard is scoped to the MISSING-session transient only.
     * A genuine end that the flow reports as a real session object — user Stop,
     * Hide-me-now and session expiry all leave a `status=stopped/expired` node
     * ([sessionPresent] true) — is a real end even while a convoy is active
     * (hide-me-now stops the session without leaving the convoy), so it still
     * stops and auto-saves. The convoy ending clears [convoyActive] as well, so
     * that path is covered twice over.
     */
    fun shouldStopRecording(
        sharing: Boolean,
        sessionObserved: Boolean,
        sessionPresent: Boolean,
        convoyActive: Boolean,
    ): Boolean = sessionObserved && !sharing && !(convoyActive && !sessionPresent)
}
