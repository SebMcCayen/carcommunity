package com.kungsbackacarcommunity.app.shell

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The rolling "past ~1 km" tail of the user's OWN recent travel, kept as pure
 * (Android-free) logic so the trimming / jitter / jump rules are JVM-unit
 * testable without a map, GPS or a device.
 *
 * This is deliberately a LOCAL, PRIVATE buffer: it is fed from the device puck's
 * own position fixes and drawn client-side only (see [MapboxMapSurface]). Nothing
 * here is written to RTDB, Firestore, or any shared location, and it is never
 * pushed across the [MapSurface] seam to other convoy members — it exists solely
 * so the signed-in user can see the road they have just driven while their own
 * live-location session is running.
 *
 * The window is by DISTANCE, not point count: as new fixes arrive the oldest
 * points are trimmed off the tail so the retained path stays at about
 * [windowMeters]. Two real-world GPS problems are handled so the tail does not
 * lie:
 * - **Jitter / stationary wander** — a parked car's fix drifts a few metres every
 *   frame. A fix closer than [minMoveMeters] to the last kept point is dropped,
 *   so a stationary puck can neither fill the buffer nor slowly inflate the
 *   trail's length.
 * - **Implausible jump / discontinuity** — a single bad fix that teleports, or a
 *   resume after a long gap (tunnel, backgrounded app). The path between the last
 *   point and a fix further than [maxJumpMeters] away is unknown, so rather than
 *   drawing a straight line across half the map the trail is reset to start fresh
 *   at the new point. This self-heals within ~1 km of driving.
 *
 * Not thread-safe: it is only ever touched on the map's main-thread position
 * callback.
 */
class BreadcrumbTrail(
    private val windowMeters: Double = DEFAULT_WINDOW_METERS,
    private val minMoveMeters: Double = DEFAULT_MIN_MOVE_METERS,
    private val maxJumpMeters: Double = DEFAULT_MAX_JUMP_METERS,
) {
    // Oldest point first, newest last — so the drawn LineString runs tail→head
    // and the fade gradient (line-progress 0 = oldest) reads the same direction.
    private val trail = ArrayDeque<MapPoint>()

    /** The current tail, oldest→newest. A defensive copy so callers can't mutate it. */
    fun points(): List<MapPoint> = trail.toList()

    /** Number of points currently retained. */
    fun size(): Int = trail.size

    fun isEmpty(): Boolean = trail.isEmpty()

    fun isNotEmpty(): Boolean = trail.isNotEmpty()

    /** The total on-the-ground length of the retained tail, in metres. */
    fun lengthMeters(): Double = totalLength()

    /** Drop the whole tail (session ended). */
    fun clear() {
        trail.clear()
    }

    /**
     * Replace the tail with [points] (oldest→newest) — used to RESTORE the visible
     * trail after a process death (#849 follow-up): the recorded drive is persisted
     * and resumed into the recorder on relaunch, but this on-screen tail is a
     * memory-only buffer that would otherwise start empty, so the user sees nothing
     * of the drive they are still recording until they have driven another window's
     * worth. Seeding it from the resumed route redraws the road just driven at once.
     *
     * The points are already-accepted recorder fixes, so the live [add] jitter/jump
     * heuristics are deliberately BYPASSED (they exist to clean a raw GPS stream,
     * not a recorded polyline). Only [trimToWindow] is applied so the restored tail
     * is the same ~[windowMeters] the live tail shows — the newest points are kept,
     * the older head is shed. A tail of fewer than two points is left as-is.
     */
    fun seed(points: List<MapPoint>) {
        trail.clear()
        for (point in points) trail.addLast(point)
        if (trail.size > 2) trimToWindow()
    }

    /**
     * Feed the next position fix. Returns true when the retained tail actually
     * CHANGED (a point was added, or the trail was reset), so the caller only
     * re-draws the map line when there is something new — a stationary puck
     * emitting a fix every frame returns false and triggers no redraw.
     */
    fun add(point: MapPoint): Boolean {
        val last = trail.lastOrNull()
        if (last == null) {
            trail.addLast(point)
            return true
        }
        val moved = haversineMeters(last, point)
        // Jitter / duplicate: ignore fixes that have not moved far enough.
        if (moved < minMoveMeters) return false
        // Implausible jump / discontinuity: start a fresh tail rather than
        // drawing a straight line across the unknown gap.
        if (moved > maxJumpMeters) {
            trail.clear()
            trail.addLast(point)
            return true
        }
        trail.addLast(point)
        trimToWindow()
        return true
    }

    /**
     * Trim the oldest points as the head advances. When the total path is longer
     * than [windowMeters], the retained tail is shed down to the shortest suffix
     * whose length is still >= [windowMeters] (so a touch more than ~1 km stays on
     * screen, never less). When the total path is shorter than the window, every
     * point is kept — a short path is never padded up to the window. Always leaves
     * at least two points so a drawable segment survives.
     */
    private fun trimToWindow() {
        var total = totalLength()
        while (trail.size > 2) {
            val leadSegment = haversineMeters(trail[0], trail[1])
            if (total - leadSegment >= windowMeters) {
                trail.removeFirst()
                total -= leadSegment
            } else {
                break
            }
        }
    }

    private fun totalLength(): Double {
        var sum = 0.0
        for (i in 1 until trail.size) {
            sum += haversineMeters(trail[i - 1], trail[i])
        }
        return sum
    }

    companion object {
        /** ~1 km rolling window, as the feature is specified ("past 1 km"). */
        const val DEFAULT_WINDOW_METERS = 1_000.0

        /**
         * Jitter floor. Below this a fix is treated as stationary wander and
         * dropped. 5 m comfortably clears typical parked-car GPS noise while
         * still capturing genuine slow movement (walking pace between fixes).
         */
        const val DEFAULT_MIN_MOVE_METERS = 5.0

        /**
         * Discontinuity ceiling. The puck's indicator position updates many times
         * a second while driving, so consecutive kept fixes are metres apart even
         * at motorway speed; a gap of more than 300 m between two fixes therefore
         * means a real discontinuity (bad fix, tunnel, resumed app), where the
         * intervening path is unknown and must not be drawn as a straight line.
         */
        const val DEFAULT_MAX_JUMP_METERS = 300.0

        private const val EARTH_RADIUS_METERS = 6_371_000.0

        /** Great-circle distance between two lng/lat points, in metres. */
        fun haversineMeters(a: MapPoint, b: MapPoint): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
            return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
        }
    }
}
