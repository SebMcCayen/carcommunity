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

    override suspend fun remove(policeReportId: String): Boolean {
        val data = callForData(REMOVE, mapOf("policeReportId" to policeReportId))
        // A GENUINE idempotent no-op comes back as removed:false (a real Boolean) —
        // that is a valid result and returned as-is. A MISSING payload or a
        // non-boolean `removed` is a parse/server fault, NOT "nothing was removed":
        // conflating the two would show the user "couldn't remove" for a call that
        // may have failed to even reach the handler. Throw so the caller surfaces a
        // real error (mirrors report/confirm/dispute).
        return (data?.get("removed") as? Boolean)
            ?: throw IllegalStateException("$REMOVE returned no usable result")
    }

    override suspend fun confirm(policeReportId: String): PoliceVerifyResult {
        val data = callForData(CONFIRM, mapOf("policeReportId" to policeReportId))
        return PoliceResponseParser.parseVerify(data)
            ?: throw IllegalStateException("$CONFIRM returned no usable result")
    }

    override suspend fun dispute(policeReportId: String): PoliceVerifyResult {
        val data = callForData(DISPUTE, mapOf("policeReportId" to policeReportId))
        return PoliceResponseParser.parseVerify(data)
            ?: throw IllegalStateException("$DISPUTE returned no usable result")
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
        private const val REMOVE = "police-remove"
        private const val CONFIRM = "police-confirm"
        private const val DISPUTE = "police-dispute"

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

    /** Parses `police-confirm` / `police-dispute` — the updated verify tallies. */
    fun parseVerify(data: Map<String, Any?>?): PoliceVerifyResult? {
        val id = data?.get("policeReportId") as? String ?: return null
        return PoliceVerifyResult(
            policeReportId = id,
            confirmationCount = (data["confirmationCount"] as? Number)?.toInt() ?: 0,
            disputeCount = (data["disputeCount"] as? Number)?.toInt() ?: 0,
            alreadyVoted = (data["alreadyVoted"] as? Boolean) ?: false,
            switched = (data["switched"] as? Boolean) ?: false,
        )
    }

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
            // Server-resolved per-caller ownership; absent/legacy payloads default
            // to false (not mine), so an old row simply keeps alerting as before.
            mine = (map["mine"] as? Boolean) ?: false,
            // Verify tallies shown on the tap sheet; absent/legacy payloads → 0.
            confirmationCount = (map["confirmationCount"] as? Number)?.toInt() ?: 0,
            disputeCount = (map["disputeCount"] as? Number)?.toInt() ?: 0,
        )
    }
}
