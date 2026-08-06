package com.kungsbackacarcommunity.app.crownhunt

import kotlinx.coroutines.flow.Flow

/**
 * Read-only access to the member's own Kronjakt statistics and the current
 * season's leaderboard, for the hub page.
 *
 * Firebase-free interface so the page + route are unit-testable with fakes. The
 * one implementation ([FirebaseCrownHuntStatsRepository]) is direct, rules-gated
 * Firestore reads — the collections expose no callable, and every write rule is
 * `false` (backend triggers own them), so there is nothing to write and nothing
 * for the client to compute beyond ranking + display-name resolution.
 */
interface CrownHuntStatsRepository {
    /**
     * Emits [CrownStatsUiState.Loading], then [CrownStatsUiState.Loaded] once the
     * viewer's stats + this season's board have been read (or
     * [CrownStatsUiState.Error] on a failed read). A one-shot read per
     * subscription — the aggregates change slowly (they move only when someone
     * collects a crown) so the page reads them on open rather than holding a live
     * listener open.
     */
    fun observeStats(uid: String): Flow<CrownStatsUiState>
}
