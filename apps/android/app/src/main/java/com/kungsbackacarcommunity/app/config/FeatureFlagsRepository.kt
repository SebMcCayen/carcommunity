package com.kungsbackacarcommunity.app.config

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
}
