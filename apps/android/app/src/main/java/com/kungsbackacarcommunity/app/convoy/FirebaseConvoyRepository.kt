package com.kungsbackacarcommunity.app.convoy

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [ConvoyRepository] backed by the member-gated convoy callables (europe-west1):
 * `convoy-list`, `convoy-create`, `convoy-respond`, `convoy-start`,
 * `convoy-end`. Guarded ([createIfAvailable]) so a config-less build gets a null
 * repository and the screen renders a loading placeholder.
 *
 * HttpsError codes (never messages) are translated to the pure [ConvoyErrorCode]
 * and mapped per-callable by [ConvoyErrorMapper]; the raw SDK→pure translation
 * lives here so the mapping/parsing stays testable off-device.
 */
class FirebaseConvoyRepository private constructor(
    private val functions: FirebaseFunctions,
) : ConvoyRepository {

    override suspend fun list(): ConvoyListResult =
        callForData(LIST, emptyMap()).fold(
            onSuccess = { ConvoyResponseParser.parseList(it) },
            onFailure = { ConvoyListResult.Failed(ConvoyErrorMapper.mapList(it.toErrorCode())) },
        )

    override suspend fun create(inviteeUids: List<String>, title: String?): CreateConvoyResult =
        callForData(
            CREATE,
            buildMap {
                put("inviteeUids", inviteeUids)
                if (!title.isNullOrBlank()) put("title", title)
            },
        ).fold(
            onSuccess = { ConvoyResponseParser.parseCreate(it) },
            onFailure = { CreateConvoyResult.Failed(ConvoyErrorMapper.mapCreate(it.toErrorCode())) },
        )

    override suspend fun respond(convoyId: String, accept: Boolean): ConvoyMutationResult =
        callForData(
            RESPOND,
            mapOf("convoyId" to convoyId, "action" to if (accept) "accept" else "decline"),
        ).fold(
            onSuccess = { ConvoyResponseParser.parseMutation(it) },
            onFailure = { ConvoyMutationResult.Failed(ConvoyErrorMapper.mapRespond(it.toErrorCode())) },
        )

    override suspend fun start(convoyId: String): ConvoyMutationResult =
        callForData(START, mapOf("convoyId" to convoyId)).fold(
            onSuccess = { ConvoyResponseParser.parseMutation(it) },
            onFailure = { ConvoyMutationResult.Failed(ConvoyErrorMapper.mapStart(it.toErrorCode())) },
        )

    override suspend fun end(convoyId: String): ConvoyMutationResult =
        callForData(END, mapOf("convoyId" to convoyId)).fold(
            onSuccess = { ConvoyResponseParser.parseMutation(it) },
            onFailure = { ConvoyMutationResult.Failed(ConvoyErrorMapper.mapEnd(it.toErrorCode())) },
        )

    private suspend fun callForData(
        name: String,
        payload: Map<String, Any?>,
    ): Result<Map<String, Any?>> = functions.callConvoyFunction(name, payload)

    companion object {
        private const val REGION = "europe-west1"
        private const val LIST = "convoy-list"
        private const val CREATE = "convoy-create"
        private const val RESPOND = "convoy-respond"
        private const val START = "convoy-start"
        private const val END = "convoy-end"

        fun createIfAvailable(context: Context): ConvoyRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseConvoyRepository(FirebaseFunctions.getInstance(REGION))
        }
    }
}

/**
 * Invokes a convoy callable and unwraps its `Map` payload. Shared by
 * [FirebaseConvoyRepository] and [FirebaseConvoyDestinationRepository] so the two
 * halves of the convoy domain cannot drift apart in how they treat an empty or
 * failed response.
 */
internal suspend fun FirebaseFunctions.callConvoyFunction(
    name: String,
    payload: Map<String, Any?>,
): Result<Map<String, Any?>> =
    suspendCancellableCoroutine { continuation ->
        this
            .getHttpsCallable(name)
            .call(payload)
            .addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (task.isSuccessful) {
                    @Suppress("UNCHECKED_CAST")
                    val data = task.result?.getData() as? Map<String, Any?>
                    // A successful callable that returns no Map payload is an
                    // unexpected response — surface it as an error rather than
                    // rendering an empty/half-built convoy.
                    if (data == null) {
                        continuation.resume(
                            Result.failure(
                                IllegalStateException("$name returned an unexpected or empty payload"),
                            ),
                        )
                    } else {
                        continuation.resume(Result.success(data))
                    }
                } else {
                    continuation.resume(
                        Result.failure(
                            task.exception ?: IllegalStateException("$name failed without a cause"),
                        ),
                    )
                }
            }
    }

/** Translates a raw callable failure into the pure, testable error code. */
internal fun Throwable.toErrorCode(): ConvoyErrorCode {
    val functionsError = this as? FirebaseFunctionsException ?: return ConvoyErrorCode.Other
    return when (functionsError.code) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> ConvoyErrorCode.Unauthenticated
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> ConvoyErrorCode.PermissionDenied
        FirebaseFunctionsException.Code.INVALID_ARGUMENT -> ConvoyErrorCode.InvalidArgument
        FirebaseFunctionsException.Code.NOT_FOUND -> ConvoyErrorCode.NotFound
        FirebaseFunctionsException.Code.FAILED_PRECONDITION -> ConvoyErrorCode.FailedPrecondition
        else -> ConvoyErrorCode.Other
    }
}
