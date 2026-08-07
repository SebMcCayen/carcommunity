package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.kungsbackacarcommunity.app.badges.BadgesRepository
import com.kungsbackacarcommunity.app.badges.BadgesState
import kotlinx.coroutines.flow.flowOf

/**
 * Kronjakt hub route: wires the read-only stats/leaderboard stream and the
 * member's own badge standing into [CrownHuntScreen].
 *
 * No longer wires a claim path — the hub no longer collects crowns (that happens
 * on the map). It is a pure read: the #710 aggregates via [statsRepository], and
 * the badge ladder via [badgesRepository].
 *
 * @param statsRepository the viewer's stats + season leaderboard source. Null in a
 *   config-less build → the page shows its loading state and the badge band only.
 * @param badgesRepository powers the member's own Kronjägare TIER standing — the
 *   same owner-scoped `users/{uid}/badges` listener the profile badge wall uses,
 *   so the page adds no new query shape or index for it.
 */
@Composable
fun CrownHuntRoute(
    statsRepository: CrownHuntStatsRepository?,
    passesMemberGate: Boolean,
    onBack: () -> Unit,
    badgesRepository: BadgesRepository? = null,
    uid: String? = null,
) {
    val statsState by
        remember(statsRepository, uid, passesMemberGate) {
            if (statsRepository != null && uid != null && passesMemberGate) {
                statsRepository.observeStats(uid)
            } else {
                flowOf(CrownStatsUiState.Loading)
            }
        }
            .collectAsState(initial = CrownStatsUiState.Loading)

    // The member's own crown-hunter badge standing. Only subscribed when the
    // member gate passes (a non-member sees the teaser) and a repo + uid are
    // wired; otherwise it stays null and the badge band is omitted.
    val badgesState by
        remember(badgesRepository, uid, passesMemberGate) {
            if (badgesRepository != null && uid != null && passesMemberGate) {
                badgesRepository.observeBadges(uid)
            } else {
                flowOf(BadgesState.Loading)
            }
        }
            .collectAsState(initial = BadgesState.Loading)
    val kronjagare =
        remember(badgesState) {
            (badgesState as? BadgesState.Loaded)?.let { CrownHuntStats.kronjagare(it.badges) }
        }

    CrownHuntScreen(
        statsState = statsState,
        passesMemberGate = passesMemberGate,
        onBack = onBack,
        kronjagare = kronjagare,
    )
}
