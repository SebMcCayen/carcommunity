package com.kungsbackacarcommunity.app.groupdrive

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [GroupDriveRepository] backed by a Firestore listener on the roster plus the
 * groupDrive-join / groupDrive-updateStatus / groupDrive-leave callables (europe-west1), Phase 12
 * slice 11. Guarded ([createIfAvailable]).
 */
class FirebaseGroupDriveRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : GroupDriveRepository {

    override fun observeParticipants(eventId: String): Flow<List<GroupDriveParticipant>> = callbackFlow {
        val registration =
            firestore
                .collection(EVENTS)
                .document(eventId)
                .collection(PARTICIPANTS)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    trySend(snapshot?.documents?.mapNotNull { it.toParticipant() } ?: emptyList())
                }
        awaitClose { registration.remove() }
    }

    override fun observeMyStatus(eventId: String, uid: String): Flow<GroupDriveStatus?> = callbackFlow {
        val registration =
            firestore
                .collection(EVENTS)
                .document(eventId)
                .collection(PARTICIPANTS)
                .document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(null)
                        return@addSnapshotListener
                    }
                    trySend(GroupDriveStatus.fromWire(snapshot?.getString("status")))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun join(eventId: String) {
        call(JOIN, mapOf("eventId" to eventId))
    }

    override suspend fun updateStatus(eventId: String, status: GroupDriveStatus) {
        call(UPDATE_STATUS, mapOf("eventId" to eventId, "status" to status.wire))
    }

    override suspend fun leave(eventId: String) {
        call(LEAVE, mapOf("eventId" to eventId))
    }

    private suspend fun call(name: String, data: Map<String, Any>) {
        functions.getHttpsCallable(name).call(data)
            .awaitOrThrow { "$name failed without a cause" }
    }

    companion object {
        private const val EVENTS = "events"
        private const val PARTICIPANTS = "groupDriveParticipants"
        private const val REGION = "europe-west1"
        private const val JOIN = "groupDrive-join"
        private const val UPDATE_STATUS = "groupDrive-updateStatus"
        private const val LEAVE = "groupDrive-leave"

        fun createIfAvailable(context: Context): GroupDriveRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseGroupDriveRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

private fun DocumentSnapshot.toParticipant(): GroupDriveParticipant? {
    if (!exists()) return null
    val status = GroupDriveStatus.fromWire(getString("status")) ?: return null
    return GroupDriveParticipant(
        uid = id,
        displayName = getString("displayName"),
        status = status,
    )
}
