package com.kungsbackacarcommunity.app.crownhunt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of a claim attempt. */
sealed interface CrownHuntClaimStatus {
    data object Idle : CrownHuntClaimStatus

    data object Claiming : CrownHuntClaimStatus

    /** No device position was available (GPS lands with the map slice). */
    data object NeedsLocation : CrownHuntClaimStatus

    /** The callable returned a result code (awarded or an eligibility reason). */
    data class Done(val outcome: ClaimOutcome) : CrownHuntClaimStatus

    data object Failed : CrownHuntClaimStatus
}

/**
 * Orchestrates a Kronjakt claim (Phase 12 slice 16). Pure Kotlin so it is
 * unit-testable with a fake repository and an injected coordinate. The claim
 * needs a fresh device position; until the map/background-location slice
 * supplies one, a null coordinate surfaces [CrownHuntClaimStatus.NeedsLocation]
 * instead of calling the backend.
 */
class CrownHuntCoordinator(
    private val repository: CrownHuntRepository,
) {
    private val state = MutableStateFlow<CrownHuntClaimStatus>(CrownHuntClaimStatus.Idle)
    val status: StateFlow<CrownHuntClaimStatus> = state.asStateFlow()

    suspend fun claim(pointId: String, coordinate: ClaimCoordinate?, idempotencyKey: String) {
        if (state.value == CrownHuntClaimStatus.Claiming) return
        if (coordinate == null) {
            state.value = CrownHuntClaimStatus.NeedsLocation
            return
        }
        state.value = CrownHuntClaimStatus.Claiming
        try {
            val outcome = repository.submitClaim(pointId, coordinate, idempotencyKey)
            state.value = CrownHuntClaimStatus.Done(outcome)
        } catch (cancellation: CancellationException) {
            state.value = CrownHuntClaimStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = CrownHuntClaimStatus.Failed
        }
    }

    /** Clears the last result/failure so the buttons are usable again. */
    fun reset() {
        state.value = CrownHuntClaimStatus.Idle
    }
}
