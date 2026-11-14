package com.kungsbackacarcommunity.app.roadcameras

import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

/** One map marker per named photo-camera site. Speed cameras remain individual. */
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
            val group = if (camera.type == RoadCameraType.Speed) null else groups.firstOrNull {
                val anchor = it.first()
                val north = (camera.latitude - anchor.latitude) * 111_000
                val east = (camera.longitude - anchor.longitude) * 111_000 * cos(Math.toRadians(anchor.latitude))
                hypot(north, east) <= 50
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
