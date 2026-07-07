package com.kungsbackacarcommunity.app.drives

import java.time.Instant

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

    /** The save callable failed; the prompt can be retried. */
    data class Failed(
        val pointCount: Int,
        val elapsedMillis: Long,
    ) : RecordingState
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
        val endedAt = basis.coerceAtLeast(lastPointMillis)
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
