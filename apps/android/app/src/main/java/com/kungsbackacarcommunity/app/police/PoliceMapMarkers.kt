package com.kungsbackacarcommunity.app.police

import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.MapIncidentMarker

/**
 * Maps live police pins to the shell's [MapIncidentMarker] drawing primitives, so
 * police markers RENDER THROUGH the existing shared incident marker layer
 * ([com.kungsbackacarcommunity.app.shell.IncidentMarkerLayer]) rather than a
 * second, separately-tuned Mapbox annotation layer. That reuse is deliberate: the
 * incident layer already solves the hard parts — a glyph-on-disc badge that is
 * legible to a colour-blind driver, one-time style-image registration, and, most
 * importantly, `iconAllowOverlap = true` so a marker is NEVER dropped by symbol
 * collision-culling (the #867/#897 zoom-cull class of bug lives in the projected
 * live-user chip path, which this does not touch).
 *
 * A police pin gets a DISTINCT look: the police glyph on a red disc (the same red
 * the convoy police reaction uses), so it reads as "police" at a glance and is
 * tellable from the incident layer's own blue "Police" incident category.
 *
 * The marker id is NAMESPACED ([POLICE_MARKER_ID_PREFIX]) so a tap on a police
 * marker resolves to no incident (the host's tap lookup finds nothing and no-ops)
 * — a police pin needs no detail sheet. Pure + host-owned so it is unit-testable
 * off-device ([PoliceMapMarkersTest]).
 */
object PoliceMapMarkers {
    /** Prefix on a police marker's id, so it never collides with an incident id. */
    const val POLICE_MARKER_ID_PREFIX = "police:"

    /** Red disc — matches the convoy police-reaction tint (KccPalette.errorRed). */
    const val DISC_COLOR_ARGB: Int = 0xFFC5221F.toInt()

    /** White glyph, for contrast on the red disc. */
    const val GLYPH_COLOR_ARGB: Int = 0xFFFFFFFF.toInt()

    /** One police [MapIncidentMarker] per pin, ready to append to the layer's list. */
    fun markers(pins: List<PoliceReport>): List<MapIncidentMarker> =
        pins.map { pin ->
            MapIncidentMarker(
                id = POLICE_MARKER_ID_PREFIX + pin.id,
                longitude = pin.longitude,
                latitude = pin.latitude,
                colorArgb = DISC_COLOR_ARGB,
                iconRes = R.drawable.ic_incident_police,
                glyphColorArgb = GLYPH_COLOR_ARGB,
                // Police pins never carry the incident layer's "reported gone"
                // fade — they are transient and age out on their own TTL.
                reportedCleared = false,
            )
        }
}
