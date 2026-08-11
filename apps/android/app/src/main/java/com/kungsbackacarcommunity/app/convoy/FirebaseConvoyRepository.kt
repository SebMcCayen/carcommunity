package com.kungsbackacarcommunity.app.convoy

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [ConvoyRepository] backed by the member-gated convoy callables (europe-west1):
 * `convoy-list`, `convoy-create`, `convoy-respond`, `convoy-invite`,
 * `convoy-leave`, `convoy-start`, `convoy-end`. Guarded ([createIfAvailable]) so
 * a config-less build gets a null repository and the screen renders a loading
 * placeholder.
 *
 * HttpsError codes (never messages) are translated to the pure [ConvoyErrorCode]
 * and mapped per-callable by [ConvoyErrorMapper]; the raw SDK→pure translation
 * lives here so the mapping/parsing stays testable off-device.
 */
class FirebaseConvoyRepository private constructor(
    private val functions: FirebaseFunctions,
    private val firestore: FirebaseFirestore,
) : ConvoyRepository {

    /**
     * Live single-convoy read. Attaches a Firestore snapshot listener to
     * `convoys/{convoyId}` (the firestore.rules already permit any member in
     * `memberUids` to read it) and maps each raw document to [ConvoySummary] via
     * the Firebase-free [ConvoyDocument.toSummary], injecting the
     * `Timestamp`→ISO conversion.
     *
     * The listener's lifetime IS the collection's: `addSnapshotListener` on
     * attach, `registration.remove()` in [awaitClose] when collection stops — so a
     * leaked listener is impossible as long as the collecting scope is bounded
     * (it is: the coordinator collects inside a `collectLatest` keyed on the
     * active convoy, and the whole collection runs in a screen-scoped
     * `LaunchedEffect`). Mirrors [FirebaseGroupDriveRepository.observeParticipants]
     * and the live-location value listeners.
     *
     * On an error (read denied after leaving, or a transient failure) it emits
     * null rather than closing the flow, so a later successful read self-corrects
     * and the merge simply keeps the previous value until then.
     */
    override fun observeConvoy(convoyId: String, callerUid: String?): Flow<ConvoySummary?> =
        callbackFlow {
            val registration =
                firestore
                    .collection(CONVOYS)
                    .document(convoyId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            trySend(null)
                            return@addSnapshotListener
                        }
                        trySend(
                            ConvoyDocument.toSummary(
                                convoyId = convoyId,
                                data = snapshot?.data,
                                callerUid = callerUid,
                                toIso = ::timestampToIso,
                            ),
                        )
                    }
            awaitClose { registration.remove() }
        }

    override suspend fun list(): ConvoyListResult =
        callForData(LIST, emptyMap()).fold(
            onSuccess = { ConvoyResponseParser.parseList(it) },
            onFailure = { ConvoyListResult.Failed(ConvoyErrorMapper.mapList(it.toErrorCode())) },
        )

    override suspend fun create(
        inviteeUids: List<String>,
        title: String?,
        vehicleId: String?,
    ): CreateConvoyResult =
        callForData(
            CREATE,
            buildMap {
                put("inviteeUids", inviteeUids)
                if (!title.isNullOrBlank()) put("title", title)
                // Only send the owner's chosen car when one was picked; the
                // callable schema rejects a blank string.
                if (!vehicleId.isNullOrBlank()) put("vehicleId", vehicleId)
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

    override suspend fun invite(convoyId: String, inviteeUids: List<String>): CreateConvoyResult =
        callForData(
            INVITE,
            mapOf("convoyId" to convoyId, "inviteeUids" to inviteeUids),
        ).fold(
            // Same `{ convoy, invited, skipped }` shape as create — reuse its parser.
            onSuccess = { ConvoyResponseParser.parseCreate(it) },
            onFailure = { CreateConvoyResult.Failed(ConvoyErrorMapper.mapInvite(it.toErrorCode())) },
        )

    override suspend fun leave(convoyId: String): LeaveConvoyResult =
        callForData(LEAVE, mapOf("convoyId" to convoyId)).fold(
            // NOT parseMutation: leaving reports more than the convoy — what the
            // exit did to it, and who inherited leadership.
            onSuccess = { ConvoyResponseParser.parseLeave(it) },
            onFailure = { LeaveConvoyResult.Failed(ConvoyErrorMapper.mapLeave(it.toErrorCode())) },
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
        private const val CONVOYS = "convoys"
        private const val LIST = "convoy-list"
        private const val CREATE = "convoy-create"
        private const val RESPOND = "convoy-respond"
        private const val INVITE = "convoy-invite"
        private const val LEAVE = "convoy-leave"
        private const val START = "convoy-start"
        private const val END = "convoy-end"

        fun createIfAvailable(context: Context): ConvoyRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseConvoyRepository(
                FirebaseFunctions.getInstance(REGION),
                FirebaseFirestore.getInstance(),
            )
        }
    }
}

/**
 * Converts a stored convoy timestamp to an ISO-8601 string for [ConvoySummary],
 * matching the callable path's already-ISO strings so the two convoy read paths
 * produce structurally identical summaries (which lets the coordinator dedupe an
 * unchanged live update against the polled value). A Firebase `Timestamp` is
 * rendered via [Instant]; an already-ISO string passes through; anything else is
 * null. These fields are display-best-effort — the summary's authoritative
 * duration is the precomputed `durationSeconds`, never parsed back from these.
 */
private fun timestampToIso(value: Any?): String? =
    when (value) {
        is Timestamp -> Instant.ofEpochMilli(value.toDate().time).toString()
        is String -> value.takeIf { it.isNotBlank() }
        else -> null
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
