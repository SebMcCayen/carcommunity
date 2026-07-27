package com.kungsbackacarcommunity.app.usersearch

/**
 * Member typeahead domain ("search for a person").
 *
 * The backend (`userSearch-members`, europe-west1) is the only way to look
 * members up: `users/{uid}` is authenticated-readable, but a direct client query
 * could not be bounded, rate-limited, field-stripped or block-filtered, so the
 * client never queries Firestore for this. Everything in this file is pure
 * Kotlin so the normalization, min-length gate and response mapping are
 * JVM-unit-testable without Firebase.
 *
 * MATCHING SEMANTICS (mirrors functions/src/users/user-search-core.ts): typing
 * matches from the START of a nickname, case-insensitively — 'gt' finds
 * 'gt_86', but '86' does not. Firestore has no substring operator; the screen
 * says so via `userSearch.matchesFromStart` rather than leaving a user to
 * conclude the person does not exist.
 */

/** One typeahead row: the minimal PUBLIC profile the callable returns. */
data class MemberSearchResult(
    val uid: String,
    val displayName: String?,
    val avatarPath: String?,
)

/**
 * Query normalization, kept in lockstep with the backend's `toSearchKey`.
 *
 * The client normalizes for two local decisions only — whether the query is
 * long enough to send, and whether a keystroke actually CHANGED the query in key
 * space (so " gt " after "gt" does not refire the callable). The authoritative
 * normalization still happens server-side; this is not a substitute for it.
 */
object UserSearchQuery {
    /**
     * Shortest query worth sending, in CODE POINTS.
     *
     * Matches MIN_QUERY_CODE_POINTS on the backend, which refuses anything
     * shorter with `invalid-argument` + reason QUERY_TOO_SHORT. Enforcing it
     * here too means the common case (the first keystroke) costs no round trip
     * at all and renders as a calm "keep typing" hint instead of an error.
     */
    const val MIN_QUERY_CODE_POINTS = 2

    /**
     * Trim + locale-invariant lowercase.
     *
     * `lowercase()` (no Locale argument) is Locale.ROOT by definition in the
     * Kotlin stdlib. That is deliberate and must not be swapped for a
     * locale-sensitive fold: on a Turkish-locale device 'I' would fold to 'ı'
     * and the query key would no longer match the key the backend stored.
     */
    fun normalize(raw: String): String = raw.trim().lowercase()

    /**
     * True when the NORMALIZED query is long enough to search.
     *
     * Counted in code points, not `length`: a single emoji is one character to
     * the person typing it but two UTF-16 units, so a `length >= 2` test would
     * send a one-character query the backend then rejects.
     */
    fun isSearchable(normalizedQuery: String): Boolean =
        normalizedQuery.codePointCount(0, normalizedQuery.length) >= MIN_QUERY_CODE_POINTS
}

/**
 * A user-facing failure category for the search field. Mapped from the HttpsError
 * code; each renders a `userSearch.*` string, never a raw backend message.
 *
 * There is deliberately no "too short" member here: a query below the minimum is
 * a normal STATE of a typeahead ([UserSearchState.TooShort]), not a failure, and
 * modelling it as an error would paint the field red while someone is still
 * typing the second letter.
 */
enum class UserSearchError {
    SignedOut,
    NotMember,

    /**
     * The per-user rate limit tripped (`resource-exhausted`). Distinct from
     * [Generic] because it is self-correcting and the advice — wait a moment —
     * is real, whereas "something went wrong" tells a user nothing.
     */
    RateLimited,

    /** The device could not reach us (or the call timed out). */
    Network,

    /** Last-resort sink for an unmapped code or a non-callable throwable. */
    Generic,
}

/** The canonical HttpsError codes the search branches on, decoupled from the SDK. */
enum class UserSearchErrorCode {
    Unauthenticated,
    PermissionDenied,
    InvalidArgument,
    ResourceExhausted,
    Unavailable,
    Other,
}

/**
 * Pure representation of a callable failure: the [code] plus the optional
 * `details.reason` discriminator.
 *
 * [reason] matters because `invalid-argument` is overloaded — it covers both a
 * too-short query (a normal typing state the backend tags with
 * [REASON_QUERY_TOO_SHORT]) and a genuinely malformed payload (an app bug). The
 * code alone cannot tell them apart.
 */
data class UserSearchCallableError(
    val code: UserSearchErrorCode,
    val reason: String?,
)

/** `details.reason` tag the backend attaches to a below-minimum query. */
const val REASON_QUERY_TOO_SHORT = "QUERY_TOO_SHORT"

/** Outcome of one `userSearch-members` call. */
sealed interface UserSearchOutcome {
    data class Loaded(val members: List<MemberSearchResult>) : UserSearchOutcome

    /**
     * The backend judged the query too short. Reachable even though the client
     * gates on the same minimum, because the two could disagree (an older app
     * against a newer backend). Treated as "keep typing", never as an error.
     */
    data object TooShort : UserSearchOutcome

    data class Failed(val error: UserSearchError) : UserSearchOutcome
}

/** Pure code→error mapping. Branches on the code (and reason), never on text. */
object UserSearchErrorMapper {
    fun map(error: UserSearchCallableError): UserSearchOutcome =
        when {
            // Checked BEFORE the bare code: `invalid-argument` is shared with a
            // malformed payload, and the two want opposite treatment.
            error.reason == REASON_QUERY_TOO_SHORT -> UserSearchOutcome.TooShort
            else -> UserSearchOutcome.Failed(errorFor(error.code))
        }

    private fun errorFor(code: UserSearchErrorCode): UserSearchError =
        when (code) {
            UserSearchErrorCode.Unauthenticated -> UserSearchError.SignedOut
            UserSearchErrorCode.PermissionDenied -> UserSearchError.NotMember
            UserSearchErrorCode.ResourceExhausted -> UserSearchError.RateLimited
            UserSearchErrorCode.Unavailable -> UserSearchError.Network
            // An untagged invalid-argument is a client/backend contract
            // mismatch — an app fault, not something the user typed wrong.
            UserSearchErrorCode.InvalidArgument,
            UserSearchErrorCode.Other,
            -> UserSearchError.Generic
        }
}

/**
 * Pure parsing of the callable response payload (plain `Map`/`List`, as the
 * Firebase Functions SDK deserializes JSON). A row missing its uid is DROPPED
 * rather than crashing the list, so a partial response degrades to fewer
 * suggestions instead of a broken screen.
 */
object UserSearchResponseParser {
    fun parseMembers(data: Map<String, Any?>?): List<MemberSearchResult> {
        val list = data?.get("members") as? List<*> ?: return emptyList()
        return list.mapNotNull { parseMember(it) }
    }

    fun reasonOf(details: Any?): String? = (details as? Map<*, *>)?.get("reason") as? String

    private fun parseMember(raw: Any?): MemberSearchResult? {
        val map = raw as? Map<*, *> ?: return null
        val uid = (map["uid"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return MemberSearchResult(
            uid = uid,
            displayName = map["displayName"] as? String,
            avatarPath = map["avatarPath"] as? String,
        )
    }
}
