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
