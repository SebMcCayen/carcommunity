package com.kungsbackacarcommunity.app.access

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.firebase.await
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

data class CarCommunityStatus(val uid: String, val suspended: Boolean, val blacklisted: Boolean, val sharedRestricted: Boolean = false, val reason: String? = null, val version: Long = 0) {
    val restricted: Boolean get() = suspended || blacklisted || sharedRestricted
    companion object {
        fun fromResponse(data: Map<*, *>, expectedUid: String): CarCommunityStatus? {
            if (data["userId"] != expectedUid) return null
            val suspended = data["suspended"] as? Boolean ?: return null
            val blacklisted = data["blacklisted"] as? Boolean ?: return null
            return CarCommunityStatus(expectedUid, suspended, blacklisted, data["sharedRestricted"] == true, data["reason"] as? String, (data["version"] as? Number)?.toLong() ?: 0)
        }
        fun fromProjection(uid: String, data: Map<String, Any>?, profile: Map<String, Any>?): CarCommunityStatus? {
            if (data?.get("initialized") != true || profile == null) return null
            return CarCommunityStatus(uid, data["suspended"] == true, data["explicitBlacklistId"] is String || data["inheritedBlocked"] == true,
                profile["suspended"] == true || profile["deleted"] == true,
                if (data["suspended"] == true) data["suspensionReason"] as? String else data["blacklistReason"] as? String,
                (data["version"] as? Number)?.toLong() ?: 0)
        }
    }
}
sealed interface CarCommunityGateState {
    data object Loading : CarCommunityGateState
    data class Ready(val status: CarCommunityStatus) : CarCommunityGateState
    data object Failed : CarCommunityGateState
}
/** Presentation follows own server-owned metadata. Backend/rules enforce every
 * online operation; cached presentation preserves existing offline drive flows. */
class FirebaseCarCommunityAccessRepository(
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val firestore: FirebaseFirestore,
) {
    private val invalidations = ConcurrentHashMap<String, Pair<String, Channel<Unit>>>()
    fun refresh(uid: String) { for ((target, channel) in invalidations.values) if (target == uid) channel.trySend(Unit) }
    fun updates(uid: String): Flow<CarCommunityGateState> = callbackFlow {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val subscription = UUID.randomUUID().toString()
        invalidations[subscription] = uid to changes
        val last = AtomicReference<CarCommunityStatus?>(null)
        val profile = AtomicReference<Map<String, Any>?>(null)
        val product = AtomicReference<Map<String, Any>?>(null)
        fun publish(value: CarCommunityStatus) {
            if (auth.currentUser?.uid != uid) { trySend(CarCommunityGateState.Failed); return }
            val prior = last.get()
            if (prior != null && (value.version < prior.version || ((prior.suspended || prior.blacklisted) && !(value.suspended || value.blacklisted) && value.version <= prior.version))) return
            last.set(value); trySend(CarCommunityGateState.Ready(value))
        }
        fun projection() { CarCommunityStatus.fromProjection(uid, product.get(), profile.get())?.let(::publish) }
        val profileListener = firestore.collection("users").document(uid).addSnapshotListener { snapshot, error ->
            if (error != null) { if (last.get() == null) trySend(CarCommunityGateState.Failed); return@addSnapshotListener }
            if (snapshot?.metadata?.hasPendingWrites() == false) { profile.set(snapshot.data); projection() }
        }
        val accessListener = firestore.collection("carCommunityAccess").document(uid).addSnapshotListener { snapshot, error ->
            if (error != null) { if (last.get() == null) trySend(CarCommunityGateState.Failed); return@addSnapshotListener }
            if (snapshot?.metadata?.hasPendingWrites() == false) { product.set(snapshot.data); projection(); if (!snapshot.metadata.isFromCache) changes.trySend(Unit) }
        }
        val worker = launch {
            changes.trySend(Unit)
            for (ignored in changes) {
                try {
                    if (auth.currentUser?.uid != uid) { trySend(CarCommunityGateState.Failed); continue }
                    val response = functions.getHttpsCallable("account-getAccess").call(emptyMap<String, Any>()).await().data as? Map<*, *> ?: error("Invalid access status")
                    val status = CarCommunityStatus.fromResponse(response, uid) ?: error("Wrong access identity")
                    publish(status)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val authFailure = error is FirebaseFunctionsException && error.code in setOf(FirebaseFunctionsException.Code.PERMISSION_DENIED, FirebaseFunctionsException.Code.UNAUTHENTICATED)
                    if (last.get() == null || authFailure) trySend(CarCommunityGateState.Failed)
                }
            }
        }
        awaitClose { invalidations.remove(subscription); changes.close(); worker.cancel(); profileListener.remove(); accessListener.remove() }
    }
    companion object {
        fun createIfAvailable(context: Context): FirebaseCarCommunityAccessRepository? {
            if (runCatching { FirebaseApp.getInstance() }.isFailure) return null
            return FirebaseCarCommunityAccessRepository(FirebaseAuth.getInstance(), FirebaseFunctions.getInstance("europe-west1"), FirebaseFirestore.getInstance())
        }
    }
}
