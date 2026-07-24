package com.kungsbackacarcommunity.app.friends

/**
 * Friends domain (member-gated friend graph). The backend (europe-west1
 * callables `friend-sendRequest` / `friend-respondRequest` /
 * `friend-cancelRequest` / `friend-remove` / `friend-list`) is the source of
 * truth; the client never writes the graph directly. Everything here is pure Kotlin so the mapping/parsing logic is
 * JVM-unit-testable without Firebase.
 */

/** A member as referenced by a friend row, request, or ambiguity candidate. */
data class FriendUser(
    val uid: String,
    val displayName: String?,
    val avatarPath: String?,
)

/** An established friendship. */
data class FriendSummary(
    val uid: String,
    val displayName: String?,
    val avatarPath: String?,
    /** ISO-8601 timestamp; kept as the raw string (display is best-effort). */
    val friendsSince: String?,
)

enum class FriendRequestDirection { Incoming, Outgoing }

/** A pending friend request, in either direction. */
data class FriendRequestSummary(
    val requestId: String,
    val fromUid: String,
    val toUid: String,
    val direction: FriendRequestDirection,
    val otherUser: FriendUser,
    val createdAt: String?,
)

/** The full snapshot returned by `friend-list`. */
data class FriendsData(
    val friends: List<FriendSummary>,
    val incoming: List<FriendRequestSummary>,
    val outgoing: List<FriendRequestSummary>,
)

/**
 * A user-facing failure category, mapped from an HttpsError code (+ details
 * reason). The screen renders each via a `friends.*` string. `NotAddable` is
 * deliberately neutral — it must never reveal whether the caller was blocked or
 * did the blocking.
 *
 * [AlreadyFriends] and [RequestAlreadySent] both arrive as `already-exists` and
 * are separated by `details.reason`; before that discriminator existed the two
 * shared one hedged string ("...or a request is already pending"). Likewise
 * [Network] is split out of what used to collapse into [Generic], so a dropped
 * connection reads as a retryable network problem rather than an app fault.
 *
 * [Generic] is the LAST-RESORT sink for a failure we could not classify (an
 * unmapped callable code, or a non-callable throwable such as an App Check
 * token failure). It is reported to the backend error pipeline because it
 * represents a genuine, undiagnosed runtime fault rather than a normal,
 * actionable outcome of what the user typed. [TemporarilyUnavailable] is the
 * only OTHER reported category — it is classified, but the fault is still ours.
 * FriendsCoordinator.reportIfFault is the enforcing code; the full rationale
 * for which categories do and do not reach the pipeline lives there.
 */
enum class FriendActionError {
    SignedOut,
    NotMember,
    Invalid,
    SelfRequest,
    NotFound,
    AlreadyFriends,
    RequestAlreadySent,
    NotAddable,
    RequestGone,
    Network,

    /**
     * The backend is reachable but cannot serve the request right now — the
     * fault is OURS, not the caller's, and retrying is the only useful advice.
     *
     * Raised by `friend-list` as `unavailable` + `details.reason =
     * BACKEND_UNAVAILABLE` (today: a Firestore query with no deployed composite
     * index). Distinct from [Network], which means the DEVICE could not reach
     * us and where the user's connection is the thing to check — telling
     * someone to check their signal when the server is misconfigured sends them
     * chasing a problem they cannot fix.
     *
     * Reported to the backend error pipeline alongside [Generic]: it is a
     * genuine fault, even though it is classified rather than unknown.
     */
    TemporarilyUnavailable,
    Generic,
}

/**
 * The unmapped status/throwable identifier behind a failure, carried alongside
 * the mapped [FriendActionError] purely so an unclassified ([FriendActionError.Generic])
 * failure can be REPORTED with enough detail to diagnose. Never rendered.
 */
typealias FriendErrorDiagnostic = String?

/** Outcome of `friend-sendRequest`. */
sealed interface SendRequestResult {
    /** A pending request was created. */
    data object Requested : SendRequestResult

    /** The request auto-accepted an inbound one — the two are now friends. */
    data object NowFriends : SendRequestResult

    /** The nickname matched several members; the caller must pick one. */
    data class Ambiguous(val candidates: List<FriendUser>) : SendRequestResult

    data class Failed(
        val error: FriendActionError,
        val diagnostic: FriendErrorDiagnostic = null,
    ) : SendRequestResult
}

/** Outcome of `friend-respondRequest`. */
sealed interface RespondResult {
    data object Accepted : RespondResult

    data object Declined : RespondResult

    data class Failed(
        val error: FriendActionError,
        val diagnostic: FriendErrorDiagnostic = null,
    ) : RespondResult
}

/**
 * Outcome of `friend-cancelRequest` (withdrawing the caller's OWN pending
 * outgoing request).
 *
 * There is no "nothing to cancel" failure: the callable answers every
 * non-cancellable case (no request, already accepted/declined) with the same
 * successful no-op, so the client's post-state — "I no longer have a pending
 * request to this member" — holds either way. Only a thrown error is a failure.
 */
sealed interface CancelResult {
    data object Cancelled : CancelResult

    data class Failed(
        val error: FriendActionError,
        val diagnostic: FriendErrorDiagnostic = null,
    ) : CancelResult
}

/** Outcome of `friend-remove` (idempotent). */
sealed interface RemoveResult {
    data object Removed : RemoveResult

    data class Failed(
        val error: FriendActionError,
        val diagnostic: FriendErrorDiagnostic = null,
    ) : RemoveResult
}

/** Outcome of `friend-list`. */
sealed interface FriendsResult {
    data class Loaded(val data: FriendsData) : FriendsResult

    data class Failed(
        val error: FriendActionError,
        val diagnostic: FriendErrorDiagnostic = null,
    ) : FriendsResult
}

/**
 * The canonical HttpsError codes we branch on, decoupled from the Firebase
 * `FirebaseFunctionsException.Code` enum so the mapping is testable on a plain
 * JVM. Any code we don't special-case collapses to [Other].
 */
enum class FriendErrorCode {
    Unauthenticated,
    PermissionDenied,
    InvalidArgument,
    NotFound,
    AlreadyExists,
    FailedPrecondition,
    /** Transport-level: no/lost connectivity or a server-side timeout. */
    Unavailable,
    Other,
}

/**
 * Pure representation of a callable failure: the [code], the optional
 * `details.reason` discriminator, and any ambiguity [candidates] carried in
 * `details.candidates`.
 *
 * [rawCode] is the ORIGINAL, unmapped identifier of the failure — the callable
 * status name (e.g. "INTERNAL", "UNAVAILABLE") or, when the throwable was not a
 * callable exception at all, its class name. [FriendErrorCode.Other] is a sink
 * that erases exactly the information needed to diagnose a report of
 * "Something went wrong", so the raw value is preserved here and attached to
 * the error report (never shown to the user, never PII).
 */
data class FriendCallableError(
    val code: FriendErrorCode,
    val reason: String?,
    val candidates: List<FriendUser>,
    val rawCode: String? = null,
)

/**
 * Pure code→result mapping. We branch on the HttpsError code (never the
 * message) and, for the overloaded `failed-precondition` code, on the
 * `details.reason` discriminator.
 */
object FriendsErrorMapper {
    const val REASON_AMBIGUOUS = "AMBIGUOUS_NICKNAME"
    const val REASON_NOT_ADDABLE = "NOT_ADDABLE"
    const val REASON_ALREADY_FRIENDS = "ALREADY_FRIENDS"
    const val REASON_REQUEST_ALREADY_SENT = "REQUEST_ALREADY_SENT"
    const val REASON_NICKNAME_NOT_FOUND = "NICKNAME_NOT_FOUND"
    const val REASON_SELF_REQUEST = "SELF_REQUEST"
    const val REASON_BACKEND_UNAVAILABLE = "BACKEND_UNAVAILABLE"

    fun mapSend(error: FriendCallableError): SendRequestResult =
        when (error.reason) {
            // Reason-tagged failures are checked BEFORE the bare code: several
            // distinct outcomes share one code ('already-exists' =
            // already-friends OR request-already-sent; 'failed-precondition' =
            // ambiguous OR not-addable), so the code alone cannot pick the right
            // message and must never mis-route a picker or leak block direction.
            REASON_AMBIGUOUS -> SendRequestResult.Ambiguous(error.candidates)
            REASON_NOT_ADDABLE -> SendRequestResult.Failed(FriendActionError.NotAddable)
            REASON_ALREADY_FRIENDS -> SendRequestResult.Failed(FriendActionError.AlreadyFriends)
            REASON_REQUEST_ALREADY_SENT ->
                SendRequestResult.Failed(FriendActionError.RequestAlreadySent)
            REASON_NICKNAME_NOT_FOUND -> SendRequestResult.Failed(FriendActionError.NotFound)
            REASON_SELF_REQUEST -> SendRequestResult.Failed(FriendActionError.SelfRequest)
            else -> SendRequestResult.Failed(sendErrorFor(error.code), error.rawCode)
        }

    /**
     * Code-only fallback for a send failure with no `details.reason` — e.g. an
     * older backend, or a failure raised before the callable's own handler runs.
     */
    private fun sendErrorFor(code: FriendErrorCode): FriendActionError =
        when (code) {
            FriendErrorCode.Unauthenticated -> FriendActionError.SignedOut
            FriendErrorCode.PermissionDenied -> FriendActionError.NotMember
            FriendErrorCode.InvalidArgument -> FriendActionError.Invalid
            FriendErrorCode.NotFound -> FriendActionError.NotFound
            // Untagged 'already-exists': we cannot tell already-friends from
            // request-already-sent, so fall back to the friendship reading.
            FriendErrorCode.AlreadyExists -> FriendActionError.AlreadyFriends
            // A failed-precondition on send that isn't reason-tagged is treated
            // as the neutral not-addable case (the only documented non-ambiguous
            // precondition failure for this callable).
            FriendErrorCode.FailedPrecondition -> FriendActionError.NotAddable
            FriendErrorCode.Unavailable -> FriendActionError.Network
            FriendErrorCode.Other -> FriendActionError.Generic
        }

    fun mapRespond(error: FriendCallableError): FriendActionError =
        when (error.code) {
            FriendErrorCode.Unauthenticated -> FriendActionError.SignedOut
            FriendErrorCode.PermissionDenied -> FriendActionError.NotMember
            // No such request / not the recipient, or already accepted/declined:
            // the request can no longer be acted on.
            FriendErrorCode.NotFound,
            FriendErrorCode.FailedPrecondition,
            -> FriendActionError.RequestGone
            FriendErrorCode.Unavailable -> FriendActionError.Network
            else -> FriendActionError.Generic
        }

    fun mapGeneric(error: FriendCallableError): FriendActionError =
        when (error.code) {
            FriendErrorCode.Unauthenticated -> FriendActionError.SignedOut
            FriendErrorCode.PermissionDenied -> FriendActionError.NotMember
            FriendErrorCode.Unavailable -> FriendActionError.Network
            else -> FriendActionError.Generic
        }

    /**
     * Maps a `friend-list` failure. Separate from [mapGeneric] because loading
     * the snapshot has a failure mode the mutations do not: the backend can be
     * reachable yet unable to serve the read at all
     * ([FriendActionError.TemporarilyUnavailable]).
     *
     * WHY THIS EXISTS (regression guard, 2026-07-19): production `friend-list`
     * failed for every caller because the friendRequests composite indexes had
     * never been deployed. That surfaced as an opaque INTERNAL, which
     * [mapGeneric] collapsed to [FriendActionError.Generic] — rendered as a
     * flat "couldn't load your friends" on BOTH the Friends page and the convoy
     * invite picker, with nothing to distinguish a backend outage from being
     * signed out, from a dropped connection, or from simply having no friends
     * yet. An empty list is NOT a failure and never reaches this mapper.
     *
     * The reason discriminator is checked BEFORE the bare code, matching
     * [mapSend]: `unavailable` alone cannot distinguish "we are misconfigured"
     * from "your connection dropped", and those two want opposite advice.
     */
    fun mapList(error: FriendCallableError): FriendActionError =
        when {
            error.reason == REASON_BACKEND_UNAVAILABLE -> FriendActionError.TemporarilyUnavailable
            else -> mapGeneric(error)
        }
}

/**
 * Pure parsing of the callable response payloads (plain `Map`/`List` as the
 * Firebase Functions SDK deserializes JSON). Missing/blank required fields drop
 * the row rather than crash, so a partial backend response degrades gracefully.
 */
object FriendsResponseParser {
    fun parseList(data: Map<String, Any?>?): FriendsData {
        if (data == null) return FriendsData(emptyList(), emptyList(), emptyList())
        return FriendsData(
            friends = (data["friends"] as? List<*>).orEmptyList().mapNotNull { parseFriend(it) },
            incoming = (data["incoming"] as? List<*>).orEmptyList().mapNotNull {
                parseRequest(it, FriendRequestDirection.Incoming)
            },
            outgoing = (data["outgoing"] as? List<*>).orEmptyList().mapNotNull {
                parseRequest(it, FriendRequestDirection.Outgoing)
            },
        )
    }

    /** Maps a `friend-sendRequest` success payload to its result. */
    fun parseSendSuccess(data: Map<String, Any?>?): SendRequestResult =
        when (data?.get("status")) {
            "friends" -> SendRequestResult.NowFriends
            // Default to "requested" — a missing status on a 2xx is still a
            // created request, not a failure.
            else -> SendRequestResult.Requested
        }

    /** Maps a `friend-respondRequest` success payload to its result. */
    fun parseRespondSuccess(data: Map<String, Any?>?): RespondResult =
        when (data?.get("status")) {
            "declined" -> RespondResult.Declined
            else -> RespondResult.Accepted
        }

    /** Parses the `details.candidates` of an ambiguous-nickname failure. */
    fun parseCandidates(details: Any?): List<FriendUser> {
        val map = details as? Map<*, *> ?: return emptyList()
        val list = map["candidates"] as? List<*> ?: return emptyList()
        return list.mapNotNull { parseUser(it) }
    }

    fun reasonOf(details: Any?): String? = (details as? Map<*, *>)?.get("reason") as? String

    private fun parseFriend(raw: Any?): FriendSummary? {
        val map = raw as? Map<*, *> ?: return null
        val uid = (map["uid"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return FriendSummary(
            uid = uid,
            displayName = map["displayName"] as? String,
            avatarPath = map["avatarPath"] as? String,
            friendsSince = map["friendsSince"] as? String,
        )
    }

    private fun parseRequest(raw: Any?, direction: FriendRequestDirection): FriendRequestSummary? {
        val map = raw as? Map<*, *> ?: return null
        val requestId = (map["requestId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val other = parseUser(map["otherUser"]) ?: return null
        return FriendRequestSummary(
            requestId = requestId,
            fromUid = map["fromUid"] as? String ?: "",
            toUid = map["toUid"] as? String ?: "",
            direction = direction,
            otherUser = other,
            createdAt = map["createdAt"] as? String,
        )
    }

    private fun parseUser(raw: Any?): FriendUser? {
        val map = raw as? Map<*, *> ?: return null
        val uid = (map["uid"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return FriendUser(
            uid = uid,
            displayName = map["displayName"] as? String,
            avatarPath = map["avatarPath"] as? String,
        )
    }

    private fun List<*>?.orEmptyList(): List<*> = this ?: emptyList<Any?>()
}
