package com.kungsbackacarcommunity.app.drives

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
 * [DrivesRepository] backed by an owner Firestore query on `rides` plus the
 * `drives-delete` callable (europe-west1), Phase 12 slice 12. Guarded
 * ([createIfAvailable]).
 */
class FirebaseDrivesRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : DrivesRepository {

    override fun observeDrives(uid: String): Flow<DrivesState> = callbackFlow {
        val registration =
            firestore
                .collection(RIDES)
                .whereEqualTo(FIELD_USER_ID, uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(DrivesState.Error)
                        return@addSnapshotListener
                    }
                    val drives = snapshot?.documents?.mapNotNull { it.toSavedDrive() } ?: emptyList()
                    trySend(DrivesState.Loaded(SavedDrives.sortedForList(drives)))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun deleteDrive(rideId: String): Unit =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(DELETE_DRIVE)
                .call(mapOf<String, Any?>("rideId" to rideId))
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("$DELETE_DRIVE failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val RIDES = "rides"
        private const val FIELD_USER_ID = "userId"
        private const val DELETE_DRIVE = "drives-delete"

        fun createIfAvailable(context: Context): DrivesRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseDrivesRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

private fun DocumentSnapshot.toSavedDrive(): SavedDrive? {
    if (!exists()) return null
    val duration = getLong("durationSeconds") ?: return null
    return SavedDrive(
        rideId = id,
        title = getString("title"),
        distanceMeters = getDouble("distanceMeters"),
        durationSeconds = duration,
        averageSpeedMetersPerSecond = getDouble("averageSpeedMetersPerSecond"),
        startedAtMillis = getTimestamp("startedAt")?.toDate()?.time,
        endedAtMillis = getTimestamp("endedAt")?.toDate()?.time,
        createdAtMillis = getTimestamp("createdAt")?.toDate()?.time,
    )
}
