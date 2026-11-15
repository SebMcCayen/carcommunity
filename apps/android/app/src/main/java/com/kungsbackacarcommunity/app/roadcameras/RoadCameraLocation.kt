package com.kungsbackacarcommunity.app.roadcameras

import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** A named photo-camera site or a verified pair of opposite-facing speed cameras. */
data class RoadCameraLocation(val views: List<RoadCamera>) {
    val camera get() = views.first()
}

fun RoadCamera.compassIndex(): Int? = direction?.takeIf { it.isFinite() && it in 0.0..360.0 }
    ?.let { ((it % 360 + 22.5) / 45).toInt() % 8 }

fun groupRoadCameraLocations(cameras: List<RoadCamera>): List<RoadCameraLocation> {
    val locations = mutableListOf<RoadCameraLocation>()
    // Sorting both anchors and views makes the marker and initial direction independent
    // of catalog order. Anchor distance avoids joining a chain of neighboring sites.
    cameras.groupBy { it.type to it.name.trim().lowercase(Locale.ROOT) }.values.forEach { named ->
        val groups = mutableListOf<MutableList<RoadCamera>>()
        named.sortedBy { it.id }.forEach { camera ->
            val group = groups.firstOrNull {
                val anchor = it.first()
                val north = (camera.latitude - anchor.latitude) * 111_000
                val east = (camera.longitude - anchor.longitude) * 111_000 * cos(Math.toRadians(anchor.latitude))
                if (camera.type == RoadCameraType.Speed) {
                    it.size == 1 && hypot(north, east) <= 150 && oppositeCameraDirections(anchor, camera)
                } else hypot(north, east) <= 50
            }
            if (group == null) groups.add(mutableListOf(camera)) else group.add(camera)
        }
        groups.forEach { group ->
            locations.add(RoadCameraLocation(group.sortedWith(compareBy<RoadCamera> {
                it.direction?.takeIf { bearing -> bearing.isFinite() && bearing in 0.0..360.0 }?.rem(360) ?: 361.0
            }.thenBy { it.id })))
        }
    }
    return locations.sortedBy { it.camera.id }
}

fun oppositeCameraDirections(first: RoadCamera, second: RoadCamera): Boolean {
    val a = first.direction?.takeIf { it.isFinite() && it in 0.0..360.0 } ?: return false
    val b = second.direction?.takeIf { it.isFinite() && it in 0.0..360.0 } ?: return false
    val difference = abs(a - b) % 360
    return minOf(difference, 360 - difference) >= 135
}

data class RoadCameraMapGroup(val sites: List<RoadCameraLocation>) {
    // Anchor to a real camera, never a centroid that could land on a lawn.
    val camera = sites.first().camera
    val views = sites.flatMap { it.views }
    val opposingDirections = sites.size == 1 && camera.type == RoadCameraType.Speed &&
        sites.first().views.size == 2 && oppositeCameraDirections(sites.first().views[0], sites.first().views[1])
}

/** Visual groups only: zooming in restores every site, without merging source data. */
fun groupRoadCameraMarkers(sites: List<RoadCameraLocation>, zoom: Double): List<RoadCameraMapGroup> {
    val bucket = floor(zoom.takeIf { it.isFinite() } ?: 14.0).coerceIn(0.0, 22.0)
    val ordered = sites.sortedBy { it.camera.id }
    if (bucket >= 17) return ordered.map { RoadCameraMapGroup(listOf(it)) }
    val latitude = ordered.firstOrNull()?.camera?.latitude ?: return emptyList()
    val longitudeScale = cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
    val radius = (64 * 78_271.516964 * longitudeScale / 2.0.pow(bucket)).coerceIn(1.0, 10_000.0)
    val groups = mutableListOf<MutableList<RoadCameraLocation>>()
    val cells = mutableMapOf<Triple<RoadCameraType, Long, Long>, MutableList<Int>>()
    ordered.forEach { site ->
        val camera = site.camera
        val x = floor(camera.longitude * 111_000 * longitudeScale / radius).toLong()
        val y = floor(camera.latitude * 111_000 / radius).toLong()
        val candidates = (-1L..1L).flatMap { dx -> (-1L..1L).flatMap { dy ->
            cells[Triple(camera.type, x + dx, y + dy)].orEmpty()
        } }
        val index = candidates.filter { candidate ->
            val anchor = groups[candidate].first().camera
            val north = (camera.latitude - anchor.latitude) * 111_000
            val east = (camera.longitude - anchor.longitude) * 111_000 * longitudeScale
            hypot(north, east) <= radius
        }.minOrNull()
        if (index == null) {
            cells.getOrPut(Triple(camera.type, x, y)) { mutableListOf() }.add(groups.size)
            groups.add(mutableListOf(site))
        } else groups[index].add(site)
    }
    return groups.map { RoadCameraMapGroup(it) }
}
