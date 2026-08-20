package com.kungsbackacarcommunity.app.police

import com.kungsbackacarcommunity.app.navigation.LatLng

/**
 * The small police-pin API surfaced to the map shell: report a police pin at a
 * location, and read the live pins near a point to draw markers + drive the
 * proximity alert. Mirrors [com.kungsbackacarcommunity.app.incidents.IncidentRepository]
 * so the two map layers share one shape.
 *
 * Backed on device by [FirebasePoliceRepository] (the `police-*` callables);
 * injectable so the controller stays JVM-unit-testable with a fake.
 */
interface PoliceRepository {
    /**
     * Reports a police pin at [location]. [source] records how it was raised
     * ("manual" for the standalone action, "convoy" when raised alongside a
     * convoy police reaction). Returns the created pin. Throws on failure.
     */
    suspend fun report(location: LatLng, source: String = SOURCE_MANUAL): PoliceReport

    /** Active, unexpired police pins within [radiusMeters] of [center]. */
    suspend fun listNearby(center: LatLng, radiusMeters: Double = DEFAULT_RADIUS_METERS): List<PoliceReport>

    /**
     * Removes the caller's OWN pin [policeReportId] (`police.remove`, owner-only on
     * the server). Returns true when a pin was deleted; false is the idempotent
     * no-op for a pin that had already aged out. Throws on any other failure.
     */
    suspend fun remove(policeReportId: String): Boolean

    /**
     * Confirms someone else's pin [policeReportId] is still there
     * (`police.confirm`). Returns the updated tallies. Throws on failure (e.g. the
     * reporter cannot confirm their own pin; an expired pin cannot be verified).
     */
    suspend fun confirm(policeReportId: String): PoliceVerifyResult

    /**
     * Disputes someone else's pin [policeReportId] ("Borta/Not here",
     * `police.dispute`). Returns the updated tallies. A dispute informs only — it
     * never removes the pin. Throws on failure.
     */
    suspend fun dispute(policeReportId: String): PoliceVerifyResult

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_CONVOY = "convoy"

        /** Default query radius, matching the incident layer's default. */
        const val DEFAULT_RADIUS_METERS = 15_000.0
    }
}

/**
 * The result of a `police.confirm` / `police.dispute` call — the pin's tallies
 * after the vote, plus whether this caller had already voted this side.
 */
data class PoliceVerifyResult(
    val policeReportId: String,
    val confirmationCount: Int,
    val disputeCount: Int,
    val alreadyVoted: Boolean,
    val switched: Boolean,
)
