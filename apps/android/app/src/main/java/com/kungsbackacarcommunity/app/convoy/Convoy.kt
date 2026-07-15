package com.kungsbackacarcommunity.app.convoy

/**
 * Convoy domain (member-gated grouped drives). The backend (europe-west1
 * callables `convoy-create` / `convoy-respond` / `convoy-start` / `convoy-end` /
 * `convoy-list`) is the source of truth; the client never writes the tree. It is
 * re-fetched via `convoy-list` after every mutation (the management UI keeps no
 * live listener — the separate convoy live-map is a distinct surface). Everything
 * here is pure Kotlin so the mapping/parsing/decision logic is JVM-unit-testable
 * without Firebase.
 *
 * Wire contract (functions/src/convoy/convoy-core.ts → ConvoySummary):
 *  - create: invitees must be the owner's friends; non-friends/blocked are
 *    skipped (with a neutral reason), never surfaced as who blocked whom.
 *  - respond: an invitee accepts/declines their pending invite.
 *  - start/end: owner-only (a non-owner gets not-found, so a convoy can't be
 *    probed); end computes + stores the summary all members read.
 */

/** Lifecycle of a convoy. */
enum class ConvoyStatus { Forming, Active, Ended }

/** A member's role in a convoy. */
enum class ConvoyRole { Owner, Member }

/** State of a member's invite. */
enum class ConvoyInviteStatus { Invited, Accepted, Declined }

/** One convoy member row (denormalized profile included by the backend). */
data class ConvoyMember(
    val uid: String,
    val role: ConvoyRole,
    val inviteStatus: ConvoyInviteStatus,
    /** ISO-8601 timestamp; kept as the raw string (display is best-effort). */
    val joinedAt: String?,
    val displayName: String?,
    val avatarPath: String?,
)

/** The caller's own membership in a convoy (role + invite state), or null. */
data class ConvoyViewer(
    val role: ConvoyRole,
    val inviteStatus: ConvoyInviteStatus,
)

/** Post-convoy summary, computed + stored on end; every member reads it. */
data class ConvoySummaryStats(
    val durationSeconds: Long,
    val participantUids: List<String>,
    val participantCount: Int,
    /** Null in the current backend (no shared-route aggregation yet). */
    val distanceMeters: Double?,
)

/** One convoy as returned by `convoy-list` / `convoy-create` (the wire contract). */
data class ConvoySummary(
    val convoyId: String,
    val ownerUid: String,
    val title: String?,
    val status: ConvoyStatus,
    val members: List<ConvoyMember>,
    val memberUids: List<String>,
    val viewer: ConvoyViewer?,
    /** Accepted members whose live position the convoy map subscribes to. */
    val livePositionUids: List<String>,
    val summary: ConvoySummaryStats?,
    val createdAt: String?,
    val startedAt: String?,
    val endedAt: String?,
) {
    /** True when the caller owns this convoy (drives the Start/End controls). */
    val viewerIsOwner: Boolean get() = viewer?.role == ConvoyRole.Owner
}

/** Why a requested invitee was skipped by `convoy-create` (neutral reasons). */
enum class ConvoySkipReason { Self, NotFriend, NotFound, Duplicate, Unknown }

/** A requested invitee that `convoy-create` skipped, with the reason. */
data class SkippedInvitee(
    val uid: String,
    val reason: ConvoySkipReason,
)

/**
 * A user-facing failure category, mapped from an HttpsError code (per callable,
 * since `failed-precondition` is overloaded and carries a different meaning for
 * each action). We branch on the code, never the message.
 */
enum class ConvoyActionError {
    SignedOut,
    NotMember,
    Invalid,
    NotFound,
    NoInvitees,
    InviteGone,
    CannotStart,
    AlreadyEnded,
    Generic,
}

/** Outcome of `convoy-list`. */
sealed interface ConvoyListResult {
    data class Loaded(
        val convoys: List<ConvoySummary>,
        val pendingInvites: List<ConvoySummary>,
    ) : ConvoyListResult

    data class Failed(val error: ConvoyActionError) : ConvoyListResult
}

/** Outcome of `convoy-create`. */
sealed interface CreateConvoyResult {
    data class Created(
        val convoy: ConvoySummary,
        val invited: List<String>,
        val skipped: List<SkippedInvitee>,
    ) : CreateConvoyResult

    data class Failed(val error: ConvoyActionError) : CreateConvoyResult
}

/** Outcome of `convoy-respond` / `convoy-start` / `convoy-end`. */
sealed interface ConvoyMutationResult {
    data class Updated(val convoy: ConvoySummary) : ConvoyMutationResult

    data class Failed(val error: ConvoyActionError) : ConvoyMutationResult
}

/**
 * The canonical HttpsError codes we branch on, decoupled from the Firebase
 * `FirebaseFunctionsException.Code` enum so the mapping is testable on a plain
 * JVM. Any code we don't special-case collapses to [Other].
 */
enum class ConvoyErrorCode {
    Unauthenticated,
    PermissionDenied,
    InvalidArgument,
    NotFound,
    FailedPrecondition,
    Other,
}

/**
 * Pure code→error mapping, one function per callable because
 * `failed-precondition` means something different for each (no invitees /
 * invite gone / can't start / already ended). Auth codes map identically
 * everywhere: unauthenticated→SignedOut, permission-denied→NotMember.
 */
object ConvoyErrorMapper {
    fun mapCreate(code: ConvoyErrorCode): ConvoyActionError =
        when (code) {
            ConvoyErrorCode.Unauthenticated -> ConvoyActionError.SignedOut
            ConvoyErrorCode.PermissionDenied -> ConvoyActionError.NotMember
            ConvoyErrorCode.InvalidArgument -> ConvoyActionError.Invalid
            // The only precondition failure on create is "no one could be added".
            ConvoyErrorCode.FailedPrecondition -> ConvoyActionError.NoInvitees
            else -> ConvoyActionError.Generic
        }

    fun mapRespond(code: ConvoyErrorCode): ConvoyActionError =
        when (code) {
            ConvoyErrorCode.Unauthenticated -> ConvoyActionError.SignedOut
            ConvoyErrorCode.PermissionDenied -> ConvoyActionError.NotMember
            ConvoyErrorCode.InvalidArgument -> ConvoyActionError.Invalid
            // No such convoy / not invited / ended / already answered: the invite
            // can no longer be acted on.
            ConvoyErrorCode.NotFound,
            ConvoyErrorCode.FailedPrecondition,
            -> ConvoyActionError.InviteGone
            else -> ConvoyActionError.Generic
        }

    fun mapStart(code: ConvoyErrorCode): ConvoyActionError =
        when (code) {
            ConvoyErrorCode.Unauthenticated -> ConvoyActionError.SignedOut
            ConvoyErrorCode.PermissionDenied -> ConvoyActionError.NotMember
            ConvoyErrorCode.InvalidArgument -> ConvoyActionError.Invalid
            // Owner-only: a non-owner gets not-found (can't probe a convoy).
            ConvoyErrorCode.NotFound -> ConvoyActionError.NotFound
            // Already started/ended — no longer forming.
            ConvoyErrorCode.FailedPrecondition -> ConvoyActionError.CannotStart
            else -> ConvoyActionError.Generic
        }

    fun mapEnd(code: ConvoyErrorCode): ConvoyActionError =
        when (code) {
            ConvoyErrorCode.Unauthenticated -> ConvoyActionError.SignedOut
            ConvoyErrorCode.PermissionDenied -> ConvoyActionError.NotMember
            ConvoyErrorCode.InvalidArgument -> ConvoyActionError.Invalid
            ConvoyErrorCode.NotFound -> ConvoyActionError.NotFound
            ConvoyErrorCode.FailedPrecondition -> ConvoyActionError.AlreadyEnded
            else -> ConvoyActionError.Generic
        }

    /** For `convoy-list` (only the auth/member gate can realistically fail). */
    fun mapList(code: ConvoyErrorCode): ConvoyActionError =
        when (code) {
            ConvoyErrorCode.Unauthenticated -> ConvoyActionError.SignedOut
            ConvoyErrorCode.PermissionDenied -> ConvoyActionError.NotMember
            else -> ConvoyActionError.Generic
        }
}

/**
 * Pure parsing of the callable response payloads (plain `Map`/`List` as the
 * Firebase Functions SDK deserializes JSON). Missing/blank required fields drop
 * the row rather than crash, so a partial backend response degrades gracefully.
 */
object ConvoyResponseParser {
    fun parseList(data: Map<String, Any?>?): ConvoyListResult.Loaded {
        if (data == null) return ConvoyListResult.Loaded(emptyList(), emptyList())
        return ConvoyListResult.Loaded(
            convoys = (data["convoys"] as? List<*>).orEmpty().mapNotNull { parseConvoy(it) },
            pendingInvites =
                (data["pendingInvites"] as? List<*>).orEmpty().mapNotNull { parseConvoy(it) },
        )
    }

    fun parseCreate(data: Map<String, Any?>?): CreateConvoyResult {
        val convoy = parseConvoy(data?.get("convoy"))
            ?: return CreateConvoyResult.Failed(ConvoyActionError.Generic)
        return CreateConvoyResult.Created(
            convoy = convoy,
            invited = (data?.get("invited") as? List<*>).orEmpty().mapNotNull { it as? String },
            skipped = (data?.get("skipped") as? List<*>).orEmpty().mapNotNull { parseSkipped(it) },
        )
    }

    /** Shared by respond/start/end — each returns `{ convoy, ... }`. */
    fun parseMutation(data: Map<String, Any?>?): ConvoyMutationResult {
        val convoy = parseConvoy(data?.get("convoy"))
            ?: return ConvoyMutationResult.Failed(ConvoyActionError.Generic)
        return ConvoyMutationResult.Updated(convoy)
    }

    fun reasonOf(details: Any?): String? = (details as? Map<*, *>)?.get("reason") as? String

    private fun parseConvoy(raw: Any?): ConvoySummary? {
        val map = raw as? Map<*, *> ?: return null
        val convoyId = (map["convoyId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val ownerUid = map["ownerUid"] as? String ?: ""
        return ConvoySummary(
            convoyId = convoyId,
            ownerUid = ownerUid,
            title = map["title"] as? String,
            status = parseStatus(map["status"]),
            members = (map["members"] as? List<*>).orEmpty().mapNotNull { parseMember(it) },
            memberUids = (map["memberUids"] as? List<*>).orEmpty().mapNotNull { it as? String },
            viewer = parseViewer(map["viewer"]),
            livePositionUids =
                (map["livePositionUids"] as? List<*>).orEmpty().mapNotNull { it as? String },
            summary = parseSummary(map["summary"]),
            createdAt = map["createdAt"] as? String,
            startedAt = map["startedAt"] as? String,
            endedAt = map["endedAt"] as? String,
        )
    }

    private fun parseMember(raw: Any?): ConvoyMember? {
        val map = raw as? Map<*, *> ?: return null
        val uid = (map["uid"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return ConvoyMember(
            uid = uid,
            role = parseRole(map["role"]),
            inviteStatus = parseInviteStatus(map["inviteStatus"]),
            joinedAt = map["joinedAt"] as? String,
            displayName = map["displayName"] as? String,
            avatarPath = map["avatarPath"] as? String,
        )
    }

    private fun parseViewer(raw: Any?): ConvoyViewer? {
        val map = raw as? Map<*, *> ?: return null
        return ConvoyViewer(
            role = parseRole(map["role"]),
            inviteStatus = parseInviteStatus(map["inviteStatus"]),
        )
    }

    private fun parseSummary(raw: Any?): ConvoySummaryStats? {
        val map = raw as? Map<*, *> ?: return null
        val participantUids =
            (map["participantUids"] as? List<*>).orEmpty().mapNotNull { it as? String }
        return ConvoySummaryStats(
            durationSeconds = (map["durationSeconds"] as? Number)?.toLong() ?: 0L,
            participantUids = participantUids,
            participantCount =
                (map["participantCount"] as? Number)?.toInt() ?: participantUids.size,
            distanceMeters = (map["distanceMeters"] as? Number)?.toDouble(),
        )
    }

    private fun parseSkipped(raw: Any?): SkippedInvitee? {
        val map = raw as? Map<*, *> ?: return null
        val uid = (map["uid"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return SkippedInvitee(uid = uid, reason = parseSkipReason(map["reason"]))
    }

    private fun parseStatus(raw: Any?): ConvoyStatus =
        when (raw) {
            "active" -> ConvoyStatus.Active
            "ended" -> ConvoyStatus.Ended
            else -> ConvoyStatus.Forming
        }

    private fun parseRole(raw: Any?): ConvoyRole =
        if (raw == "owner") ConvoyRole.Owner else ConvoyRole.Member

    private fun parseInviteStatus(raw: Any?): ConvoyInviteStatus =
        when (raw) {
            "accepted" -> ConvoyInviteStatus.Accepted
            "declined" -> ConvoyInviteStatus.Declined
            else -> ConvoyInviteStatus.Invited
        }

    private fun parseSkipReason(raw: Any?): ConvoySkipReason =
        when (raw) {
            "self" -> ConvoySkipReason.Self
            "not_friend" -> ConvoySkipReason.NotFriend
            "not_found" -> ConvoySkipReason.NotFound
            "duplicate" -> ConvoySkipReason.Duplicate
            else -> ConvoySkipReason.Unknown
        }

    private fun List<*>?.orEmpty(): List<*> = this ?: emptyList<Any?>()
}

/**
 * Pure, locale-neutral formatting for the ended-convoy summary. Kept out of the
 * Composables so the rounding/threshold logic is JVM-unit-testable.
 */
object ConvoyFormat {
    /** e.g. 0s / 45s / 3m 20s / 1h 05m (seconds dropped once hours appear). */
    fun duration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val seconds = s % 60
        return when {
            hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
            minutes > 0 -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
            else -> "${seconds}s"
        }
    }

    /** e.g. 540 m / 12.3 km (switches to km at 1000 m, one decimal). */
    fun distance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return if (m >= 1000.0) {
            val km = m / 1000.0
            "${(Math.round(km * 10.0) / 10.0)} km"
        } else {
            "${Math.round(m)} m"
        }
    }
}
