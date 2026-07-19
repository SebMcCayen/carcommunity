package com.kungsbackacarcommunity.app.incidents

/**
 * Firebase-free domain models for the crowd-sourced incidents / roadwork map
 * layer (navigation feature). Kept Android- and Mapbox-free so the wire
 * mapping and the reporting logic are JVM-unit-testable in CI without a device,
 * a token, or the network.
 *
 * The backend contract is the `incidents.*` callables (europe-west1):
 *  - `incidents-report`     { type, latitude, longitude, note? } → the created incident.
 *  - `incidents-listNearby` { latitude, longitude, radiusMeters? } → { incidents: [...] }.
 *  - `incidents-remove`     { incidentId } → { removed }.
 *
 * The wire `type` strings here MUST match the backend INCIDENT_TYPES enum.
 */

/** A reportable incident category. [wire] is the backend enum value. */
enum class IncidentType(val wire: String) {
    ACCIDENT("accident"),
    ROADWORK("roadwork"),
    HAZARD("hazard"),
    POLICE("police"),
    ROAD_CLOSED("road_closed"),
    ;

    companion object {
        /** Maps a backend wire value to a type, or null when unknown. */
        fun fromWire(value: String?): IncidentType? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * An active incident to draw on the map. Only the coordinate + type are needed
 * to render a marker; [id] identifies it for removal, [note] for a future
 * detail sheet.
 */
data class Incident(
    val id: String,
    val type: IncidentType,
    val longitude: Double,
    val latitude: Double,
    val note: String? = null,
    val source: String = "user",
)

/** The backend's `source` value for incidents imported from Trafikverket. */
const val INCIDENT_SOURCE_TRAFIKVERKET = "trafikverket"

/**
 * Whether any of [incidents] actually came from Trafikverket.
 *
 * Drives the "Källa: Trafikverket" credit: we owe the attribution wherever their
 * open data is ON SCREEN, and only there. Trafikverket is a Swedish road
 * authority and the importer only covers Sweden — which is intended, we are not
 * sourcing other countries' road data — so a member driving in France gets a
 * nearby-incidents list with no imported rows and must not be shown a credit for
 * data that is not being displayed. The same holds for a Swedish area that
 * simply has no active imported incidents right now.
 *
 * Note this is about ATTRIBUTION only. An empty list abroad is a legitimate
 * result, not a failure: [IncidentReportController.refresh] keeps the previous
 * markers on a fetch error and swaps in whatever it got on success, with no
 * error surface either way, and no incidents path reports to
 * `ClientErrorReporter` — so an empty foreign area cannot auto-file a GitHub
 * issue.
 */
fun hasTrafikverketData(incidents: List<Incident>): Boolean =
    incidents.any { it.source == INCIDENT_SOURCE_TRAFIKVERKET }
