package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a tap on a drawn crown marker actually does, on the real
 * [MapboxMapSurface] handler.
 *
 * The sibling of [IncidentAnnotationClickTest], and here for the same reason its
 * KDoc sets out: the handler's Boolean IS the `ClickInteraction` result, so
 * returning false lets the tap fall through to `ClickInteraction.standardPoi` —
 * which would offer to route the user to whatever shop happens to sit under the
 * crown they tapped. [dispatchTap] models that two-handler stack so the tests
 * assert the user-visible outcome rather than a return value.
 *
 * The other half of what this file guards is CHANNEL SEPARATION: the crown layer
 * and the incidents layer have their own managers, their own lookups and their
 * own tap flows, and a crown must never surface as an incident (which would open
 * a detail sheet for something that is not an incident) or vice versa.
 */
class CrownAnnotationClickTest {

    private fun dispatchTap(surface: MapboxMapSurface, annotationId: String) {
        val consumed = surface.onCrownAnnotationClicked(annotationId)
        if (!consumed) {
            // What ClickInteraction.standardPoi does when the tap reaches it.
            surface.emitPlaceTap(MapPoint(longitude = 12.0, latitude = 57.0), name = "Circle K")
        }
    }

    @Test
    fun `tapping a crown opens that crown and no navigate prompt`() {
        val surface = MapboxMapSurface()
        surface.crownIdsByAnnotation["annotation-1"] = "spawn-42"

        dispatchTap(surface, "annotation-1")

        assertEquals("spawn-42", surface.crownTap.value)
        assertNull("tapping a crown also raised a navigate prompt", surface.placeRequest.value)
    }

    /**
     * An unresolvable annotation id means the lookup drifted from what is drawn.
     * The gesture is still consumed — offering a route preview because of an
     * internal desync would be a worse outcome than a no-op, and the desync
     * repairs itself before the user can tap again.
     */
    @Test
    fun `an unresolvable crown id still consumes the tap`() {
        val surface = MapboxMapSurface()

        dispatchTap(surface, "annotation-unknown")

        assertNull(surface.crownTap.value)
        assertNull("an unresolved crown tap must not open a navigate prompt", surface.placeRequest.value)
    }

    /**
     * The two layers' lookups are separate, so a crown tap never resolves to an
     * incident and an incident tap never resolves to a crown — even when the two
     * managers happen to hand out the same annotation id, which they can, since
     * ids are only unique within a manager.
     */
    @Test
    fun `crown and incident taps never resolve through each other's lookup`() {
        val surface = MapboxMapSurface()
        // Same annotation id in both managers: entirely possible, because each
        // manager numbers its own annotations.
        surface.crownIdsByAnnotation["1"] = "spawn-42"
        surface.incidentIdsByAnnotation["1"] = "incident-7"

        surface.onCrownAnnotationClicked("1")
        assertEquals("spawn-42", surface.crownTap.value)
        assertNull("a crown tap must not publish an incident", surface.incidentTap.value)

        surface.consumeCrownTap()
        surface.onIncidentAnnotationClicked("1")
        assertEquals("incident-7", surface.incidentTap.value)
        assertNull("an incident tap must not publish a crown", surface.crownTap.value)
    }

    /** Consuming clears the slot, so a later tap on the same crown re-triggers. */
    @Test
    fun `consuming a crown tap lets the same crown be tapped again`() {
        val surface = MapboxMapSurface()
        surface.crownIdsByAnnotation["a"] = "spawn-1"

        surface.onCrownAnnotationClicked("a")
        assertEquals("spawn-1", surface.crownTap.value)
        surface.consumeCrownTap()
        assertNull(surface.crownTap.value)
        surface.onCrownAnnotationClicked("a")
        assertEquals("spawn-1", surface.crownTap.value)
    }

    /**
     * The stub honours the same contract, so the shell's wiring can be exercised
     * without a GL surface.
     */
    @Test
    fun `the stub surface carries the crown seam too`() {
        val stub = StubMapSurface(autoLoad = false)
        assertNull(stub.crownTap.value)
        stub.emitCrownTap("spawn-9")
        assertEquals("spawn-9", stub.crownTap.value)
        stub.consumeCrownTap()
        assertNull(stub.crownTap.value)

        val markers =
            listOf(
                MapCrownMarker(
                    id = "spawn-9",
                    longitude = 12.0,
                    latitude = 57.0,
                    discColorArgb = 0xFF8E9AA6.toInt(),
                    iconRes = 1,
                    glyphColorArgb = 0xFF1A1A1A.toInt(),
                    glowColorArgb = null,
                ),
            )
        stub.setCrownMarkers(markers)
        assertEquals(markers, stub.crownMarkers.value)
        // The layer comes down by being handed an empty list — which is what the
        // host pushes the moment the flag reads false.
        stub.setCrownMarkers(emptyList())
        assertEquals(emptyList<MapCrownMarker>(), stub.crownMarkers.value)
    }
}
