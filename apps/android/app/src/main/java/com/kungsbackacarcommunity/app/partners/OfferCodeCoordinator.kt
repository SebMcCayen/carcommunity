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

    suspend fun reveal(offerId: String) {
        val current = state.value
        // Only dedupe an in-flight reveal for the *same* offer; a switch to a
        // different offer must be able to start its own reveal.
        if (current is OfferCodeStatus.Loading && current.offerId == offerId) return
        state.value = OfferCodeStatus.Loading(offerId)
        try {
            val code = repository.showOfferCode(offerId)
            // Publish only if we're still loading this offer — a reset() or a
            // switch to another offer while the callable was in flight wins.
            if (isLoadingOffer(offerId)) state.value = OfferCodeStatus.Shown(offerId, code)
        } catch (cancellation: CancellationException) {
            if (isLoadingOffer(offerId)) state.value = OfferCodeStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            if (isLoadingOffer(offerId)) state.value = OfferCodeStatus.Failed(offerId)
        }
    }

    fun reset() {
        state.value = OfferCodeStatus.Idle
    }

    private fun isLoadingOffer(offerId: String): Boolean {
        val s = state.value
        return s is OfferCodeStatus.Loading && s.offerId == offerId
    }
}
