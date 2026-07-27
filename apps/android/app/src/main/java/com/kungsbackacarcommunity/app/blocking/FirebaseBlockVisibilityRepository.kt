package com.kungsbackacarcommunity.app.blocking

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [BlockVisibilityRepository] backed by a single owner-readable document
 * listener on `blockVisibility/{uid}` (see firebase/firestore.rules).
 *
 * ONE document listener covers every filtered surface for the whole session —
 * the alternative, resolving "did this sender block me" per message, is a read
 * per message and is not even possible client-side (the other party's
 * `userBlocks` subcollection is not readable by this user).
 *
 * The signed-in uid is resolved from [FirebaseAuth] rather than threaded through
 * the chat repository interfaces, so the block filter could be added to the
 * existing message flows without reshaping every `observeMessages` signature.
 */
class FirebaseBlockVisibilityRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) : BlockVisibilityRepository {

    override fun observeHiddenUids(): Flow<Set<String>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            // Signed out: nothing to hide, and nothing to listen to. Emit once so
            // downstream `combine`s still produce a value instead of stalling on
            // a flow that never emits.
            trySend(emptySet())
            awaitClose { }
            return@callbackFlow
        }

        // Every consumer `combine`s this flow with a message flow, so a flow that
        // never emits would leave the chat stuck on Loading forever. That makes
        // "emit something" the load-bearing requirement here: a user with no
        // blocks has NO blockVisibility document, but Firestore still delivers a
        // first (non-existent) snapshot, so the happy paths all emit. The error
        // paths are the ones that must not go silent.
        var emitted = false
        fun emit(hidden: Set<String>) {
            emitted = true
            trySend(hidden)
        }

        val registration =
            firestore
                .collection(BLOCK_VISIBILITY)
                .document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null && snapshot == null) {
                        // No usable snapshot. Once a set has been emitted, hold it:
                        // flipping to "hide nothing" on a transient failure would
                        // briefly render a blocked party's messages, and the SDK
                        // retries anyway.
                        //
                        // But if nothing has been emitted yet, holding would stall
                        // the whole chat screen behind a filter that never arrives —
                        // including on a PERMISSION_DENIED, which is terminal (e.g. an
                        // app build running against a deployment whose rules predate
                        // this collection). Degrade to the empty set: the chat renders
                        // unfiltered, exactly as it did before this feature, while the
                        // SERVER-side filters (the *-list callables and the DM rules)
                        // keep working regardless of what the client can read.
                        if (!emitted) emit(emptySet())
                        return@addSnapshotListener
                    }
                    val raw = snapshot?.get(HIDDEN_UIDS) as? List<*>
                    emit(raw.orEmpty().filterIsInstance<String>().filter { it.isNotEmpty() }.toSet())
                }
        awaitClose { registration.remove() }
    }.distinctUntilChanged()

    companion object {
        private const val BLOCK_VISIBILITY = "blockVisibility"
        private const val HIDDEN_UIDS = "hiddenUids"

        /**
         * Returns a live repository, or [BlockVisibilityRepository.EMPTY] when
         * Firebase is not configured. Never null: a chat repository always has a
         * filter to combine with, and "hide nothing" is the safe config-less
         * default (a config-less build has no messages to filter either).
         */
        fun createOrEmpty(context: Context): BlockVisibilityRepository {
            if (FirebaseApp.getApps(context).isEmpty()) return BlockVisibilityRepository.EMPTY
            return FirebaseBlockVisibilityRepository(
                FirebaseFirestore.getInstance(),
                FirebaseAuth.getInstance(),
            )
        }
    }
}
