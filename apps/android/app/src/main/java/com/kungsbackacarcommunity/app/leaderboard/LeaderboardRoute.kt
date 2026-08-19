package com.kungsbackacarcommunity.app.leaderboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.flowOf

/**
 * Social leaderboard route: holds the All-time / This-month scope selection and
 * wires the matching read-only board stream into [LeaderboardScreen].
 *
 * A pure read of the precomputed `leaderboards/{scope}` document via [repository]
 * — the board is server-ranked, name/avatar-resolved and opt-out-filtered, so there
 * is nothing to write and nothing to compute here beyond the pure fold in
 * [LeaderboardBoard]. The stream re-subscribes when the scope changes; the selected
 * scope survives configuration changes and process death via [rememberSaveable].
 *
 * @param repository the board source. Null in a config-less/CI build → the screen
 *   shows its loading affordance.
 * Back is handled centrally by the shell (AeroPage's pinned arrow + the system-Back
 * dispatcher), so no back callback is threaded here.
 *
 * @param uid the signed-in member, so their own row is highlighted where it appears.
 */
@Composable
fun LeaderboardRoute(
    repository: LeaderboardRepository?,
    uid: String?,
) {
    var scope by rememberSaveable { mutableStateOf(LeaderboardScope.ALL_TIME) }

    val state by
        remember(repository, uid, scope) {
            if (repository != null) {
                repository.observeBoard(scope, uid)
            } else {
                flowOf(LeaderboardUiState.Loading)
            }
        }
            .collectAsState(initial = LeaderboardUiState.Loading)

    LeaderboardScreen(
        scope = scope,
        onScopeChange = { scope = it },
        state = state,
    )
}
