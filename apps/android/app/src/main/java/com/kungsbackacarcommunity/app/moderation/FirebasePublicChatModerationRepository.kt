package com.kungsbackacarcommunity.app.moderation

import android.content.Context
import android.os.SystemClock
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow

/** Neither a local role flag nor a cached document grants a moderation capability. */
class FirebasePublicChatModerationRepository private constructor(
    private val uid: String,
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : PublicChatModerationRepository {
    private fun checkAccount() { check(auth.currentUser?.uid == uid) { "Account changed" } }

    override fun observeAdminCandidate() = callbackFlow {
        val authListener = FirebaseAuth.AuthStateListener { if (it.currentUser?.uid != uid) trySend(false) }
        auth.addAuthStateListener(authListener)
        val listener = db.collection("users").document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            trySend(error == null && snapshot != null && snapshot.exists() && !snapshot.metadata.isFromCache &&
                auth.currentUser?.uid == uid && snapshot.getString("role") in setOf("admin", "owner") &&
                snapshot.getBoolean("suspended") != true && snapshot.getBoolean("deleted") != true)
        }
        awaitClose { listener.remove(); auth.removeAuthStateListener(authListener) }
    }

    override fun observeOwnPause() = callbackFlow {
        var refresh: Job? = null
        val authListener = FirebaseAuth.AuthStateListener { if (it.currentUser?.uid != uid) { refresh?.cancel(); trySend(null) } }
        auth.addAuthStateListener(authListener)
        val listener = db.collection("carCommunityChatRestrictions").document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { _, error ->
                refresh?.cancel()
                if (error != null || auth.currentUser?.uid != uid) { trySend(null); return@addSnapshotListener }
                refresh = launch {
                    try {
                        checkAccount()
                        val started = SystemClock.elapsedRealtime()
                        val data = functions.getHttpsCallable("moderation-getMyChatWriteStatus").call(emptyMap<String, Any>())
                            .awaitOrThrow { "Status request cancelled" }.data as? Map<*, *> ?: error("Invalid pause status")
                        checkAccount()
                        val serverNow = (data["serverNowMillis"] as? Number)?.toLong() ?: error("Missing server time")
                        val until = (data["pauseUntilMillis"] as? Number)?.toLong()
                        trySend(until?.takeIf { it > serverNow }?.let {
                            PublicChatWritePause(it, data["reason"] as? String ?: "",
                                publicChatPauseDeadline(started, it, serverNow))
                        })
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep last confirmed deadline; the server still checks every send. */ }
                }
            }
        awaitClose { refresh?.cancel(); listener.remove(); auth.removeAuthStateListener(authListener) }
    }

    override suspend fun context(target: PublicChatTarget): PublicChatModerationContext {
        checkAccount()
        val started = SystemClock.elapsedRealtime()
        val data = functions.getHttpsCallable("admin-getChatModerationContext")
            .call(mapOf("target" to target.payload())).awaitOrThrow { "Moderation request cancelled" }.data as? Map<*, *>
            ?: error("Invalid moderation context")
        checkAccount()
        check(data["actorUid"] == uid) { "Account changed" }
        check(target.expectedAuthorUid == null || data["authorUid"] == target.expectedAuthorUid) { "Message author changed" }
        val message = data["message"] as? Map<*, *>
        return PublicChatModerationContext(
            actorUid = uid, authorUid = data["authorUid"] as? String ?: error("Missing author"),
            version = data["version"] as? String ?: error("Missing version"),
            canRemove = data["canRemove"] == true, canPause = data["canPause"] == true,
            canWarn = data["canWarn"] == true,
            maxPauseDays = (data["maxPauseDays"] as? Number)?.toInt()?.takeIf { it in 1..30 } ?: 30,
            authorName = message?.get("authorName") as? String,
            text = message?.get("text") as? String,
            pauseUntilMillis = (data["pauseUntilMillis"] as? Number)?.toLong(),
            serverNowMillis = (data["serverNowMillis"] as? Number)?.toLong() ?: error("Missing server time"),
            observedElapsedMillis = started,
        )
    }

    override suspend fun decide(payload: Map<String, Any?>) {
        checkAccount()
        check(payload["expectedActorUid"] == uid)
        finishSubmittedChatModeration {
            completeChatModerationCleanup(payload, ::checkAccount, { sameDecision ->
                functions.getHttpsCallable("admin-moderateChatMessage").call(sameDecision)
                    .awaitOrThrow { "Moderation request cancelled" }.data
            })
        }
        // The bounded submitted operation can finish after its sheet disappears, but its
        // cancelled UI owner must not run stale success/dismiss callbacks afterwards.
        currentCoroutineContext().ensureActive()
    }

    companion object {
        fun createIfAvailable(context: Context, uid: String): PublicChatModerationRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebasePublicChatModerationRepository(uid, FirebaseAuth.getInstance(), FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance("europe-west1"))
        }
    }
}
