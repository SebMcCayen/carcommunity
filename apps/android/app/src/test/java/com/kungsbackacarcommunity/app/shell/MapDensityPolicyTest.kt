package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class MapDensityPolicyTest {
    private fun item(id: String, offset: Double = 0.0, count: Int = 1, critical: Boolean = false, selected: Boolean = false) =
        MapDensityItem(id, 57.0, 12.0 + offset, count, critical, selected)

    private fun groups(items: List<MapDensityItem>, vararg projectedX: Double): List<MapDensityGroup> {
        val positions = items.mapIndexed { i, item ->
            item.id to MapDensityScreenPoint(projectedX.getOrElse(i) { 0.0 }, 100.0)
        }.toMap()
        return MapDensityPolicy.groups(items, positions, 14.0)
    }

    @Test fun `a camera cannot be absorbed by a different category at the same zoom`() {
        val first = item("camera-west").copy(category = 1)
        val next = item("camera-east", offset = 0.00065).copy(category = 1)
        val hazard = first.copy(id = "hazard", category = 2, critical = true)
        val crown = next.copy(id = "crown", category = 3)
        val groups = groups(listOf(first, next, hazard, crown), 0.0, 30.0, 0.0, 30.0)
        assertEquals(setOf(first, next, hazard, crown), groups.map { it.members.single() }.toSet())
    }
    @Test fun `selection changes emphasis but not membership or group identity`() {
        val input = listOf(item("a-camera"), item("z-crown"), item("hazard", critical = true))
        val original = groups(input).single()
        val selected = groups(input.map { it.copy(selected = it.id == "z-crown") }).single()
        assertEquals(original.id, selected.id)
        assertEquals(original.members.map { it.id }, selected.members.map { it.id })
        assertEquals("z-crown", selected.anchor.id)
        assertTrue(selected.selected)
        assertTrue(selected.critical)
    }
    @Test fun `dense crowns bound visible count without losing accessible members`() {
        val source = (1..1_000).map { item("crown-$it") }
        val group = groups(source).single()
        assertEquals(1_000, group.members.size)
        assertEquals(source.map { it.id }.toSet(), group.members.map { it.id }.toSet())
    }
    @Test fun `catalog order and camera pan cannot change groups`() {
        val source = listOf(item("a"), item("b", .0001), item("c", .2))
        assertEquals(groups(source, 0.0, 5.0, 80.0), groups(source.reversed(), 100.0, 25.0, 20.0))
    }
    @Test fun `zoom hysteresis prevents boundary flicker and still allows zooming in`() {
        val zoom = MapDensityZoom()
        assertEquals(15, zoom.update(15.99))
        assertEquals(15, zoom.update(16.01))
        assertEquals(16, zoom.update(16.2))
        assertEquals(16, zoom.update(15.99))
        assertEquals(15, zoom.update(15.8))
        val source = listOf(item("a"), item("b", .001))
        assertEquals(1, groups(source, 0.0, 10.0).size)
        assertEquals(2, groups(source, 0.0, 32.0).size)
        assertTrue(MapDensityPolicy.overlapRadiusDp(16.14, 15) <= 28.0)
    }
    @Test fun `anchor bounded groups do not form a chain across the map`() {
        val groups = groups(listOf(item("a"), item("b"), item("c")), 0.0, 12.0, 24.0)
        assertEquals(2, groups.size)
        assertEquals(3, groups.sumOf { it.members.size })
    }
    @Test fun `badge protects actual own puck with a bounded truthful leader`() {
        val own = MapDensityScreenPoint(120.0, 300.0)
        val placed = MapDensityPolicy.badgePosition(own, own, 390.0, 844.0)
        assertTrue(hypot(placed.x - own.x, placed.y - own.y) >= 43.99)
        assertTrue(hypot(placed.x - own.x, placed.y - own.y) <= 88)
        val pannedAnchor = MapDensityScreenPoint(260.0, 300.0)
        assertEquals(pannedAnchor, MapDensityPolicy.badgePosition(pannedAnchor, own, 390.0, 844.0))
        assertEquals(own, MapDensityPolicy.badgePosition(own, null, 390.0, 844.0))
    }
    @Test fun `bad coordinates cannot crash layout and valid ids remain`() {
        val groups = groups(listOf(item("valid"), item("bad").copy(latitude = Double.NaN)))
        assertEquals(listOf("valid"), groups.flatMap { it.members }.map { it.id })
    }
    @Test fun `covered native hit cannot open invisible incident or crown`() {
        val surface = MapboxMapSurface()
        surface.incidentIdsByAnnotation["incident-native"] = "incident"
        surface.crownIdsByAnnotation["crown-native"] = "crown"
        surface.setGroupedMarkerIds(setOf("incident"), setOf("crown"))
        assertTrue(surface.onIncidentAnnotationClicked("incident-native"))
        assertTrue(surface.onCrownAnnotationClicked("crown-native"))
        assertNull(surface.incidentTap.value)
        assertNull(surface.crownTap.value)
        assertNull(surface.placeRequest.value)
        surface.emitIncidentTap("incident")
        surface.emitCrownTap("crown")
        assertEquals("incident", surface.incidentTap.value)
        assertEquals("crown", surface.crownTap.value)
    }
}
