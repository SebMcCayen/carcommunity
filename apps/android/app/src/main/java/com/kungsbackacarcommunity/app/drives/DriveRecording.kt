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
        /**
         * Running distance in metres over the accepted fixes so far (backend
         * jump filter applied). Drives the map's live-session distance readout.
         * Defaults to 0 so existing constructions (and the initial "just
         * started, no fixes yet" state) stay valid.
         */
        val distanceMeters: Double = 0.0,
    ) : RecordingState

    /**
     * Recording stopped; the save prompt is raised. The MANUAL recorder
     * ([RecordDriveScreen]) shows explicit Save/Discard here and WAITS for the
     * user. The LIVE session ([SingleSessionRecording]) also lands here on stop,
     * but the UI immediately [DriveRecordingCoordinator.autoSave]s from this
     * state — moving on to [SavedPendingChoice] — so the summary asks KEEP/DELETE
     * over an already-saved drive rather than forcing a Save. A recreation that
     * cancels an in-flight auto-save restores THIS state so the auto-save
     * re-fires (the drive is never left unsaved).
     */
    data class PromptSave(
        val pointCount: Int,
        val elapsedMillis: Long,
    ) : RecordingState

    /** The save callable is in flight. */
    data object Saving : RecordingState

    /** The drive was saved (terminal for the MANUAL recorder). */
    data object Saved : RecordingState

    /**
     * A LIVE session ended: the end-of-session summary is shown IMMEDIATELY over
     * the client-side estimate while the `drives-save` callable runs in the
     * BACKGROUND (#798 — stopping must feel instant). The user is asked whether to
     * KEEP the drive or DELETE it while (or after) that background save settles:
     * - KEEP resolves instantly — the background save carries on fire-and-forget
     *   (it retries transient faults) so a slow save can never make the summary
     *   wait.
     * - DELETE waits for the background save to finish (so it knows the rideId and
     *   cannot race a save that would recreate the drive) and then removes it.
     *
     * The created ride's id is held by the coordinator (not carried here) because
     * this state is reached BEFORE the save has produced one; DELETE reads it off
     * the coordinator after joining the background save.
     *
     * @property elapsedMillis the frozen recording duration, for the summary.
     * @property deleteFailed true after a delete attempt failed, so the prompt
     *   re-shows with a delete-error line and the choice still stands (the drive
     *   is still safely saved).
     * @property savePending true while the background save is still in flight, so
     *   the summary can show a small inline "saving…" indicator instead of the old
     *   full-screen blocking modal. Flips false the moment the save lands; a save
     *   that ultimately fails moves to [Failed] instead (while still on this
     *   state — once the user has chosen Keep/Delete the choice stands).
     */
    data class SavedPendingChoice(
        val elapsedMillis: Long,
        val deleteFailed: Boolean = false,
        val savePending: Boolean = false,
    ) : RecordingState

    /**
     * A LIVE session's drive was KEPT while its background save was STILL in
     * flight. KEEP does not wait on the network, but the drive must not be
     * finalized until the save DEFINITIVELY succeeds — otherwise a save that then
     * fails after the terminal [Kept] (which releases everything) would silently
     * lose the drive, the exact never-lose-a-drive failure #798 guards against. So
     * an early Keep parks HERE and the background save resolves it: success →
     * terminal [Kept]; a definitive failure → [Failed], which re-raises the retry
     * prompt so the drive can still be saved.
     *
     * Only reached when Keep is tapped BEFORE the save lands; once the save has
     * landed ([SavedPendingChoice.savePending] is false) Keep goes straight to
     * [Kept]. The summary renders this as the small "saving…" indicator, not a
     * Keep/Delete choice (the choice is already made).
     */
    data class KeptPendingSave(
        val elapsedMillis: Long,
    ) : RecordingState

    /** The `drives-delete` callable is in flight (deleting the auto-saved ride). */
    data object Deleting : RecordingState

    /** The auto-saved drive was KEPT (terminal for the live flow). */
    data object Kept : RecordingState

    /** The auto-saved drive was DELETED again (terminal for the live flow). */
    data object Deleted : RecordingState

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
         * help. `drives-save` rejects restricted accounts with
         * `PERMISSION_DENIED`; telling them to "try again" would loop forever.
         */
        val isPermanentRefusal: Boolean
            get() = code == PERMISSION_DENIED
    }

    companion object {
        /** Firebase Functions status for a backend access refusal. */
        const val PERMISSION_DENIED: String = "PERMISSION_DENIED"

        /**
         * Callable status codes for TRANSIENT `drives-save` faults — a server
         * hiccup, an unreachable backend, a call that ran out of time — that a
         * retry can plausibly clear. `drives-save` is idempotent per
         * `sourceSessionId` (functions/src/drives/saveDrive.ts), so re-issuing the
         * same request is safe: at worst it returns the already-created drive.
         *
         * This is deliberately a CLOSED allow-list, not "everything that isn't
         * PERMISSION_DENIED": a `PERMISSION_DENIED` (restricted access) or an
         * `INVALID_ARGUMENT` (a malformed payload — a client bug) can NEVER be
         * fixed by trying again, so retrying them would just loop. A null
         * (unclassified) code is likewise not retried — the background save
         * surfaces it as a failure the user can retry by hand rather than spinning
         * on an error we cannot reason about. #798/#800.
         */
        val TRANSIENT_SAVE_CODES: Set<String> =
            setOf("INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED")

        /** Whether a `drives-save` failure [code] is worth an automatic retry. */
        fun isTransientSaveCode(code: String?): Boolean = code != null && code in TRANSIENT_SAVE_CODES
    }
}

/**
 * The end-of-session "Drive saved" confirmation reconciled against the BACKGROUND
 * save, as a PURE function of the recording state so the host's Compose wiring
 * stays unit-testable.
 *
 * The confirmation used to be gated on the background `drives-save` callable
 * COMPLETING — the dialog only appeared on the terminal [RecordingState.Kept],
 * which is reached only after the network round-trip (and any transient retries)
 * finished, so ending a session felt slow. It is now OPTIMISTIC: the moment the
 * drive is committed to keep — [RecordingState.KeptPendingSave], while the save is
 * still in flight — the confirmation is shown, so it appears INSTANTLY. The
 * background save (already process-scoped, so it outlives dismissing the dialog)
 * then reconciles the optimistic UI with the real outcome:
 * - a definitive SUCCESS ([RecordingState.Kept]) keeps the confirmation up (and
 *   the host fills in the now-known ride id for the History deep-link);
 * - a definitive FAILURE ([RecordingState.Failed]) RETRACTS it so the failure
 *   safety-net ([SessionSummaryDialog]) is the only surface — the app never claims
 *   "saved" over a drive that was not persisted, and History (which reads the
 *   real server state) shows nothing for it.
 */
object DriveSaveConfirmation {
    /**
     * Whether the optimistic "Drive saved" confirmation should be shown for
     * [state]. True the instant the drive is committed to keep —
     * [RecordingState.KeptPendingSave] (background save still in flight) or the
     * terminal [RecordingState.Kept] (a fast / config-less save that landed before
     * the auto-keep) — so the confirmation never waits on the save's network
     * round-trip. Both map to true so the host can key a one-shot raise on this
     * predicate: it stays true across the KeptPendingSave → Kept transition, so a
     * confirmation the user has since dismissed is never re-raised when the save
     * finally lands.
     */
    fun shouldShow(state: RecordingState): Boolean =
        state is RecordingState.KeptPendingSave || state is RecordingState.Kept

    /**
     * Whether an already-shown confirmation must be RETRACTED because the
     * background save DEFINITIVELY failed ([RecordingState.Failed]) — so the
     * failure safety-net is the sole surface and the app never leaves a "saved"
     * confirmation standing over a drive that was not persisted.
     */
    fun shouldRetract(state: RecordingState): Boolean = state is RecordingState.Failed
}

/**
 * Whether a live-sharing session should ALSO record a drive for History.
 *
 * Sharing and saving are free for authenticated active accounts. Subscription
 * entitlement is deliberately absent: only backend availability and the live
 * sharing capability decide whether automatic recording starts.
 */
object DriveRecordingGate {
    /**
     * @param hasDrivesBackend false in a config-less/CI build with no drives
     *   repository — live sharing still works, nothing records.
     */
    fun shouldRecord(hasDrivesBackend: Boolean, canShareLive: Boolean): Boolean =
        hasDrivesBackend && canShareLive
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
    /**
     * The Storage path of the car this drive is being driven in — the live
     * session's denormalized cover photo — recorded on the saved drive so the
     * History card can draw a round photo of the car with no extra read. Null
     * when the sharer has no car (generic marker) or on a manual recording.
     */
    private val carImagePath: String? = null,
    /**
     * The garage-vehicle id this drive is being driven in, recorded on the saved
     * drive so it links back to the exact vehicle. Null when the sharer has no car
     * or on a manual recording.
     */
    private val vehicleId: String? = null,
    /**
     * The other members of the convoy this drive is part of, recorded on the
     * saved drive so the History card can show who you drove with. Empty on a
     * solo drive or a manual recording — the field is then omitted from the
     * payload entirely. Captured once when the recording begins (same as
     * [carImagePath]); an empty roster keeps the drive a plain solo drive.
     */
    private val convoyMembers: List<ConvoyDriveMember> = emptyList(),
) {
    private val points = ArrayList<RecordedPoint>()

    /** Millis of the last accepted fix, used to derive elapsed time. */
    private var lastPointMillis: Long = startedAtMillis

    /**
     * Running distance in metres, accumulated segment-by-segment as fixes arrive
     * with the SAME jump/backwards-time filter [DriveSummary.totalDistanceMetres]
     * applies in bulk. Kept incrementally so a live readout (the map's
     * live-session bar) can show "km driven this session" without recomputing
     * over up to 20k points on every tick.
     */
    private var accumulatedDistanceMetres: Double = 0.0

    val pointCount: Int
        get() = points.size

    /** Running distance in metres over the accepted fixes so far. */
    val distanceMetres: Double
        get() = accumulatedDistanceMetres

    /** True once the cap is reached and further points are dropped. */
    val isFull: Boolean
        get() = points.size >= MAX_ROUTE_POINTS

    /**
     * Adds a fix in arrival order. Out-of-order or capacity-exceeding points
     * are dropped rather than throwing: the backend requires monotonically
     * non-decreasing timestampMs, and GPS clocks can jitter backwards.
     *
     * @return true if the point was ACCEPTED (appended), false if it was dropped
     *   as out-of-order or over capacity. The caller uses this to journal only
     *   the exact fixes that landed in the recorder (#849), so a resume replays
     *   an identical recording.
     */
    fun addPoint(point: RecordedPoint): Boolean {
        if (isFull) return false
        if (points.isNotEmpty() && point.timestampMs < points.last().timestampMs) return false
        // Accumulate the new segment BEFORE appending, using the previous last
        // fix, so the running total mirrors totalDistanceMetres exactly.
        if (points.isNotEmpty()) {
            accumulatedDistanceMetres += DriveSummary.segmentDistanceMetres(points.last(), point)
        }
        points.add(point)
        if (point.timestampMs > lastPointMillis) lastPointMillis = point.timestampMs
        return true
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
        // Record which car was driven — its garage id (links back to the vehicle)
        // and its cover-photo path (what the History card renders) — when the
        // session carried one. Only sent when present: the backend fields are
        // optional and a blank string would be rejected by the callable schema.
        vehicleId?.takeIf { it.isNotBlank() }?.let { request["vehicleId"] = it }
        carImagePath?.takeIf { it.isNotBlank() }?.let { request["carImagePath"] = it }
        // Who this drive was driven with — only when it was a convoy drive. The
        // shared wire shape caps + de-dupes the roster; an empty list omits the
        // field so a solo drive's payload is byte-for-byte what it was before.
        ConvoyDriveMembers.toRequestList(convoyMembers)
            .takeIf { it.isNotEmpty() }
            ?.let { request["convoyMembers"] = it }
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
 * / duration / top speed before deciding. It is NEVER persisted: on save the
 * backend recomputes the authoritative figures from the submitted route points
 * (see functions/src/drives/drive-calculations.ts), and the History list shows
 * those. Fields are null when there is nothing to estimate (fewer than two
 * fixes, or — for top speed — every segment filtered out as a GPS jump).
 */
data class DriveSummaryPreview(
    val distanceMeters: Double?,
    val durationSeconds: Long,
    val averageSpeedMetersPerSecond: Double?,
    /**
     * Highest plausible instantaneous speed (m/s) implied by consecutive fixes,
     * or null when it cannot be derived. Same figure as
     * [DriveSummary.topSpeedMetersPerSecond] over the same points; the summary
     * shows it as a fourth stat row (added 2026-07 at Seb's request).
     */
    val topSpeedMetersPerSecond: Double?,
)

/**
 * The single fastest fix on a route: the top plausible instantaneous speed AND
 * where on the route it occurred, so the end-of-session summary can both show
 * the number and drop a marker at that spot on its route map. Pure data (no
 * Android / Mapbox types) so [DriveSummary.topSpeedPoint] stays fully
 * JVM-unit-testable — the map only renders the [latitude]/[longitude] it returns.
 *
 * @property metersPerSecond the top speed; equals
 *   [DriveSummary.topSpeedMetersPerSecond] over the same points.
 * @property latitude latitude of the fix that ENDS the fastest segment.
 * @property longitude longitude of that fix.
 * @property index its index in the source point list. Because the summary maps
 *   its recorded fixes 1:1 in arrival order into the drawn route
 *   ([SessionRoutePreview]), this index addresses the same vertex on the drawn
 *   polyline.
 */
data class TopSpeedPoint(
    val metersPerSecond: Double,
    val latitude: Double,
    val longitude: Double,
    val index: Int,
)

/**
 * Pure, dependency-light preview calculator mirroring the backend Haversine
 * distance + average-speed logic (functions/src/drives/drive-calculations.ts)
 * so the in-app summary roughly matches what the server will store. Kept in
 * Kotlin (no Android/Firebase types) for JVM unit testing. The end-of-session
 * preview shows distance / duration / average speed only — it is a "save or
 * discard?" prompt, not a stats page. Maximum speed IS computed here for the
 * share text, and (since 2026-07) stored by the backend and shown in History;
 * the preview simply has no room for a fourth number.
 */
object DriveSummary {
    /** Mean spherical Earth radius in metres (backend EARTH_RADIUS_METRES). */
    private const val EARTH_RADIUS_METRES = 6_371_000.0

    /**
     * Backend MAX_PLAUSIBLE_SPEED_MPS: segments faster than this are GPS jumps.
     *
     * Public because this is no longer only a drives concern: the live-position
     * quality rules
     * ([com.kungsbackacarcommunity.app.location.LivePositionQuality.isImplausibleSpeed])
     * throw away a fix implying more than this since the last accepted one — on
     * BOTH sides of the wire, the sharing device before it publishes and the map
     * before it moves a marker — for exactly the reason this scan skips such a
     * segment. Read from here rather than restated there, so the app has ONE
     * answer to "too fast to be a car" and retuning it moves all of them.
     *
     * Note what it does NOT catch, since that is easy to over-trust: implied
     * speed is distance ÷ elapsed time, so the ceiling it enforces grows with
     * the gap between samples. It is sharp at the publisher's ~5 s fix cadence
     * and nearly useless across a parked phone's 3-minute heartbeat, which is
     * why [com.kungsbackacarcommunity.app.location.LivePositionQuality] pairs it
     * with accuracy and distance rules rather than relying on it alone.
     */
    const val MAX_PLAUSIBLE_SPEED_MPS = 55.6

    /**
     * Maximum plausible forward ACCELERATION for a car, in m/s². Used by the
     * top-speed scan (NOT distance) to reject a single GPS position glitch that
     * stays UNDER [MAX_PLAUSIBLE_SPEED_MPS] and so slips past that absolute cap.
     *
     * Why the absolute cap is not enough for a maximum: implied speed is
     * distance ÷ elapsed time, so a fix that jumps ~100 m sideways over a normal
     * ~5 s cadence implies ~20 m/s (~70 km/h) of extra speed — comfortably under
     * 200 km/h, so [MAX_PLAUSIBLE_SPEED_MPS] waves it through, and a maximum
     * takes that single worst sample. That is exactly the reported bug: a real
     * ~80 km/h drive saved with a ~150 km/h "top speed". Distance averages such a
     * fix away; a maximum cannot, so it needs a second, tighter guard.
     *
     * The guard is corroboration by physics: a segment's implied speed only
     * counts toward the max if it is REACHABLE from the last trustworthy speed
     * without super-car acceleration. 3.5 m/s² is ~0–100 km/h in ~8 s — generous
     * for real road cars (so genuine brisk acceleration is never clipped) yet far
     * below the tens of m/s² a lone position glitch implies. A single glitchy
     * fix corrupts the TWO segments that touch it (out, then back); because a
     * rejected segment does NOT advance the trusted anchor, both halves are
     * measured against the real speed on either side and both are rejected.
     *
     * What it deliberately does NOT do: clip a genuinely high SUSTAINED speed —
     * once you are cruising fast, segment-to-segment change is ~0, so every
     * segment is admitted and the true top speed stands. Only an isolated,
     * physically impossible jump is dropped. It also cannot vet the very first
     * segment (there is no prior speed to compare); that lone case still relies
     * on the [MAX_PLAUSIBLE_SPEED_MPS] backstop.
     */
    const val MAX_PLAUSIBLE_ACCEL_MPS2 = 3.5

    /**
     * The acceleration budget for a segment is [MAX_PLAUSIBLE_ACCEL_MPS2] × its
     * elapsed seconds — but the elapsed time is capped here first. Fixes normally
     * arrive every ~2–5 s ([com.kungsbackacarcommunity.app.location.BackgroundLocation]),
     * so this cap is a no-op for a healthy stream; it only bites after a GAP in
     * fixes (lost signal), where an uncapped budget would grow large enough to let
     * a glitch coinciding with that gap slip through. Capping the window keeps the
     * per-segment jump ceiling bounded (~21 m/s ≈ 75 km/h at the cap) regardless of
     * how long the gap was, so a glitch after a long silence is still caught. The
     * cost — under-reporting a real hard acceleration sustained across a >6 s gap —
     * is an honest under-read, never the inflation this fixes.
     */
    const val ACCEL_WINDOW_CAP_SECONDS = 6.0

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
     * Distance in metres contributed by ONE ordered segment (prev → curr),
     * applying the same filters the backend uses: a non-positive time delta or an
     * implied speed above [MAX_PLAUSIBLE_SPEED_MPS] (a GPS jump) contributes 0.
     *
     * Extracted so a live recorder can accumulate distance incrementally as each
     * fix arrives (see [DriveRecorder]) using the EXACT rule
     * [totalDistanceMetres] applies in bulk, rather than a second, drifting copy.
     */
    fun segmentDistanceMetres(prev: RecordedPoint, curr: RecordedPoint): Double =
        segmentDistanceMetres(
            prev.latitude, prev.longitude, prev.timestampMs,
            curr.latitude, curr.longitude, curr.timestampMs,
        )

    /**
     * [RoutePoint] overload of [segmentDistanceMetres] for consumers that hold
     * DECODED route points (e.g. the History per-km marker util
     * [RouteDistanceMarkers]) rather than [RecordedPoint]s. Delegates to the SAME
     * primitive as the [RecordedPoint] overload, so the GPS-jump / backwards-time
     * filter can never drift between the two and the History markers agree with
     * the drive's stored distance.
     */
    fun segmentDistanceMetres(prev: RoutePoint, curr: RoutePoint): Double =
        segmentDistanceMetres(
            prev.latitude, prev.longitude, prev.timestampMs,
            curr.latitude, curr.longitude, curr.timestampMs,
        )

    /**
     * The one place the segment filter lives: a non-positive time delta or an
     * implied speed above [MAX_PLAUSIBLE_SPEED_MPS] (a GPS jump) contributes 0;
     * otherwise the Haversine distance. Both [RecordedPoint] and [RoutePoint]
     * overloads funnel through here so there is a single source of truth.
     */
    private fun segmentDistanceMetres(
        prevLat: Double,
        prevLon: Double,
        prevTimestampMs: Long,
        currLat: Double,
        currLon: Double,
        currTimestampMs: Long,
    ): Double {
        val deltaMs = currTimestampMs - prevTimestampMs
        if (deltaMs <= 0) return 0.0
        val distance = haversineMetres(prevLat, prevLon, currLat, currLon)
        val impliedSpeed = distance / (deltaMs / 1000.0)
        if (impliedSpeed > MAX_PLAUSIBLE_SPEED_MPS) return 0.0
        return distance
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
            total += segmentDistanceMetres(points[i - 1], points[i])
        }
        return total
    }

    /**
     * Highest instantaneous speed (m/s) implied by consecutive route points, or
     * null when it cannot be derived (fewer than two points, or every segment is
     * filtered out). Rejects GPS spikes two ways (see [scanPlausibleSegments]):
     * the absolute [MAX_PLAUSIBLE_SPEED_MPS] (~200 km/h) backstop the distance
     * scan also uses, AND — because a maximum takes the single worst sample where
     * distance averages it away — an acceleration guard that drops a lone segment
     * implying an impossible jump from the last real speed
     * ([MAX_PLAUSIBLE_ACCEL_MPS2]). That second guard is what stops a position
     * glitch UNDER 200 km/h (e.g. a ~150 km/h spike on a real 80 km/h drive) from
     * inflating the figure. Non-positive time deltas are skipped exactly like the
     * distance scan. A genuinely high SUSTAINED speed is never clipped.
     *
     * This is the SHARE-TEXT figure, derived client-side from points already in
     * memory. Since 2026-07 the backend ALSO persists an equivalent figure on
     * the ride document (`maxSpeedMetersPerSecond`, same 55.6 m/s filter) — the
     * note that used to stand here, that top speed "is deliberately NOT
     * persisted server-side", no longer holds and has been corrected rather
     * than left to mislead. The two are not merged because they answer
     * different questions: this one exists the moment a recording ends, before
     * any save; the stored one is what the History list shows for a drive whose
     * route points it never loads. Callers with no loaded points get null and
     * must omit the top-speed sentence rather than render 0.
     */
    fun topSpeedMetersPerSecond(points: List<RecordedPoint>): Double? =
        topSpeedOverPoints(
            size = points.size,
            latitude = { points[it].latitude },
            longitude = { points[it].longitude },
            timestampMs = { points[it].timestampMs },
        )

    /**
     * [RoutePoint] overload of [topSpeedMetersPerSecond] for the History share
     * text, which feeds the DECODED route points ([RouteReplayState.Ready]) in
     * directly. It computes the identical figure WITHOUT first `.map`-ing every
     * point to a parallel `List<RecordedPoint>`: a long route is up to
     * [DriveRecorder.MAX_ROUTE_POINTS] (~20k) points and that intermediate
     * allocation ran on the UI thread the moment the route loaded. The two point
     * types are the same latitude/longitude/timestamp triple ([RoutePoint] ⇄
     * [RecordedPoint]), so the same filter over the same values yields the same
     * result — verified in DriveSummaryTest.
     *
     * `@JvmName` disambiguates the JVM signature: both overloads erase to
     * `topSpeedMetersPerSecond(List)`, so one needs a distinct JVM name. Kotlin
     * callers still resolve the right one by argument type.
     */
    @JvmName("topSpeedMetersPerSecondRoute")
    fun topSpeedMetersPerSecond(points: List<RoutePoint>): Double? =
        topSpeedOverPoints(
            size = points.size,
            latitude = { points[it].latitude },
            longitude = { points[it].longitude },
            timestampMs = { points[it].timestampMs },
        )

    /**
     * The fastest fix on a route AND where it occurred — the top speed plus the
     * point that ends the fastest segment — or null when it cannot be derived
     * (fewer than two points, or every segment filtered out). Shares the EXACT
     * same [scanPlausibleSegments] core as [topSpeedMetersPerSecond] — the
     * absolute [MAX_PLAUSIBLE_SPEED_MPS] cap AND the [MAX_PLAUSIBLE_ACCEL_MPS2]
     * jump guard — so a spike (over OR under 200 km/h) can never be chosen as the
     * top and the marked point always matches the scalar figure. A tie keeps the
     * FIRST (earliest) fastest segment (strictly-greater comparison).
     *
     * Scans [RoutePoint]s — the very list the summary's route map draws — so the
     * returned [TopSpeedPoint.index] and coordinate line up with the drawn
     * polyline. The returned [TopSpeedPoint.metersPerSecond] equals
     * [topSpeedMetersPerSecond] over the same points (same filter, same
     * Haversine, same tie-break), verified in DriveSummaryTest — so the value the
     * summary shows in its stat row and the point it marks on the map never
     * disagree.
     */
    fun topSpeedPoint(points: List<RoutePoint>): TopSpeedPoint? {
        var best: TopSpeedPoint? = null
        scanPlausibleSegments(
            size = points.size,
            latitude = { points[it].latitude },
            longitude = { points[it].longitude },
            timestampMs = { points[it].timestampMs },
        ) { index, impliedSpeed ->
            val current = best
            // Strictly greater → a tie keeps the earliest fastest segment.
            if (current == null || impliedSpeed > current.metersPerSecond) {
                best =
                    TopSpeedPoint(
                        metersPerSecond = impliedSpeed,
                        latitude = points[index].latitude,
                        longitude = points[index].longitude,
                        index = index,
                    )
            }
        }
        return best
    }

    /**
     * Shared core for both [topSpeedMetersPerSecond] overloads: the highest
     * plausible instantaneous speed (m/s) over [size] ordered points addressed
     * by index. Funnels through [scanPlausibleSegments] so it applies the EXACT
     * same GPS-jump + spike rejection as [topSpeedPoint] — the scalar figure and
     * the marked point can never disagree.
     */
    private inline fun topSpeedOverPoints(
        size: Int,
        latitude: (Int) -> Double,
        longitude: (Int) -> Double,
        timestampMs: (Int) -> Long,
    ): Double? {
        var top: Double? = null
        scanPlausibleSegments(size, latitude, longitude, timestampMs) { _, impliedSpeed ->
            val current = top
            if (current == null || impliedSpeed > current) top = impliedSpeed
        }
        return top
    }

    /**
     * The ONE place the top-speed segment filter lives. Walks [size] ordered
     * points and invokes [onAccepted] with `(index, impliedSpeedMps)` for each
     * segment (ending at `index`) whose implied speed is accepted as a real
     * ground speed. Every top-speed reader ([topSpeedMetersPerSecond] overloads
     * and [topSpeedPoint]) folds over this, so they can never drift apart.
     *
     * A segment is REJECTED (never handed to [onAccepted]) when:
     * - its time delta is non-positive (out-of-order / duplicate fix), or the
     *   implied speed is non-finite; or
     * - it exceeds [MAX_PLAUSIBLE_SPEED_MPS] — the absolute >200 km/h backstop,
     *   the same one the distance total applies; or
     * - it implies impossible ACCELERATION from the last accepted speed: the
     *   jump up exceeds [MAX_PLAUSIBLE_ACCEL_MPS2] × the (window-capped) elapsed
     *   time. This is what catches a GPS position glitch that stays under
     *   200 km/h yet spikes the max — the case the absolute cap misses.
     *
     * The trusted `anchor` (last accepted speed) is deliberately NOT advanced by
     * a rejected segment: a single glitchy fix corrupts the two segments that
     * touch it, and measuring both against the real speed either side rejects
     * both. Only positive jumps are gated — a deceleration can never inflate a
     * maximum, and hard braking is physically fine, so a downward change is
     * always accepted and simply re-anchors to the lower speed. The first
     * accepted segment has no prior speed to compare, so it is admitted on the
     * absolute cap alone.
     *
     * `inline` so the index accessors and the [onAccepted] callback are inlined
     * and no intermediate list is allocated over a route of up to
     * [DriveRecorder.MAX_ROUTE_POINTS] points.
     */
    private inline fun scanPlausibleSegments(
        size: Int,
        latitude: (Int) -> Double,
        longitude: (Int) -> Double,
        timestampMs: (Int) -> Long,
        onAccepted: (index: Int, impliedSpeedMps: Double) -> Unit,
    ) {
        if (size < 2) return
        var anchor: Double? = null
        for (i in 1 until size) {
            val deltaMs = timestampMs(i) - timestampMs(i - 1)
            if (deltaMs <= 0) continue
            val elapsedSeconds = deltaMs / 1000.0
            val distance =
                haversineMetres(latitude(i - 1), longitude(i - 1), latitude(i), longitude(i))
            val impliedSpeed = distance / elapsedSeconds
            // Absolute >200 km/h backstop (+ non-finite guard). A rejected
            // segment must not advance the anchor.
            if (!impliedSpeed.isFinite() || impliedSpeed > MAX_PLAUSIBLE_SPEED_MPS) continue
            val previous = anchor
            if (previous != null) {
                val budgetSeconds = minOf(elapsedSeconds, ACCEL_WINDOW_CAP_SECONDS)
                val maxIncrease = MAX_PLAUSIBLE_ACCEL_MPS2 * budgetSeconds
                // Only positive jumps are implausible; a slowdown is always fine.
                if (impliedSpeed - previous > maxIncrease) continue
            }
            anchor = impliedSpeed
            onAccepted(i, impliedSpeed)
        }
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
                topSpeedMetersPerSecond = null,
            )
        }
        val distance = totalDistanceMetres(points)
        val averageSpeed = if (durationSeconds > 0) distance / durationSeconds else null
        return DriveSummaryPreview(
            distanceMeters = distance,
            durationSeconds = durationSeconds,
            averageSpeedMetersPerSecond = averageSpeed,
            topSpeedMetersPerSecond = topSpeedMetersPerSecond(points),
        )
    }
}
