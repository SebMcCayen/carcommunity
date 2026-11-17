package com.kungsbackacarcommunity.app.shell

import kotlin.math.*

internal data class MapDensityItem(
    val id: String, val latitude: Double, val longitude: Double,
    val count: Int = 1, val critical: Boolean = false, val selected: Boolean = false,
)
internal data class MapDensityGroup(val members: List<MapDensityItem>) {
    val anchor get() = members.first()
    val id get() = anchor.id
    val count get() = members.sumOf { it.count.coerceAtLeast(1) }
    val critical get() = members.any { it.critical }
    val selected get() = members.any { it.selected }
    val compactCount get() = if (count > 99) "99+" else count.toString()
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

/** Stable anchor-bounded groups across ALL static types; never drops a source id. */
internal object MapDensityPolicy {
    const val RADIUS_PIXELS = 56.0
    fun groups(items: List<MapDensityItem>, zoomBucket: Int): List<MapDensityGroup> {
        val size = 512.0 * 2.0.pow(zoomBucket.coerceIn(0, 22))
        val groups = mutableListOf<MutableList<MapDensityItem>>()
        val anchors = mutableListOf<Pair<Double, Double>>()
        val cells = mutableMapOf<Pair<Long, Long>, MutableList<Int>>()
        val ordered = items.distinctBy { it.id }.sortedWith(
            compareByDescending<MapDensityItem> { it.selected }.thenByDescending { it.critical }.thenBy { it.id })
        for (item in ordered) {
            val latitude = item.latitude.coerceIn(-85.05112878, 85.05112878) * PI / 180
            val x = (item.longitude + 180) / 360 * size
            val y = (1 - ln(tan(latitude) + 1 / cos(latitude)) / PI) / 2 * size
            val cell = floor(x / RADIUS_PIXELS).toLong() to floor(y / RADIUS_PIXELS).toLong()
            val candidates = mutableListOf<Int>()
            for (dx in -1L..1L) for (dy in -1L..1L) candidates += cells[cell.first + dx to cell.second + dy].orEmpty()
            val match = candidates.filter { index ->
                val anchor = anchors[index]
                hypot(x - anchor.first, y - anchor.second) <= RADIUS_PIXELS
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
