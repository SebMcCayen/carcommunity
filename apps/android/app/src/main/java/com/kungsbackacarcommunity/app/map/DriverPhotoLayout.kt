package com.kungsbackacarcommunity.app.map

import kotlin.math.sqrt

/** Display points (dp on Android, points on iOS), never geographic coordinates. */
data class DriverPhotoPoint(val x: Double, val y: Double) {
    fun distanceTo(other: DriverPhotoPoint): Double = kotlin.math.hypot(x - other.x, y - other.y)
}
data class DriverPhotoRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun intersects(other: DriverPhotoRect): Boolean = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    fun inflated(by: Double) = DriverPhotoRect(left - by, top - by, right + by, bottom + by)
    fun contains(other: DriverPhotoRect): Boolean = other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom
}
data class DriverPhotoAnchor(val uid: String, val point: DriverPhotoPoint)
data class DriverPhotoLine(val start: DriverPhotoPoint, val end: DriverPhotoPoint)
data class DriverPhotoPlacement(
    val uid: String,
    val anchor: DriverPhotoPoint,
    val photo: DriverPhotoPoint,
    val lineStart: DriverPhotoPoint,
    val lineEnd: DriverPhotoPoint,
    val showAnchor: Boolean,
    val slot: Int,
    val lines: List<DriverPhotoLine> = listOf(DriverPhotoLine(lineStart, lineEnd)),
) {
    val hitBounds: DriverPhotoRect get() = DriverPhotoRect(photo.x - 24, photo.y - 24, photo.x + 24, photo.y + 24)
}
data class DriverPhotoLayoutResult(val placed: List<DriverPhotoPlacement>, val overflow: List<DriverPhotoAnchor>)

/**
 * Shared nearby+convoy layout. Stable uid ordering and sticky slots prevent a
 * harmless roster reorder or small GPS movement from swapping photographs.
 * Only photos move: every anchor stays at the real projected position.
 */
class DriverPhotoLayout {
    private val slots = mutableMapOf<String, Int>()
    private var expanded = false
    private var expandedCount = 0
    private var nextToken = 1_000
    private val offsets = mutableMapOf<String, DriverPhotoPoint>()
    private data class Candidate(val slot: Int, val photo: DriverPhotoPoint)

    fun layout(
        anchors: List<DriverPhotoAnchor>, width: Double, height: Double,
        ownPoint: DriverPhotoPoint?, exclusions: List<DriverPhotoRect> = emptyList(),
    ): DriverPhotoLayoutResult {
        val valid = anchors.filter { it.uid.isNotEmpty() && it.point.x.isFinite() && it.point.y.isFinite() }
            .distinctBy { it.uid }.sortedWith(compareBy<DriverPhotoAnchor> { if (it.uid in slots) 0 else 1 }.thenBy { it.uid })
        slots.keys.retainAll(valid.map { it.uid }.toSet())
        offsets.keys.retainAll(valid.map { it.uid }.toSet())
        if (valid.isEmpty()) expanded = false
        val grid = buildList {
            var y = 32.0
            while (y <= height - 32 && size < 2_500) {
                var x = 32.0
                while (x <= width - 32 && size < 2_500) { add(DriverPhotoPoint(x, y)); x += 48 }
                y += 48
            }
        }
        // Count/spatial hysteresis: once a crowd clears, return to short leaders
        // only with extra clearance. Tiny frame-to-frame movement keeps its slots.
        val spread = valid.indices.all { i -> valid.drop(i + 1).all { valid[i].point.distanceTo(it.point) >= 104 } }
        val canCollapse = valid.size <= maxOf(2, (expandedCount * 0.6).toInt()) || spread
        val short = if (expanded && canCollapse) plan(valid, width, height, ownPoint, exclusions, grid, false, 8.0) else null
        if (short != null && short.overflow.isEmpty()) expanded = false
        var result = short?.takeIf { it.overflow.isEmpty() } ?: plan(valid, width, height, ownPoint, exclusions, grid, expanded)
        if (!expanded && result.overflow.isNotEmpty()) {
            val wide = plan(valid, width, height, ownPoint, exclusions, grid, true)
            if (wide.placed.size > result.placed.size) { result = wide; expanded = true; expandedCount = valid.size }
        }
        if (expanded && result.overflow.isNotEmpty()) {
            // Sticky offsets can fragment otherwise sufficient grid capacity
            // after drivers separate. Repack only on an actual packing failure.
            val packed = plan(valid, width, height, ownPoint, exclusions, grid, true, reusePrior = false)
            if (packed.placed.size > result.placed.size) result = packed
        }
        val committed = result.placed.map { if (it.slot < 0) it.copy(slot = nextToken++) else it }
        for (item in committed) {
            slots[item.uid] = item.slot
            offsets[item.uid] = DriverPhotoPoint(item.photo.x - item.anchor.x, item.photo.y - item.anchor.y)
        }
        return result.copy(placed = committed)
    }

    private fun plan(
        anchors: List<DriverPhotoAnchor>, width: Double, height: Double, ownPoint: DriverPhotoPoint?,
        exclusions: List<DriverPhotoRect>, grid: List<DriverPhotoPoint>, wide: Boolean, clearance: Double = 4.0, reusePrior: Boolean = true,
    ): DriverPhotoLayoutResult {
        val viewport = DriverPhotoRect(8.0, 8.0, width - 8, height - 8)
        val placed = mutableListOf<DriverPhotoPlacement>()
        val overflow = mutableListOf<DriverPhotoAnchor>()
        for (item in anchors) {
            val candidates = if (wide) {
                val ordered = grid.withIndex().sortedWith(compareBy<IndexedValue<DriverPhotoPoint>> { it.value.distanceTo(item.point) }
                    .thenBy { it.index xor stableHash(item.uid) }).map { Candidate(-1 - it.index, it.value) }
                val prior = slots[item.uid]?.takeIf { reusePrior && it >= 1_000 }?.let { slot -> offsets[item.uid]?.let {
                    Candidate(slot, DriverPhotoPoint(item.point.x + it.x, item.point.y + it.y))
                } }
                listOfNotNull(prior) + ordered
            } else {
                val first = stableHash(item.uid) % 8
                val choices = buildList {
                    slots[item.uid]?.takeIf { it < 16 }?.let(::add)
                    for (ring in 0..1) for (step in 0..7) add(ring * 8 + (first + step) % 8)
                }.distinct()
                choices.map { slot ->
                    val vector = directions[slot % 8]
                    val radius = if (slot < 8) 56.0 else 72.0
                    Candidate(slot, DriverPhotoPoint(item.point.x + vector.x * radius, item.point.y + vector.y * radius))
                }
            }
            val chosen = candidates.firstNotNullOfOrNull { candidate ->
                val photo = candidate.photo
                val rect = DriverPhotoRect(photo.x - 24, photo.y - 24, photo.x + 24, photo.y + 24)
                if (!viewport.contains(rect) || exclusions.any { (if (wide) it else it.inflated(clearance)).intersects(rect) } ||
                    placed.any { (if (wide) it.hitBounds else it.hitBounds.inflated(clearance)).intersects(rect) } ||
                    (ownPoint != null && circleIntersectsRect(ownPoint, OWN_FREE_RADIUS, rect))) return@firstNotNullOfOrNull null
                val dx = photo.x - item.point.x; val dy = photo.y - item.point.y
                val distance = kotlin.math.hypot(dx, dy)
                if (distance < PHOTO_RADIUS + 4) return@firstNotNullOfOrNull null
                val end = DriverPhotoPoint(photo.x - dx / distance * PHOTO_RADIUS, photo.y - dy / distance * PHOTO_RADIUS)
                val insideOwn = ownPoint != null && item.point.distanceTo(ownPoint) < OWN_FREE_RADIUS
                if (!wide && ownPoint != null && !insideOwn && segmentDistance(ownPoint, item.point, end) < OWN_FREE_RADIUS) return@firstNotNullOfOrNull null
                val lines = if (ownPoint != null) clippedLines(item.point, end, ownPoint, OWN_FREE_RADIUS) else listOf(DriverPhotoLine(item.point, end))
                val start = lines.lastOrNull()?.start ?: end
                DriverPhotoPlacement(item.uid, item.point, photo, start, end, !insideOwn, candidate.slot, lines)
            }
            if (chosen == null) overflow += item else placed += chosen
        }
        return DriverPhotoLayoutResult(placed, overflow)
    }

    companion object {
        const val PHOTO_RADIUS = 20.0
        const val OWN_FREE_RADIUS = 28.0
        private val diagonal = sqrt(0.5)
        private val directions = listOf(
            DriverPhotoPoint(1.0, 0.0), DriverPhotoPoint(diagonal, diagonal), DriverPhotoPoint(0.0, 1.0), DriverPhotoPoint(-diagonal, diagonal),
            DriverPhotoPoint(-1.0, 0.0), DriverPhotoPoint(-diagonal, -diagonal), DriverPhotoPoint(0.0, -1.0), DriverPhotoPoint(diagonal, -diagonal),
        )
        /** FNV-1a bytes, unlike platform-randomized hashCode/Hasher. */
        fun stableHash(uid: String): Int {
            var hash = 2_166_136_261L
            for (byte in uid.toByteArray(Charsets.UTF_8)) hash = ((hash xor (byte.toLong() and 255L)) * 16_777_619L) and 0xffff_ffffL
            return (hash and 0x7fff_ffffL).toInt()
        }
        private fun circleIntersectsRect(point: DriverPhotoPoint, radius: Double, rect: DriverPhotoRect): Boolean {
            val x = point.x.coerceIn(rect.left, rect.right)
            val y = point.y.coerceIn(rect.top, rect.bottom)
            return kotlin.math.hypot(x - point.x, y - point.y) < radius
        }
        private fun segmentDistance(point: DriverPhotoPoint, a: DriverPhotoPoint, b: DriverPhotoPoint): Double {
            val dx = b.x - a.x; val dy = b.y - a.y
            val length = dx * dx + dy * dy
            val fraction = if (length > 0) (((point.x - a.x) * dx + (point.y - a.y) * dy) / length).coerceIn(0.0, 1.0) else 0.0
            return point.distanceTo(DriverPhotoPoint(a.x + dx * fraction, a.y + dy * fraction))
        }
        private fun clippedLines(a: DriverPhotoPoint, b: DriverPhotoPoint, center: DriverPhotoPoint, radius: Double): List<DriverPhotoLine> {
            val dx = b.x - a.x; val dy = b.y - a.y
            val x = a.x - center.x; val y = a.y - center.y
            val aa = dx * dx + dy * dy
            if (aa <= 0) return emptyList()
            val bb = 2 * (x * dx + y * dy)
            val cc = x * x + y * y - radius * radius
            val discriminant = bb * bb - 4 * aa * cc
            if (discriminant <= 0) return listOf(DriverPhotoLine(a, b))
            val enter = (-bb - sqrt(discriminant)) / (2 * aa)
            val exit = (-bb + sqrt(discriminant)) / (2 * aa)
            if (exit <= 0 || enter >= 1) return listOf(DriverPhotoLine(a, b))
            return buildList {
                if (enter > 0) add(DriverPhotoLine(a, DriverPhotoPoint(a.x + dx * enter, a.y + dy * enter)))
                if (exit < 1) add(DriverPhotoLine(DriverPhotoPoint(a.x + dx * exit, a.y + dy * exit), b))
            }
        }
    }
}
