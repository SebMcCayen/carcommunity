package com.kungsbackacarcommunity.app.clubs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep field presence: LiveProfile.avatarPath alone cannot distinguish Driver from CC removal. */
internal fun clubMemberAvatar(uid: String, fields: Map<String, Any?>): ClubMemberAvatar {
    if (fields.containsKey("avatarPath")) {
        val path = (fields["avatarPath"] as? String)?.trim()?.takeIf {
            it.startsWith("profileImages/$uid/") && it.length <= 500 &&
                it.removePrefix("profileImages/$uid/").isNotBlank() &&
                it.split('/').none { part -> part == "." || part == ".." }
        }
        // An explicit null/invalid/removed CC path must not resurrect a legacy URL.
        return ClubMemberAvatar(path, null)
    }
    return ClubMemberAvatar(null, safeClubImage(fields["avatarUrl"] as? String))
}

internal fun ClubMember.withAvatar(avatars: Map<String, ClubMemberAvatar>): ClubMember =
    avatars[uid]?.let { copy(avatarPath = it.avatarPath, avatarUrl = it.avatarUrl) } ?: this

/** Public profile I/O is best-effort; account and fresh private access checks are not. */
internal suspend fun loadClubMemberAvatars(
    expectedUid: String,
    uids: Set<String>,
    currentUid: () -> String?,
    read: suspend (Set<String>) -> Map<String, Map<String, Any?>>,
    requireCurrentAccess: suspend () -> Unit,
): Map<String, ClubMemberAvatar> {
    fun requireUser() { if (currentUid() != expectedUid) throw ClubException("unauthenticated") }
    requireUser()
    require(uids.size <= 25 && uids.none { it.isBlank() })
    if (uids.isEmpty()) return emptyMap()
    val profiles = try { read(uids) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { emptyMap() }
    currentCoroutineContext().ensureActive()
    requireUser()
    requireCurrentAccess()
    currentCoroutineContext().ensureActive()
    requireUser()
    return profiles.filterKeys { it in uids }.mapValues { (uid, fields) -> clubMemberAvatar(uid, fields) }
}

/** A slow previous roster/club cannot overwrite the current private composition. */
internal suspend fun currentClubAvatarPage(
    request: suspend () -> Map<String, ClubMemberAvatar>,
    isCurrent: () -> Boolean,
): Map<String, ClubMemberAvatar>? {
    if (!isCurrent()) return null
    val result = request()
    currentCoroutineContext().ensureActive()
    return result.takeIf { isCurrent() }
}
