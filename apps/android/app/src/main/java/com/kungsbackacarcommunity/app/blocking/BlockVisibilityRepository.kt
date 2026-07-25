package com.kungsbackacarcommunity.app.blocking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Read access to the caller's mutual-hidden set — the uids neither party of a
 * block may see, in either direction.
 *
 * Backed by `blockVisibility/{uid}.hiddenUids` (owner-read-only, written solely
 * by the `blocking-onBlockWrite` trigger). The client cannot derive this set
 * itself: `userBlocks/{uid}/blocked` only holds the uids this user blocked, and
 * the other direction lives in the OTHER party's owner-read-only subcollection.
 *
 * Firebase-free so chat repositories stay unit-testable with a fake.
 */
fun interface BlockVisibilityRepository {
    /**
     * Emits the current mutual-hidden uid set, re-emitting when it changes.
     *
     * Never fails: a listener error emits the last-known set (initially empty)
     * rather than an error state, because the consumers are message filters that
     * must keep rendering. An empty set means "hide nothing" — the same thing a
     * user with no blocks sees — so a transient failure degrades to today's
     * behaviour rather than to a blank channel.
     */
    fun observeHiddenUids(): Flow<Set<String>>

    companion object {
        /** A repository that hides nothing — the fallback for a config-less build. */
        val EMPTY: BlockVisibilityRepository = BlockVisibilityRepository {
            flowOf(emptySet())
        }
    }
}
