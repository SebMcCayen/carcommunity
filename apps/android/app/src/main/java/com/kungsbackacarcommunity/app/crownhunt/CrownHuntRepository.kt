package com.kungsbackacarcommunity.app.crownhunt

import kotlinx.coroutines.flow.Flow

/** UI-facing state of the active-points list. */
sealed interface CrownHuntPointsState {
    data object Loading : CrownHuntPointsState

    data object Error : CrownHuntPointsState

    data class Loaded(val points: List<CrownHuntPoint>) : CrownHuntPointsState
}

/**
 * Kronjakt operations (Phase 12 slice 16). Firebase-free interface so the
 * screen/coordinator logic is unit-testable with fakes.
 *
 * Active points are a rules-gated Firestore read (member + active). Submitting
 * a claim is the crownHunt.submitClaim callable, which returns a result CODE
 * (eligibility failures are results, not errors) — the caller maps it to a
 * localized message.
 */
interface CrownHuntRepository {
    /** Active reward points; Loading until the first snapshot. */
    fun observeActivePoints(): Flow<CrownHuntPointsState>

    /** crownHunt.submitClaim — returns the claim outcome for the given point. */
    suspend fun submitClaim(
        pointId: String,
        coordinate: ClaimCoordinate,
        idempotencyKey: String,
    ): ClaimOutcome
}
