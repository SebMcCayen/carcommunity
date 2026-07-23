package com.kungsbackacarcommunity.app.incidents

import kotlin.math.max
import kotlin.math.min

/**
 * Where the incident layer last queried, and how wide. Compared against the
 * current camera to decide whether a settled pan/zoom is worth a fresh
 * `listNearby` — see [CameraRequeryDecision].
 */
data class QueryAnchor(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
)

/**
 * The pure "is this camera move worth re-querying?" rule for the incident layer.
 *
 * The map fires a camera-idle event after every pan or zoom, however tiny. Firing
 * a callable on each one would hammer the backend for jitter and for settles that
 * land back where we already fetched. This gates that: a requery happens only
 * when the camera moved FAR enough (relative to what we already loaded) or the
 * zoom changed the radius MATERIALLY.
 *
 * Kept pure and off the map so the decision is unit-tested in the blocking job;
 * the camera read and the debounce timing are wired on the device side.
 */
object CameraRequeryDecision {
    /**
     * Requery once the centre has moved more than this fraction of the LAST
     * query's radius. At 0.25 the user must pan a quarter of the queried circle's
     * radius before we refetch — well within the markers already on screen, so
     * the layer never looks empty mid-pan, yet a genuine move to a new area still
     * refetches promptly. Measured against the OLD radius so a zoomed-out view
     * (large radius) tolerates larger pans before refetching.
     */
    const val MOVE_FRACTION: Double = 0.25

    /**
     * Requery once the radius (i.e. the zoom) has changed by more than this
     * fraction either way. At 0.25 a pinch that grows or shrinks the visible area
     * by a quarter refetches to fill/tighten the layer; smaller zoom nudges ride
     * on the existing markers.
     */
    const val RADIUS_CHANGE_FRACTION: Double = 0.25

    /**
     * True when the layer should re-query for [next]. Always true for the first
     * query ([last] null). Otherwise true when the centre moved more than
     * [MOVE_FRACTION] of [last]'s radius, OR the radius changed by more than
     * [RADIUS_CHANGE_FRACTION]. A tiny jitter or a settle at the same spot and
     * zoom returns false, so the callable is not hammered.
     */
    fun shouldRequery(last: QueryAnchor?, next: QueryAnchor): Boolean {
        if (last == null) return true
        val moved =
            ViewportRadius.haversineMeters(
                last.latitude,
                last.longitude,
                next.latitude,
                next.longitude,
            )
        if (moved > MOVE_FRACTION * last.radiusMeters) return true
        // A non-positive prior radius is degenerate; refetch rather than divide by it.
        val lo = min(last.radiusMeters, next.radiusMeters)
        val hi = max(last.radiusMeters, next.radiusMeters)
        if (lo <= 0.0) return true
        return hi / lo > 1.0 + RADIUS_CHANGE_FRACTION
    }
}
