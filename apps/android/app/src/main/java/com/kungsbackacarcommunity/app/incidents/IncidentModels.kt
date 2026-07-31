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
 *  - `incidents-confirm`    { incidentId } → { confirmationCount, clearedCount,
 *                           reportedCleared, expiresAt, alreadyConfirmed,
 *                           switchedFromClearVote }.
 *  - `incidents-reportCleared` { incidentId, latitude, longitude, capturedAt,
 *                           accuracyMeters?, mockLocationReported? } →
 *                           { clearedCount, confirmationCount, reportedCleared,
 *                           removed, alreadyVoted, switchedFromConfirmation }.
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
 * to render a marker; [id] identifies it for removal, [note] and the two fields
 * below feed the tap-to-open detail sheet.
 *
 * [reporterUid] and [createdAtIso] were ALREADY on the wire (the backend's
 * `IncidentView` has carried both since the callables were written) — the client
 * simply threw them away. They are parsed now because the detail sheet needs to
 * answer two questions the marker alone cannot: *whose* report is this (mine ⇒
 * offer remove, someone else's ⇒ offer confirm) and *how old* is it. Both stay
 * nullable: an imported Trafikverket row has no reporter, and a report read back
 * in the same round-trip as its own write has a server timestamp that has not
 * resolved yet, so `createdAt` legitimately comes back null.
 */
data class Incident(
    val id: String,
    val type: IncidentType,
    val longitude: Double,
    val latitude: Double,
    val note: String? = null,
    val source: String = "user",
    /** The uid that reported it, or null for imported/anonymous rows. */
    val reporterUid: String? = null,
    /** ISO-8601 creation instant as sent by the backend, or null when unresolved. */
    val createdAtIso: String? = null,
    /**
     * ISO-8601 instant Trafikverket ORIGINALLY posted about this, or null.
     *
     * Only Trafikverket imports carry it: their [createdAtIso] is our SYNC time
     * (when the backend importer wrote the doc every 30 min), which is NOT when
     * Trafikverket posted — showing it claimed a days-old roadwork was reported
     * "2 min ago". The backend now stores the upstream original time and sends it
     * here, and the sheet shows THIS as the age for a Trafikverket row. Null on a
     * Trafikverket row (upstream sent no usable time) ⇒ the sheet HIDES the age
     * line rather than showing the sync time. Always null for member reports,
     * whose [createdAtIso] already is their post time.
     */
    val postedAtIso: String? = null,
    /**
     * How many OTHER members have confirmed this incident is still there
     * (`incidents-confirm`). Carried on the backend's `IncidentView`; 0 until the
     * first confirmation and for imported rows (which are not confirmable). Drives
     * the "confirmed by N" line on the detail sheet as ambient social proof.
     */
    val confirmationCount: Int = 0,
    /**
     * How many members have voted that this incident is GONE
     * (`incidents-reportCleared`).
     *
     * Deliberately carried ALONGSIDE [confirmationCount] rather than netted into
     * it. The backend decides fade/removal on the net score, but the client shows
     * BOTH numbers, because the person reading the sheet is about to drive into
     * the spot and is better served by "3 say it's there, 1 says it's gone" than
     * by someone else's arithmetic.
     */
    val clearedCount: Int = 0,
    /**
     * True when the clear votes LEAD but have not reached the backend's removal
     * threshold: the incident is still real enough to draw, so the marker is
     * drawn FADED and the sheet says "reported gone by N".
     *
     * Server-derived, never computed here. The client must not infer the fade
     * from `clearedCount > confirmationCount` of its own accord: the threshold
     * and the tie-breaking rules live on the backend (evaluateClearVote), and a
     * second implementation here would be one more place for them to drift —
     * with a live hazard drawn as stale as the failure mode.
     */
    val reportedCleared: Boolean = false,
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
