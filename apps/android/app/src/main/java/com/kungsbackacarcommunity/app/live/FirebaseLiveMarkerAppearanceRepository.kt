package com.kungsbackacarcommunity.app.live

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** No process cache or subscription reads. One public document listener per drawn UID. */
class FirebaseLiveMarkerAppearanceRepository private constructor(private val firestore: FirebaseFirestore) : LiveMarkerAppearanceRepository {
    override fun observeProfile(uid: String): Flow<LiveMarkerProfile?> = callbackFlow {
        val registration = firestore.collection("users").document(uid).addSnapshotListener { snapshot, error ->
            trySend(if (error == null && snapshot?.exists() == true) {
                LiveMarkerProfile.fromFields(snapshot.data.orEmpty())
            } else null)
        }
        awaitClose { registration.remove() }
    }

    companion object {
        fun createIfAvailable(context: Context): FirebaseLiveMarkerAppearanceRepository? = runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseLiveMarkerAppearanceRepository(FirebaseFirestore.getInstance())
        }.getOrNull()
    }
}
