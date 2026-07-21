package com.kungsbackacarcommunity.app.drives

import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Drive recording domain (Phase 12 slice 12, write side). Pure Kotlin — no
 * Android or Firebase imports — so point accumulation, the callable payload
 * shape, and state transitions are JVM-unit-testable.
 *
 * Product rule: a recorded drive is stored ONLY after an explicit user action.
 * The recorder just accumulates GPS points in memory; nothing is persisted
 * until [buildSaveRequest] feeds the `drives-save` callable from the save
 * prompt. Backend computes ALL stats (distance / duration / average speed)
 * server-side from the submitted points — the client never computes or stores
 * distance or top speed. See functions/src/drives/drives-core.ts.
 */

/** A single recorded GPS fix. Mirrors the backend routePointSchema. */
data class RecordedPoint(
    val latitude: Double,
    val longitude: Double,
    /** Unix timestamp in milliseconds (backend field: timestampMs). */
    val timestampMs: Long,
)

/** UI-facing state machine for a recording session. */
sealed interface RecordingState {
    /** Nothing recorded yet; the start button is shown. */
    data object Idle : RecordingState

    /** Actively collecting fixes. Carries live counters for the UI. */
    data class Recording(
        val pointCount: Int,
        val elapsedMillis: Long,
    ) : RecordingState

    /** Recording stopped; the explicit save/discard prompt is shown. */
    data class PromptSave(
        val pointCount: Int,
        val elapsedMillis: Long,
    ) : RecordingState

    /** The save callable is in flight. */
    data object Saving : RecordingState

    /** The drive was saved. */
    data object Saved : RecordingState

    /** The drive was explicitly discarded — nothing was stored. */
    data object Discarded : RecordingState

    /**
     * The save callable failed; the prompt can be retried.
     *
     * @property code the callable status name that caused the failure
     *   (`PERMISSION_DENIED`, `UNAVAILABLE`, …), or null when the failure
     *   carried no status. [isPermanentRefusal] reads it to decide whether a
     *   retry is pointless, and the auto error report files it as the dedup
     *   code.
     */
    data class Failed(
        val pointCount: Int,
        val elapsedMillis: Long,
        val code: String? = null,
    ) : RecordingState {
        /**
         * True when the backend refused the save outright and retrying cannot
         * help. `drives-save` is member-gated (requireMemberActor), so a caller
         * without the activeMember entitlement gets `PERMISSION_DENIED` on every
         * attempt; telling them to "try again" would loop forever.
         */
        val isPermanentRefusal: Boolean
            get() = code == PERMISSION_DENIED
    }

    companion object {
        /** Firebase Functions status for a backend refusal (the member gate). */
        const val PERMISSION_DENIED: String = "PERMISSION_DENIED"
    }
}

/**
 * Whether a live-sharing session should ALSO record a drive for History.
 *
 * This mirrors the SAVE gate rather than the live-sharing one, and the
 * distinction is the whole point. Sharing your OWN position is FREE
 * (live-startSession only needs requireActiveActor), whereas SAVING a drive is
 * member-gated (drives-save uses requireMemberActor — and note that gate has no
 * admin/owner bypass, unlike requireMemberOrAdminActor). Gating the recording on
 * the sharing rule instead of the saving rule is what shipped in v0.8.0: a
 * non-member's session recorded a drive, the end-of-session prompt then forced a
 * save/discard choice, and Save could only ever fail with PERMISSION_DENIED.
 *
 * Member gating is currently DISABLED, so BOTH gates are open and everyone
 * records and saves. That does not make this function redundant — it makes it
 * load-bearing in the other direction: pass the GATE RESULT here
 * (MemberGating.allows(...)), never the raw activeMember entitlement, or the
 * app would refuse to record drives the backend would happily store. Whichever
 * way the switches are set, recording and saving must agree.
 *
 * [RecordDriveScreen] already applies exactly this rule to the manual recorder
 * (it refuses to record without passing the member gate); this keeps the
 * live-sharing entry point honest with it, so no recording is started that
 * cannot be saved.
 */
object DriveRecordingGate {
    /**
     * @param hasDrivesBackend false in a config-less/CI build with no drives
     *   repository — live sharing still works, nothing records.
     * @param passesMemberGate whether the caller passes the MEMBER GATE that
     *   drives-save enforces — NOT the raw `users/{uid}.activeMember`
     *   entitlement. While member gating is disabled
     *   (functions/src/shared/memberGating.ts, config/MemberGating.kt) that
     *   gate is open to any signed-in, non-suspended user, so callers must
     *   pass `MemberGating.allows(...)` here rather than the raw flag. Passing
     *   the raw flag would refuse to record drives the backend would save.
     */
    fun shouldRecord(hasDrivesBackend: Boolean, canShareLive: Boolean, passesMemberGate: Boolean): Boolean =
        hasDrivesBackend && canShareLive && passesMemberGate
}

/**
 * Accumulates GPS points during a single recording, capped at
 * [DriveRecorder.MAX_ROUTE_POINTS]. Once full it silently stops accepting
 * points (never crashes) so a very long drive is bounded exactly like the
 * backend expects (clients downsample/cap; the server rejects beyond the cap).
 *
 * A single recording carries one [sourceSessionId] UUID so repeat saves are
 * idempotent backend-side.
 *
 * Mutable and single-threaded by contract: the location controller feeds it
 * from the main looper while the screen owns it. Not thread-safe.
 */
class DriveRecorder(
    /** Stable identifier for idempotent retries of the same recording. */
    val sourceSessionId: String,
    private val startedAtMillis: Long,
) {
    private val points = ArrayList<RecordedPoint>()

    /** Millis of the last accepted fix, used to derive elapsed time. */
    private var lastPointMillis: Long = startedAtMillis

    val pointCount: Int
        get() = points.size

    /** True once the cap is reached and further points are dropped. */
    val isFull: Boolean
        get() = points.size >= MAX_ROUTE_POINTS

    /**
     * Adds a fix in arrival order. Out-of-order or capacity-exceeding points
     * are dropped rather than throwing: the backend requires monotonically
     * non-decreasing timestampMs, and GPS clocks can jitter backwards.
     */
    fun addPoint(point: RecordedPoint) {
        if (isFull) return
        if (points.isNotEmpty() && point.timestampMs < points.last().timestampMs) return
        points.add(point)
        if (point.timestampMs > lastPointMillis) lastPointMillis = point.timestampMs
    }

    /** Elapsed recording time given a current clock reading. */
    fun elapsedMillis(nowMillis: Long): Long =
        (nowMillis - startedAtMillis).coerceAtLeast(0L)

    /** Immutable snapshot of the accumulated points, in arrival order. */
    fun snapshot(): List<RecordedPoint> = points.toList()

    /**
     * Builds the exact `drives-save` callable payload as a `Map<String, Any?>`.
     *
     * `startedAt` / `endedAt` are ISO-8601 instant strings (the backend parses
     * them with `z.string().datetime()`); route points carry `latitude`,
     * `longitude`, `timestampMs`. `title` is included only when non-blank and
     * is trimmed and capped at [DRIVE_TITLE_MAX_LENGTH]. `sourceSessionId` is
     * always included for idempotency.
     *
     * @param endedAtMillis the wall-clock stop moment, used verbatim only for
     *   summary-only saves (no route points). When points exist the end time is
     *   taken from the last accepted fix's timestamp instead, so `endedAt` stays
     *   consistent with the last route point: route points use `Location.time`
     *   while the stop moment uses `System.currentTimeMillis`, and the two
     *   clocks can disagree. As a floor the value is still clamped to at least
     *   the last fix so it can never precede the last accepted point.
     */
    fun buildSaveRequest(
        title: String?,
        endedAtMillis: Long,
    ): Map<String, Any?> {
        // With route points, the last fix's timestamp is the authoritative end
        // time (same clock as the points). Without points, fall back to the
        // wall clock. Either way clamp to the last fix so endedAt never precedes
        // the last accepted point.
        val basis = if (points.isNotEmpty()) lastPointMillis else endedAtMillis
        // Guarantee endedAt is STRICTLY after startedAt: the backend guard
        // (functions/src/drives/drives-core.ts) rejects endedAt <= startedAt.
        // An instant stop (same-millisecond clock) or a stale first-fix
        // timestamp that never advances lastPointMillis would otherwise land on
        // startedAtMillis, so floor the result at startedAtMillis + 1 as well as
        // at the last accepted fix.
        val endedAt = maxOf(basis, lastPointMillis, startedAtMillis + 1)
        val request = LinkedHashMap<String, Any?>()
        request["startedAt"] = Instant.ofEpochMilli(startedAtMillis).toString()
        request["endedAt"] = Instant.ofEpochMilli(endedAt).toString()

        val trimmed = title?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            request["title"] = trimmed.take(DRIVE_TITLE_MAX_LENGTH)
        }

        if (points.isNotEmpty()) {
            request["routePoints"] =
                points.map { point ->
                    linkedMapOf<String, Any?>(
                        "latitude" to point.latitude,
                        "longitude" to point.longitude,
                        "timestampMs" to point.timestampMs,
                    )
                }
        }

        request["sourceSessionId"] = sourceSessionId
        return request
    }

    companion object {
        /** Backend DRIVE_TITLE_MAX_LENGTH parity. */
        const val DRIVE_TITLE_MAX_LENGTH = 200

        /** Backend MAX_ROUTE_POINTS parity (~5.5 h at 1 Hz). */
        const val MAX_ROUTE_POINTS = 20_000
    }
}

/**
 * A client-side estimate of a drive's headline stats, shown ONLY in the
 * end-of-session save/discard summary so the user sees distance / average speed
 * / duration before deciding. It is NEVER persisted: on save the backend
 * recomputes the authoritative figures from the submitted route points (see
 * functions/src/drives/drive-calculations.ts), and the History list shows those.
 * Fields are null when there is nothing to estimate (fewer than two fixes).
 */
data class DriveSummaryPreview(
    val distanceMeters: Double?,
    val durationSeconds: Long,
    val averageSpeedMetersPerSecond: Double?,
)

/**
 * Pure, dependency-light preview calculator mirroring the backend Haversine
 * distance + average-speed logic (functions/src/drives/drive-calculations.ts)
 * so the in-app summary roughly matches what the server will store. Kept in
 * Kotlin (no Android/Firebase types) for JVM unit testing. Per the backend
 * privacy rule there is deliberately no top-speed estimate.
 */
object DriveSummary {
    /** Mean spherical Earth radius in metres (backend EARTH_RADIUS_METRES). */
    private const val EARTH_RADIUS_METRES = 6_371_000.0

    /** Backend MAX_PLAUSIBLE_SPEED_MPS: segments faster than this are GPS jumps. */
    private const val MAX_PLAUSIBLE_SPEED_MPS = 55.6

    private fun toRadians(degrees: Double): Double = degrees * Math.PI / 180.0

    /** Haversine distance in metres between two coordinates; 0 for identical points. */
    fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = toRadians(lat2 - lat1)
        val dLon = toRadians(lon2 - lon1)
        val a =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(toRadians(lat1)) * cos(toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METRES * c
    }

    /**
     * Total distance in metres over ordered points, excluding implausible jumps
     * and non-positive time deltas exactly like the backend. Returns 0 for
     * fewer than two points.
     */
    fun totalDistanceMetres(points: List<RecordedPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val deltaMs = curr.timestampMs - prev.timestampMs
            if (deltaMs <= 0) continue
            val distance = haversineMetres(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            val impliedSpeed = distance / (deltaMs / 1000.0)
            if (impliedSpeed > MAX_PLAUSIBLE_SPEED_MPS) continue
            total += distance
        }
        return total
    }

    /**
     * Highest instantaneous speed (m/s) implied by consecutive route points, or
     * null when it cannot be derived (fewer than two points, or every segment is
     * filtered out). Applies the SAME implausible-jump filter the backend uses
     * for distance (functions/src/drives/drive-calculations.ts): a segment
     * implying more than [MAX_PLAUSIBLE_SPEED_MPS] (~200 km/h) is treated as a
     * GPS glitch and excluded, so a lone spike can never claim an absurd top
     * speed in the share text. Non-positive time deltas are skipped exactly like
     * the distance scan.
     *
     * Top speed is deliberately NOT persisted server-side (the drives privacy
     * rule stores only average speed), so it is derived here purely client-side
     * for the user-facing share text. Callers that have no loaded route points
     * (e.g. the History read model, which never fetches them) get null and must
     * omit the top-speed sentence rather than render 0.
     */
    fun topSpeedMetersPerSecond(points: List<RecordedPoint>): Double? {
        if (points.size < 2) return null
        var top: Double? = null
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val deltaMs = curr.timestampMs - prev.timestampMs
            if (deltaMs <= 0) continue
            val distance =
                haversineMetres(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            val impliedSpeed = distance / (deltaMs / 1000.0)
            // Same >200 km/h GPS-glitch guard the distance total applies; also
            // drop any non-finite result defensively.
            if (!impliedSpeed.isFinite() || impliedSpeed > MAX_PLAUSIBLE_SPEED_MPS) continue
            if (top == null || impliedSpeed > top) top = impliedSpeed
        }
        return top
    }

    /**
     * Elapsed millis → whole seconds, rounded (not floored) to match the
     * backend's `driveDurationSeconds` (`Math.round(ms / 1000)`). O(1), so the
     * summary dialog can render the duration immediately while the O(n)
     * distance scan resolves off the main thread.
     */
    fun durationSeconds(elapsedMillis: Long): Long =
        Math.round(elapsedMillis / 1000.0).coerceAtLeast(0L)

    /**
     * Builds the preview from the recorded points plus the elapsed recording
     * time. Distance / average speed are null when there are too few points to
     * estimate (a summary-only save), so the dialog renders an em dash for them.
     */
    fun preview(points: List<RecordedPoint>, elapsedMillis: Long): DriveSummaryPreview {
        val durationSeconds = durationSeconds(elapsedMillis)
        if (points.size < 2) {
            return DriveSummaryPreview(
                distanceMeters = null,
                durationSeconds = durationSeconds,
                averageSpeedMetersPerSecond = null,
            )
        }
        val distance = totalDistanceMetres(points)
        val averageSpeed = if (durationSeconds > 0) distance / durationSeconds else null
        return DriveSummaryPreview(
            distanceMeters = distance,
            durationSeconds = durationSeconds,
            averageSpeedMetersPerSecond = averageSpeed,
        )
    }
}
