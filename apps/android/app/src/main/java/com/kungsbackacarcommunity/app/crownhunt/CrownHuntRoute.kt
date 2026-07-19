package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
 */
@Composable
fun CrownHuntRoute(
    repository: CrownHuntRepository,
    coordinator: CrownHuntCoordinator?,
    passesMemberGate: Boolean,
    onBack: () -> Unit,
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
    )
}
