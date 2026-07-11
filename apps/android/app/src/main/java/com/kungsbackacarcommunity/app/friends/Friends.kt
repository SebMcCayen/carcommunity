package com.kungsbackacarcommunity.app.friends

/**
 * Friends domain (member-gated friend graph). The backend (europe-west1
 * callables `friend-sendRequest` / `friend-respondRequest` / `friend-remove` /
 * `friend-list`) is the source of truth; the client never writes the graph
 * directly. Everything here is pure Kotlin so the mapping/parsing logic is
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
 */
enum class FriendActionError {
    SignedOut,
    NotMember,
    Invalid,
    NotFound,
    AlreadyExists,
    NotAddable,
    RequestGone,
    Generic,
}

/** Outcome of `friend-sendRequest`. */
sealed interface SendRequestResult {
    /** A pending request was created. */
    data object Requested : SendRequestResult

    /** The request auto-accepted an inbound one — the two are now friends. */
    data object NowFriends : SendRequestResult

    /** The nickname matched several members; the caller must pick one. */
    data class Ambiguous(val candidates: List<FriendUser>) : SendRequestResult

    data class Failed(val error: FriendActionError) : SendRequestResult
}

/** Outcome of `friend-respondRequest`. */
sealed interface RespondResult {
    data object Accepted : RespondResult

    data object Declined : RespondResult

    data class Failed(val error: FriendActionError) : RespondResult
}

/** Outcome of `friend-remove` (idempotent). */
sealed interface RemoveResult {
    data object Removed : RemoveResult

    data class Failed(val error: FriendActionError) : RemoveResult
}

/** Outcome of `friend-list`. */
sealed interface FriendsResult {
    data class Loaded(val data: FriendsData) : FriendsResult

    data class Failed(val error: FriendActionError) : FriendsResult
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
    Other,
}

/**
 * Pure representation of a callable failure: the [code], the optional
 * `details.reason` discriminator, and any ambiguity [candidates] carried in
 * `details.candidates`.
 */
data class FriendCallableError(
    val code: FriendErrorCode,
    val reason: String?,
    val candidates: List<FriendUser>,
)

/**
 * Pure code→result mapping. We branch on the HttpsError code (never the
 * message) and, for the overloaded `failed-precondition` code, on the
 * `details.reason` discriminator.
 */
object FriendsErrorMapper {
    const val REASON_AMBIGUOUS = "AMBIGUOUS_NICKNAME"
    const val REASON_NOT_ADDABLE = "NOT_ADDABLE"

    fun mapSend(error: FriendCallableError): SendRequestResult =
        when {
            // Ambiguity and not-addable are reason-tagged (both arrive under
            // failed-precondition); check the reason before the bare code so we
            // never mis-route a picker or leak block direction.
            error.reason == REASON_AMBIGUOUS -> SendRequestResult.Ambiguous(error.candidates)
            error.reason == REASON_NOT_ADDABLE -> SendRequestResult.Failed(FriendActionError.NotAddable)
            else -> SendRequestResult.Failed(sendErrorFor(error.code))
        }

    private fun sendErrorFor(code: FriendErrorCode): FriendActionError =
        when (code) {
            FriendErrorCode.Unauthenticated -> FriendActionError.SignedOut
            FriendErrorCode.PermissionDenied -> FriendActionError.NotMember
            FriendErrorCode.InvalidArgument -> FriendActionError.Invalid
            FriendErrorCode.NotFound -> FriendActionError.NotFound
            FriendErrorCode.AlreadyExists -> FriendActionError.AlreadyExists
            // A failed-precondition on send that isn't reason-tagged is treated
            // as the neutral not-addable case (the only documented non-ambiguous
            // precondition failure for this callable).
            FriendErrorCode.FailedPrecondition -> FriendActionError.NotAddable
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
            else -> FriendActionError.Generic
        }

    fun mapGeneric(error: FriendCallableError): FriendActionError =
        when (error.code) {
            FriendErrorCode.Unauthenticated -> FriendActionError.SignedOut
            FriendErrorCode.PermissionDenied -> FriendActionError.NotMember
            else -> FriendActionError.Generic
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
