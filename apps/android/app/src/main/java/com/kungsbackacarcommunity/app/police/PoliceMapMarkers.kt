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
 * A police pin uses the police glyph on a BLUE disc — the SAME blue the incident
 * layer's "Police" category uses ([IncidentPalette] POLICE = 0xFF1565C0), the
 * app's "police = blue" look. This deliberately RESEMBLES the incidents-police
 * pin: the owner (Seb) chose the shared blue over a distinct tint, accepting the
 * overlap, because "police is blue" reads correctly at a glance and the two
 * layers age out on their own anyway.
 *
 * The marker id is NAMESPACED ([POLICE_MARKER_ID_PREFIX]) so the host can tell a
 * police-pin tap from an incident tap: the incident lookup misses (the id is not
 * an incident id) and the host routes the tap to the police detail sheet instead
 * ([com.kungsbackacarcommunity.app.police.PoliceDetailsSheet]). Pure + host-owned
 * so it is unit-testable off-device ([PoliceMapMarkersTest]).
 */
object PoliceMapMarkers {
    /** Prefix on a police marker's id, so it never collides with an incident id. */
    const val POLICE_MARKER_ID_PREFIX = "police:"

    /**
     * Blue disc — matches the incident layer's "Police" category
     * ([com.kungsbackacarcommunity.app.incidents.IncidentPalette] POLICE), so
     * every police pin on the map is the one "police = blue" colour. Referenced as
     * a literal (not imported) to keep this object free of the incidents module,
     * exactly as the id prefix and glyph colour are; [PoliceMapMarkersTest] pins it
     * equal to the incidents police blue so the two cannot silently drift.
     */
    const val DISC_COLOR_ARGB: Int = 0xFF1565C0.toInt()

    /** White glyph, for contrast on the blue disc. */
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
