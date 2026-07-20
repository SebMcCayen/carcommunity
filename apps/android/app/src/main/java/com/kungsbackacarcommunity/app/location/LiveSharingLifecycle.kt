package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.live.LiveLocation
import com.kungsbackacarcommunity.app.live.LiveSessionInfo
import com.kungsbackacarcommunity.app.live.LiveSessionStatus

/**
 * What the foreground service should do after an observation of the caller's
 * own live session.
 */
sealed interface LiveSharingDecision {
    /**
     * Keep publishing. [remainingSeconds] is the time left on the session
     * (null when unknown — e.g. an unparseable expiry, or before the first
     * successful session read), used for the persistent notification's
     * countdown only. It is never a reason to stop.
     */
    data class Continue(val remainingSeconds: Long?) : LiveSharingDecision

    /** Sharing is over: tear the service and its notification down. */
    data class Stop(val reason: LiveSharingStopReason) : LiveSharingDecision
}

/** Why sharing ended. Drives nothing but diagnostics/tests — never logged with a uid. */
enum class LiveSharingStopReason {
    /** No signed-in user (sign-out, or the signed-in uid changed). */
    SIGNED_OUT,

    /** The session passed its 1h/2h/4h expiry. */
    EXPIRED,

    /** The session node says stopped/expired — a manual stop or a remote end. */
    SESSION_ENDED,

    /** The session node stayed absent past the grace window (remote end / erased). */
    SESSION_ABSENT,
}

/**
 * The foreground service's session state machine, as pure Kotlin so every
 * transition (start, expiry, manual stop, sign-out, remote end) is
 * JVM-unit-testable without a device, Firebase or the Android framework.
 *
 * The service owns NO session state of its own: it observes exactly the same
 * `liveLocation/{uid}/session` node the in-app UI observes
 * ([com.kungsbackacarcommunity.app.live.LiveLocationRepository.observeOwnSession])
 * and derives its whole lifetime from it. There is one source of truth for
 * "am I sharing", and it is the backend's session node.
 *
 * ### Why the absent-session grace window exists
 * The session flow emits `null` both for "there is genuinely no session" AND
 * for "the Realtime Database read was denied or interrupted" (the repository's
 * `onCancelled` maps errors to null so the UI cannot hang). Treating the first
 * null as "stop" would kill background sharing the moment the car enters a
 * tunnel or loses data — precisely the situation this service exists for. So an
 * absent session only stops sharing once it has persisted for
 * [absentGraceMillis]; a single reconnect inside the window resumes silently.
 *
 * Expiry, by contrast, stops IMMEDIATELY and is evaluated against the local
 * clock, so a session always ends on time even with no connectivity at all.
 * That asymmetry is deliberate: erring toward "keep sharing" is only acceptable
 * while the user's chosen time window is still open.
 */
class LiveSharingLifecycle(
    private val absentGraceMillis: Long = ABSENT_GRACE_MILLIS,
    private val maxRuntimeMillis: Long = MAX_RUNTIME_MILLIS,
) {
    private var lastSession: LiveSessionInfo? = null
    private var firstAbsentAtMillis: Long? = null
    private var startedAtMillis: Long? = null

    /**
     * Folds one observation of the session node into a decision.
     *
     * @param signedIn whether the uid this service was started for is still the
     *   signed-in user. False covers sign-out and account switches, and stops
     *   immediately — no grace window, since publishing another account's
     *   position is a privacy incident, not a connectivity blip.
     */
    fun onObservation(
        signedIn: Boolean,
        session: LiveSessionInfo?,
        nowMillis: Long,
    ): LiveSharingDecision {
        if (session != null) {
            // A real session node arrived — any earlier absence was a blip.
            lastSession = session
            firstAbsentAtMillis = null
        } else if (firstAbsentAtMillis == null) {
            firstAbsentAtMillis = nowMillis
        }
        return evaluate(signedIn, nowMillis)
    }

    /**
     * Re-evaluates the last known session against a newer clock reading, without
     * counting as a fresh observation. This is what expires a session while the
     * app is backgrounded and nothing new is arriving from the database — the
     * service ticks, the window closes, the service stops itself.
     */
    fun onTick(signedIn: Boolean, nowMillis: Long): LiveSharingDecision =
        evaluate(signedIn, nowMillis)

    private fun evaluate(signedIn: Boolean, nowMillis: Long): LiveSharingDecision {
        if (!signedIn) return LiveSharingDecision.Stop(LiveSharingStopReason.SIGNED_OUT)

        // Hard ceiling. The longest session a user can pick is 4 hours, so a
        // service that has been publishing for longer than that plus slack is
        // running on state it should not trust — most plausibly a session whose
        // expiry could not be parsed, which would otherwise share forever. Ending
        // sharing that the user did not ask to continue is always the safe error.
        val startedAt = startedAtMillis ?: nowMillis.also { startedAtMillis = it }
        if (nowMillis - startedAt >= maxRuntimeMillis) {
            return LiveSharingDecision.Stop(LiveSharingStopReason.EXPIRED)
        }

        // Expiry and an ended session are evaluated against the LAST KNOWN
        // session even while the node is momentarily unreadable, so a dropped
        // connection can never extend the user's chosen window.
        val known = lastSession
        if (known != null) {
            if (known.status != LiveSessionStatus.ACTIVE) {
                return LiveSharingDecision.Stop(LiveSharingStopReason.SESSION_ENDED)
            }
            if (!LiveLocation.isSharing(known, nowMillis)) {
                // ACTIVE but past its expiry: the backend has not swept it yet,
                // so the client enforces its own 1h/2h/4h window.
                return LiveSharingDecision.Stop(LiveSharingStopReason.EXPIRED)
            }
        }

        val absentSince = firstAbsentAtMillis
        if (absentSince != null && nowMillis - absentSince >= absentGraceMillis) {
            return LiveSharingDecision.Stop(LiveSharingStopReason.SESSION_ABSENT)
        }
        return LiveSharingDecision.Continue(LiveLocation.remainingSeconds(known, nowMillis))
    }

    companion object {
        /** How long an absent session node is tolerated before stopping. */
        const val ABSENT_GRACE_MILLIS = 60_000L

        /**
         * Absolute ceiling on one service run: the longest pickable session
         * (4 hours) plus five minutes of slack for clock skew and a late
         * backend sweep.
         */
        const val MAX_RUNTIME_MILLIS = 4 * 60 * 60_000L + 5 * 60_000L
    }
}
