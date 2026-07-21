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
 * Where the hard-ceiling anchor lives.
 *
 * The ceiling has to be anchored to the SESSION, not to the service instance:
 * anchoring it to the instance means a process kill (LMK, OEM task killer, a
 * crash) resets it, and `START_REDELIVER_INTENT` then restarts the service with
 * a fresh 4h05m budget. With an unparseable expiry — the one case the ceiling
 * exists for, since [LiveLocation.isSharing] treats a null expiry as still
 * sharing — repeated restarts would let background location sharing run
 * unbounded, which is exactly the outcome the ceiling was written to prevent.
 *
 * Keyed by `sessionId`, so a genuinely new session always gets a fresh anchor
 * while a restart within the same session keeps the original one.
 */
interface SharingAnchorStore {
    /**
     * The first time [sessionId] was ever observed, recording [nowMillis] as
     * that time if it has not been seen before. Only the most recent session is
     * retained — a new id supersedes the previous one, so this never grows.
     */
    fun anchorFor(sessionId: String, nowMillis: Long): Long

    /** Forgets the stored anchor; called once sharing has actually ended. */
    fun clear()
}

/** Non-persistent [SharingAnchorStore] — the default, and what tests use. */
class InMemorySharingAnchorStore : SharingAnchorStore {
    private var sessionId: String? = null
    private var anchorMillis: Long = 0L

    override fun anchorFor(sessionId: String, nowMillis: Long): Long {
        if (this.sessionId != sessionId) {
            this.sessionId = sessionId
            anchorMillis = nowMillis
        }
        return anchorMillis
    }

    override fun clear() {
        sessionId = null
        anchorMillis = 0L
    }
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
    private val anchorStore: SharingAnchorStore = InMemorySharingAnchorStore(),
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
        if (!signedIn) return stop(LiveSharingStopReason.SIGNED_OUT)

        // Expiry and an ended session are evaluated against the LAST KNOWN
        // session even while the node is momentarily unreadable, so a dropped
        // connection can never extend the user's chosen window.
        val known = lastSession

        // Hard ceiling — the BACKSTOP for a session with no usable expiry.
        //
        // When the expiry parses (the normal case, INCLUDING a session that was
        // extended: extendSession just pushes expiresAt forward to a fresh capped
        // window ≤ 6h out), the expiry check below is the real bound and this
        // ceiling must NOT fire — otherwise a legitimately-extended session would
        // be force-stopped at the old runtime limit despite a valid future expiry.
        //
        // When the expiry is null/unparseable, [LiveLocation.isSharing] treats the
        // session as still sharing, so nothing else would ever stop it. The ceiling
        // exists for exactly that case: a service publishing longer than the 6h cap
        // plus slack is running on state it should not trust, and ending sharing the
        // user did not ask to continue is the safe error.
        //
        // Anchored to the SESSION via [anchorStore], not to this service instance:
        // an instance-local anchor resets on process death, and
        // START_REDELIVER_INTENT would then hand a restarted service a fresh budget —
        // so repeated kills could extend sharing without limit. Before any session
        // has been read there is no id to key on, so fall back to the instance clock.
        val expiryUnknown = known == null || known.expiresAtMillis == null
        if (expiryUnknown) {
            val startedAt =
                known?.let { anchorStore.anchorFor(it.sessionId, nowMillis) }
                    ?: startedAtMillis
                    ?: nowMillis.also { startedAtMillis = it }
            if (nowMillis - startedAt >= maxRuntimeMillis) {
                return stop(LiveSharingStopReason.EXPIRED)
            }
        }

        if (known != null) {
            if (known.status != LiveSessionStatus.ACTIVE) {
                return stop(LiveSharingStopReason.SESSION_ENDED)
            }
            if (!LiveLocation.isSharing(known, nowMillis)) {
                // ACTIVE but past its (parseable) expiry: the backend may not have
                // swept it yet, so the client enforces its own capped window.
                return stop(LiveSharingStopReason.EXPIRED)
            }
        }

        val absentSince = firstAbsentAtMillis
        if (absentSince != null && nowMillis - absentSince >= absentGraceMillis) {
            return stop(LiveSharingStopReason.SESSION_ABSENT)
        }
        return LiveSharingDecision.Continue(LiveLocation.remainingSeconds(known, nowMillis))
    }

    /**
     * Stops, dropping the persisted anchor ONLY on positive evidence that the
     * session is genuinely over.
     *
     * Clearing it on every reason would reopen the hole the anchor exists to
     * close. [LiveSharingStopReason.SESSION_ABSENT] does not mean "ended" — the
     * repository maps read failures to a null session, so a long tunnel is
     * indistinguishable from an erased node, and that is precisely why the
     * absent path has a grace window rather than stopping outright. Clearing on
     * it would let a restart within the same `sessionId` re-anchor a fresh
     * 4h05m, in exactly the unparseable-expiry case the ceiling bounds.
     *
     * [LiveSharingStopReason.SIGNED_OUT] is treated the same way, which is
     * stricter than it strictly needs to be: the session can still be ACTIVE
     * server-side, so if the user signs back in and resumes it the original
     * bound should still apply.
     *
     * Retaining an anchor costs nothing — it is a single entry keyed by
     * `sessionId`, superseded by the next session and never read by one with a
     * different id. Erring toward keeping the bound is the safe direction.
     */
    private fun stop(reason: LiveSharingStopReason): LiveSharingDecision {
        if (reason == LiveSharingStopReason.SESSION_ENDED ||
            reason == LiveSharingStopReason.EXPIRED
        ) {
            anchorStore.clear()
        }
        return LiveSharingDecision.Stop(reason)
    }

    companion object {
        /** How long an absent session node is tolerated before stopping. */
        const val ABSENT_GRACE_MILLIS = 60_000L

        /**
         * Absolute ceiling on one service run when the expiry cannot be trusted
         * (null/unparseable): the 6h hard cap ([LiveLocation.LIVE_SESSION_MAX_MS])
         * plus five minutes of slack for clock skew and a late backend sweep. A
         * session with a usable expiry is bounded by that expiry instead (see
         * [evaluate]); this only backstops the no-usable-expiry case.
         */
        const val MAX_RUNTIME_MILLIS = LiveLocation.LIVE_SESSION_MAX_MS + 5 * 60_000L
    }
}
