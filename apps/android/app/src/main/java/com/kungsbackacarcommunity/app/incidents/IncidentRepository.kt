package com.kungsbackacarcommunity.app.incidents

import com.kungsbackacarcommunity.app.navigation.LatLng

/**
 * Data-layer seam for the incidents domain, backed by the `incidents.*`
 * callables. Kept as an interface so the shell/controller and tests are written
 * against it (a fake in tests, [FirebaseIncidentRepository] on device).
 */
interface IncidentRepository {
    /** Reports an incident of [type] at [location]; throws on failure. */
    suspend fun report(type: IncidentType, location: LatLng, note: String? = null)

    /**
     * Active, unexpired incidents within [radiusMeters] of [center]. The
     * backend filters by geo-cell + exact radius; the client never scans.
     */
    suspend fun listNearby(center: LatLng, radiusMeters: Double = DEFAULT_RADIUS_METERS): List<Incident>

    /** Removes the caller's own incident (idempotent); admins may remove any. */
    suspend fun remove(incidentId: String)

    companion object {
        const val DEFAULT_RADIUS_METERS: Double = 15_000.0
    }
}
