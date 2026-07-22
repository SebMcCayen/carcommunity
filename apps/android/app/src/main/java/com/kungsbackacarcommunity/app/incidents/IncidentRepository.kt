package com.kungsbackacarcommunity.app.incidents

import com.kungsbackacarcommunity.app.navigation.LatLng

/**
 * Data-layer seam for the incidents domain, backed by the `incidents.*`
 * callables. Kept as an interface so the shell/controller and tests are written
 * against it (a fake in tests, [FirebaseIncidentRepository] on device).
 */
interface IncidentRepository {
    /**
     * Reports an incident of [type] at [location]; throws on failure.
     *
     * Returns the CREATED incident (the callable answers with it, id and all),
     * so the caller can put it on the map from the write alone. It used to return
     * Unit and the map only learned about the report via a follow-up
     * [listNearby] — a second, independently-failing round-trip standing between
     * "reported" and the pin the user was promised.
     */
    suspend fun report(type: IncidentType, location: LatLng, note: String? = null): Incident

    /**
     * Active, unexpired incidents within [radiusMeters] of [center]. The
     * backend filters by geo-cell + exact radius; the client never scans.
     */
    suspend fun listNearby(center: LatLng, radiusMeters: Double = DEFAULT_RADIUS_METERS): List<Incident>

    /** Removes the caller's own incident (idempotent); admins may remove any. */
    suspend fun remove(incidentId: String)

    /**
     * Confirms SOMEONE ELSE'S incident is still there (the "still there?" action).
     * Returns the updated count and whether this caller had already confirmed;
     * throws on failure (the reporter confirming their own report is rejected by
     * the backend, as is confirming an imported or expired incident).
     */
    suspend fun confirm(incidentId: String): IncidentConfirmResult

    companion object {
        const val DEFAULT_RADIUS_METERS: Double = 15_000.0
    }
}

/**
 * The `incidents-confirm` result the sheet actually uses. [expiresAt] also comes
 * back on the wire but is not surfaced in the UI, so it is not modelled here.
 *
 * [alreadyConfirmed] is true when this caller had confirmed before — an
 * idempotent repeat, not an error and not a second count — so the UI can say
 * "already confirmed" rather than "thanks" the second time.
 */
data class IncidentConfirmResult(
    val confirmationCount: Int,
    val alreadyConfirmed: Boolean,
)
