package com.kungsbackacarcommunity.app.shell

/** One display frame. The trail is an immutable accepted prefix ending at [point]. */
internal data class RecordingMapFrame(
    val point: MapPoint,
    val bearingDegrees: Double?,
    val trail: List<MapPoint>,
    val animating: Boolean,
)

/**
 * Presentation-only interpolation along accepted recorder vertices. Nothing in
 * this class is fed back to the recorder, saved drive, or shared live position.
 * Global point counts keep the cursor unambiguous at loops and when the recent
 * recorder buffer sheds old points.
 */
internal class RecordingMapMotion {
    private data class Rendered(val frame: RecordingMapFrame, val completedCount: Int)

    private data class Movement(
        val startedMs: Long,
        val durationMs: Long,
        val start: Rendered,
        val targetBearing: Double?,
        val route: List<MapPoint>,
        val firstCount: Int,
        val firstTargetIndex: Int,
        val cumulativeMeters: DoubleArray,
    )

    private var latestFix: MapRecordingPosition? = null
    private var updatedMs: Long? = null
    private var renderedMs: Long? = null
    private var rendered: Rendered? = null
    private var movement: Movement? = null

    fun reset() {
        latestFix = null
        updatedMs = null
        renderedMs = null
        rendered = null
        movement = null
    }

    fun update(fix: MapRecordingPosition, points: List<MapPoint>, bearingDegrees: Double?, nowMs: Long) {
        if (!valid(fix.point) || fix.elapsedRealtimeMs < 0L) {
            reset()
            return
        }
        val previousFix = latestFix
        val restartedCount = previousFix != null && fix.pointCount > 0 && fix.pointCount < previousFix.pointCount
        // Timer ticks and style re-registration must not restart a running glide.
        if (!restartedCount && previousFix != null && fix.elapsedRealtimeMs <= previousFix.elapsedRealtimeMs) return

        val current = stateAt(nowMs)
        val targetBearing = normalized(bearingDegrees) ?: current?.frame?.bearingDegrees
        val validRoute = points.isNotEmpty() && points.last() == fix.point && points.all(::valid)
        val source = if (validRoute) points else listOf(fix.point)
        val lastBreak = lastDiscontinuity(source)
        val firstCount = fix.pointCount - source.size + 1
        val rawDelta = previousFix?.let { fix.elapsedRealtimeMs - it.elapsedRealtimeMs } ?: 0L
        val displayDelta = updatedMs?.let { nowMs - it } ?: 0L
        val previousIndex = previousFix?.let { it.pointCount - firstCount } ?: -1
        val unchangedAnchor = previousFix == null || previousIndex !in source.indices || source[previousIndex] == previousFix.point
        val canContinue = !restartedCount && current != null && previousFix != null && previousFix.pointCount > 0 && validRoute && unchangedAnchor &&
            fix.pointCount >= source.size && fix.pointCount > previousFix.pointCount &&
            rawDelta in 1L..MAX_CONTINUITY_GAP_MS && displayDelta in 0L..MAX_CONTINUITY_GAP_MS &&
            current.completedCount >= firstCount + lastBreak - 1 && current.completedCount < fix.pointCount

        latestFix = fix
        updatedMs = nowMs
        renderedMs = nowMs
        if (!canContinue) {
            snap(fix, source, lastBreak, normalized(bearingDegrees))
            return
        }

        val start = requireNotNull(current)
        val startIndex = (start.completedCount - firstCount + 1).coerceAtLeast(0)
        var prior = start.frame.point
        val cumulative = DoubleArray(source.size - startIndex)
        var distance = 0.0
        for (index in startIndex until source.size) {
            val segment = BreadcrumbTrail.haversineMeters(prior, source[index])
            if (segment > BreadcrumbTrail.DEFAULT_MAX_JUMP_METERS) {
                snap(fix, source, lastBreak, normalized(bearingDegrees))
                return
            }
            distance += segment
            cumulative[index - startIndex] = distance
            prior = source[index]
        }

        // Trim the settled history once per accepted update, retaining all
        // pending vertices. Per-frame work visits only the short pending path;
        // the historical prefix is exposed by a read-only list view.
        val historyStart = historyStart(source, firstCount, start.completedCount, start.frame.point, lastBreak)
        val cached = source.subList(historyStart, source.size).toList()
        val firstCachedCount = firstCount + historyStart
        val firstTargetIndex = startIndex - historyStart
        movement = Movement(
            startedMs = nowMs,
            durationMs = rawDelta.coerceAtMost(MAX_ANIMATION_MS),
            start = start,
            targetBearing = targetBearing,
            route = cached,
            firstCount = firstCachedCount,
            firstTargetIndex = firstTargetIndex,
            cumulativeMeters = cumulative,
        )
        rendered = stateAt(nowMs)
    }

    fun frame(nowMs: Long): RecordingMapFrame? = stateAt(nowMs)?.frame

    private fun stateAt(nowMs: Long): Rendered? {
        val active = movement ?: return rendered
        val effectiveNow = maxOf(nowMs, renderedMs ?: nowMs)
        val fraction = ((effectiveNow - active.startedMs).toDouble() / active.durationMs).coerceIn(0.0, 1.0)
        val targets = active.route.subList(active.firstTargetIndex, active.route.size)
        val distance = active.cumulativeMeters.last() * fraction
        var segment = 0
        while (segment < targets.size && active.cumulativeMeters[segment] <= distance) segment++
        val point: MapPoint
        val completed: Int
        if (segment == targets.size) {
            point = targets.last()
            completed = active.firstCount + active.route.lastIndex
        } else {
            val previousPoint = if (segment == 0) active.start.frame.point else targets[segment - 1]
            val previousDistance = if (segment == 0) 0.0 else active.cumulativeMeters[segment - 1]
            val segmentFraction = (distance - previousDistance) / (active.cumulativeMeters[segment] - previousDistance)
            point = interpolate(previousPoint, targets[segment], segmentFraction)
            completed = if (segment == 0) active.start.completedCount else active.firstCount + active.firstTargetIndex + segment - 1
        }
        val prefixSize = (completed - active.firstCount + 1).coerceIn(0, active.route.size)
        val result = Rendered(
            RecordingMapFrame(
                point = point,
                bearingDegrees = interpolateBearing(active.start.frame.bearingDegrees, active.targetBearing, fraction),
                trail = TrailView(active.route, prefixSize, point),
                animating = fraction < 1.0 &&
                    (active.cumulativeMeters.last() > 0.0 || active.start.frame.bearingDegrees != active.targetBearing),
            ),
            completedCount = completed,
        )
        rendered = result
        renderedMs = effectiveNow
        if (fraction >= 1.0 || !result.frame.animating) movement = null
        return result
    }

    private fun snap(fix: MapRecordingPosition, source: List<MapPoint>, lastBreak: Int, bearing: Double?) {
        val start = historyStart(source, 1, source.size, fix.point, lastBreak)
        rendered = Rendered(RecordingMapFrame(fix.point, bearing, source.subList(start, source.size).toList(), false), fix.pointCount)
        movement = null
    }

    private fun historyStart(source: List<MapPoint>, firstCount: Int, completedCount: Int, point: MapPoint, lastBreak: Int): Int {
        var index = (completedCount - firstCount).coerceIn(lastBreak, source.lastIndex)
        var distance = BreadcrumbTrail.haversineMeters(point, source[index])
        while (index > lastBreak && distance < BreadcrumbTrail.DEFAULT_WINDOW_METERS) {
            distance += BreadcrumbTrail.haversineMeters(source[index], source[index - 1])
            index--
        }
        return index
    }

    private fun lastDiscontinuity(points: List<MapPoint>): Int {
        var start = 0
        for (index in 1 until points.size) {
            if (BreadcrumbTrail.haversineMeters(points[index - 1], points[index]) > BreadcrumbTrail.DEFAULT_MAX_JUMP_METERS) start = index
        }
        return start
    }

    /** A new endpoint never copies the cached historical coordinate array. */
    private class TrailView(private val points: List<MapPoint>, private val prefixSize: Int, private val point: MapPoint) : AbstractList<MapPoint>() {
        private val append = prefixSize == 0 || points[prefixSize - 1] != point
        override val size: Int get() = prefixSize + if (append) 1 else 0
        override fun get(index: Int): MapPoint {
            if (index !in indices) throw IndexOutOfBoundsException("Index: $index, size: $size")
            return if (index < prefixSize) points[index] else point
        }
    }

    companion object {
        private const val MAX_ANIMATION_MS = 2_000L
        private const val MAX_CONTINUITY_GAP_MS = 5_000L

        private fun valid(point: MapPoint): Boolean = point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0

        private fun normalized(bearing: Double?): Double? = bearing?.takeIf { it.isFinite() }?.let { (it % 360.0 + 360.0) % 360.0 }

        private fun interpolate(a: MapPoint, b: MapPoint, fraction: Double): MapPoint {
            if (fraction <= 0.0) return a
            if (fraction >= 1.0) return b
            val deltaLongitude = (b.longitude - a.longitude + 540.0) % 360.0 - 180.0
            val longitude = (a.longitude + deltaLongitude * fraction + 540.0) % 360.0 - 180.0
            return MapPoint(longitude, a.latitude + (b.latitude - a.latitude) * fraction)
        }

        private fun interpolateBearing(from: Double?, to: Double?, fraction: Double): Double? {
            if (from == null) return to
            if (to == null) return from
            val turn = (to - from + 540.0) % 360.0 - 180.0
            return normalized(from + turn * fraction)
        }
    }
}
