package com.kungsbackacarcommunity.app.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Privacy settings integration route: the partner-stats opt-in (Phase 12 slice
 * 19) plus the leaderboard-visibility opt-out (Leaderboard PR4). Both are
 * rules-validated owner writes to `userPrivate/{uid}`; the leaderboard section
 * renders only when its (optional) repository is wired.
 */
@Composable
fun PartnerStatsRoute(
    repository: PartnerStatsRepository,
    coordinator: PartnerStatsCoordinator?,
    uid: String,
    onBack: () -> Unit,
    leaderboardRepository: LeaderboardVisibilityRepository? = null,
    leaderboardCoordinator: LeaderboardVisibilityCoordinator? = null,
) {
    val scope = rememberCoroutineScope()
    val consent by
        remember(repository, uid) { repository.observeConsent(uid) }
            .collectAsState(initial = PartnerStatsConsentState.Unknown)
    val saveStatus by
        (coordinator?.saveStatus ?: flowOf(PartnerStatsSaveStatus.Idle))
            .collectAsState(initial = PartnerStatsSaveStatus.Idle)

    val leaderboardVisibility by
        remember(leaderboardRepository, uid) {
            leaderboardRepository?.observeVisibility(uid) ?: flowOf(LeaderboardVisibilityState.Unknown)
        }.collectAsState(initial = LeaderboardVisibilityState.Unknown)
    val leaderboardSaveStatus by
        (leaderboardCoordinator?.saveStatus ?: flowOf(LeaderboardVisibilitySaveStatus.Idle))
            .collectAsState(initial = LeaderboardVisibilitySaveStatus.Idle)

    PartnerStatsScreen(
        consent = consent,
        saveStatus = saveStatus,
        onSave = { optIn -> coordinator?.let { c -> scope.launch { c.save(uid, optIn) } } },
        onBack = {
            coordinator?.reset()
            leaderboardCoordinator?.reset()
            onBack()
        },
        leaderboardVisibility = leaderboardVisibility,
        leaderboardSaveStatus = leaderboardSaveStatus,
        // The switch models "shown"; persist the inverse `leaderboardOptOut`.
        onSaveLeaderboard =
            leaderboardCoordinator?.let { c ->
                { shown: Boolean -> scope.launch { c.save(uid, optOut = !shown) } }
            },
    )
}
