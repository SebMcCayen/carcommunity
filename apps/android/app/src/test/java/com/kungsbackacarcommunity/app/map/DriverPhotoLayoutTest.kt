package com.kungsbackacarcommunity.app.map

import org.junit.Assert.*
import org.junit.Test

class DriverPhotoLayoutTest {
    private val own = DriverPhotoPoint(195.0, 500.0)
    private val coLocated = listOf("convoy-driver", "nearby-driver").map { DriverPhotoAnchor(it, own) }
    private fun layout(engine: DriverPhotoLayout = DriverPhotoLayout(), anchors: List<DriverPhotoAnchor> = coLocated,
        ownPoint: DriverPhotoPoint? = own, exclusions: List<DriverPhotoRect> = emptyList()) = engine.layout(anchors, 390.0, 844.0, ownPoint, exclusions)

    @Test fun `co located nearby and convoy photos preserve the own puck and distinct hit targets`() {
        val result = layout()
        assertEquals(2, result.placed.size)
        assertTrue(result.overflow.isEmpty())
        assertFalse(result.placed[0].hitBounds.intersects(result.placed[1].hitBounds))
        for (item in result.placed) {
            assertEquals(own, item.anchor)
            assertFalse(item.showAnchor) // The real dot sits under the user's protected puck.
            assertTrue(item.lineStart.distanceTo(own) >= 27.999)
            assertEquals(48.0, item.hitBounds.right - item.hitBounds.left, 0.0)
            assertTrue(item.photo.distanceTo(own) >= 56.0 - 1e-9)
        }
    }

    @Test fun `a roster reorder does not swap uid bound positions or targets`() {
        val engine = DriverPhotoLayout()
        val before = layout(engine).placed.associateBy { it.uid }
        val after = layout(engine, coLocated.reversed()).placed.associateBy { it.uid }
        assertEquals(before, after)
    }

    @Test fun `small geographic movement retains the same slot`() {
        val engine = DriverPhotoLayout()
        val before = layout(engine).placed.associate { it.uid to it.slot }
        val moved = coLocated.map { it.copy(point = DriverPhotoPoint(it.point.x + 0.2, it.point.y - 0.2)) }
        assertEquals(before, layout(engine, moved).placed.associate { it.uid to it.slot })
    }

    @Test fun `actual own point at a panned edge is protected rather than camera center`() {
        val edge = DriverPhotoPoint(22.0, 400.0)
        val result = layout(anchors = listOf(DriverPhotoAnchor("nearby", edge)), ownPoint = edge)
        val item = result.placed.single()
        assertEquals(edge, item.anchor)
        assertTrue(item.hitBounds.left >= 8)
        assertTrue(item.lineStart.distanceTo(edge) >= 27.999)
        assertFalse(item.photo == DriverPhotoPoint(195.0, 422.0))
    }

    @Test fun `toolbar and session dock rectangles exclude photo touch bounds`() {
        val controls = listOf(DriverPhotoRect(310.0, 80.0, 390.0, 600.0), DriverPhotoRect(0.0, 730.0, 390.0, 844.0))
        val result = layout(anchors = listOf(DriverPhotoAnchor("near-rail", DriverPhotoPoint(300.0, 350.0)),
            DriverPhotoAnchor("near-dock", DriverPhotoPoint(190.0, 725.0))), exclusions = controls)
        assertEquals(2, result.placed.size)
        result.placed.forEach { item -> assertTrue(controls.none { it.intersects(item.hitBounds) }) }
    }

    @Test fun `unplaceable drivers remain explicit overflow instead of silently vanishing`() {
        val anchors = (0..199).map { DriverPhotoAnchor("driver-${it.toString().padStart(2, '0')}", own) }
        val result = layout(anchors = anchors)
        assertEquals(anchors.map { it.uid }.toSet(), (result.placed.map { it.uid } + result.overflow.map { it.uid }).toSet())
        assertTrue(result.overflow.isNotEmpty())
        for ((index, first) in result.placed.withIndex()) result.placed.drop(index + 1).forEach { second -> assertFalse(first.hitBounds.intersects(second.hitBounds)) }
    }

    @Test fun `dense co location expands to longer lines and retains every photograph`() {
        val anchors = (0..11).map { DriverPhotoAnchor("driver-$it", own) }
        val result = layout(anchors = anchors)
        assertEquals(12, result.placed.size)
        assertTrue(result.overflow.isEmpty())
        assertTrue(result.placed.any { it.photo.distanceTo(it.anchor) > 72 })
    }

    @Test fun `configured maximum roster fits phones with actual individual control bounds`() {
        for (height in listOf(700.0, 844.0)) {
            val center = DriverPhotoPoint(195.0, height * 0.6)
            val controls = listOf(DriverPhotoRect(16.0, 16.0, 64.0, 64.0), DriverPhotoRect(326.0, 16.0, 374.0, 64.0),
                DriverPhotoRect(12.0, height - 68, 378.0, height - 12)) +
                (0..4).map { DriverPhotoRect(326.0, 88.0 + it * 60, 374.0, 136.0 + it * 60) }
            val anchors = (0..73).map { DriverPhotoAnchor("driver-$it", center) }
            val result = DriverPhotoLayout().layout(anchors, 390.0, height, center, controls)
            assertEquals(74, result.placed.size)
            assertTrue(result.overflow.isEmpty())
            for ((index, first) in result.placed.withIndex()) {
                assertTrue(controls.none { it.intersects(first.hitBounds) })
                result.placed.drop(index + 1).forEach { assertFalse(first.hitBounds.intersects(it.hitBounds)) }
            }
        }
    }

    @Test fun `dense placement tokens and offsets remain stable during geographic motion`() {
        val engine = DriverPhotoLayout()
        val anchors = (0..11).map { DriverPhotoAnchor("driver-$it", own) }
        val first = layout(engine, anchors).placed
        val shift = DriverPhotoPoint(own.x + 0.1, own.y + 0.1)
        val second = layout(engine, anchors.map { it.copy(point = shift) }, shift).placed
        assertEquals(first.map { it.slot }, second.map { it.slot })
        first.zip(second).forEach { (a, b) -> assertEquals(0.1, b.photo.x - a.photo.x, 1e-8) }
    }

    @Test fun `a crowd returning to isolated drivers restores short leaders`() {
        val engine = DriverPhotoLayout()
        val crowded = (0..73).map { DriverPhotoAnchor("driver-$it", own) }
        assertEquals(74, layout(engine, crowded).placed.size)
        val small = layout(engine, crowded.take(2)).placed
        assertEquals(2, small.size)
        assertTrue(small.all { it.slot < 16 && it.photo.distanceTo(it.anchor) <= 72.001 })
        val second = layout(engine, crowded.take(2)).placed
        assertEquals(small, second)
    }

    @Test fun `a spread out former crowd returns to short leaders without roster churn`() {
        val engine = DriverPhotoLayout()
        val crowded = (0..11).map { DriverPhotoAnchor("driver-$it", own) }
        layout(engine, crowded)
        val spread = crowded.mapIndexed { i, item -> item.copy(point = DriverPhotoPoint(75.0 + i % 3 * 120, 110.0 + i / 3 * 160)) }
        val result = layout(engine, spread, ownPoint = DriverPhotoPoint(20.0, 810.0))
        assertEquals(12, result.placed.size)
        assertTrue(result.placed.all { it.slot < 16 })
    }

    @Test fun `uid hashes are stable across platform implementations`() {
        assertEquals(1_700_010_463, DriverPhotoLayout.stableHash("driver"))
    }

    @Test fun `invalid projections and empty rosters do not leave cached photos`() {
        val engine = DriverPhotoLayout()
        layout(engine)
        assertTrue(layout(engine, emptyList()).placed.isEmpty())
        assertTrue(layout(engine, listOf(DriverPhotoAnchor("bad", DriverPhotoPoint(Double.NaN, 0.0)))).placed.isEmpty())
    }
}
