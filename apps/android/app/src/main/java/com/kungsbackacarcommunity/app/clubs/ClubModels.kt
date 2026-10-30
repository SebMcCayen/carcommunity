package com.kungsbackacarcommunity.app.clubs

import java.time.Instant

enum class ClubRole(val wire: String) {
    Owner("owner"), Moderator("moderator"), Member("member");
    val managesMembers get() = this != Member
    companion object { fun parse(value: Any?) = entries.firstOrNull { it.wire == value } }
}

data class ClubStats(val driveCount: Long = 0, val distanceMeters: Double = 0.0, val durationSeconds: Long = 0)
data class Club(
    val id: String, val name: String, val description: String, val location: String,
    val visibility: String, val ownerUid: String, val logoUrl: String?, val memberCount: Int,
    val moderatorCount: Int, val stats: ClubStats, val status: String = "active",
)
data class ClubMembership(val clubId: String, val role: ClubRole, val joinedAt: String)
data class ClubInvitation(val clubId: String, val clubName: String, val expiresAt: String)
data class ClubState(
    val club: Club? = null, val membership: ClubMembership? = null,
    val joinAllowedAt: String? = null, val invitations: List<ClubInvitation> = emptyList(),
    val legacyConflict: Boolean = false,
)
data class ClubMember(val uid: String, val displayName: String, val role: ClubRole, val avatarUrl: String? = null, val avatarPath: String? = null)
/** A present value, including both nulls, overrides the callable copy. */
data class ClubMemberAvatar(val avatarPath: String?, val avatarUrl: String?)
data class ClubEvent(val eventId: String, val title: String, val startMillis: Long, val endMillis: Long?, val locationName: String?)
data class ClubMessage(val id: String, val authorUid: String, val authorName: String?, val text: String, val createdAtMillis: Long?)
data class ClubMessageCursor(val id: String, val seconds: Long, val nanoseconds: Int)
data class ClubMessagePage(val ids: List<String>, val nextCursor: ClubMessageCursor?, val hasMore: Boolean)
data class ClubPage<T>(val items: List<T>, val nextCursor: String?)

/** Fresh server proof, never an offline membership cache or a UI authorization decision. */
sealed interface ClubAccess {
    data object Unverified : ClubAccess
    data object None : ClubAccess
    data class Member(val membership: ClubMembership) : ClubAccess
}
sealed interface ClubChatState {
    data object Unavailable : ClubChatState
    data class Ready(val membership: ClubMembership, val messages: List<ClubMessage>, val hiddenUids: Set<String> = emptySet(), val oldestCursor: ClubMessageCursor? = null, val hasOlder: Boolean = false) : ClubChatState
}
class ClubException(val code: String, val cooldown: com.kungsbackacarcommunity.app.chat.ChatRateLimit? = null) : Exception(code)

internal fun Any?.clubMap(): Map<String, Any?> = (this as? Map<*, *>)?.entries
    ?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }?.toMap().orEmpty()
internal fun Map<String, Any?>.text(key: String) = (get(key) as? String)?.trim().orEmpty()
internal fun Map<String, Any?>.nullableText(key: String) = text(key).takeIf { it.isNotEmpty() }
internal fun Map<String, Any?>.items(key: String) = (get(key) as? List<*>)?.map { it.clubMap() }.orEmpty()
internal fun parseClub(raw: Map<String, Any?>): Club? {
    val id = raw.text("id"); val name = raw.text("name")
    if (id.isBlank() || name.isBlank()) return null
    val stats = raw["stats"].clubMap()
    return Club(id, name, raw.text("description"), raw.text("location"), raw.text("visibility").ifBlank { "open" },
        raw.text("ownerUid"), safeClubImage(raw.nullableText("logoUrl")),
        (raw["memberCount"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
        (raw["moderatorCount"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
        ClubStats((stats["driveCount"] as? Number)?.toLong()?.coerceAtLeast(0) ?: 0,
            (stats["distanceMeters"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0,
            (stats["durationSeconds"] as? Number)?.toLong()?.coerceAtLeast(0) ?: 0),
        raw.text("status").ifBlank { "active" })
}
internal fun parseMembership(raw: Map<String, Any?>): ClubMembership? {
    val id = raw.text("clubId"); val role = ClubRole.parse(raw["role"]) ?: return null
    val joined = raw.text("joinedAt")
    return if (id.isNotBlank() && clubInstant(joined) != null) ClubMembership(id, role, joined) else null
}
internal fun parseClubState(raw: Map<String, Any?>) = ClubState(
    parseClub(raw["club"].clubMap()), parseMembership(raw["membership"].clubMap()), raw.nullableText("joinAllowedAt"),
    raw.items("invitations").mapNotNull { i ->
        i.text("clubId").takeIf { it.isNotBlank() }?.let { ClubInvitation(it, i.text("clubName"), i.text("expiresAt")) }
    }, raw["legacyConflict"] == true,
)
internal fun safeClubImage(value: String?): String? = value?.takeIf {
    runCatching { java.net.URI(it).let { u -> u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null } }.getOrDefault(false)
}
internal fun clubInstant(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
internal fun ClubRole.canRemove(viewerUid: String, target: ClubMember) = target.uid != viewerUid && target.role != ClubRole.Owner &&
    (this == ClubRole.Owner || (this == ClubRole.Moderator && target.role == ClubRole.Member))

/** Callable error envelope only: session/auth denial takes precedence over cosmetic send retry UX. */
internal fun clubCallableFailure(code: String, details: Any?, currentSession: Boolean): ClubException {
    val normalized = code.lowercase().replace('_', '-')
    if (!currentSession || normalized == "unauthenticated") return ClubException("unauthenticated")
    if (normalized == "permission-denied") return ClubException("permission-denied")
    val cooldown = com.kungsbackacarcommunity.app.chat.ChatRateLimits.parse(code, details)
    val reason = details.clubMap().nullableText("reason")
    return ClubException(reason ?: normalized, cooldown)
}
