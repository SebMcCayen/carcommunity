package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [CrownHuntRepository] backed by a Firestore listener on active points plus
 * the crownHunt-submitClaim callable (europe-west1), Phase 12 slice 16.
 * Construction is guarded ([createIfAvailable] returns null without Firebase).
 */
class FirebaseCrownHuntRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : CrownHuntRepository {

    override fun observeActivePoints(): Flow<CrownHuntPointsState> = callbackFlow {
        val registration =
            firestore
                .collection(POINTS)
                .whereEqualTo("status", CrownHuntPointStatus.ACTIVE.wire)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(CrownHuntPointsState.Error)
                        return@addSnapshotListener
                    }
                    val points = snapshot?.documents?.mapNotNull { it.toPoint() } ?: emptyList()
                    trySend(CrownHuntPointsState.Loaded(points))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun submitClaim(
        pointId: String,
        coordinate: ClaimCoordinate,
        idempotencyKey: String,
    ): ClaimOutcome {
        val data =
            buildMap<String, Any> {
                put("pointId", pointId)
                put("latitude", coordinate.latitude)
                put("longitude", coordinate.longitude)
                put("recordedAt", coordinate.recordedAtIso)
                put("idempotencyKey", idempotencyKey)
                coordinate.speedMetersPerSecond?.let { put("speedMetersPerSecond", it) }
                coordinate.accuracyMeters?.let { put("accuracyMeters", it) }
            }
        return suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(SUBMIT_CLAIM)
                .call(data)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        // HttpsCallableResult exposes getData(); the `data`
                        // field itself is private, so call the accessor.
                        @Suppress("UNCHECKED_CAST")
                        val result = task.result?.getData() as? Map<String, Any?>
                        val outcome = result?.toClaimOutcome()
                        if (outcome != null) {
                            continuation.resume(outcome)
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException("submitClaim returned an unrecognized result"),
                            )
                        }
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("submitClaim failed without a cause"),
                        )
                    }
                }
        }
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val POINTS = "crownHuntPoints"
        private const val SUBMIT_CLAIM = "crownHunt-submitClaim"

        fun createIfAvailable(context: Context): CrownHuntRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseCrownHuntRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

private fun DocumentSnapshot.toPoint(): CrownHuntPoint? {
    if (!exists()) return null
    val title = getString("title") ?: return null
    // rewardPoints is required — skip a malformed doc rather than silently
    // rendering a "0 KP" reward (schema drift stays visible).
    val rewardPoints = (get("rewardPoints") as? Number)?.toInt() ?: return null
    return CrownHuntPoint(
        id = id,
        title = title,
        description = getString("description"),
        rewardPoints = rewardPoints,
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
        geofenceRadiusMeters = getDouble("geofenceRadiusMeters"),
    )
}

private fun Map<String, Any?>.toClaimOutcome(): ClaimOutcome? {
    val result = CrownHuntClaimResult.fromWire(this["result"] as? String) ?: return null
    return ClaimOutcome(
        result = result,
        pointsAwarded = (this["pointsAwarded"] as? Number)?.toInt(),
        newBalance = (this["newBalance"] as? Number)?.toInt(),
    )
}
