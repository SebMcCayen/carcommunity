package com.kungsbackacarcommunity.app.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing progress of an onboarding submission. */
sealed interface OnboardingStatus {
    data object Idle : OnboardingStatus

    data object Submitting : OnboardingStatus

    /** The callable succeeded; the auth/profile listener advances the UI. */
    data object Done : OnboardingStatus

    data object Failed : OnboardingStatus
}

/**
 * Orchestrates the onboarding submission (Phase 12 slice 2). Pure Kotlin
 * (no Firebase/Android types) so the flow is unit-testable with fakes.
 */
class OnboardingCoordinator(
    private val repository: OnboardingRepository,
) {
    private val state = MutableStateFlow<OnboardingStatus>(OnboardingStatus.Idle)
    val status: StateFlow<OnboardingStatus> = state.asStateFlow()

    /**
     * Submits once; re-entrant calls while submitting are ignored.
     *
     * [anonymousPartnerStatsOptIn] carries the onboarding partner-statistics
     * choice (default-on / opt-out). null omits it — the backend keeps the
     * provisioning default (ON); false opts the member out.
     */
    suspend fun submit(displayName: String?, anonymousPartnerStatsOptIn: Boolean? = null) {
        if (state.value == OnboardingStatus.Submitting) return
        state.value = OnboardingStatus.Submitting
        try {
            repository.completeOnboarding(displayName, anonymousPartnerStatsOptIn)
            state.value = OnboardingStatus.Done
        } catch (cancellation: CancellationException) {
            state.value = OnboardingStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            // Details may reference the request payload — never logged.
            state.value = OnboardingStatus.Failed
        }
    }

    /** Clears a failure so the user can retry. */
    fun resetFailure() {
        if (state.value == OnboardingStatus.Failed) {
            state.value = OnboardingStatus.Idle
        }
    }
}
