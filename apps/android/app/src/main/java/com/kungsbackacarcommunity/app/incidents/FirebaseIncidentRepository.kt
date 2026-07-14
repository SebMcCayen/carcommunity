package com.kungsbackacarcommunity.app.incidents

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [IncidentRepository] backed by the `incidents.*` callables (europe-west1):
 * `incidents-report`, `incidents-listNearby`, `incidents-remove`. Tasks are
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

    override suspend fun report(type: IncidentType, location: LatLng, note: String?) {
        val payload =
            buildMap<String, Any> {
                put("type", type.wire)
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                note?.trim()?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
            }
        callForData(REPORT, payload)
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

        fun createIfAvailable(context: Context): IncidentRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseIncidentRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}

/**
 * Pure SDK→model parser for the `incidents-listNearby` payload
 * (`{ incidents: [ { id, type, latitude, longitude, note?, source? }, ... ] }`).
 * Unknown types and malformed rows are dropped rather than crashing the map.
 */
object IncidentResponseParser {
    fun parseListNearby(data: Map<String, Any?>?): List<Incident> {
        val raw = data?.get("incidents") as? List<*> ?: return emptyList()
        return raw.mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"] as? String ?: return@mapNotNull null
            val type = IncidentType.fromWire(map["type"] as? String) ?: return@mapNotNull null
            val latitude = (map["latitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            val longitude = (map["longitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            Incident(
                id = id,
                type = type,
                latitude = latitude,
                longitude = longitude,
                note = map["note"] as? String,
                source = (map["source"] as? String) ?: "user",
            )
        }
    }
}
