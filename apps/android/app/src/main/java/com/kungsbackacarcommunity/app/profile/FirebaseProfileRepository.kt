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
 * writes of the Phase 9a whitelist (displayName, bio, the three social
 * handles, server-timestamp updatedAt) — the Security Rules enforce ownership,
 * the field whitelist, length bounds AND the social-handle character patterns.
 * There is no callable in front of this write, so firebase/firestore.rules is
 * the server-side authority: the client-side SocialLinks check is UX only.
 * Construction is guarded ([createIfAvailable] returns null when Firebase is
 * not configured).
 */
class FirebaseProfileRepository private constructor(
    private val firestore: FirebaseFirestore,
) : ProfileRepository {

    override fun observeProfile(uid: String): Flow<ProfileState> = callbackFlow {
        val registration =
            firestore.collection(USERS).document(uid).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Surface an error state so the UI shows the shell (not an
                    // infinite spinner); a later successful snapshot self-corrects.
                    trySend(ProfileState.Error)
                    return@addSnapshotListener
                }
                val profile =
                    if (snapshot != null && snapshot.exists()) {
                        UserProfile(
                            displayName = snapshot.getString("displayName"),
                            bio = snapshot.getString("bio"),
                            avatarPath = snapshot.getString("avatarPath"),
                            onboardingComplete = snapshot.get("onboardingCompletedAt") != null,
                            activeMember = snapshot.getBoolean("activeMember") ?: false,
                            // Backend-managed admin/owner role. Mirrors the backend's
                            // canAccessAdminFeatures EXACTLY: admin/owner role AND not
                            // suspended AND not deleted — suspension/deletion revoke
                            // admin access there, so a suspended admin must not be
                            // treated as an admin here either.
                            isAdmin =
                                snapshot.getString("role").let { it == "admin" || it == "owner" } &&
                                    snapshot.getBoolean("suspended") != true &&
                                    snapshot.getBoolean("deleted") != true,
                            createdAtMillis = snapshot.getTimestamp("createdAt")?.toDate()?.time,
                            social =
                                SocialHandles(
                                    facebook = snapshot.getString("facebook"),
                                    instagram = snapshot.getString("instagram"),
                                    youtube = snapshot.getString("youtube"),
                                ),
                        )
                    } else {
                        null
                    }
                trySend(ProfileState.Loaded(profile))
            }
        awaitClose { registration.remove() }
    }

    override suspend fun updateProfile(
        uid: String,
        displayName: String,
        bio: String,
        social: SocialHandles,
    ) {
        writeUser(
            uid,
            mapOf(
                "displayName" to displayName.trim(),
                "bio" to bio.trim(),
                // A null handle DELETES the field rather than writing "" — an
                // unset platform then has exactly one representation on the
                // wire, and the public profile decides purely on presence.
                "facebook" to (social.facebook ?: FieldValue.delete()),
                "instagram" to (social.instagram ?: FieldValue.delete()),
                "youtube" to (social.youtube ?: FieldValue.delete()),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
    }

    override suspend fun updateAvatarPath(uid: String, avatarPath: String) {
        writeUser(
            uid,
            mapOf(
                "avatarPath" to avatarPath,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
    }

    /** Owner update of a whitelisted subset of users/{uid} fields. */
    private suspend fun writeUser(uid: String, update: Map<String, Any>) {
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
