package com.kungsbackacarcommunity.app.shell

import kotlin.math.*

internal data class MapDensityScreenPoint(val x: Double, val y: Double)

internal data class MapDensityItem(
    val id: String, val latitude: Double, val longitude: Double,
    val count: Int = 1, val critical: Boolean = false, val selected: Boolean = false,
    val category: Int = 0,
) {
    val hasValidLocation get() = latitude in -90.0..90.0 && longitude in -180.0..180.0
}
internal data class MapDensityGroup(val members: List<MapDensityItem>) {
    val anchor get() = members.firstOrNull { it.selected } ?: members.first()
    val id get() = members.minOf { it.id }
    val count get() = members.sumOf { it.count.coerceAtLeast(1) }
    val critical get() = members.any { it.critical }
    val selected get() = members.any { it.selected }
}

/** Retained zoom hysteresis; pan, rotation and tiny zoom oscillations do not regroup data. */
internal class MapDensityZoom {
    private var bucket: Int? = null
    fun update(zoom: Double): Int {
        val z = zoom.takeIf { it.isFinite() }?.coerceIn(0.0, 22.0) ?: 14.0
        var value = bucket ?: floor(z).toInt()
        while (value < 22 && z >= value + 1.15) value++
        while (value > 0 && z < value - 0.15) value--
        bucket = value
        return value
    }
}

/** Keep each marker category identifiable when neighbouring symbols overlap. */
internal object MapDensityPolicy {
    // Keep groups stable within a zoom bucket, but never hide two icons more than a marker width apart.
    fun overlapRadiusDp(zoom: Double, bucket: Int): Double =
        (14.0 * 2.0.pow(zoom - bucket)).coerceIn(10.0, 28.0)

    /** Actual rendered own position, never camera center; leader offset is bounded. */
    fun badgePosition(anchor: MapDensityScreenPoint, own: MapDensityScreenPoint?, width: Double, height: Double): MapDensityScreenPoint {
        if (own == null || hypot(anchor.x - own.x, anchor.y - own.y) >= 44) return anchor
        val candidates = (0..7).map { index ->
            val angle = (-45 + index * 45) * PI / 180
            MapDensityScreenPoint(own.x + cos(angle) * 44, own.y + sin(angle) * 44)
        }.filter { it.x in 24.0..(width - 24) && it.y in 24.0..(height - 24) && hypot(it.x - anchor.x, it.y - anchor.y) <= 88 }
        return candidates.minByOrNull { hypot(it.x - anchor.x, it.y - anchor.y) } ?: anchor
    }

    fun groups(items: List<MapDensityItem>, positions: Map<String, MapDensityScreenPoint>,
        radius: Double,
    ): List<MapDensityGroup> {
        val groups = mutableListOf<MutableList<MapDensityItem>>()
        val anchors = mutableListOf<Pair<Double, Double>>()
        val cells = mutableMapOf<Pair<Long, Long>, MutableList<Int>>()
        val ordered = items.filter { it.hasValidLocation && it.id in positions }.distinctBy { it.id }.sortedWith(
            compareByDescending<MapDensityItem> { it.critical }.thenBy { it.id })
        for (item in ordered) {
            val (x, y) = positions[item.id] ?: continue
            val cell = floor(x / radius).toLong() to floor(y / radius).toLong()
            val candidates = mutableListOf<Int>()
            for (dx in -1L..1L) for (dy in -1L..1L) candidates += cells[cell.first + dx to cell.second + dy].orEmpty()
            val match = candidates.filter { index ->
                if (groups[index].first().category != item.category) return@filter false
                val anchor = anchors[index]
                hypot(x - anchor.first, y - anchor.second) <= radius
            }.minOrNull()
            if (match != null) groups[match].add(item) else {
                cells.getOrPut(cell) { mutableListOf() }.add(groups.size)
                anchors.add(x to y)
                groups.add(mutableListOf(item))
            }
        }
        return groups.map { MapDensityGroup(it.toList()) }
    }
}
