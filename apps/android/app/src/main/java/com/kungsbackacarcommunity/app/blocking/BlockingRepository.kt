package com.kungsbackacarcommunity.app.blocking

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the blocked-users list. */
sealed interface BlockedUsersState {
    data object Loading : BlockedUsersState

    data object Error : BlockedUsersState

    data class Loaded(val users: List<BlockedUser>) : BlockedUsersState
}

/**
 * Blocking access (Phase 12 slice 8). Firebase-free for testability. Reads are
 * owner-scoped (`userBlocks/{uid}/blocked`, rules-gated); mutations go through
 * the `blocking-block` / `blocking-unblock` callables — the client never writes
 * userBlocks directly.
 */
interface BlockingRepository {
    fun observeBlocked(uid: String): Flow<BlockedUsersState>

    /** Blocks a target user. Backend rejects self-blocks and missing users. */
    suspend fun block(targetUserId: String)

    /** Unblocks a target user. Idempotent — unblocking a non-block is a no-op. */
    suspend fun unblock(targetUserId: String)
}
