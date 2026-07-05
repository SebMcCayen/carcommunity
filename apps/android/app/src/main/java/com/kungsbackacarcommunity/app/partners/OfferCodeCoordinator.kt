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
        if (current is OfferCodeStatus.Loading) return
        state.value = OfferCodeStatus.Loading(offerId)
        try {
            val code = repository.showOfferCode(offerId)
            state.value = OfferCodeStatus.Shown(offerId, code)
        } catch (cancellation: CancellationException) {
            state.value = OfferCodeStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            state.value = OfferCodeStatus.Failed(offerId)
        }
    }

    fun reset() {
        state.value = OfferCodeStatus.Idle
    }
}
