package com.kungsbackacarcommunity.app.police

import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.shell.MapIncidentMarker
import kotlin.math.abs

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

    /**
     * Coincidence threshold in RAW degrees, applied independently to latitude and
     * longitude (see [coincidesWithAnyIncident]). 1e-4° is ~11 m of LATITUDE everywhere;
     * for LONGITUDE a degree shrinks with latitude, so 1e-4° is a TIGHTER bound
     * east-west (≈6 m at Sweden's ~59°N) — fine here, since the pin and the
     * Police-category INCIDENT created by ONE "report police" tap land at the
     * identical GPS fix and any gap between them is only backend rounding; two
     * genuinely distinct police sightings this close are the same spot on a moving
     * map anyway. Used by [markers] to suppress the coincident duplicate. (A raw
     * per-axis degree check is deliberate — no haversine needed at this scale.)
     */
    const val INCIDENT_COINCIDENCE_EPSILON_DEG: Double = 1e-4

    /**
     * One police [MapIncidentMarker] per pin, ready to append to the layer's list.
     *
     * [suppressNearPoliceIncidents] are the locations of the Police-category
     * incident markers ALREADY drawn by the incident layer. A police pin coincident
     * with one of them is DROPPED here: reporting a police sighting from the map
     * creates BOTH a Police incident AND its short-TTL proximity pin at the same
     * fix, and both render as the identical blue police disc — so without this the
     * one sighting is drawn TWICE (the "two police icons" bug). Only the redundant
     * DRAW is removed; the full pin list still drives the proximity ALERT, and a
     * pin with no coincident incident (e.g. a convoy signal) is unaffected.
     */
    fun markers(
        pins: List<PoliceReport>,
        suppressNearPoliceIncidents: List<LatLng> = emptyList(),
    ): List<MapIncidentMarker> =
        // Common case (nothing to suppress): a single map pass, no coincidence
        // check. Only when there ARE Police incidents to dedupe against do we pay
        // the extra filter pass.
        (if (suppressNearPoliceIncidents.isEmpty()) {
            pins
        } else {
            pins.filterNot { pin -> coincidesWithAnyIncident(pin, suppressNearPoliceIncidents) }
        }).map { pin ->
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

    /**
     * True when [pin] sits within [INCIDENT_COINCIDENCE_EPSILON_DEG] of any of the
     * given Police-incident [incidents] — i.e. the same sighting the incident layer
     * is already drawing, so this pin must not be drawn a second time.
     */
    private fun coincidesWithAnyIncident(pin: PoliceReport, incidents: List<LatLng>): Boolean =
        incidents.any { incident ->
            abs(pin.latitude - incident.latitude) <= INCIDENT_COINCIDENCE_EPSILON_DEG &&
                abs(pin.longitude - incident.longitude) <= INCIDENT_COINCIDENCE_EPSILON_DEG
        }
}
