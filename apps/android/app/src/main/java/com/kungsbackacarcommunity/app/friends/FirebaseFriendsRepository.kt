package com.kungsbackacarcommunity.app.friends

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [FriendsRepository] backed by the member-gated friend callables
 * (europe-west1): `friend-list`, `friend-sendRequest`, `friend-respondRequest`,
 * `friend-remove`. Guarded ([createIfAvailable]) so a config-less build gets a
 * null repository and the screen renders a loading placeholder.
 *
 * HttpsError codes (never messages) are translated to the pure
 * [FriendCallableError] and mapped by [FriendsErrorMapper]; the raw
 * SDK→pure translation lives here so the mapping/parsing stays testable off-device.
 */
class FirebaseFriendsRepository private constructor(
    private val functions: FirebaseFunctions,
) : FriendsRepository {

    override suspend fun list(): FriendsResult =
        callForData(LIST, emptyMap()).fold(
            onSuccess = { FriendsResult.Loaded(FriendsResponseParser.parseList(it)) },
            onFailure = { FriendsResult.Failed(FriendsErrorMapper.mapGeneric(it.toCallableError())) },
        )

    override suspend fun sendRequestByNickname(nickname: String): SendRequestResult =
        sendRequest(mapOf("nickname" to nickname))

    override suspend fun sendRequestToUid(toUid: String): SendRequestResult =
        sendRequest(mapOf("toUid" to toUid))

    private suspend fun sendRequest(payload: Map<String, Any?>): SendRequestResult =
        callForData(SEND_REQUEST, payload).fold(
            onSuccess = { FriendsResponseParser.parseSendSuccess(it) },
            onFailure = { FriendsErrorMapper.mapSend(it.toCallableError()) },
        )

    override suspend fun respond(requestId: String, accept: Boolean): RespondResult =
        callForData(
            RESPOND_REQUEST,
            mapOf("requestId" to requestId, "action" to if (accept) "accept" else "decline"),
        ).fold(
            onSuccess = { FriendsResponseParser.parseRespondSuccess(it) },
            onFailure = { RespondResult.Failed(FriendsErrorMapper.mapRespond(it.toCallableError())) },
        )

    override suspend fun remove(friendUid: String): RemoveResult =
        callForData(REMOVE, mapOf("friendUid" to friendUid)).fold(
            // The `removed` boolean is idempotent bookkeeping — either value is a
            // success (the friend is gone). Only a thrown error is a failure.
            onSuccess = { RemoveResult.Removed },
            onFailure = { RemoveResult.Failed(FriendsErrorMapper.mapGeneric(it.toCallableError())) },
        )

    private suspend fun callForData(
        name: String,
        payload: Map<String, Any?>,
    ): Result<Map<String, Any?>?> =
        suspendCancellableCoroutine { continuation ->
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
                                task.exception ?: IllegalStateException("$name failed without a cause"),
                            ),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val LIST = "friend-list"
        private const val SEND_REQUEST = "friend-sendRequest"
        private const val RESPOND_REQUEST = "friend-respondRequest"
        private const val REMOVE = "friend-remove"

        fun createIfAvailable(context: Context): FriendsRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseFriendsRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}

/** Translates a raw callable failure into the pure, testable error shape. */
private fun Throwable.toCallableError(): FriendCallableError {
    val functionsError = this as? FirebaseFunctionsException
        ?: return FriendCallableError(FriendErrorCode.Other, reason = null, candidates = emptyList())
    val details = functionsError.details
    return FriendCallableError(
        code = functionsError.code.toFriendErrorCode(),
        reason = FriendsResponseParser.reasonOf(details),
        candidates = FriendsResponseParser.parseCandidates(details),
    )
}

private fun FirebaseFunctionsException.Code.toFriendErrorCode(): FriendErrorCode =
    when (this) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> FriendErrorCode.Unauthenticated
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> FriendErrorCode.PermissionDenied
        FirebaseFunctionsException.Code.INVALID_ARGUMENT -> FriendErrorCode.InvalidArgument
        FirebaseFunctionsException.Code.NOT_FOUND -> FriendErrorCode.NotFound
        FirebaseFunctionsException.Code.ALREADY_EXISTS -> FriendErrorCode.AlreadyExists
        FirebaseFunctionsException.Code.FAILED_PRECONDITION -> FriendErrorCode.FailedPrecondition
        else -> FriendErrorCode.Other
    }
