package com.kungsbackacarcommunity.app.leaderboard

import kotlinx.coroutines.flow.Flow

/**
 * Read-only access to the precomputed social leaderboard document for one scope.
 *
 * Firebase-free interface so the route + screen are unit-testable with fakes. The
 * one implementation ([FirebaseLeaderboardRepository]) is a single rules-gated
 * Firestore listener on `leaderboards/{scope}` — the collection exposes no callable
 * and its write rule is `false` (the scheduled Admin-SDK generator owns it), so
 * there is nothing to write and nothing for the client to compute beyond the pure
 * mapping in [LeaderboardBoard]. Names, avatars, ranks, opt-out and deleted-member
 * filtering are all resolved server-side, so a member reads the whole board from
 * this one cheap document.
 */
interface LeaderboardRepository {
    /**
     * Emits [LeaderboardUiState.Loading], then [LeaderboardUiState.Loaded] for
     * [scope]'s document (or [LeaderboardUiState.Error] on a failed read). A live
     * listener: the board is regenerated hourly, so an open screen refreshes on its
     * own when a new snapshot lands. [viewerUid] flags the signed-in member's own
     * row where present.
     */
    fun observeBoard(scope: LeaderboardScope, viewerUid: String?): Flow<LeaderboardUiState>
}
