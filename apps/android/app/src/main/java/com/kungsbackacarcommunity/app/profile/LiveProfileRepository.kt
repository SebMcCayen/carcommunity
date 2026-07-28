package com.kungsbackacarcommunity.app.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Batched read access to members' CURRENT public profiles (`users/{uid}`), used
 * to overlay the stale denormalized copies described on [LiveProfiles].
 *
 * Two entry points because the surfaces need different shapes, not because the
 * data differs:
 *  - [observeProfiles] for a listener-backed window whose uid set changes as
 *    messages/rows arrive (chat live windows, the DM inbox).
 *  - [loadProfiles] for a one-shot read alongside a callable (an older chat page,
 *    a convoy list/detail refresh).
 *
 * Firebase-free so every consumer stays unit-testable with a fake, matching
 * [com.kungsbackacarcommunity.app.blocking.BlockVisibilityRepository].
 */
interface LiveProfileRepository {

    /**
     * Emits the live profiles for [uids], re-emitting as more of them resolve.
     *
     * MUST emit at least once, promptly, even when nothing is known yet: every
     * consumer folds this into a message/row flow, so a flow that only emitted
     * once the network answered would hold the whole screen on Loading. The first
     * emission is therefore whatever is already cached (often empty), and a
     * second follows when the reads land. An empty map means "no live opinion" —
     * every row falls back to its stored copy, i.e. exactly today's behaviour.
     *
     * A uid is present in the map ONLY when its user document exists; see
     * [LiveProfiles.resolve] for why absent and null-valued must stay distinct.
     */
    fun observeProfiles(uids: Set<String>): Flow<Map<String, LiveProfile>>

    /**
     * Reads the live profiles for [uids] once.
     *
     * Best-effort with the same contract as [observeProfiles]: a failed read
     * yields an absent entry, never an exception and never a blank profile, so a
     * caller degrades to the stored copies instead of failing.
     */
    suspend fun loadProfiles(uids: Set<String>): Map<String, LiveProfile>

    companion object {
        /**
         * A repository with no live opinion about anyone — the fallback for a
         * config-less build. Every surface then renders its stored denormalized
         * copies, which is precisely the pre-hydration behaviour, so a missing
         * Firebase config degrades to the old rendering rather than to blank rows.
         */
        val EMPTY: LiveProfileRepository = object : LiveProfileRepository {
            override fun observeProfiles(uids: Set<String>): Flow<Map<String, LiveProfile>> =
                flowOf(emptyMap())

            override suspend fun loadProfiles(uids: Set<String>): Map<String, LiveProfile> =
                emptyMap()
        }
    }
}
