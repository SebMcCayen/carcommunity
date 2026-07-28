package com.kungsbackacarcommunity.app.incidents

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [IncidentRepository] backed by the `incidents.*` callables (europe-west1):
 * `incidents-report`, `incidents-listNearby`, `incidents-remove`,
 * `incidents-confirm`, `incidents-reportCleared`. Tasks are
 * bridged to coroutines with the same isActive-guarded pattern as the other
 * repositories. Construction is guarded ([createIfAvailable] returns null when
 * Firebase is not configured), so the config-less / CI build gets a null
 * repository and the map simply shows no incident markers.
 *
 * Parsing of the listNearby payload is delegated to [IncidentResponseParser] so
 * the SDK→model mapping stays unit-testable off-device.
 */
class FirebaseIncidentRepository private constructor(
    private val functions: FirebaseFunctions,
) : IncidentRepository {

    override suspend fun report(type: IncidentType, location: LatLng, note: String?): Incident {
        val payload =
            buildMap<String, Any> {
                put("type", type.wire)
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                note?.trim()?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
            }
        val data = callForData(REPORT, payload)
        // The callable answers with the created incident view (id + the stored
        // fields). Parse it rather than discarding it, so the reporter's own pin
        // does not depend on a separate listNearby round-trip landing.
        return IncidentResponseParser.parseIncident(data)
            ?: throw IllegalStateException("$REPORT returned no usable incident")
    }

    override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<Incident> {
        val payload =
            mapOf(
                "latitude" to center.latitude,
                "longitude" to center.longitude,
                "radiusMeters" to radiusMeters,
            )
        val data = callForData(LIST_NEARBY, payload)
        return IncidentResponseParser.parseListNearby(data)
    }

    override suspend fun remove(incidentId: String) {
        callForData(REMOVE, mapOf("incidentId" to incidentId))
    }

    override suspend fun confirm(incidentId: String): IncidentConfirmResult {
        val data = callForData(CONFIRM, mapOf("incidentId" to incidentId))
        // The callable answers { incidentId, confirmationCount, clearedCount,
        // reportedCleared, expiresAt, alreadyConfirmed, switchedFromClearVote }.
        // A missing/malformed count degrades to 0 rather than crashing the sheet
        // — the confirmation still landed server-side.
        val count = (data?.get("confirmationCount") as? Number)?.toInt() ?: 0
        val already = data?.get("alreadyConfirmed") as? Boolean ?: false
        return IncidentConfirmResult(
            confirmationCount = count,
            alreadyConfirmed = already,
            clearedCount = (data?.get("clearedCount") as? Number)?.toInt() ?: 0,
            // Only a literal `true` fades a marker: a missing or malformed value
            // must never dim a live hazard, which is the more dangerous of the
            // two ways to be wrong here.
            reportedCleared = data?.get("reportedCleared") == true,
        )
    }

    override suspend fun reportCleared(
        incidentId: String,
        fix: IncidentClearFix,
    ): IncidentClearResult {
        val payload =
            buildMap<String, Any> {
                put("incidentId", incidentId)
                put("latitude", fix.latitude)
                put("longitude", fix.longitude)
                put("capturedAt", fix.capturedAtIso)
                // Omitted when the fix carries none. The backend treats an absent
                // accuracy as buying ZERO geofence slack, so omitting is the safe
                // direction — never substitute a guess.
                fix.accuracyMeters?.let { put("accuracyMeters", it) }
                // Reported truthfully and never suppressed: the backend treats it
                // as a one-way signal (only `true` scores), so an honest client
                // gives nothing away and a dishonest one gains nothing by lying.
                if (fix.isMock) put("mockLocationReported", true)
            }
        val data =
            try {
                callForData(REPORT_CLEARED, payload)
            } catch (error: FirebaseFunctionsException) {
                // The backend attaches a machine-readable `details.reason` so the
                // UI can say "drive closer" rather than "try again" to someone who
                // would retry forever. Anything unrecognised propagates unchanged.
                val reason =
                    IncidentClearRejection.fromWire(
                        (error.details as? Map<*, *>)?.get("reason") as? String,
                    )
                if (reason != null) throw IncidentClearRejectedException(reason, error)
                throw error
            }
        return IncidentClearResult(
            clearedCount = (data?.get("clearedCount") as? Number)?.toInt() ?: 0,
            confirmationCount = (data?.get("confirmationCount") as? Number)?.toInt() ?: 0,
            reportedCleared = data?.get("reportedCleared") == true,
            removed = data?.get("removed") == true,
            alreadyVoted = data?.get("alreadyVoted") == true,
        )
    }

    private suspend fun callForData(name: String, payload: Map<String, Any?>): Map<String, Any?>? =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(name)
                .call(payload)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        @Suppress("UNCHECKED_CAST")
                        val data = task.result?.getData() as? Map<String, Any?>
                        continuation.resume(data)
                    } else {
                        continuation.resumeWithException(
                            task.exception
                                ?: IllegalStateException("$name failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val REPORT = "incidents-report"
        private const val LIST_NEARBY = "incidents-listNearby"
        private const val REMOVE = "incidents-remove"
        private const val CONFIRM = "incidents-confirm"
        private const val REPORT_CLEARED = "incidents-reportCleared"

        fun createIfAvailable(context: Context): IncidentRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseIncidentRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}

/**
 * Pure SDK→model parser for the `incidents-*` payloads. Unknown types and
 * malformed rows are dropped rather than crashing the map.
 */
object IncidentResponseParser {
    /**
     * Parses the `incidents-listNearby` payload
     * (`{ incidents: [ { id, type, latitude, longitude, note?, source?,
     * reporterUid?, createdAt? }, ... ] }`).
     */
    fun parseListNearby(data: Map<String, Any?>?): List<Incident> {
        val raw = data?.get("incidents") as? List<*> ?: return emptyList()
        return raw.mapNotNull { row -> parseRow(row) }
    }

    /**
     * Parses the `incidents-report` payload — the created incident view itself
     * (the same row shape as a listNearby entry, not wrapped in a list). Null
     * when the payload is missing or malformed.
     */
    fun parseIncident(data: Map<String, Any?>?): Incident? = parseRow(data)

    /** The shared row shape, used by both payloads. */
    private fun parseRow(row: Any?): Incident? {
        val map = row as? Map<*, *> ?: return null
        val id = map["id"] as? String ?: return null
        val type = IncidentType.fromWire(map["type"] as? String) ?: return null
        val latitude = (map["latitude"] as? Number)?.toDouble() ?: return null
        val longitude = (map["longitude"] as? Number)?.toDouble() ?: return null
        return Incident(
            id = id,
            type = type,
            latitude = latitude,
            longitude = longitude,
            note = map["note"] as? String,
            source = (map["source"] as? String) ?: "user",
            // Both already travel on the backend's IncidentView; they were simply
            // discarded before the detail sheet existed. Absent/malformed values
            // degrade to null rather than dropping the whole row — a marker with
            // an unknown age is still worth drawing.
            reporterUid = map["reporterUid"] as? String,
            createdAtIso = map["createdAt"] as? String,
            // Present on every IncidentView; absent/malformed degrades to 0 so a
            // single odd row still draws rather than dropping.
            confirmationCount = (map["confirmationCount"] as? Number)?.toInt() ?: 0,
            clearedCount = (map["clearedCount"] as? Number)?.toInt() ?: 0,
            // Only a literal `true` fades a marker. A missing or malformed value
            // reads as "not reported gone": of the two ways this can be wrong,
            // dimming a live hazard is the one that gets someone hurt.
            reportedCleared = map["reportedCleared"] == true,
        )
    }
}
