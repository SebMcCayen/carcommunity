package com.kungsbackacarcommunity.app.badges

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/** Badges integration route (Phase 12 slice 14): observe → render. */
@Composable
fun BadgesRoute(
    repository: BadgesRepository,
    uid: String,
    onBack: () -> Unit,
) {
    val state by
        remember(repository, uid) { repository.observeBadges(uid) }
            .collectAsState(initial = BadgesState.Loading)
    BadgesScreen(state = state, onBack = onBack)
}
