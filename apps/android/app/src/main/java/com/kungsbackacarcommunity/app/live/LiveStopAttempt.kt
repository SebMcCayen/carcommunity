package com.kungsbackacarcommunity.app.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * OPTIMISTIC live-session STOP state — the mirror image of [OptimisticLiveStart].
 *
 * ## The problem this exists for (#798)
 * Whether the shell shows "you are sharing" is DERIVED from the observed RTDB
 * session node (see `LiveLocation.isSharing`), and there is already an optimistic
 * overlay that flips the control to a STOP sign the instant START is tapped. STOP
 * had no such overlay: tapping it fired `live-stopSession` and then WAITED for the
 * server to write `status = stopped` and the RTDB listener to echo it back before
 * the STOP sign, the top live-session bar and the map dot went away. For the whole
 * round trip the session looked like it was still running — "avslutet är
 * fördröjt", the exact complaint in #798.
 *
 * ## The fix
 * The tap records a STOP ATTEMPT here, and the shell treats "observed (or
 * optimistically-started) sharing, MINUS a live stop attempt" as sharing. The
 * attempt is a strictly TEMPORARY overlay that is always resolved:
 *
 *  - the observed session flips to stopped → [reconcile] drops it (truth wins),
 *  - the stop command fails               → [failed] drops it immediately, so the
 *    STOP sign comes back rather than pretending a still-live session ended,
 *  - the command succeeded but the stop never echoes (already gone server-side) →
 *    the attempt is [settled] and expires [ECHO_GRACE_MS] later,
 *  - the command never returns at all → the attempt expires [IN_FLIGHT_TIMEOUT_MS]
 *    after the tap, so a hung stop cannot hide a still-live session forever.
 *
 * So the sharing chrome can never be left hidden over a session that is in fact
 * still running: the moment the optimism is not backed by a real stop, the
 * observed truth shows through again.
 *
 * All decision-making is PURE ([OptimisticLiveStop]) so it is JVM-unit-testable;
 * [LiveShareStop] is only the process-scoped holder that stores the attempt.
 *
 * NOTE: like [OptimisticLiveStart], this deliberately does NOT drive the
 * session-bound side effects (drive recording / the Keep-Delete summary, the
 * foreground publisher). Those stay on the OBSERVED session so a stop that fails
 * cannot tear down a recording or a service for a session that is still live. It
 * only drives what the user LOOKS at.
 */
sealed interface LiveStopAttempt {
    /** No stop attempt is outstanding — the observed session alone decides. */
    data object None : LiveStopAttempt

    /**
     * The stop command was issued at [requestedAtMillis] and has not returned yet.
     * Counts as "not sharing" until [OptimisticLiveStop.IN_FLIGHT_TIMEOUT_MS] after
     * the tap, after which a hung stop stops hiding the (possibly still-live)
     * session and the observed truth shows through again.
     */
    data class InFlight(val requestedAtMillis: Long) : LiveStopAttempt

    /**
     * The stop command RETURNED successfully at [settledAtMillis]; we are now only
     * waiting for the RTDB echo of the stopped session. Counts as "not sharing" for
     * [OptimisticLiveStop.ECHO_GRACE_MS] longer, which covers the case where the
     * call succeeded but no `status = stopped` echo ever arrives (the session was
     * already gone server-side).
     */
    data class Settled(val settledAtMillis: Long) : LiveStopAttempt
}

/** Pure transitions for [LiveStopAttempt]. No Android/Firebase types. */
object OptimisticLiveStop {
    /**
     * Ceiling on an unanswered stop command. Only a backstop: a command that FAILS
     * clears the attempt at once ([failed]), so this is reached solely when the
     * callable never returns. Kept identical to [OptimisticLiveStart.IN_FLIGHT_TIMEOUT_MS]:
     * the risk it bounds is symmetric (a hung call must not lie to the user for
     * long), and here erring means briefly showing a session that may already be
     * stopping — the safe direction.
     */
    const val IN_FLIGHT_TIMEOUT_MS: Long = 20_000L

    /**
     * How long a SUCCESSFUL stop keeps the optimistic "not sharing" state while
     * waiting for the RTDB echo of the stopped session. The echo normally lands in
     * well under a second; this only runs out when the stop succeeded without a
     * distinct stopped echo (the session was already gone), after which the
     * observed truth (also not sharing) simply takes over.
     */
    const val ECHO_GRACE_MS: Long = 5_000L

    /**
     * Records a stop TAP. A second tap while one is still pending keeps the FIRST
     * attempt (so the deadline does not restart) — the double-tap guard.
     */
    fun request(current: LiveStopAttempt, nowMillis: Long): LiveStopAttempt =
        if (isStopping(current, nowMillis)) current else LiveStopAttempt.InFlight(nowMillis)

    /**
     * The stop command returned successfully: hold the optimistic state for the
     * short echo window. An attempt already dropped ([LiveStopAttempt.None] — the
     * session was observed stopped, or a new start took over) stays dropped so a
     * late success cannot re-hide a fresh session.
     */
    fun settled(current: LiveStopAttempt, nowMillis: Long): LiveStopAttempt =
        when (current) {
            is LiveStopAttempt.InFlight -> LiveStopAttempt.Settled(settledAtMillis = nowMillis)
            is LiveStopAttempt.Settled, LiveStopAttempt.None -> current
        }

    /** The stop failed (callable error/exception): revert to sharing immediately. */
    fun failed(): LiveStopAttempt = LiveStopAttempt.None

    /**
     * Folds the OBSERVED session in: once the session is no longer observed as
     * sharing the stop has truly landed, so the overlay has done its job and is
     * dropped. While the session is still observed sharing the attempt is left
     * alone (the stop is still pending / in its echo window).
     */
    fun reconcile(current: LiveStopAttempt, observedSharing: Boolean): LiveStopAttempt =
        if (!observedSharing) LiveStopAttempt.None else current

    /**
     * When the attempt stops counting (i.e. stops HIDING the session), as an
     * absolute timestamp, or null when there is nothing to expire. Callers schedule
     * their timeout off this so the deadline is defined in exactly one place.
     */
    fun pendingUntilMillis(current: LiveStopAttempt): Long? =
        when (current) {
            LiveStopAttempt.None -> null
            is LiveStopAttempt.InFlight -> current.requestedAtMillis + IN_FLIGHT_TIMEOUT_MS
            is LiveStopAttempt.Settled -> current.settledAtMillis + ECHO_GRACE_MS
        }

    /** Whether the attempt still counts as "stopping" (hiding the session) at [nowMillis]. */
    fun isStopping(current: LiveStopAttempt, nowMillis: Long): Boolean {
        val until = pendingUntilMillis(current) ?: return false
        return nowMillis < until
    }
}

/**
 * Process-scoped holder for the current [LiveStopAttempt].
 *
 * Process-scoped (like [LiveShareStart]) so an Activity recreation mid-stop does
 * not drop the overlay and bounce a just-ended session's chrome back on screen.
 * All the logic lives in [OptimisticLiveStop]; this only stores state.
 */
object LiveShareStop {
    private val state = MutableStateFlow<LiveStopAttempt>(LiveStopAttempt.None)
    val attempt: StateFlow<LiveStopAttempt> = state.asStateFlow()

    /** Records a stop tap so the sharing chrome flips off on the next frame. */
    fun request(nowMillis: Long) {
        state.value = OptimisticLiveStop.request(state.value, nowMillis)
    }

    /** The stop command returned successfully; wait out the echo window. */
    fun settled(nowMillis: Long) {
        state.value = OptimisticLiveStop.settled(state.value, nowMillis)
    }

    /**
     * The stop command failed: drop the overlay now so the STOP sign comes back.
     *
     * @return true when an attempt was actually still pending. False means the
     *   attempt had already been resolved (timed out, or the session was observed
     *   stopped), so a caller reporting the failure can stay quiet rather than
     *   repeat a message the timeout / reconcile has already covered.
     */
    fun failed(): Boolean {
        val previous = state.getAndUpdate { OptimisticLiveStop.failed() }
        return previous != LiveStopAttempt.None
    }

    /** Folds in the observed session (clears the overlay once the stop is real). */
    fun reconcile(observedSharing: Boolean) {
        state.value = OptimisticLiveStop.reconcile(state.value, observedSharing)
    }

    /** Drops the overlay unconditionally (a fresh start, sign-out). */
    fun clear() {
        state.value = LiveStopAttempt.None
    }

    /**
     * Drops [expected] if it is still the current attempt. Used by the timeout, so
     * a deadline that fires just as a NEWER attempt is recorded cannot wipe it.
     */
    fun clearIf(expected: LiveStopAttempt) {
        state.compareAndSet(expected, LiveStopAttempt.None)
    }
}
