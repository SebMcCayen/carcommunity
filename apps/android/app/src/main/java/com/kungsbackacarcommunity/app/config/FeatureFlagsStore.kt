package com.kungsbackacarcommunity.app.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the current feature-flag set and refreshes it on launch/resume
 * (Phase 12 slice 3). Starts at the contract defaults so the UI is always
 * usable; a failed refresh keeps the last good values. Pure Kotlin so it is
 * unit-testable with a fake repository.
 */
class FeatureFlagsStore(
    private val repository: FeatureFlagsRepository?,
) {
    private val state = MutableStateFlow(FeatureFlags.DEFAULTS)
    val flags: StateFlow<FeatureFlags> = state.asStateFlow()

    /** Re-reads config/featureFlags; keeps the current values on failure. */
    suspend fun refresh() {
        val repo = repository ?: return
        try {
            state.value = repo.fetch()
        } catch (cancellation: CancellationException) {
            // Never swallow coroutine cancellation (matches the coordinators).
            throw cancellation
        } catch (failure: Exception) {
            // Keep the last good flags (or defaults). Flags never fail "off".
        }
    }
}
