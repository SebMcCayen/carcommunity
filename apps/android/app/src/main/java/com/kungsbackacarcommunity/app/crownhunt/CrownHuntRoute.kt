package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kungsbackacarcommunity.app.badges.BadgesRepository
import com.kungsbackacarcommunity.app.badges.BadgesState
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Kronjakt integration route (Phase 12 slice 16): wires the active-points
 * stream and the claim coordinator into [CrownHuntScreen].
 *
 * [locationProvider] supplies the device position at collect time; it defaults
 * to null because GPS capture lands with the map/background-location slice.
 * When that arrives, a real provider is injected and the same claim pipeline
 * (coordinator → callable) runs unchanged.
 *
 * [badgesRepository] powers the member's own Kronjägare standing shown above the
 * nearby list — the same owner-scoped `users/{uid}/badges` listener the profile
 * badge wall already uses, so the page adds no new query shape or index. Null in
 * a config-less build: the stats band is then simply omitted and the page still
 * renders its empty state / nearby list.
 */
@Composable
fun CrownHuntRoute(
    repository: CrownHuntRepository,
    coordinator: CrownHuntCoordinator?,
    passesMemberGate: Boolean,
    onBack: () -> Unit,
    badgesRepository: BadgesRepository? = null,
    uid: String? = null,
    locationProvider: suspend () -> ClaimCoordinate? = { null },
    idempotencyKeyProvider: () -> String = { UUID.randomUUID().toString() },
) {
    val scope = rememberCoroutineScope()
    val pointsState by
        remember(repository, passesMemberGate) {
            if (passesMemberGate) repository.observeActivePoints() else flowOf(CrownHuntPointsState.Loaded(emptyList()))
        }
            .collectAsState(initial = CrownHuntPointsState.Loading)
    val claimStatus by
        (coordinator?.status ?: flowOf(CrownHuntClaimStatus.Idle))
            .collectAsState(initial = CrownHuntClaimStatus.Idle)

    // The member's own crown-hunter standing. Only subscribed when the member
    // gate passes (a non-member sees the teaser, not the stats) and a repo + uid
    // are wired; otherwise it stays null and the stats band is omitted.
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
        pointsState = pointsState,
        claimStatus = claimStatus,
        passesMemberGate = passesMemberGate,
        onCollect = { pointId ->
            coordinator?.let { c ->
                scope.launch { c.claim(pointId, locationProvider(), idempotencyKeyProvider()) }
            }
        },
        onBack = {
            coordinator?.reset()
            onBack()
        },
        kronjagare = kronjagare,
    )
}
