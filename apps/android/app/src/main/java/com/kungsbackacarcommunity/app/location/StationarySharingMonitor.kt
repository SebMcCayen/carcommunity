package com.kungsbackacarcommunity.app.location

/**
 * The stationary-sharing cost/safety state machine, as pure Kotlin so every
 * transition (moving → parked → prompted → auto-stop, and every reset) is
 * JVM-unit-testable without a device, GPS or the Android framework.
 *
 * ## Why it exists
 * A phone left sharing while its owner has parked and walked away keeps a live
 * marker up — a privacy cost the owner did not intend, and (with the heartbeat)
 * a small ongoing data cost. This monitor detects "parked for a while", asks
 * once whether the user is still sharing, and — if nothing answers — stops the
 * session on the same server path a manual stop uses.
 *
 * ## The rule (Seb-approved timeframes, all named constants)
 * - **Stationary** = successive fixes stay within [MOVEMENT_THRESHOLD_METERS] of
 *   a fixed anchor. The anchor is set on the first fix and re-set the moment a
 *   fix lands beyond the threshold, so genuine driving continuously resets it.
 * - After [STATIONARY_PROMPT_MS] (10 min) parked → **[StationaryDecision.Prompt]**:
 *   ask "still sharing your location?".
 * - If not answered within [STATIONARY_AUTOSTOP_MS] (5 min) MORE → **AutoStop**
 *   (≈15 min parked with no response). Same stop path as a manual stop.
 * - Any movement beyond the threshold, OR an explicit [answerStillSharing], cancels
 *   a pending prompt/stop and starts the clock over.
 *
 * ## Convoy members are EXEMPT (session↔convoy coupling)
 * A convoy member shares through this very same live session — but a convoy is
 * precisely where standing still is normal (a meet-up, a fuel stop, waiting for
 * stragglers), and Seb's invariant is "an active convoy always has a live session
 * ongoing". Auto-stopping a convoy member's session would strand the convoy
 * ACTIVE with that member's live-share dead — the exact decoupling behind the
 * "convoy ended on its own after ~20 min while I stood still" and "my location
 * stopped but the convoy bar stayed up" reports. So while the observed session is
 * a convoy-auto session, [decide] is called with `inConvoy = true` and never
 * prompts or auto-stops: the convoy keeps the session alive regardless of
 * movement. The session's own 6h hard cap ([LiveSharingLifecycle]) remains the
 * backstop, so a forgotten phone in a never-ended convoy still stops there.
 *
 * The monitor holds NO clock of its own: the caller supplies `nowMillis` on every
 * call, exactly like [LiveSharingLifecycle].
 */
class StationarySharingMonitor(
    private val movementThresholdMeters: Double = MOVEMENT_THRESHOLD_METERS,
    private val promptAfterMillis: Long = STATIONARY_PROMPT_MS,
    private val autoStopAfterPromptMillis: Long = STATIONARY_AUTOSTOP_MS,
) {
    private var anchorLatitude: Double? = null
    private var anchorLongitude: Double? = null

    /** When the current stationary stretch began; null while never-yet-anchored. */
    private var stationarySinceMillis: Long? = null

    /** Set once the prompt has been surfaced for the current stationary stretch. */
    private var promptedAtMillis: Long? = null

    /**
     * Folds one location fix into the monitor. Movement beyond the threshold from
     * the current anchor re-anchors here and clears any pending prompt/stop; a fix
     * within the threshold leaves the stationary clock running.
     *
     * Returns nothing — call [decide] with the same (or a later) clock to read the
     * resulting decision. Kept separate so a service ticker can re-evaluate against
     * a newer clock without a fresh fix (that is what fires the auto-stop while the
     * device sits still and stops producing distinct fixes).
     */
    fun onFix(latitude: Double, longitude: Double, nowMillis: Long) {
        val anchorLat = anchorLatitude
        val anchorLon = anchorLongitude
        if (anchorLat == null || anchorLon == null) {
            anchor(latitude, longitude, nowMillis)
            return
        }
        val moved =
            BackgroundLocation.distanceMeters(anchorLat, anchorLon, latitude, longitude)
        if (moved >= movementThresholdMeters) {
            // Genuine movement: re-anchor and restart the stationary clock. Any
            // pending prompt/auto-stop is cancelled — the user is clearly active.
            anchor(latitude, longitude, nowMillis)
        }
        // Within the threshold: still parked. Leave stationarySince/prompted as-is.
    }

    /**
     * The user answered the "still sharing?" prompt affirmatively (or moved via the
     * UI). Treated exactly like fresh movement: the stationary clock restarts from
     * [nowMillis] and the prompt is cleared, so they get another full quiet window
     * before being asked again.
     */
    fun answerStillSharing(nowMillis: Long) {
        stationarySinceMillis = nowMillis
        promptedAtMillis = null
        // Keep the current anchor: they are still where they were, just engaged.
    }

    /**
     * The decision for [nowMillis], given the folded fixes so far. Pure and
     * idempotent — calling it does not advance any state EXCEPT latching that the
     * prompt has now been shown (so a [StationaryDecision.Prompt] is emitted once,
     * then the monitor waits out the auto-stop window rather than re-prompting).
     */
    fun decide(nowMillis: Long, inConvoy: Boolean = false): StationaryDecision {
        // Convoy↔session coupling: a member sharing THROUGH a convoy-auto session
        // is never stationary-prompted or auto-stopped — standing still in a
        // convoy is expected, and stopping the broadcast would leave the convoy
        // active with a dead session.
        //
        // Also CLEAR any prompt that was already latched BEFORE the convoy began:
        // without this, a member who parked solo (prompt latched), then joined a
        // convoy, then LEFT while still parked would carry the stale
        // promptedAtMillis across — and the first post-leave decision would jump
        // straight to AutoStop (now - promptedAt >= grace) instead of prompting
        // once. Clearing it here restarts the prompt-once cycle: the stationary
        // clock keeps running, so on leaving they get a single fresh Prompt (never
        // an immediate stop). See this class's KDoc.
        if (inConvoy) {
            promptedAtMillis = null
            return StationaryDecision.None
        }
        val since = stationarySinceMillis ?: return StationaryDecision.None
        val stationaryFor = nowMillis - since
        if (stationaryFor < promptAfterMillis) return StationaryDecision.None

        val promptedAt = promptedAtMillis
        if (promptedAt == null) {
            // First crossing of the 10-min line: surface the prompt and latch it.
            promptedAtMillis = nowMillis
            return StationaryDecision.Prompt
        }
        if (nowMillis - promptedAt >= autoStopAfterPromptMillis) {
            return StationaryDecision.AutoStop
        }
        // Prompt is up, still inside the grace window: nothing new to do.
        return StationaryDecision.None
    }

    /**
     * Clears all state back to "never seen a fix". Called when the service reuses
     * one instance for a fresh session (stopSelf() is async, so a new start can
     * land before onDestroy), so the previous session's parked time cannot bleed
     * into the new one and prompt early.
     */
    fun reset() {
        anchorLatitude = null
        anchorLongitude = null
        stationarySinceMillis = null
        promptedAtMillis = null
    }

    /**
     * Whether a stationary prompt is currently outstanding — it has been surfaced
     * (via a [StationaryDecision.Prompt]) and neither answered
     * ([answerStillSharing]) nor cancelled by movement yet. Lets the service keep
     * showing the prompt through the grace window, where [decide] returns
     * [StationaryDecision.None] and so cannot itself signal "still pending".
     */
    fun isPromptOutstanding(): Boolean = promptedAtMillis != null

    private fun anchor(latitude: Double, longitude: Double, nowMillis: Long) {
        anchorLatitude = latitude
        anchorLongitude = longitude
        stationarySinceMillis = nowMillis
        promptedAtMillis = null
    }

    companion object {
        /**
         * Reuses the publish throttle's movement threshold so "stationary" here
         * means exactly what "no worth-publishing movement" means there — one
         * definition of parked across the service.
         */
        const val MOVEMENT_THRESHOLD_METERS: Double = BackgroundLocation.MOVEMENT_THRESHOLD_METERS

        /** Parked this long → prompt "are you still sharing?". */
        const val STATIONARY_PROMPT_MS: Long = 10 * 60 * 1000L // 10 minutes

        /** Prompt unanswered this long after it appeared → auto-stop the session. */
        const val STATIONARY_AUTOSTOP_MS: Long = 5 * 60 * 1000L // 5 minutes
    }
}

/** What the stationary monitor wants the service to do at a given instant. */
sealed interface StationaryDecision {
    /** Moving, or parked but not long enough / already handled — do nothing. */
    data object None : StationaryDecision

    /** Parked past the threshold: show the "still sharing?" prompt (once). */
    data object Prompt : StationaryDecision

    /** Prompt went unanswered past the grace window: stop the session now. */
    data object AutoStop : StationaryDecision
}
