package com.kungsbackacarcommunity.app.convoy

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The shared "Follow me" leader-trail state as read off the followMe doc.
 * [leaderUid] is the current trail leader (null = no active trail); [polyline] is
 * the base64 CCRB-encoded rolling ~15 km trail; [updatedAtMillis] is the last
 * write instant, the member-side freshness signal.
 */
data class FollowMeState(
    val leaderUid: String?,
    val polyline: String?,
    val updatedAtMillis: Long?,
)

/**
 * The convoy FOLLOW-ME leader trail (distinct from the transient follow-me
 * REACTION — see [ConvoyReactionRepository]).
 *
 * - Toggling leadership is the member-gated `convoy-setFollowMe` callable
 *   ([setFollowMe]); the server owns the leaderUid pointer (takeover + toggle).
 * - The trail POINTS are written DIRECTLY by the current leader ([writeTrail]) on
 *   a throttle, gated by Firestore rules to the current leaderUid — so the
 *   ~3-5s updates never invoke a function.
 * - Every member observes the shared doc ([observeFollowMe]) and draws the line.
 *
 * Firebase-free interface for testability.
 */
interface ConvoyFollowMeRepository {
    /**
     * Toggles the caller as trail leader on [convoyId]. active=true takes over
     * leadership (resetting the trail); active=false turns it off (only if the
     * caller currently leads). Returns true when the caller is leading afterwards,
     * or null on failure.
     */
    suspend fun setFollowMe(convoyId: String, active: Boolean): Boolean?

    /**
     * Writes the leader's rolling [polyline] to the followMe doc. Only the current
     * leader passes the Firestore write rule; any other caller is silently denied
     * (this is a best-effort ~4s trail flush, not a user action). Returns whether
     * the write was accepted.
     */
    suspend fun writeTrail(convoyId: String, polyline: String): Boolean

    /** Live [FollowMeState] for [convoyId], or null while there is no trail / on error. */
    fun observeFollowMe(convoyId: String): Flow<FollowMeState?>
}

/**
 * [ConvoyFollowMeRepository] backed by the `convoy-setFollowMe` callable
 * (europe-west1), a direct leader write to `convoys/{convoyId}/followMe/current`,
 * and a live listener on that doc. Guarded ([createIfAvailable]) so a config-less
 * / CI build gets a null repository and no follow-me UI is wired.
 */
class FirebaseConvoyFollowMeRepository private constructor(
    private val functions: FirebaseFunctions,
    private val firestore: FirebaseFirestore,
) : ConvoyFollowMeRepository {

    private fun followMeDoc(convoyId: String) =
        firestore
            .collection(CONVOYS)
            .document(convoyId)
            .collection(FOLLOW_ME)
            .document(CURRENT)

    override suspend fun setFollowMe(convoyId: String, active: Boolean): Boolean? =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(SET_FOLLOW_ME)
                .call(mapOf("convoyId" to convoyId, "active" to active))
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (!task.isSuccessful) {
                        continuation.resume(null)
                        return@addOnCompleteListener
                    }
                    val data = (task.result?.data as? Map<*, *>)
                    continuation.resume((data?.get("leading") as? Boolean) ?: false)
                }
        }

    override suspend fun writeTrail(convoyId: String, polyline: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            followMeDoc(convoyId)
                .update(
                    mapOf(
                        "polyline" to polyline,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    // Denied (not the leader any more) or offline is a non-event —
                    // the next flush retries, and the shared doc self-corrects.
                    continuation.resume(task.isSuccessful)
                }
        }

    override fun observeFollowMe(convoyId: String): Flow<FollowMeState?> =
        callbackFlow {
            val registration =
                followMeDoc(convoyId).addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(null)
                        return@addSnapshotListener
                    }
                    if (snapshot == null || !snapshot.exists()) {
                        trySend(null)
                        return@addSnapshotListener
                    }
                    trySend(
                        FollowMeState(
                            leaderUid = snapshot.getString("leaderUid"),
                            polyline = snapshot.getString("polyline"),
                            updatedAtMillis = snapshot.getTimestamp("updatedAt")?.toDate()?.time,
                        ),
                    )
                }
            awaitClose { registration.remove() }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val SET_FOLLOW_ME = "convoy-setFollowMe"
        private const val CONVOYS = "convoys"
        private const val FOLLOW_ME = "followMe"
        private const val CURRENT = "current"

        fun createIfAvailable(context: Context): ConvoyFollowMeRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseConvoyFollowMeRepository(
                FirebaseFunctions.getInstance(REGION),
                FirebaseFirestore.getInstance(),
            )
        }
    }
}
