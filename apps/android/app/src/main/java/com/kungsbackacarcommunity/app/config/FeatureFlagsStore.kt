package com.kungsbackacarcommunity.app.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the current feature-flag set (Phase 12 slice 3). Starts at the
 * contract defaults so the UI is always usable, then tracks the backend LIVE
 * via [observe]. Pure Kotlin so it is unit-testable with a fake repository.
 *
 * The durable source of truth is [observe]: a realtime listener whose
 * emissions update [flags] as the backend changes and whose auto-reconnect
 * recovers a client that missed the value at startup. A listener/flow ERROR
 * keeps the last good value (a transient failure never silently forces flags
 * "off"); a backend value that omits a flag still resolves to that flag's
 * documented default, by design. [refresh] is an optional one-shot fast path
 * with the same never-fail-off-on-error contract.
 */
class FeatureFlagsStore(
    private val repository: FeatureFlagsRepository?,
) {
    private val state = MutableStateFlow(FeatureFlags.DEFAULTS)
    val flags: StateFlow<FeatureFlags> = state.asStateFlow()

    /**
     * Collects the repository's LIVE flag stream into [flags], updating on
     * every emission and keeping the last good value if the stream errors.
     * Returns when the stream ends or the collecting scope is cancelled; the
     * production stream ([FirebaseFeatureFlagsRepository.observe]) never
     * completes on its own, so in practice this suspends for the lifetime of
     * the authenticated session and its listener is removed (awaitClose) on
     * cancellation. Callers therefore launch it in an authenticated,
     * lifecycle-bound scope. A null repository (Firebase not configured) is a
     * no-op that leaves the defaults in place.
     */
    suspend fun observe() {
        val repo = repository ?: return
        try {
            repo.observe().collect { latest ->
                state.value = latest
            }
        } catch (cancellation: CancellationException) {
            // Never swallow coroutine cancellation (matches the coordinators).
            throw cancellation
        } catch (failure: Exception) {
            // Keep the last good flags (or defaults). Flags never fail "off".
        }
    }

    /** Re-reads config/featureFlags once; keeps the current values on failure. */
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
