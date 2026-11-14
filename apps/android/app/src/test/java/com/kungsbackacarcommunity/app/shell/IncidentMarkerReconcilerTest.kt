package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentMarkerReconcilerTest {
    private class Annotation(var marker: MapIncidentMarker)

    private class Renderer {
        val reconciler = IncidentMarkerReconciler<Annotation>()
        val visible = mutableMapOf<String, Annotation>()
        val operations = mutableListOf<String>()
        var imageReady = true
        var failCreate = false
        var failUpdate = false
        var failDelete = false

        fun draw(markers: List<MapIncidentMarker>): Boolean = reconciler.reconcile(
            markers = markers,
            prepare = { imageReady },
            create = { marker ->
                check(!failCreate)
                Annotation(marker).also {
                    visible[marker.id] = it
                    operations += "create:${marker.id}"
                }
            },
            update = { annotation, marker ->
                // Mapbox annotation properties are mutable before update() runs.
                annotation.marker = marker
                check(!failUpdate)
                operations += "update:${marker.id}"
            },
            delete = { annotation ->
                check(!failDelete)
                visible.remove(annotation.marker.id)
                operations += "delete:${annotation.marker.id}"
            },
        )
    }

    private fun camera(id: String) = MapIncidentMarker(
        id = "road-camera:$id",
        longitude = 12.0,
        latitude = 57.0,
        colorArgb = 1,
        iconRes = 2,
        glyphColorArgb = 3,
    )

    @Test
    fun `zoom and distance reordering retain all 250 native annotations and tap ids`() {
        val renderer = Renderer()
        val cameras = (1..250).map { camera(it.toString()) }
        assertTrue(renderer.draw(cameras))
        val original = renderer.visible.toMap()
        renderer.operations.clear()

        repeat(20) { offset ->
            assertTrue(renderer.draw(cameras.drop(offset) + cameras.take(offset)))
        }

        assertTrue(renderer.operations.isEmpty())
        renderer.reconciler.forEachAnnotation { id, annotation ->
            assertSame(original.getValue(id), annotation)
            assertEquals(id, annotation.marker.id)
        }
        assertEquals(original.keys, renderer.visible.keys)
    }

    @Test
    fun `changing the nearby selection only adds and removes affected cameras`() {
        val renderer = Renderer()
        val a = camera("a")
        val b = camera("b")
        val c = camera("c")
        renderer.draw(listOf(a, b))
        val retained = renderer.visible.getValue(b.id)
        renderer.operations.clear()

        assertTrue(renderer.draw(listOf(c, b)))

        assertSame(retained, renderer.visible.getValue(b.id))
        assertEquals(listOf("delete:${a.id}", "create:${c.id}"), renderer.operations)
    }

    @Test
    fun `changed position and appearance update the same annotation`() {
        val renderer = Renderer()
        val camera = camera("a")
        renderer.draw(listOf(camera))
        val original = renderer.visible.getValue(camera.id)
        renderer.operations.clear()
        val changed = camera.copy(
            longitude = 13.0,
            latitude = 58.0,
            colorArgb = 4,
            iconRes = 5,
            glyphColorArgb = 6,
            reportedCleared = true,
        )

        assertTrue(renderer.draw(listOf(changed)))
        assertTrue(renderer.draw(listOf(changed)))

        assertSame(original, renderer.visible.getValue(camera.id))
        assertEquals(changed, original.marker)
        assertEquals(listOf("update:${camera.id}"), renderer.operations)
    }

    @Test
    fun `disabling camera layers preserves other incident annotations`() {
        val renderer = Renderer()
        val camera = camera("a")
        val incident = camera.copy(id = "incident:a")
        renderer.draw(listOf(camera, incident))
        val retained = renderer.visible.getValue(incident.id)
        renderer.operations.clear()

        assertTrue(renderer.draw(listOf(incident)))

        assertSame(retained, renderer.visible.getValue(incident.id))
        assertEquals(listOf("delete:${camera.id}"), renderer.operations)
        assertTrue(renderer.draw(emptyList()))
        assertTrue(renderer.visible.isEmpty())
    }

    @Test
    fun `missing image leaves existing badge intact and retries the desired appearance`() {
        val renderer = Renderer()
        val camera = camera("a")
        renderer.draw(listOf(camera))
        val original = renderer.visible.getValue(camera.id)
        val changed = camera.copy(iconRes = 99)
        renderer.imageReady = false
        renderer.operations.clear()

        assertFalse(renderer.draw(listOf(changed, camera("b"))))
        assertSame(original, renderer.visible.getValue(camera.id))
        assertEquals(camera, original.marker)
        assertTrue(renderer.operations.isEmpty())

        renderer.imageReady = true
        assertTrue(renderer.draw(listOf(changed, camera("b"))))
        assertSame(original, renderer.visible.getValue(camera.id))
        assertEquals(changed, original.marker)
        assertEquals(2, renderer.visible.size)
    }

    @Test
    fun `failed native creation is retried with the same desired list`() {
        val renderer = Renderer()
        val camera = camera("a")
        renderer.failCreate = true
        assertFalse(renderer.draw(listOf(camera)))
        assertTrue(renderer.visible.isEmpty())

        renderer.failCreate = false
        assertTrue(renderer.draw(listOf(camera)))
        assertEquals(listOf("create:${camera.id}"), renderer.operations)
    }

    @Test
    fun `failed mutable update retries without recreating the badge`() {
        val renderer = Renderer()
        val camera = camera("a")
        renderer.draw(listOf(camera))
        val original = renderer.visible.getValue(camera.id)
        val changed = camera.copy(longitude = 13.0)
        renderer.operations.clear()
        renderer.failUpdate = true
        assertFalse(renderer.draw(listOf(changed)))

        renderer.failUpdate = false
        assertTrue(renderer.draw(listOf(changed)))
        assertSame(original, renderer.visible.getValue(camera.id))
        assertEquals(listOf("update:${camera.id}"), renderer.operations)
    }

    @Test
    fun `reverting desired data after a failed mutable update restores the badge`() {
        val renderer = Renderer()
        val camera = camera("a")
        renderer.draw(listOf(camera))
        renderer.failUpdate = true
        assertFalse(renderer.draw(listOf(camera.copy(longitude = 13.0))))

        renderer.failUpdate = false
        assertTrue(renderer.draw(listOf(camera)))
        assertEquals(camera, renderer.visible.getValue(camera.id).marker)
    }

    @Test
    fun `failed deletion retains the tap lookup until a successful retry`() {
        val renderer = Renderer()
        val camera = camera("a")
        renderer.draw(listOf(camera))
        renderer.failDelete = true
        assertFalse(renderer.draw(emptyList()))
        val ids = mutableListOf<String>()
        renderer.reconciler.forEachAnnotation { id, _ -> ids += id }
        assertEquals(listOf(camera.id), ids)

        renderer.failDelete = false
        assertTrue(renderer.draw(emptyList()))
        renderer.reconciler.forEachAnnotation { _, _ -> error("stale tap target") }
        assertTrue(renderer.visible.isEmpty())
    }

    @Test
    fun `style reload resets annotation handles and recreates current badges`() {
        val renderer = Renderer()
        val cameras = listOf(camera("a"), camera("b"))
        renderer.draw(cameras)
        val original = renderer.visible.toMap()

        renderer.reconciler.clear()
        renderer.visible.clear() // The old manager and its annotations are gone.
        renderer.operations.clear()
        assertTrue(renderer.draw(cameras))

        assertEquals(2, renderer.operations.size)
        assertTrue(renderer.visible.all { (id, annotation) -> original[id] !== annotation })
    }
}
