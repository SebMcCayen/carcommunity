package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class MapDensityPolicyTest {
    private fun item(id: String, offset: Double = 0.0, count: Int = 1, critical: Boolean = false, selected: Boolean = false) =
        MapDensityItem(id, 57.0, 12.0 + offset, count, critical, selected)

    @Test fun `mixed types retain every source and critical shape metadata`() {
        val input = listOf(item("camera", count = 2), item("weather"), item("crown"), item("accident", critical = true))
        val group = MapDensityPolicy.groups(input, 15).single()
        assertEquals(input.map { it.id }.toSet(), group.members.map { it.id }.toSet())
        assertEquals(5, group.count)
        assertTrue(group.critical)
        assertEquals("accident", group.anchor.id)
    }
    @Test fun `selection changes emphasis but not membership or group identity`() {
        val input = listOf(item("a-camera"), item("z-crown"), item("hazard", critical = true))
        val original = MapDensityPolicy.groups(input, 15).single()
        val selected = MapDensityPolicy.groups(input.map { it.copy(selected = it.id == "z-crown") }, 15).single()
        assertEquals(original.id, selected.id)
        assertEquals(original.members.map { it.id }, selected.members.map { it.id })
        assertEquals("z-crown", selected.anchor.id)
        assertTrue(selected.selected)
        assertTrue(selected.critical)
    }
    @Test fun `dense crowns bound visible count without losing accessible members`() {
        val source = (1..1_000).map { item("crown-$it") }
        val group = MapDensityPolicy.groups(source, 18).single()
        assertEquals(1_000, group.members.size)
        assertEquals("99+", group.compactCount)
        assertEquals(source.map { it.id }.toSet(), group.members.map { it.id }.toSet())
    }
    @Test fun `catalog order and camera pan cannot change groups`() {
        val source = listOf(item("a"), item("b", .0001), item("c", .2))
        assertEquals(MapDensityPolicy.groups(source, 15), MapDensityPolicy.groups(source.reversed(), 15))
    }
    @Test fun `zoom hysteresis prevents boundary flicker and still allows zooming in`() {
        val zoom = MapDensityZoom()
        assertEquals(15, zoom.update(15.99))
        assertEquals(15, zoom.update(16.01))
        assertEquals(16, zoom.update(16.2))
        assertEquals(16, zoom.update(15.99))
        assertEquals(15, zoom.update(15.8))
        val source = listOf(item("a"), item("b", .001))
        assertTrue(MapDensityPolicy.groups(source, 18).size > MapDensityPolicy.groups(source, 13).size)
    }
    @Test fun `anchor bounded groups do not form a chain across the map`() {
        val degreePerPixel = 360 / (512.0 * (1 shl 15))
        val groups = MapDensityPolicy.groups(listOf(item("a"), item("b", degreePerPixel * 50), item("c", degreePerPixel * 100)), 15)
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
        val groups = MapDensityPolicy.groups(listOf(item("valid"), item("bad").copy(latitude = Double.NaN)), 15)
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
