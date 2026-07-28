package com.kungsbackacarcommunity.app.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * OPTIMISTIC live-session start state.
 *
 * ## The problem this exists for
 * Whether the shell shows "you are sharing" (the bottom bar's red STOP disc and
 * the top live-session bar) is DERIVED from the observed RTDB session node
 * `liveLocation/{uid}/session` (see `LiveLocation.isSharing`). That is the right
 * source of truth, but it makes the UI wait for a full round trip before it
 * reacts to a tap:
 *
 *   tap → `live-startSession` callable (possibly a cold start) → server writes
 *   the session → RTDB value listener echoes it back → recomposition.
 *
 * Until the echo lands the user is still looking at a "+", so starting a session
 * feels broken. The same is true for a convoy, whose session is started
 * SERVER-side by `convoy-create` / `convoy-respond` / `convoy-start`.
 *
 * ## The fix
 * The tap records an ATTEMPT here, and the shell treats "observed session OR a
 * live attempt" as sharing. The attempt is a strictly TEMPORARY overlay that is
 * always resolved:
 *
 *  - the observed session arrives  → [reconcile] drops it (truth takes over),
 *  - the command fails             → [failed] drops it immediately,
 *  - the command succeeded but no session ever appears (live-share flag off
 *    server-side, a convoy that was still `forming`, …) → the attempt is
 *    [settled] and expires [ECHO_GRACE_MS] later,
 *  - the command never returns at all → the attempt expires
 *    [IN_FLIGHT_TIMEOUT_MS] after the tap.
 *
 * So a fake STOP sign can never be left on screen with no session behind it.
 *
 * All of the decision-making is PURE ([OptimisticLiveStart]) so it is
 * JVM-unit-testable; [LiveShareStart] is only the process-scoped holder that
 * stores the current attempt.
 *
 * NOTE: this deliberately does NOT drive the side effects bound to a session
 * (drive recording, the foreground position publisher). Those stay on the
 * observed session, so a start that fails cannot leave a phantom recording or a
 * running foreground service behind. It only drives what the user LOOKS at.
 */
sealed interface LiveStartAttempt {
    /** No start attempt is outstanding — the observed session alone decides. */
    data object None : LiveStartAttempt

    /**
     * The start command was issued at [requestedAtMillis] and has not returned
     * yet. Expires [OptimisticLiveStart.IN_FLIGHT_TIMEOUT_MS] after the tap so a
     * hung call cannot strand the UI in a fake sharing state.
     */
    data class InFlight(val requestedAtMillis: Long) : LiveStartAttempt

    /**
     * The start command RETURNED successfully at [settledAtMillis]; we are now
     * only waiting for the RTDB echo of the session it created. Expires
     * [OptimisticLiveStart.ECHO_GRACE_MS] later, which is the case where the call
     * succeeded but produced no session for this user at all.
     *
     * [requestedAtMillis] is carried through unchanged so the top live-session
     * bar keeps ticking from the moment of the TAP rather than restarting.
     */
    data class Settled(val requestedAtMillis: Long, val settledAtMillis: Long) : LiveStartAttempt
}

/** [OptimisticLiveStart.request]'s answer: the next attempt + whether to call. */
data class LiveStartDecision(
    val attempt: LiveStartAttempt,
    /** False when the start must NOT be issued (already sharing, or in flight). */
    val proceed: Boolean,
)

/** Pure transitions for [LiveStartAttempt]. No Android/Firebase types. */
object OptimisticLiveStart {
    /**
     * Ceiling on an unanswered start command. Only a backstop: a command that
     * FAILS clears the attempt at once ([failed]), so this is reached solely when
     * the callable never returns (dead network, a call hung past its own
     * deadline). Generous enough to cover a Cloud Functions cold start, short
     * enough that the user is not lied to for long.
     */
    const val IN_FLIGHT_TIMEOUT_MS: Long = 20_000L

    /**
     * How long a SUCCESSFUL start keeps the optimistic state while waiting for
     * the RTDB echo. The echo normally lands in well under a second; this only
     * runs out when the call succeeded without creating a session for this user
     * (e.g. the server-side live-share flag is off, or a convoy accept into a
     * convoy that was still `forming`), in which case the UI quietly reverts.
     */
    const val ECHO_GRACE_MS: Long = 5_000L

    /**
     * Decides what a start TAP should do.
     *
     * Refuses to proceed when a session is already observed (nothing to start)
     * or when an attempt is still pending — that is the double-tap guard, so two
     * quick taps issue exactly one `startSession`.
     */
    fun request(
        current: LiveStartAttempt,
        nowMillis: Long,
        observedSharing: Boolean,
    ): LiveStartDecision =
        when {
            observedSharing -> LiveStartDecision(current, proceed = false)
            isPending(current, nowMillis) -> LiveStartDecision(current, proceed = false)
            else -> LiveStartDecision(LiveStartAttempt.InFlight(nowMillis), proceed = true)
        }

    /**
     * The start command returned successfully: hold the optimistic state for the
     * short echo window. An attempt that was already dropped ([LiveStartAttempt.None],
     * e.g. the user hit Stop while the call was in flight) stays dropped — a late
     * success must not resurrect it.
     */
    fun settled(current: LiveStartAttempt, nowMillis: Long): LiveStartAttempt =
        when (current) {
            is LiveStartAttempt.InFlight ->
                LiveStartAttempt.Settled(
                    requestedAtMillis = current.requestedAtMillis,
                    settledAtMillis = nowMillis,
                )
            is LiveStartAttempt.Settled, LiveStartAttempt.None -> current
        }

    /** The start failed (callable error/exception): revert to "+" immediately. */
    fun failed(): LiveStartAttempt = LiveStartAttempt.None

    /**
     * Folds the OBSERVED session in: once a real session is visible the overlay
     * has done its job and is dropped, so everything downstream is driven by
     * truth again. Not sharing leaves the attempt alone (it is still pending).
     */
    fun reconcile(current: LiveStartAttempt, observedSharing: Boolean): LiveStartAttempt =
        if (observedSharing) LiveStartAttempt.None else current

    /**
     * When the attempt stops counting as sharing, as an absolute timestamp, or
     * null when there is nothing to expire. Callers schedule their timeout off
     * this so the deadline is defined in exactly one place.
     */
    fun pendingUntilMillis(current: LiveStartAttempt): Long? =
        when (current) {
            LiveStartAttempt.None -> null
            is LiveStartAttempt.InFlight -> current.requestedAtMillis + IN_FLIGHT_TIMEOUT_MS
            is LiveStartAttempt.Settled -> current.settledAtMillis + ECHO_GRACE_MS
        }

    /** Whether the attempt still counts as "sharing" at [nowMillis]. */
    fun isPending(current: LiveStartAttempt, nowMillis: Long): Boolean {
        val until = pendingUntilMillis(current) ?: return false
        return nowMillis < until
    }

    /**
     * What the shell shows: the observed session, or a still-pending attempt.
     * This is what flips the "+" to a STOP disc on the very next frame after a
     * tap instead of after the server round trip.
     */
    fun isSharing(
        observedSharing: Boolean,
        current: LiveStartAttempt,
        nowMillis: Long,
    ): Boolean = observedSharing || isPending(current, nowMillis)

    /**
     * The session start to tick the top live-session bar from: the real one when
     * known, otherwise the moment of the TAP. Without this fallback the STOP disc
     * would appear while the bar stayed hidden — the same inconsistency in
     * reverse. Null means neither is available, and no bar is composed.
     */
    fun sessionStartMillis(
        observedStartMillis: Long?,
        current: LiveStartAttempt,
        nowMillis: Long,
    ): Long? =
        observedStartMillis
            ?: if (isPending(current, nowMillis)) requestedAtMillis(current) else null

    /** The moment the user tapped, for a pending attempt; null for [LiveStartAttempt.None]. */
    private fun requestedAtMillis(current: LiveStartAttempt): Long? =
        when (current) {
            LiveStartAttempt.None -> null
            is LiveStartAttempt.InFlight -> current.requestedAtMillis
            is LiveStartAttempt.Settled -> current.requestedAtMillis
        }
}

/**
 * Process-scoped holder for the current [LiveStartAttempt].
 *
 * Process-scoped (like `SingleSessionRecording`) rather than composition-scoped
 * for two reasons: an Activity recreation mid-start must not drop the overlay and
 * bounce the control back to "+", and the convoy taps that start a session
 * server-side happen inside the convoy route, far from the shell that renders the
 * control. All the logic lives in [OptimisticLiveStart]; this only stores state.
 */
object LiveShareStart {
    private val state = MutableStateFlow<LiveStartAttempt>(LiveStartAttempt.None)
    val attempt: StateFlow<LiveStartAttempt> = state.asStateFlow()

    /**
     * Records a start tap.
     *
     * @return true when the caller should actually issue the start command;
     *   false when it must not (already sharing, or a start is already pending).
     */
    fun request(nowMillis: Long, observedSharing: Boolean): Boolean {
        val decision = OptimisticLiveStart.request(state.value, nowMillis, observedSharing)
        state.value = decision.attempt
        return decision.proceed
    }

    /** The start command returned successfully; wait out the echo window. */
    fun settled(nowMillis: Long) {
        state.value = OptimisticLiveStart.settled(state.value, nowMillis)
    }

    /**
     * The start command failed: drop the overlay now.
     *
     * @return true when an attempt was actually still pending. False means the
     *   attempt had already been resolved — it timed out, or the user stopped —
     *   so a caller that reports the failure to the user can stay quiet rather
     *   than repeat a message the timeout has already shown.
     */
    fun failed(): Boolean {
        val previous = state.getAndUpdate { OptimisticLiveStart.failed() }
        return previous != LiveStartAttempt.None
    }

    /** Folds in the observed session (clears the overlay once it is real). */
    fun reconcile(observedSharing: Boolean) {
        state.value = OptimisticLiveStart.reconcile(state.value, observedSharing)
    }

    /** Drops the overlay unconditionally (Stop, sign-out). */
    fun clear() {
        state.value = LiveStartAttempt.None
    }

    /**
     * Drops [expected] if it is still the current attempt. Used by the timeout,
     * so a deadline that fires just as a NEWER attempt is recorded cannot wipe
     * the new one.
     */
    fun clearIf(expected: LiveStartAttempt) {
        state.compareAndSet(expected, LiveStartAttempt.None)
    }
}
