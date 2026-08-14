package com.kungsbackacarcommunity.app.crownhunt

/**
 * Why the Collect button is, or is not, live — and the ONLY place in this
 * feature that looks at speed.
 *
 * ## The stance
 *
 * Kronjakt must never be a reason to touch the phone while the car is moving.
 * The backend enforces that with a stationary check a client cannot argue with
 * (two fixes, both slow, and a speed the SERVER derives from the pair). This
 * type is the client half: it decides in advance what the server would say, so
 * the button is honestly disabled with a reason instead of looking live and then
 * refusing.
 *
 * ## What this is NOT
 *
 * There is no speed anywhere in the UI. No speedometer, no "collected at
 * X km/h", no arrival time, no streak for getting there first, nothing that
 * makes moving quickly worth anything. [Moving] carries no number at all, on
 * purpose: telling a driver "you are doing 9 km/h, get under 7.2" is an
 * invitation to watch the number and shave it, which is precisely the behaviour
 * the rule exists to prevent. The message is a flat "stop the car first".
 *
 * Nor does it nag. This is a pure function over the state the map already has;
 * nothing here schedules an alert, a sound, a vibration or a flash. A driver who
 * passes a crown at speed sees a static popup they chose to open, and nothing
 * else happens.
 *
 * Pure Kotlin, so the whole enablement matrix (distance x speed x flag) is
 * pinned by unit tests rather than by trying it in a moving car.
 */
sealed interface CrownCollectState {
    /** Close enough and stopped — the button is live. */
    data object Ready : CrownCollectState

    /**
     * Within the world but outside the crown's radius.
     *
     * @property distanceMeters how far away, for a "x m to go" line. This is a
     *   DISTANCE, not a speed or an ETA — knowing a crown is 300 m away is what
     *   lets someone decide to walk over after they have parked.
     */
    data class TooFar(val distanceMeters: Double) : CrownCollectState

    /**
     * Close enough, but the device is moving faster than
     * [CrownSpawnLimits.MAX_COLLECT_SPEED_MPS].
     *
     * Deliberately carries no speed value — see this file's KDoc.
     */
    data object Moving : CrownCollectState

    /** No usable position fix, so neither distance nor stillness is known. */
    data object NoPosition : CrownCollectState

    /**
     * Close enough and not moving, but the two-fix stationary PROOF (or a settled
     * position) is not ready YET.
     *
     * This is the honest name for the few seconds the server's dwell rule needs:
     * a claim wants two fixes at least [CrownSpawnLimits.MIN_DWELL_SECONDS] apart,
     * so for a moment after arriving there is no partner old enough. The button is
     * disabled and SAYS "confirming you're stopped" instead of looking live and
     * silently refusing with `NeedsPosition` — the re-tap loop that made
     * collection feel like "tap, tap, then it works".
     *
     * @property secondsRemaining a whole-second hint for the button, or null when
     *   the wait is a settling fix rather than a countable dwell.
     */
    data class Confirming(val secondsRemaining: Int?) : CrownCollectState

    /**
     * The automatic half of Kronjakt is switched off.
     *
     * Unreachable through the normal UI (the whole layer is gated before a
     * popup can exist), and modelled anyway so a flag that flips WHILE a popup
     * is open disables the button instead of firing a call the backend would
     * refuse with `feature_disabled`.
     */
    data object FeatureOff : CrownCollectState
}

object CrownCollectGate {
    /**
     * The button is live only in [CrownCollectState.Ready]. Written as an
     * exhaustive `when` rather than `state == Ready` so a new state cannot
     * default to "enabled" by being forgotten — the compiler will demand a
     * decision for it.
     */
    fun isCollectEnabled(state: CrownCollectState): Boolean =
        when (state) {
            CrownCollectState.Ready -> true
            is CrownCollectState.TooFar -> false
            CrownCollectState.Moving -> false
            CrownCollectState.NoPosition -> false
            is CrownCollectState.Confirming -> false
            CrownCollectState.FeatureOff -> false
        }

    /**
     * Decides the state for one crown.
     *
     * @param featureEnabled the `crownHuntSpawn` flag. Checked FIRST, so a
     *   disabled feature can never be reported as "move closer" — the reason
     *   shown is the real one.
     * @param distanceMeters server-shaped distance from the device to the crown,
     *   or null when there is no fix.
     * @param speedMetersPerSecond the device's reported speed, or null when the
     *   platform did not supply one.
     * @param collectRadiusMeters the crown's own radius, defaulting to the
     *   mirrored server constant. Put through
     *   [CrownSpawnLimits.resolveCollectRadiusMeters], so a broken or absurd
     *   value narrows the gate back to 75 m instead of widening it.
     *
     * ## Why an unknown speed does not block
     *
     * A device that never reports speed (some fixes simply carry none) would be
     * permanently locked out if null meant "assume moving" — the feature would
     * be broken for those users with no way to tell. Null therefore means "no
     * information", exactly as the backend treats it, and the decision falls to
     * the server: it derives its own speed from the two fixes and refuses the
     * claim if the pair says the car was rolling. So the honest description of
     * this branch is not "we allow moving collection when speed is unknown" —
     * it is "we let the request through to the check that cannot be fooled".
     *
     * A NEGATIVE or non-finite speed is treated as unknown for the same reason;
     * it is a broken reading, and inventing a verdict from it would be worse
     * than deferring.
     *
     * ## Why a "confirming" step, and why it cannot lock anyone out
     *
     * @param dwellProofReady whether the caller already holds a usable two-fix
     *   stationary proof. When the member is in range and stopped but this is
     *   still false, the state is [CrownCollectState.Confirming] rather than
     *   [CrownCollectState.Ready] — an honest "hold on a moment" in place of a
     *   button that looks live and then refuses. Defaults to true, so every caller
     *   that does not track the pair (and every existing test) keeps the old
     *   behaviour.
     * @param dwellSecondsRemaining a hint for the confirming button, passed
     *   straight through to [CrownCollectState.Confirming].
     * @param accuracyMeters the current fix's reported radius, if any. A KNOWN
     *   accuracy worse than the collect radius means the distance reading cannot
     *   be trusted, so the state is [CrownCollectState.Confirming] until GPS
     *   settles — but a null/absent accuracy defers (it never blocks), exactly as
     *   an unknown speed does, so a device that never reports accuracy is never
     *   locked out.
     */
    fun evaluate(
        featureEnabled: Boolean,
        distanceMeters: Double?,
        speedMetersPerSecond: Double?,
        collectRadiusMeters: Double = CrownSpawnLimits.COLLECT_RADIUS_METERS,
        dwellProofReady: Boolean = true,
        dwellSecondsRemaining: Int? = null,
        accuracyMeters: Double? = null,
    ): CrownCollectState {
        if (!featureEnabled) return CrownCollectState.FeatureOff
        if (distanceMeters == null || !distanceMeters.isFinite()) {
            return CrownCollectState.NoPosition
        }
        // Belt and braces: [CrownSpawn.collectRadiusMeters] is already sanitized
        // at the parse boundary, but this is a public entry point with a default
        // and the SAME resolver runs here, so a caller that hands over a raw
        // field gets the backend's answer rather than a second opinion.
        val radius = CrownSpawnLimits.resolveCollectRadiusMeters(collectRadiusMeters)
        if (distanceMeters > radius) return CrownCollectState.TooFar(distanceMeters)
        // Distance first, THEN stillness: a crown 5 km away is "too far"
        // whatever the car is doing, and reporting it as "stop the car" would be
        // a nonsense instruction that also happens to be advice to stop on a
        // road for no reason.
        if (isMoving(speedMetersPerSecond)) return CrownCollectState.Moving
        // In range and stopped, but the position is too coarse to trust the
        // distance yet — wait for GPS to settle rather than sending a pair one bad
        // sample would fail as `outside_radius`. Known-and-too-coarse only; an
        // absent accuracy defers, like an absent speed.
        if (isPositionUnsettled(accuracyMeters, radius)) {
            return CrownCollectState.Confirming(dwellSecondsRemaining)
        }
        // In range and stopped, but no two-fix proof has aged in yet — the honest
        // "confirming you're stopped" step that removes the re-tap loop.
        if (!dwellProofReady) return CrownCollectState.Confirming(dwellSecondsRemaining)
        return CrownCollectState.Ready
    }

    /**
     * Whether a KNOWN accuracy is too coarse to trust the distance against
     * [radius]. Null, non-finite and negative all answer false — an unknown
     * accuracy defers to the server rather than blocking, so it can never lock a
     * device out.
     */
    fun isPositionUnsettled(accuracyMeters: Double?, radius: Double): Boolean {
        if (accuracyMeters == null) return false
        if (!accuracyMeters.isFinite()) return false
        if (accuracyMeters < 0.0) return false
        return accuracyMeters > radius
    }

    /**
     * Whether [speedMetersPerSecond] is a KNOWN speed above the ceiling. Null,
     * non-finite and negative all answer false — see [evaluate]'s KDoc.
     */
    fun isMoving(speedMetersPerSecond: Double?): Boolean {
        if (speedMetersPerSecond == null) return false
        if (!speedMetersPerSecond.isFinite()) return false
        if (speedMetersPerSecond < 0.0) return false
        return speedMetersPerSecond > CrownSpawnLimits.MAX_COLLECT_SPEED_MPS
    }

    /**
     * Whether two fixes are a usable stationary PROOF for
     * `crownHunt.claimSpawn` — both inside the dwell window, in order.
     *
     * The client checks this only so it can wait for a good second fix instead
     * of spending a round-trip to be told `must_be_stationary` by arithmetic it
     * could have done itself. The server re-derives all of it.
     */
    fun isDwellProofUsable(previous: CrownFix, current: CrownFix): Boolean {
        val elapsedMs = current.recordedAtMillis - previous.recordedAtMillis
        if (elapsedMs <= 0L) return false
        val elapsedSeconds = elapsedMs / 1000.0
        return elapsedSeconds >= CrownSpawnLimits.MIN_DWELL_SECONDS &&
            elapsedSeconds <= CrownSpawnLimits.MAX_DWELL_SECONDS
    }
}
