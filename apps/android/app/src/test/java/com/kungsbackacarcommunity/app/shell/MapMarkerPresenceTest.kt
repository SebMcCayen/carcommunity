package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.*
import org.junit.Test

class MapMarkerPresenceTest {
    private data class Marker(val id: String, val label: String = "original")
    @Test fun `public exit retains pixels briefly but disables hit lookup immediately`() {
        val presence = MapMarkerPresence<Marker> { it.id }
        presence.frame(listOf(Marker("a")), 0, true, true)
        presence.frame(listOf(Marker("a")), 200, true, true)
        val exit = presence.frame(emptyList(), 201, true, true)
        assertEquals(listOf(Marker("a")), exit.markers)
        assertFalse(exit.frames.getValue("a").interactive)
        assertTrue(exit.animating)
        assertTrue(presence.frame(emptyList(), 401, true, true).markers.isEmpty())
        assertFalse(presence.frame(emptyList(), 402, true, true).animating)
    }
    @Test fun `metadata and ordering updates preserve opacity while background clears retention`() {
        val presence = MapMarkerPresence<Marker> { it.id }
        presence.frame(listOf(Marker("a"), Marker("b")), 0, true, true)
        val update = presence.frame(listOf(Marker("b"), Marker("a", "new")), 100, true, true)
        assertEquals(.5f, update.frames.getValue("a").alpha)
        assertTrue(update.markers.contains(Marker("a", "new")))
        assertTrue(presence.frame(emptyList(), 101, false, true).markers.isEmpty())
    }
    @Test fun `hidden grouped originals fade without exposing stale hit targets`() {
        val presence = MapMarkerPresence<Marker> { it.id }
        presence.frame(listOf(Marker("a")), 0, true, false)
        val masked = presence.frame(listOf(Marker("a")), 1, true, true, setOf("a"))
        assertFalse(masked.frames.getValue("a").interactive)
        assertTrue(presence.frame(listOf(Marker("a")), 201, true, true, setOf("a")).markers.isEmpty())
    }
    @Test fun `keyed native reconciliation retains annotations through reorder and metadata change`() {
        val reconciler = MapAnnotationReconciler<Marker, String> { it.id }
        var creates = 0; var updates = 0; var deletes = 0
        fun apply(markers: List<Marker>) = reconciler.reconcile(markers, { true },
            { creates++; "annotation-" + it.id }, { _, _ -> updates++ }, { deletes++ })
        assertTrue(apply(listOf(Marker("a"), Marker("b"))))
        assertTrue(apply(listOf(Marker("b"), Marker("a"))))
        assertEquals(2, creates); assertEquals(0, updates); assertEquals(0, deletes)
        assertTrue(apply(listOf(Marker("a", "changed"), Marker("b"))))
        assertEquals(2, creates); assertEquals(1, updates)
        assertTrue(apply(listOf(Marker("a", "changed"))))
        assertEquals(1, deletes)
    }
    @Test fun `failed native updates are retried without recreating successfully retained annotations`() {
        val reconciler = MapAnnotationReconciler<Marker, String> { it.id }
        var creates = 0; var fail = true; var attempts = 0
        fun apply(label: String) = reconciler.reconcile(listOf(Marker("a", label)), { true },
            { creates++; "native" }, { _, _ -> attempts++; check(!fail) }, {})
        assertTrue(apply("first"))
        assertFalse(apply("second")); fail = false
        assertTrue(apply("second"))
        assertEquals(1, creates); assertEquals(2, attempts)
    }
}
