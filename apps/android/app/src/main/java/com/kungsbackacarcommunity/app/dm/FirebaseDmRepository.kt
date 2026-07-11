package com.kungsbackacarcommunity.app.dm

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [DmRepository] backed by member-readable Firestore listeners plus the
 * member-gated `dm-*` callables (europe-west1). Guarded ([createIfAvailable])
 * so a config-less build gets a null repository and the screens render a
 * loading placeholder.
 *
 * The raw Firestore `DocumentSnapshot` → pure-model translation (with Firebase
 * `Timestamp`s extracted to epoch millis) lives here so [DmMapper] / [DmThread]
 * stay testable off-device. HttpsError codes (never messages) are translated to
 * the pure [DmErrorCode] and mapped by [DmErrorMapper].
 *
 * The inbox query intentionally omits an `orderBy` (it sorts client-side via
 * [DmMapper.sortConversations]) so the live listener needs only the auto-created
 * single-field `array-contains` index — no composite index at runtime.
 */
class FirebaseDmRepository private constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : DmRepository {

    override fun observeConversations(uid: String): Flow<DmConversationsState> = callbackFlow {
        val registration =
            firestore
                .collection(CONVERSATIONS)
                .whereArrayContains(MEMBERS, uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(DmConversationsState.Error)
                        return@addSnapshotListener
                    }
                    val rows =
                        snapshot?.documents?.mapNotNull { it.toConversation(uid) }.orEmpty()
                    trySend(DmConversationsState.Loaded(DmMapper.sortConversations(rows)))
                }
        awaitClose { registration.remove() }
    }

    override fun observeThread(conversationId: String): Flow<DmThreadState> = callbackFlow {
        val registration =
            firestore
                .collection(CONVERSATIONS)
                .document(conversationId)
                .collection(MESSAGES)
                .orderBy(CREATED_AT, Query.Direction.DESCENDING)
                .limit(DM_MESSAGES_PAGE_SIZE.toLong())
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        // Firestore can deliver cached data ALONGSIDE an error;
                        // prefer that over collapsing the thread to empty.
                        if (snapshot != null) {
                            val cached =
                                snapshot.documents.mapNotNull { it.toMessage() }.asReversed()
                            trySend(DmThreadState.Loaded(cached))
                            return@addSnapshotListener
                        }
                        // A self-derived pairId whose conversation doc doesn't
                        // exist yet denies the messages listen (the rule get()s
                        // the missing parent) with PERMISSION_DENIED. That's an
                        // empty, not-yet-started thread — surface it as such so
                        // the caller can send the first message; the route
                        // re-subscribes once the doc exists.
                        if ((error as? FirebaseFirestoreException)?.code ==
                            FirebaseFirestoreException.Code.PERMISSION_DENIED
                        ) {
                            trySend(DmThreadState.Loaded(emptyList()))
                            return@addSnapshotListener
                        }
                        // Any OTHER error (UNAVAILABLE, network, etc.) is
                        // transient: keep the last emitted state instead of
                        // misrendering it as "no messages". Don't close the flow
                        // — the SDK retries and will deliver a fresh snapshot.
                        return@addSnapshotListener
                    }
                    val messages =
                        snapshot?.documents?.mapNotNull { it.toMessage() }?.asReversed().orEmpty()
                    trySend(DmThreadState.Loaded(messages))
                }
        awaitClose { registration.remove() }
    }

    override suspend fun sendMessage(toUid: String, text: String): DmSendResult =
        callForData(SEND_MESSAGE, mapOf("toUid" to toUid, "text" to text.trim())).fold(
            onSuccess = { DmResponseParser.parseSendSuccess(it) },
            onFailure = { DmSendResult.Failed(DmErrorMapper.mapSend(it.toDmErrorCode())) },
        )

    override suspend fun loadOlder(conversationId: String, before: String): DmOlderResult =
        callForData(
            GET_MESSAGES,
            mapOf("conversationId" to conversationId, "before" to before),
        ).fold(
            onSuccess = { DmOlderResult.Loaded(DmResponseParser.parseMessagesPage(it)) },
            // A failed older-page is a TRANSIENT error, not end-of-pagination:
            // report it as such so the coordinator keeps the "load older"
            // affordance for a retry instead of permanently ending the thread.
            onFailure = { DmOlderResult.Failed },
        )

    override suspend fun markRead(conversationId: String) {
        // Best-effort: a not-found (never-created conversation) or transient
        // failure is swallowed — marking read is idempotent bookkeeping.
        callForData(MARK_READ, mapOf("conversationId" to conversationId))
    }

    private suspend fun callForData(
        name: String,
        payload: Map<String, Any?>,
    ): Result<Map<String, Any?>?> =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(name)
                .call(payload)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        @Suppress("UNCHECKED_CAST")
                        val data = task.result?.getData() as? Map<String, Any?>
                        continuation.resume(Result.success(data))
                    } else {
                        continuation.resume(
                            Result.failure(
                                task.exception
                                    ?: IllegalStateException("$name failed without a cause"),
                            ),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val CONVERSATIONS = "conversations"
        private const val MESSAGES = "messages"
        private const val MEMBERS = "members"
        private const val CREATED_AT = "createdAt"
        private const val SEND_MESSAGE = "dm-sendMessage"
        private const val GET_MESSAGES = "dm-getMessages"
        private const val MARK_READ = "dm-markRead"

        fun createIfAvailable(context: Context): DmRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseDmRepository(
                FirebaseFirestore.getInstance(),
                FirebaseFunctions.getInstance(REGION),
            )
        }
    }
}

/** Reads a stored conversation doc into the caller-oriented inbox row. */
private fun DocumentSnapshot.toConversation(callerUid: String): DmConversation? {
    if (!exists()) return null
    @Suppress("UNCHECKED_CAST")
    val members = (get("members") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    if (members.isEmpty()) return null

    val profilesRaw = get("memberProfiles") as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val memberProfiles =
        profilesRaw.entries.mapNotNull { (key, value) ->
            val uid = key as? String ?: return@mapNotNull null
            val map = value as? Map<*, *> ?: return@mapNotNull null
            uid to DmUser(uid, map["displayName"] as? String, map["avatarPath"] as? String)
        }.toMap()

    val unreadRaw = get("unread") as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val unread =
        unreadRaw.entries.mapNotNull { (key, value) ->
            val uid = key as? String ?: return@mapNotNull null
            uid to (value as? Number)?.toLong().orZero()
        }.toMap()

    val lastMessage = get("lastMessage") as? Map<*, *>
    val doc =
        DmConversationDoc(
            members = members,
            memberProfiles = memberProfiles,
            lastMessageText = lastMessage?.get("text") as? String,
            lastMessageSenderUid = lastMessage?.get("senderUid") as? String,
            lastMessageAtMillis = getTimestamp("lastMessageAt")?.toDate()?.time,
            unread = unread,
        )
    return DmMapper.conversation(id, doc, callerUid)
}

/** Reads a stored message doc into the pure model (Timestamp → millis + ISO). */
private fun DocumentSnapshot.toMessage(): DmMessage? {
    if (!exists()) return null
    val senderUid = getString("senderUid") ?: return null
    val millis = getTimestamp("createdAt")?.toDate()?.time
    return DmMessage(
        id = id,
        senderUid = senderUid,
        text = getString("text") ?: "",
        createdAtMillis = millis,
        createdAtIso = millis?.let(::millisToIso),
    )
}

private fun Long?.orZero(): Long = this ?: 0L

/** Translates a raw callable failure into the pure, testable error code. */
private fun Throwable.toDmErrorCode(): DmErrorCode {
    val functionsError = this as? FirebaseFunctionsException ?: return DmErrorCode.Other
    return when (functionsError.code) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> DmErrorCode.Unauthenticated
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> DmErrorCode.PermissionDenied
        FirebaseFunctionsException.Code.INVALID_ARGUMENT -> DmErrorCode.InvalidArgument
        FirebaseFunctionsException.Code.FAILED_PRECONDITION -> DmErrorCode.FailedPrecondition
        FirebaseFunctionsException.Code.NOT_FOUND -> DmErrorCode.NotFound
        else -> DmErrorCode.Other
    }
}
