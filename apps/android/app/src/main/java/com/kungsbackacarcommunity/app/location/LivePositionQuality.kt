package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.drives.DriveSummary
import kotlin.math.abs

/**
 * What happened to one live-position fix, and — when it was not used — WHY.
 *
 * The reason is the point: a boolean accept/reject tells a future investigation
 * nothing, and the whole reason this slice exists is that "my marker jumped 1-2
 * km while I was standing still" was unanalysable after the fact. Every
 * non-[ACCEPT] value is recorded into a bounded [LivePositionRejectionLog].
 */
enum class LiveFixVerdict {
    /** Use the fix (publish it, or make it the marker's next target). */
    ACCEPT,

    /** Not a position at all: NaN/infinite, or outside the WGS-84 range. */
    REJECT_UNDRAWABLE,

    /**
     * The fix's OWN reported accuracy is worse than
     * [LivePositionQuality.MAX_USABLE_ACCURACY_METERS] — a network/cell-derived
     * sample, not a GPS one. This is the verdict that catches a stationary
     * phone reporting a kilometre-wide error.
     */
    REJECT_ACCURACY,

    /**
     * Moving from the last used fix to this one would need more than
     * [DriveSummary.MAX_PLAUSIBLE_SPEED_MPS]. Powerful at the publisher (which
     * sees EVERY fix, ~5 s apart) and weak at a consumer (which only sees
     * published ones, up to 3 min apart) — see the class docs below.
     */
    REJECT_SPEED,

    /** Not newer than the fix already in use; using it would drag time backwards. */
    REJECT_NOT_NEWER,

    /**
     * A large displacement that nothing corroborates yet. NOT a rejection of the
     * position — it is held, and the very next fix either confirms it (and it is
     * accepted) or contradicts it (and it is dropped, never having been drawn).
     */
    HOLD_UNCORROBORATED,
}

/**
 * Whether a live-position fix is good enough to publish / to draw, and why not.
 *
 * ## The bug this exists for
 * A convoy member watching Seb's marker saw it jump 1-2 km and back while his
 * phone was STATIONARY. Two independent gaps let that through:
 *
 * 1. **Accuracy was published but never read.** The Android publisher does send
 *    `accuracyMeters` ([BackgroundLocation.buildCoordinate]), the callable
 *    accepts it, and `buildLatestNode` writes it to `liveLocation/{uid}/latest`
 *    — but the reader
 *    ([com.kungsbackacarcommunity.app.live.FirebaseLiveLocationRepository]'s
 *    `toLiveMarker`) dropped it, so no consumer could tell a 5 m GPS fix from a
 *    1500 m cell-tower fix. A 1-2 km error on a parked phone is essentially
 *    ALWAYS a low-accuracy fix, and accuracy is the only signal that names it.
 *
 * 2. **Implied speed is distance / time, and the time is the problem.** The
 *    existing consumer filter
 *    ([com.kungsbackacarcommunity.app.map.LiveMarkerSmoothing]) rejects a fix
 *    implying more than [DriveSummary.MAX_PLAUSIBLE_SPEED_MPS] (~200 km/h)
 *    since the last accepted one. A PARKED publisher only writes on the
 *    [BackgroundLocation.STATIONARY_HEARTBEAT_MS] heartbeat — 3 minutes — so a
 *    consumer's time delta is 180 s and the speed rule does not bite until the
 *    jump exceeds **10 km**. Seb's 1-2 km jump implies ~8 m/s (~30 km/h): a
 *    perfectly ordinary town speed. It sailed straight through, exactly as
 *    designed, because the filter only catches jumps that are large RELATIVE TO
 *    ELAPSED TIME.
 *
 * ## Why the publisher is the better place to filter
 * The publisher's fused-location callback sees every fix at
 * [BackgroundLocation.UPDATE_INTERVAL_MS] (5 s) whether or not it publishes it.
 * Against a 5 s delta the very same speed rule has a ~278 m ceiling instead of a
 * 10 km one — so the identical constant, applied one layer earlier, is two
 * orders of magnitude sharper. Filtering here also fixes every consumer at once
 * (map, convoy overlay, off-screen arrows, focus fit) INCLUDING viewers running
 * an older build, and saves the callable round trip. The consumer keeps its own
 * (now accuracy-aware) rules as defence in depth against publishers we do not
 * control.
 *
 * ## Unknown is not bad
 * A null/non-finite accuracy means the platform did not say, not that the fix is
 * poor. Treating it as poor would make every user of an older publisher — and
 * anyone whose provider omits accuracy — silently invisible on rollout. Unknown
 * accuracy therefore passes the accuracy gate and is instead handled by the
 * weaker, evidence-based [CORROBORATION_TRIGGER_METERS] rule.
 *
 * Pure Kotlin: no Android, Firebase or Compose types, so every threshold in here
 * is exercised by JVM unit tests (`LivePositionQualityTest`).
 */
object LivePositionQuality {
    /**
     * At or below this a fix is trusted outright, and a large displacement needs
     * no corroboration.
     *
     * 50 m is not a fresh number: it is the backend's own
     * `POOR_ACCURACY_THRESHOLD_METERS` (functions/src/crownHunt/crown-hunt-risk.ts),
     * the line the anti-fraud scorer already draws between "a GPS fix" and
     * "something coarser". A good open-sky fix is under 20 m; 50 m is a
     * comfortable ceiling for one taken through a windscreen in town.
     */
    const val TRUSTED_ACCURACY_METERS = 50.0

    /**
     * Above this a fix is not published and not drawn at all.
     *
     * Beyond ~100 m a fix is no longer GPS-derived — it is wifi- or cell-tower-
     * derived, and a cell-only fix routinely reports accuracy in the hundreds to
     * thousands of metres. That is precisely the shape of Seb's 1-2 km jump, so
     * this is the guard that would have caught it. 200 m rather than 100 m
     * leaves deliberate headroom: a genuine GPS fix under heavy multipath (urban
     * canyon, tunnel mouth, dense forest) can honestly report 100-150 m and is
     * still worth drawing — it is off by a street, not by a suburb.
     *
     * The cost of rejecting is a marker that goes STALE rather than one that
     * goes WRONG. That is the right trade: the staleness machinery already
     * exists on both sides (`ConvoyArrowPlanner.STALE_AFTER_MS` on the client,
     * `LATEST_STALE_MINUTES` on the server sweep), so "we do not currently know
     * where they are" is a state the app already renders honestly, whereas
     * "they are 2 km that way" is a state it renders confidently and falsely.
     */
    const val MAX_USABLE_ACCURACY_METERS = 200.0

    /**
     * A displacement larger than this, from a fix that is not
     * [TRUSTED_ACCURACY_METERS]-accurate, is held for corroboration instead of
     * being used immediately.
     *
     * Chosen so ordinary driving never reaches it: at 110 km/h a 5 s publish
     * cadence covers ~150 m, and even a 200 km/h fix covers ~278 m. 500 m is
     * comfortably above both, so a motorway convoy never enters this path. What
     * DOES reach it is a single sample that claims the car moved half a
     * kilometre between two ordinary fixes without moving fast enough to be
     * impossible — the exact signature of the outlier this fixes.
     */
    const val CORROBORATION_TRIGGER_METERS = 500.0

    /**
     * How close the NEXT fix must land to a held candidate for it to count as
     * corroborated. Wide enough that a genuinely moving car (which keeps moving
     * during the hold) still confirms its own jump, tight enough that a fix
     * bouncing back to the true position does not.
     */
    const val CORROBORATION_RADIUS_METERS = 250.0

    /**
     * Whether a coordinate can be used at all: finite and inside the WGS-84
     * range. A NaN/infinite latitude or longitude is not a position — publishing
     * one would poison every consumer, and projecting one yields an undefined
     * pixel.
     */
    fun isDrawable(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            abs(latitude) <= 90.0 &&
            abs(longitude) <= 180.0

    /**
     * The fix's accuracy as a usable number, or null for "the platform did not
     * say". A negative or non-finite value is not an accuracy, so it is folded
     * into null (unknown) rather than treated as an extreme in either direction.
     */
    fun normalizedAccuracy(accuracyMeters: Double?): Double? =
        accuracyMeters?.takeIf { it.isFinite() && it >= 0.0 }

    /** False only when accuracy is KNOWN and worse than [MAX_USABLE_ACCURACY_METERS]. */
    fun isUsableAccuracy(accuracyMeters: Double?): Boolean {
        val accuracy = normalizedAccuracy(accuracyMeters) ?: return true
        return accuracy <= MAX_USABLE_ACCURACY_METERS
    }

    /** True only when accuracy is KNOWN and at least [TRUSTED_ACCURACY_METERS] good. */
    fun isTrustedAccuracy(accuracyMeters: Double?): Boolean {
        val accuracy = normalizedAccuracy(accuracyMeters) ?: return false
        return accuracy <= TRUSTED_ACCURACY_METERS
    }

    /**
     * Metres per second implied by covering [distanceMeters] in [deltaMillis], or
     * null when the interval is unknown or non-positive — an unknown interval is
     * not evidence of a glitch, and dividing by it would invent one.
     */
    fun impliedSpeedMps(distanceMeters: Double, deltaMillis: Long?): Double? {
        if (deltaMillis == null || deltaMillis <= 0L) return null
        val speed = distanceMeters / (deltaMillis / 1000.0)
        return speed.takeIf { it.isFinite() }
    }

    /** True when the implied speed is faster than any car ([DriveSummary.MAX_PLAUSIBLE_SPEED_MPS]). */
    fun isImplausibleSpeed(distanceMeters: Double, deltaMillis: Long?): Boolean {
        val speed = impliedSpeedMps(distanceMeters, deltaMillis) ?: return false
        return speed > DriveSummary.MAX_PLAUSIBLE_SPEED_MPS
    }

    /**
     * Verdict on a fix the SHARING device is about to publish, given the last fix
     * it accepted from its own provider.
     *
     * [previousAtMillis] and the previous coordinate are the last OBSERVED
     * accepted fix, not the last PUBLISHED one: the ~5 s observation cadence is
     * what makes the speed rule sharp (see the class docs), and the publish
     * throttle deliberately drops most of those samples.
     *
     * There is no corroboration branch here. A publisher that held a fix would
     * have to hold the NEXT one against a candidate it never adopted, which is
     * the consumer's problem to solve because the consumer has no better signal;
     * the publisher has a 5 s clock and does not need one.
     *
     * A non-positive interval is treated as UNKNOWN (the speed check is skipped)
     * rather than as a rejection. Fix timestamps come from the platform, and a
     * clock correction that moved one backwards would otherwise pin
     * `previousAtMillis` in the future and reject every subsequent fix forever —
     * a permanent, silent end to sharing. The movement/heartbeat throttle
     * ([BackgroundLocation.shouldPublish]) already suppresses duplicates.
     */
    fun judgePublish(
        previousLatitude: Double?,
        previousLongitude: Double?,
        previousAtMillis: Long?,
        latitude: Double,
        longitude: Double,
        atMillis: Long,
        accuracyMeters: Double?,
    ): LiveFixVerdict {
        if (!isDrawable(latitude, longitude)) return LiveFixVerdict.REJECT_UNDRAWABLE
        if (!isUsableAccuracy(accuracyMeters)) return LiveFixVerdict.REJECT_ACCURACY
        if (previousLatitude == null || previousLongitude == null) return LiveFixVerdict.ACCEPT
        val distance =
            DriveSummary.haversineMetres(previousLatitude, previousLongitude, latitude, longitude)
        val delta = previousAtMillis?.let { atMillis - it }
        if (isImplausibleSpeed(distance, delta)) return LiveFixVerdict.REJECT_SPEED
        return LiveFixVerdict.ACCEPT
    }

    /**
     * Verdict on a fix a VIEWER received for someone else's marker, given the
     * last position it accepted for them and any candidate currently held.
     *
     * Layered deliberately, cheapest and most certain first:
     * 1. undrawable → [LiveFixVerdict.REJECT_UNDRAWABLE];
     * 2. accuracy known and worse than [MAX_USABLE_ACCURACY_METERS] →
     *    [LiveFixVerdict.REJECT_ACCURACY] (the rule that catches Seb's case);
     * 3. not newer → [LiveFixVerdict.REJECT_NOT_NEWER] (Realtime Database
     *    re-delivers an unchanged node, and an out-of-order sample would drag the
     *    marker backwards);
     * 4. implied speed impossible → [LiveFixVerdict.REJECT_SPEED];
     * 5. a large displacement from a fix that is not TRUSTED-accurate →
     *    corroborate: accept if it lands within [CORROBORATION_RADIUS_METERS] of
     *    the held candidate, otherwise hold this one instead.
     *
     * Rule 5 is what closes the time-delta hole for publishers that send no
     * accuracy at all: it is a statement about DISTANCE and EVIDENCE, so no
     * amount of elapsed time makes a single unsupported kilometre-scale jump
     * acceptable. It costs a genuinely relocated member exactly one fix of delay
     * (~5 s while moving) and costs a member with a good fix nothing at all,
     * because rule 5 does not apply to them.
     */
    fun judgeIncoming(
        previousLatitude: Double,
        previousLongitude: Double,
        previousRecordedAtMillis: Long?,
        pendingLatitude: Double?,
        pendingLongitude: Double?,
        latitude: Double,
        longitude: Double,
        recordedAtMillis: Long?,
        accuracyMeters: Double?,
    ): LiveFixVerdict {
        if (!isDrawable(latitude, longitude)) return LiveFixVerdict.REJECT_UNDRAWABLE
        if (!isUsableAccuracy(accuracyMeters)) return LiveFixVerdict.REJECT_ACCURACY
        val delta =
            if (previousRecordedAtMillis != null && recordedAtMillis != null) {
                recordedAtMillis - previousRecordedAtMillis
            } else {
                null
            }
        if (delta != null && delta <= 0L) return LiveFixVerdict.REJECT_NOT_NEWER
        val distance =
            DriveSummary.haversineMetres(previousLatitude, previousLongitude, latitude, longitude)
        if (!distance.isFinite()) return LiveFixVerdict.REJECT_UNDRAWABLE
        if (isImplausibleSpeed(distance, delta)) return LiveFixVerdict.REJECT_SPEED
        if (distance <= CORROBORATION_TRIGGER_METERS || isTrustedAccuracy(accuracyMeters)) {
            return LiveFixVerdict.ACCEPT
        }
        if (pendingLatitude != null && pendingLongitude != null) {
            val fromPending =
                DriveSummary.haversineMetres(pendingLatitude, pendingLongitude, latitude, longitude)
            if (fromPending.isFinite() && fromPending <= CORROBORATION_RADIUS_METERS) {
                return LiveFixVerdict.ACCEPT
            }
        }
        return LiveFixVerdict.HOLD_UNCORROBORATED
    }
}
