package com.kungsbackacarcommunity.app.chatchannels

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume

/** The europe-west1 region every chat-channels callable is deployed to. */
internal const val CHANNEL_FUNCTIONS_REGION = "europe-west1"

/**
 * Invokes a chat-channels callable and returns its `Map` payload, shared by both
 * the community and convoy Firebase repositories. HttpsError codes (never
 * messages) surface via a failed [Result] so the caller maps them through
 * [ChannelErrorMapper].
 */
internal suspend fun FirebaseFunctions.callChannel(
    name: String,
    payload: Map<String, Any?>,
): Result<Map<String, Any?>?> =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        getHttpsCallable(name)
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

/** Translates a raw callable failure into the pure, testable error code. */
internal fun Throwable.toChannelErrorCode(): ChannelErrorCode {
    val functionsError = this as? FirebaseFunctionsException ?: return ChannelErrorCode.Other
    return when (functionsError.code) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> ChannelErrorCode.Unauthenticated
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> ChannelErrorCode.PermissionDenied
        FirebaseFunctionsException.Code.INVALID_ARGUMENT -> ChannelErrorCode.InvalidArgument
        FirebaseFunctionsException.Code.FAILED_PRECONDITION -> ChannelErrorCode.FailedPrecondition
        FirebaseFunctionsException.Code.NOT_FOUND -> ChannelErrorCode.NotFound
        else -> ChannelErrorCode.Other
    }
}

/** Reads a stored channel message doc into the pure model (Timestamp → millis + ISO). */
internal fun DocumentSnapshot.toChannelMessage(): ChannelMessage? {
    if (!exists()) return null
    // senderUid is required (author identity + own/other + unread logic); a
    // missing OR blank value is malformed, so drop the doc.
    val senderUid = getString("senderUid")?.takeIf { it.isNotBlank() } ?: return null
    val millis = getTimestamp("createdAt")?.toDate()?.time
    return ChannelMessage(
        id = id,
        senderUid = senderUid,
        text = getString("text") ?: "",
        senderDisplayName = getString("senderDisplayName"),
        senderAvatarPath = getString("senderAvatarPath"),
        createdAtMillis = millis,
        createdAtIso = millis?.let { java.time.Instant.ofEpochMilli(it).toString() },
        // Backend-written and always present on new messages; absent only on
        // pre-mentions history, which parses as the empty set.
        mentionedUids = ChannelResponseParser.parseMentionedUids(get("mentionedUids")),
    )
}
