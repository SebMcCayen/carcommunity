package com.kungsbackacarcommunity.app.partners

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of an offer-code reveal, scoped to one offer at a time. */
sealed interface OfferCodeStatus {
    data object Idle : OfferCodeStatus

    data class Loading(val offerId: String) : OfferCodeStatus

    data class Shown(val offerId: String, val code: String?) : OfferCodeStatus

    data class Failed(val offerId: String) : OfferCodeStatus
}

/**
 * Reveals a partner offer's discount code via the callable (Phase 12 slice 17).
 * Pure Kotlin so it is unit-testable with a fake repository.
 */
class OfferCodeCoordinator(
    private val repository: PartnersRepository,
) {
    private val state = MutableStateFlow<OfferCodeStatus>(OfferCodeStatus.Idle)
    val status: StateFlow<OfferCodeStatus> = state.asStateFlow()
    private var revealGeneration = 0

    suspend fun reveal(offerId: String) {
        val current = state.value
        // Only dedupe an in-flight reveal for the *same* offer; a switch to a
        // different offer must be able to start its own reveal.
        if (current is OfferCodeStatus.Loading && current.offerId == offerId) return
        val generation = ++revealGeneration
        state.value = OfferCodeStatus.Loading(offerId)
        try {
            val code = repository.showOfferCode(offerId)
            // The generation distinguishes a reset-and-reopen of the same offer
            // from the request that was active before the reset.
            if (isCurrentReveal(offerId, generation)) {
                state.value = OfferCodeStatus.Shown(offerId, code)
            }
        } catch (cancellation: CancellationException) {
            if (isCurrentReveal(offerId, generation)) state.value = OfferCodeStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            if (isCurrentReveal(offerId, generation)) state.value = OfferCodeStatus.Failed(offerId)
        }
    }

    fun reset() {
        revealGeneration++
        state.value = OfferCodeStatus.Idle
    }

    private fun isCurrentReveal(offerId: String, generation: Int): Boolean {
        val s = state.value
        return revealGeneration == generation && s is OfferCodeStatus.Loading && s.offerId == offerId
    }
}
