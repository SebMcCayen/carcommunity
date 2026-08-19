package com.kungsbackacarcommunity.app.police

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [PoliceRepository] backed by the `police.*` callables (europe-west1):
 * `police-report`, `police-listNearby`. Tasks are bridged to coroutines with the
 * same isActive-guarded pattern as the other repositories. Construction is
 * guarded ([createIfAvailable] returns null when Firebase is not configured), so
 * the config-less / CI build gets a null repository and the map simply shows no
 * police markers and fires no proximity alerts.
 *
 * Parsing of the payloads is delegated to [PoliceResponseParser] so the
 * SDK→model mapping stays unit-testable off-device.
 */
class FirebasePoliceRepository private constructor(
    private val functions: FirebaseFunctions,
) : PoliceRepository {

    override suspend fun report(location: LatLng, source: String): PoliceReport {
        val payload =
            mapOf(
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "source" to source,
            )
        val data = callForData(REPORT, payload)
        return PoliceResponseParser.parseReport(data)
            ?: throw IllegalStateException("$REPORT returned no usable police report")
    }

    override suspend fun listNearby(center: LatLng, radiusMeters: Double): List<PoliceReport> {
        val payload =
            mapOf(
                "latitude" to center.latitude,
                "longitude" to center.longitude,
                "radiusMeters" to radiusMeters,
            )
        val data = callForData(LIST_NEARBY, payload)
        return PoliceResponseParser.parseListNearby(data)
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
        private const val REPORT = "police-report"
        private const val LIST_NEARBY = "police-listNearby"

        fun createIfAvailable(context: Context): PoliceRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebasePoliceRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}

/**
 * Pure SDK→model parser for the `police-*` payloads. Malformed rows are dropped
 * rather than crashing the map.
 */
object PoliceResponseParser {
    /** Parses `police-listNearby` (`{ policeReports: [ {row}, ... ] }`). */
    fun parseListNearby(data: Map<String, Any?>?): List<PoliceReport> {
        val raw = data?.get("policeReports") as? List<*> ?: return emptyList()
        return raw.mapNotNull { row -> parseRow(row) }
    }

    /** Parses `police-report` — the created pin itself (the same row shape). */
    fun parseReport(data: Map<String, Any?>?): PoliceReport? = parseRow(data)

    private fun parseRow(row: Any?): PoliceReport? {
        val map = row as? Map<*, *> ?: return null
        val id = map["id"] as? String ?: return null
        val latitude = (map["latitude"] as? Number)?.toDouble() ?: return null
        val longitude = (map["longitude"] as? Number)?.toDouble() ?: return null
        return PoliceReport(
            id = id,
            latitude = latitude,
            longitude = longitude,
            source = (map["source"] as? String) ?: PoliceRepository.SOURCE_MANUAL,
            expiresAtIso = map["expiresAt"] as? String,
        )
    }
}
