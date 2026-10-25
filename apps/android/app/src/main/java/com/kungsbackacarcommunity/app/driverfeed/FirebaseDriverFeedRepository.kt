package com.kungsbackacarcommunity.app.driverfeed

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One bounded server-authorized call per page. No direct content reads or fallback endpoints. */
class FirebaseDriverFeedRepository private constructor(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
) : DriverFeedRepository {
    override suspend fun feed(uid: String, after: DriverCursor?): DriverPage<DriverPost> {
        val result = read(uid, "driverFeed-list", pagePayload(after))
        return DriverPage(driverWireList(result, "posts").map { fields ->
            DriverPost.decode(fields["id"] as? String ?: driverMalformed(), fields) ?: driverMalformed()
        }, driverWireCursor(result))
    }

    override suspend fun detail(uid: String, id: String, after: DriverCursor?): DriverDetailPage {
        if (!validDriverId(id)) throw DriverFeedException(DriverFeedFailure.UNAVAILABLE)
        val result = read(uid, "driverFeed-detail", pagePayload(after) + ("threadId" to id))
        val fields = result["post"] as? Map<*, *> ?: driverMalformed()
        if (fields["id"] != id) driverMalformed()
        val post = DriverPost.decode(id, fields) ?: driverMalformed()
        val comments = driverWireList(result, "comments").map { comment ->
            val commentId = comment["id"] as? String ?: driverMalformed()
            val author = comment["authorUid"] as? String ?: driverMalformed()
            if (!validDriverId(commentId) || !validDriverId(author)) driverMalformed()
            DriverComment(commentId, author, comment["authorName"] as? String ?: driverMalformed(),
                comment["text"] as? String ?: driverMalformed(),
                (comment["createdAtMillis"] as? Number)?.toLong() ?: driverMalformed())
        }
        return DriverDetailPage(post, DriverPage(comments, driverWireCursor(result)))
    }

    private suspend fun read(uid: String, name: String, payload: Map<String, Any>): Map<*, *> {
        val user = auth.currentUser?.takeIf { it.uid == uid }
            ?: throw DriverFeedException(DriverFeedFailure.RESTRICTED)
        val data = try {
            functions.getHttpsCallable(name).call(payload).awaitOrThrow { "Driver read" }.data
        } catch (error: FirebaseFunctionsException) {
            currentCoroutineContext().ensureActive()
            if (auth.currentUser !== user) throw CancellationException("Driver account changed")
            val reason = (error.details as? Map<*, *>)?.get("reason") as? String
            throw DriverFeedException(driverReadFailure(reason, error.code.name))
        }
        currentCoroutineContext().ensureActive()
        if (auth.currentUser !== user) throw CancellationException("Driver account changed")
        return data as? Map<*, *> ?: driverMalformed()
    }

    // Owner/parent listeners only invalidate memory; content comes solely from the bridge.
    override fun invalidations(uid: String, threadId: String?): Flow<Unit> = callbackFlow {
        // Explicit override avoids exposing Firebase's Java parameter type-use
        // annotations through Kotlin's inferred SAM lambda parameter type.
        val listener = object : FirebaseAuth.IdTokenListener {
            override fun onIdTokenChanged(auth: FirebaseAuth) {
                trySend(Unit)
            }
        }
        auth.addIdTokenListener(listener)
        val documents = listOf(db.collection("users").document(uid), db.collection("driverUsers").document(uid),
            db.collection("blockVisibility").document(uid)) +
            listOfNotNull(threadId?.let { db.collection("driverThreads").document(it) })
        val registrations = documents.map { it.addSnapshotListener { _, _ -> trySend(Unit) } } +
            db.collection("driverUsers").document(uid).collection("blockedUsers").limit(101)
                .addSnapshotListener { _, _ -> trySend(Unit) }
        awaitClose { auth.removeIdTokenListener(listener); registrations.forEach { it.remove() } }
    }

    companion object {
        fun createIfAvailable(context: Context): DriverFeedRepository? =
            if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseDriverFeedRepository(
                FirebaseFirestore.getInstance(), FirebaseAuth.getInstance(), FirebaseFunctions.getInstance("europe-west1"),
            )
    }
}
