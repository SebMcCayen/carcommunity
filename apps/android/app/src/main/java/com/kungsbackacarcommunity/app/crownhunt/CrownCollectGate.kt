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
     * Close enough and not moving, but the current position fix is too COARSE to
     * trust the distance against the collect radius — the GPS has not settled yet.
     *
     * Split out of [Confirming] on purpose. The two waits feel identical from a
     * chair but are different problems: [Confirming] is "stand still a moment
     * longer so a second fix ages in", which the member fixes by doing nothing;
     * this one is "your position is still fuzzy", which resolves on its own as the
     * signal sharpens and which no amount of standing-still-longer speeds up. A
     * single "confirming you're stopped" for both left a member who was in range,
     * stopped, AND had already dwelled long enough staring at a button that named
     * a wait that was over — with no countdown and no clue that GPS, not stillness,
     * was the hold-up. This carries its own label ("waiting for a better GPS
     * signal") so the reason shown is the real one, and — because it is now its own
     * state — [Confirming] is only ever the DWELL wait, so its seconds hint stops
     * collapsing to null.
     *
     * The button is disabled here exactly as it is for [Confirming]: nothing has
     * gone wrong, so it is not a refusal, but the distance cannot be trusted enough
     * to collect on yet.
     */
    data object WaitingForSignal : CrownCollectState

    /**
     * Close enough and not moving, but the two-fix stationary PROOF is not ready
     * YET.
     *
     * This is the honest name for the few seconds the server's dwell rule needs:
     * a claim wants two fixes at least [CrownSpawnLimits.MIN_DWELL_SECONDS] apart,
     * so for a moment after arriving there is no partner old enough. The button is
     * disabled and SAYS "confirming you're stopped" instead of looking live and
     * silently refusing with `NeedsPosition` — the re-tap loop that made
     * collection feel like "tap, tap, then it works".
     *
     * A coarse-GPS wait is [WaitingForSignal], not this, so [Confirming] is now
     * only ever the DWELL wait — which ALWAYS has a countable answer. The seconds
     * hint is therefore no longer nullable-in-practice: it stops disappearing the
     * moment GPS is the reason rather than dwell.
     *
     * @property secondsRemaining a whole-second hint for the button, or null when
     *   no estimate is available (kept nullable for callers that do not track one).
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
            CrownCollectState.WaitingForSignal -> false
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
     * @param speedMetersPerSecond the MOVEMENT speed to judge stillness from.
     *   Callers with a proof pair should pass [movementSpeedMps] of it, NOT one
     *   fix's raw instantaneous `Location.speed`: a single stationary GPS sample
     *   can spike above the ceiling and would wrongly read [CrownCollectState.Moving]
     *   ("stop the car first") for a parked member. The window-derived value does
     *   not, and matches the server's speed gate. Null when the platform supplied
     *   no usable movement signal — treated as "no information", never as moving.
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
     *   be trusted, so the state is [CrownCollectState.WaitingForSignal] until GPS
     *   settles — but a null/absent accuracy defers (it never blocks), exactly as
     *   an unknown speed does, so a device that never reports accuracy is never
     *   locked out. Feed this the FRESHEST fix's accuracy, not a pinned proof
     *   fix's: the gate is "is the live signal good enough to trust the distance",
     *   and only the newest reading answers that, so an improving accuracy flips
     *   the state the moment it arrives instead of after a close/reopen.
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
        // absent accuracy defers, like an absent speed. Its OWN state, not a
        // dwell-flavoured Confirming: the reason is GPS, not stillness, and saying
        // so is the difference between a member standing usefully still and one
        // standing still at a wait that will not end until the signal sharpens.
        if (isPositionUnsettled(accuracyMeters, radius)) {
            return CrownCollectState.WaitingForSignal
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
     * The movement speed to hand [evaluate] as `speedMetersPerSecond` — derived
     * from DISPLACEMENT over the two proof fixes rather than one jittery
     * instantaneous reading, and shaped to match the SERVER's speed gate exactly.
     *
     * ## Why not the raw instantaneous reading
     *
     * A stationary phone's reported GPS speed jitters: in poor sky-view it can
     * spike well above [CrownSpawnLimits.MAX_COLLECT_SPEED_MPS] on a single
     * sample while the device has not moved a centimetre. Declaring
     * [CrownCollectState.Moving] off one such sample is declaring noise — it is
     * exactly why a genuinely parked member kept seeing "stop the car first" and
     * had to tap for the better part of a minute until a good sample happened to
     * land. Two fixes a few seconds apart do not lie the same way: real driving
     * covers real ground between them, GPS jitter does not.
     *
     * ## What it returns, and why it agrees with the server
     *
     * `crownHunt.claimSpawn` refuses a claim on speed when EITHER the
     * displacement-derived speed exceeds the ceiling OR BOTH fixes independently
     * report motion (a single reported spike is tolerated server-side too). This
     * returns the larger of:
     *  - the DERIVED speed — server-shaped metres between the two fixes divided by
     *    the seconds between them (the same quantity the server computes), and
     *  - the CORROBORATED instantaneous speed — the smaller of the two reported
     *    speeds, but only when BOTH are [isMoving]; otherwise it contributes 0, so
     *    one spike can never raise the result.
     *
     * so [isMoving] over this value is true in precisely the cases the server
     * would reject on speed, and false for a parked phone whatever a single sample
     * spikes to. The client therefore never shows Moving where the server would
     * accept, nor Ready where the server would refuse `must_be_stationary`.
     *
     * Returns null — "no information, defer" — when there is no usable pair yet
     * (the member has only just arrived, or a fix is missing): the button is
     * [CrownCollectState.Confirming] then anyway, and the server still re-derives
     * everything from the pair actually submitted, so a moving driver with no
     * stationary pair is never enabled.
     *
     * Pure: the displacement is the same haversine the rest of the feature uses,
     * so this is unit-tested against jitter series rather than in a moving car.
     */
    fun movementSpeedMps(current: CrownFix?, previous: CrownFix?): Double? {
        if (current == null || previous == null) return null
        val elapsedMs = current.recordedAtMillis - previous.recordedAtMillis
        if (elapsedMs <= 0L) return null
        val elapsedSeconds = elapsedMs / 1000.0
        val movedMeters =
            CrownSpawnQuery.distanceMeters(
                previous.latitude,
                previous.longitude,
                current.latitude,
                current.longitude,
            )
        if (!movedMeters.isFinite()) return null
        val derived = movedMeters / elapsedSeconds
        // A single reported spike is jitter and must not count; only two fixes
        // that BOTH report motion corroborate a sustained instantaneous reading,
        // mirroring the server's both-fixes rule. minOf keeps it the LOWER of the
        // two, so the corroborated value can only ever be as fast as the slower
        // fix — it cannot be inflated by one high sample.
        val corroborated =
            if (isMoving(current.speedMetersPerSecond) && isMoving(previous.speedMetersPerSecond)) {
                minOf(current.speedMetersPerSecond!!, previous.speedMetersPerSecond!!)
            } else {
                0.0
            }
        val speed = maxOf(derived, corroborated)
        return if (speed.isFinite()) speed else null
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
