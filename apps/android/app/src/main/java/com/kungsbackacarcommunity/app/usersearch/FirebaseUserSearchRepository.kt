package com.kungsbackacarcommunity.app.usersearch

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [UserSearchRepository] backed by the member-gated `userSearch-members`
 * callable (europe-west1). Guarded ([createIfAvailable]) so a config-less build
 * gets a null repository and the search field is simply not offered.
 *
 * HttpsError codes (never messages) are translated to the pure
 * [UserSearchCallableError] and mapped by [UserSearchErrorMapper]; the raw
 * SDK→pure translation lives here so the mapping/parsing stays testable
 * off-device. Mirrors friends/FirebaseFriendsRepository.
 */
class FirebaseUserSearchRepository private constructor(
    private val functions: FirebaseFunctions,
) : UserSearchRepository {

    override suspend fun search(query: String): UserSearchOutcome =
        try {
            val data = callForData(SEARCH_MEMBERS, mapOf("query" to query))
            UserSearchOutcome.Loaded(UserSearchResponseParser.parseMembers(data))
        } catch (error: FirebaseFunctionsException) {
            UserSearchErrorMapper.map(
                UserSearchCallableError(
                    code = error.code.toUserSearchErrorCode(),
                    reason = UserSearchResponseParser.reasonOf(error.details),
                ),
            )
        }

    /**
     * Invokes the callable and returns its payload map.
     *
     * A [FirebaseFunctionsException] is rethrown so [search] can map its code +
     * details; anything else (an App Check token failure, or the empty-payload
     * guard below) is an unmapped fault and surfaces as [UserSearchError.Generic].
     * CancellationException propagates untouched, which is what makes cancelling
     * the job a real cancellation of the in-flight search rather than a silently
     * swallowed one.
     */
    private suspend fun callForData(
        name: String,
        payload: Map<String, Any?>,
    ): Map<String, Any?>? =
        suspendCancellableCoroutine { continuation ->
            functions
                .getHttpsCallable(name)
                .call(payload)
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resume(task.result?.getData() as? Map<String, Any?>)
                    } else {
                        // resumeWithException, NOT continuation.cancel(cause):
                        // cancel() would surface a CancellationException that
                        // merely WRAPS the real failure, and the caller must be
                        // able to `catch (e: FirebaseFunctionsException)` to read
                        // its code and details.
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("$name failed without a cause"),
                        )
                    }
                }
        }

    companion object {
        private const val REGION = "europe-west1"
        private const val SEARCH_MEMBERS = "userSearch-members"

        fun createIfAvailable(context: Context): UserSearchRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseUserSearchRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}

private fun FirebaseFunctionsException.Code.toUserSearchErrorCode(): UserSearchErrorCode =
    when (this) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> UserSearchErrorCode.Unauthenticated
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> UserSearchErrorCode.PermissionDenied
        FirebaseFunctionsException.Code.INVALID_ARGUMENT -> UserSearchErrorCode.InvalidArgument
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> UserSearchErrorCode.ResourceExhausted
        // Transport-level failures the user can act on by retrying: the SDK
        // reports a lost/absent connection as UNAVAILABLE and a server-side
        // timeout as DEADLINE_EXCEEDED.
        FirebaseFunctionsException.Code.UNAVAILABLE,
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
        -> UserSearchErrorCode.Unavailable
        else -> UserSearchErrorCode.Other
    }
