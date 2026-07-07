package com.kungsbackacarcommunity.app.blocking

/**
 * Blocking domain (Phase 12 slice 8). The backend is the source of truth:
 * blocks are directional and written only by the `blocking-block` /
 * `blocking-unblock` callables. Clients read the owner-only mirror at
 * `userBlocks/{uid}/blocked/{blockedId}` (fields: blockedUserId, displayName,
 * createdAt) — which never reveals who blocked the caller. Pure Kotlin for
 * testability.
 */
data class BlockedUser(
    val userId: String,
    val displayName: String?,
    val blockedAtMillis: Long?,
)

object BlockedUsers {
    /** Newest block first; undated entries sort last. */
    fun sortedForList(users: List<BlockedUser>): List<BlockedUser> =
        users.sortedByDescending { it.blockedAtMillis ?: Long.MIN_VALUE }
}
