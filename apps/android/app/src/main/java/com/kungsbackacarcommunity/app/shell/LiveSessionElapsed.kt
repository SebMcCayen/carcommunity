package com.kungsbackacarcommunity.app.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the map's live-session bar gets the instant it counts up from.
 *
 * ## The bug this exists for
 * The bar renders `now − start`, and `now` is always the DEVICE clock
 * (`System.currentTimeMillis`). The `start` it used to be given was not:
 *
 * - the observed session's start is reconstructed as `expiresAt − duration`
 *   (`AuthenticatedApp`), and `expiresAt` is minted by the SERVER
 *   (`buildSession` in functions/src/live/live-core.ts writes
 *   `now.toISOString()` / `expiresAt` off the Cloud Functions clock). Subtracting
 *   a server instant from a device instant leaves the whole device↔server clock
 *   skew in the readout, so the bar opened on whatever that skew happened to be —
 *   "0:19" rather than "0:00" — instead of zero;
 * - the drive recorder's `startedAtMillis` is a device instant, but it is the
 *   moment RECORDING started, which equals the session start only for a session
 *   started in this process. Re-opening the app during a running session starts a
 *   fresh recorder, so that value reads "now" for a session that began an hour ago.
 *
 * The one instant that is BOTH on the device clock AND actually the start of the
 * session is the moment the user TAPPED start — already captured by
 * [com.kungsbackacarcommunity.app.live.LiveStartAttempt]. It was previously the
 * LAST resort (used only until the observed session arrived), which is exactly
 * backwards: the moment the server value echoed down, the bar jumped from 0:00 to
 * the skew. Here the tap wins, and — because the optimistic attempt is discarded
 * as soon as the real session lands — the chosen instant is LATCHED so nothing can
 * move it afterwards.
 *
 * ## The two rules
 * 1. **The first frame reads 0:00.** The anchor is never in the future
 *    (`coerceAtMost(now)`), so `now − anchor` is never negative, and for a session
 *    the user just started the anchor IS the tap, i.e. ~`now`.
 * 2. **It only ever counts up.** Once latched the anchor never changes for the
 *    life of the session, so no later-arriving, differently-clocked value can make
 *    the readout jump forwards or backwards mid-session.
 *
 * Pure and Android-free, so both rules are JVM-unit-testable; [LiveSessionAnchor]
 * is only the process-scoped holder that remembers the latch.
 */
object LiveSessionElapsed {
    /**
     * The instant to tick from, or null when there is nothing to show.
     *
     * @param latchedMillis the anchor already in use, from [LiveSessionAnchor].
     * @param sharing whether a session is running (the UI's optimistic view of it).
     *   False releases the latch, so the NEXT session starts from zero again
     *   rather than inheriting the finished one's anchor.
     * @param tapStartMillis the moment start was tapped, while that attempt is
     *   still pending — a DEVICE-clock instant, and the accurate one.
     * @param observedStartMillis the start derived from the observed session — a
     *   SERVER-clock instant, used only when there is no tap to go on (a session
     *   that was already running when the app opened).
     * @param nowMillis the device clock.
     */
    fun anchorMillis(
        latchedMillis: Long?,
        sharing: Boolean,
        tapStartMillis: Long?,
        observedStartMillis: Long?,
        nowMillis: Long,
    ): Long? {
        // Session over: drop the latch. Doing it here rather than in a separate
        // effect keeps "what does the bar tick from" a single expression.
        if (!sharing) return null
        // Rule 2: an anchor in use is never revised.
        latchedMillis?.let { return it }
        val candidate = tapStartMillis ?: observedStartMillis ?: return null
        // Rule 1: a start in the future (the server clock running ahead of the
        // device's) would otherwise count DOWN to zero before it counted up.
        return candidate.coerceAtMost(nowMillis)
    }

    /** Elapsed time for a latched anchor. Never negative. */
    fun elapsedMillis(anchorMillis: Long, nowMillis: Long): Long =
        (nowMillis - anchorMillis).coerceAtLeast(0L)
}

/**
 * Process-scoped holder for the live-session bar's latched start.
 *
 * Process-scoped for the same reason
 * [com.kungsbackacarcommunity.app.live.LiveShareStart] and
 * `SingleSessionRecording` are: a live session outlives the Activity (the
 * foreground publisher keeps running), so an Activity recreation mid-session must
 * not lose the anchor and re-latch onto the server-clock fallback — which would
 * make the readout jump by the skew on a rotation. All the logic is in
 * [LiveSessionElapsed]; this only stores the value.
 */
object LiveSessionAnchor {
    private val state = MutableStateFlow<Long?>(null)

    /** The latched start, or null when no session is being shown. */
    val startMillis: StateFlow<Long?> = state.asStateFlow()

    /** Stores the anchor [LiveSessionElapsed.anchorMillis] resolved (null clears it). */
    fun set(anchorMillis: Long?) {
        state.value = anchorMillis
    }

    /** Drops the anchor unconditionally (sign-out / account switch). */
    fun clear() {
        state.value = null
    }
}
