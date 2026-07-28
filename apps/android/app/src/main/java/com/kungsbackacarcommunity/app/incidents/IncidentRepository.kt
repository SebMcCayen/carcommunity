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

    /**
     * Votes that an incident is GONE (the "Nej, den är borta" action), from
     * [fix] — the position the voter is actually standing at.
     *
     * The position is REQUIRED, and that is the whole point: the backend refuses
     * a vote from outside a geofence around the incident, because the only thing
     * that makes "it's gone" worth acting on is that the voter just looked at the
     * spot. Throws on rejection; [IncidentClearRejection] names the reasons the
     * UI can explain honestly rather than showing one generic failure.
     */
    suspend fun reportCleared(incidentId: String, fix: IncidentClearFix): IncidentClearResult

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
 *
 * [clearedCount] and [reportedCleared] come back too, because a confirmation can
 * SWITCH the caller's earlier "it's gone" vote and un-fade the marker — the sheet
 * and the map would otherwise keep showing a stale faded state until the next
 * poll.
 */
data class IncidentConfirmResult(
    val confirmationCount: Int,
    val alreadyConfirmed: Boolean,
    val clearedCount: Int = 0,
    val reportedCleared: Boolean = false,
)

/**
 * The position sample a clear vote is made from — the evidence that the voter was
 * at the scene.
 *
 * [capturedAtIso] is the FIX's own timestamp, not the moment the request was
 * built: the backend checks it for freshness, and stamping it with "now" would
 * hide exactly the staleness that check exists to catch. [accuracyMeters] is
 * nullable because some fixes carry no accuracy; the backend treats absent
 * accuracy as buying ZERO geofence slack, which is the safe direction.
 */
data class IncidentClearFix(
    val latitude: Double,
    val longitude: Double,
    val capturedAtIso: String,
    val accuracyMeters: Double? = null,
    val isMock: Boolean = false,
)

/**
 * The `incidents-reportCleared` result.
 *
 * Both counts come back, never netted: the sheet shows "confirmed by N" and
 * "reported gone by M" side by side so the reader weighs the two signals rather
 * than being handed someone else's conclusion.
 *
 * [removed] is true when this vote took the incident off the map — either the net
 * threshold was reached, or the caller is the original reporter (or an admin),
 * who need no corroboration to clear their own report.
 */
data class IncidentClearResult(
    val clearedCount: Int,
    val confirmationCount: Int,
    val reportedCleared: Boolean,
    val removed: Boolean,
    val alreadyVoted: Boolean,
)

/**
 * The backend's machine-readable rejection reasons for a clear vote, mapped from
 * the callable error's `details.reason`.
 *
 * Modelled as a type rather than left as an error string so the UI can be HONEST
 * about why the action did not work — "drive closer", "this comes from
 * Trafikverket" — instead of the generic "try again" that a user out of range
 * would retry forever.
 */
enum class IncidentClearRejection(val wire: String) {
    /** Trafikverket-sourced: the importer owns it, a vote would be overwritten. */
    IMPORTED("imported_incident"),

    /** It already expired or was removed out from under the open sheet. */
    INACTIVE("incident_inactive"),

    /** The voter is not near enough to the incident. */
    OUT_OF_RANGE("out_of_range"),

    /** The position fix was too old to be evidence of anything. */
    POSITION_TOO_OLD("position_too_old"),

    /**
     * The anti-fraud pipeline declined it. Deliberately says nothing about WHICH
     * signal tripped — the backend does not tell us, precisely so an abuser
     * cannot learn what to change.
     */
    NOT_COUNTED("vote_not_counted"),
    ;

    companion object {
        /** Maps a backend `details.reason` to a reason, or null when unknown. */
        fun fromWire(value: String?): IncidentClearRejection? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * Thrown by [IncidentRepository.reportCleared] when the backend rejected the vote
 * with a reason the UI can explain. Anything else propagates as its original
 * failure.
 */
class IncidentClearRejectedException(
    val rejection: IncidentClearRejection,
    cause: Throwable? = null,
) : Exception("Clear vote rejected: ${rejection.wire}", cause)
