package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.*
import org.junit.Test

class PublicMarkerExitTapTest {
    @Test fun `events keep their original detail contract and removed pins consume without reopening`() {
        val surface = MapboxMapSurface()
        surface.setEventMarkers(listOf(MapEventMarker("event", 12.0, 57.0)))
        surface.eventIdsByAnnotation["native"] = "event"
        assertTrue(surface.onEventAnnotationClicked("native"))
        assertEquals("event", surface.eventTap.value)
        surface.consumeEventTap()
        surface.setEventMarkers(emptyList())
        surface.eventIdsByAnnotation["native"] = "event" // A queued hit from the outgoing native annotation.
        assertTrue(surface.onEventAnnotationClicked("native"))
        assertNull(surface.eventTap.value)
        assertNull(surface.placeRequest.value)
    }
    @Test fun `sponsored pins keep their original detail contract and exit cannot open an old advert`() {
        val surface = MapboxMapSurface()
        surface.setBillboardMarkers(listOf(MapBillboardMarker("ad", 12.0, 57.0)))
        surface.billboardIdsByAnnotation["native"] = "ad"
        assertTrue(surface.onBillboardAnnotationClicked("native"))
        assertEquals("ad", surface.billboardTap.value)
        surface.consumeBillboardTap()
        surface.setBillboardMarkers(emptyList())
        surface.billboardIdsByAnnotation["native"] = "ad"
        assertTrue(surface.onBillboardAnnotationClicked("native"))
        assertNull(surface.billboardTap.value)
        assertNull(surface.placeRequest.value)
    }
}
