package com.kungsbackacarcommunity.app.profile

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [ProfileRepository] backed by Cloud Firestore (Phase 12 slice 2).
 *
 * Reads are a live snapshot listener on users/{uid}; edits are direct owner
 * writes of the Phase 9a whitelist (displayName, bio, server-timestamp
 * updatedAt) — the Security Rules enforce ownership, field whitelist, and
 * length bounds. Construction is guarded ([createIfAvailable] returns null
 * when Firebase is not configured).
 */
class FirebaseProfileRepository private constructor(
    private val firestore: FirebaseFirestore,
) : ProfileRepository {

    override fun observeProfile(uid: String): Flow<ProfileState> = callbackFlow {
        val registration =
            firestore.collection(USERS).document(uid).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Transient listener errors keep the last state; do not crash.
                    return@addSnapshotListener
                }
                val profile =
                    if (snapshot != null && snapshot.exists()) {
                        UserProfile(
                            displayName = snapshot.getString("displayName"),
                            bio = snapshot.getString("bio"),
                            onboardingComplete = snapshot.get("onboardingCompletedAt") != null,
                        )
                    } else {
                        null
                    }
                trySend(ProfileState.Loaded(profile))
            }
        awaitClose { registration.remove() }
    }

    override suspend fun updateProfile(uid: String, displayName: String, bio: String) {
        val update =
            mapOf(
                "displayName" to displayName.trim(),
                "bio" to bio.trim(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        suspendCancellableCoroutine { continuation ->
            firestore
                .collection(USERS)
                .document(uid)
                .update(update)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            task.exception
                                ?: IllegalStateException("Profile update failed without a cause"),
                        )
                    }
                }
        }
    }

    companion object {
        private const val USERS = "users"

        fun createIfAvailable(context: Context): ProfileRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseProfileRepository(FirebaseFirestore.getInstance())
        }
    }
}
