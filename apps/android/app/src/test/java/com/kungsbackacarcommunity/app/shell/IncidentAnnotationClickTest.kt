package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers what a tap on a drawn incident badge actually DOES, on the real
 * [MapboxMapSurface] handler rather than the stub's emit helpers.
 *
 * [IncidentTapSeamTest] asserts that the incident and "navigate here?" channels
 * stay separate once something has been published on them. It cannot catch the
 * failure this file exists for, because that failure is the handler declining to
 * publish at all and letting the gesture continue to the next handler — from the
 * stub's point of view nothing happened, which is exactly what it asserts.
 *
 * ## Why the return value decides whether a route preview opens
 *
 * The badge listener is invoked from the annotation plugin's own
 * `ClickInteraction`, and its Boolean is that interaction's result verbatim —
 * `AnnotationManagerImpl$registerInteractions$clickInteraction$1$1.invoke`
 * calls `OnAnnotationClickListener.onAnnotationClick`, stores the result and
 * returns it (maps-annotation 11.26.0). Under the Interactions API a false
 * result means "not consumed", so the tap carries on to the next registered
 * interaction — here `ClickInteraction.standardPoi`, which publishes a
 * [MapPlaceRequest] and opens the "navigate here?" preview.
 *
 * [dispatchTap] models exactly that two-handler stack, so these tests assert the
 * user-visible outcome — did a navigate prompt appear? — instead of asserting
 * the handler's return value, which on its own would prove nothing about what
 * the user sees.
 */
class IncidentAnnotationClickTest {

    /**
     * Replays a tap through the interaction stack: the annotation interaction
     * first (it is registered last, and later registrations are evaluated
     * first), then the basemap-POI interaction if the tap was not consumed.
     */
    private fun dispatchTap(surface: MapboxMapSurface, annotationId: String) {
        val consumed = surface.onIncidentAnnotationClicked(annotationId)
        if (!consumed) {
            // What ClickInteraction.standardPoi does when the tap reaches it.
            surface.emitPlaceTap(MapPoint(longitude = 12.0, latitude = 57.0), name = "Circle K")
        }
    }

    @Test
    fun `tapping a badge opens its incident and no navigate prompt`() {
        val surface = MapboxMapSurface()
        surface.incidentIdsByAnnotation["annotation-1"] = "incident-42"

        dispatchTap(surface, "annotation-1")

        assertEquals("incident-42", surface.incidentTap.value)
        assertNull("tapping a badge also raised a navigate prompt", surface.placeRequest.value)
    }

    @Test
    fun `tapping a badge whose incident id cannot be resolved raises no navigate prompt`() {
        // The badge is drawn and was hit-tested by the annotation plugin, but the
        // lookup has drifted (a swallowed native failure during a redraw), so the
        // handler cannot name the incident. It must still consume the tap: the
        // user pressed an accident badge, and offering to route them to a nearby
        // petrol station instead is the bug.
        val surface = MapboxMapSurface()

        dispatchTap(surface, "annotation-not-in-lookup")

        assertNull(
            "an unresolvable badge tap fell through to the basemap POI interaction " +
                "and raised a navigate-here prompt",
            surface.placeRequest.value,
        )
    }

    @Test
    fun `an unresolvable badge tap opens no incident sheet either`() {
        // Consuming the tap must not invent an incident to show.
        val surface = MapboxMapSurface()

        dispatchTap(surface, "annotation-not-in-lookup")

        assertNull(surface.incidentTap.value)
    }

    @Test
    fun `handling an unresolvable tap does not crash without a map`() {
        // The unresolvable path forces a redraw to repair the lookup. That
        // redraw is a no-op until an annotation manager exists, and must stay
        // safe rather than throwing out of the click listener. Whether the
        // redraw actually repopulates the lookup is only observable with a live
        // manager, so it is verified on device, not asserted here.
        val surface = MapboxMapSurface()
        surface.setIncidentMarkers(emptyList())

        dispatchTap(surface, "annotation-not-in-lookup")

        assertNull(surface.placeRequest.value)
    }
}
