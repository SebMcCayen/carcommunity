package com.kungsbackacarcommunity.app.config

import kotlinx.coroutines.flow.Flow

/**
 * Feature-flags boundary (Phase 12 slice 3). Firebase-free so callers can
 * be unit-tested with fakes.
 */
interface FeatureFlagsRepository {
    /**
     * Reads config/featureFlags once and returns the merged flag set
     * (stored values overlaid on the contract defaults).
     *
     * @throws Exception when the read fails (network, permissions).
     */
    suspend fun fetch(): FeatureFlags

    /**
     * A LIVE, self-recovering view of config/featureFlags: emits the merged
     * flag set immediately for the current value and again on every backend
     * change. Backed by a realtime listener that auto-reconnects, so a
     * transient failure (or a race with sign-in) at startup no longer leaves a
     * client stuck on the conservative defaults — the real value arrives as
     * soon as the read succeeds. A listener error is NOT surfaced as a
     * failure/reset emission; the collector simply keeps the last good value.
     */
    fun observe(): Flow<FeatureFlags>
}
