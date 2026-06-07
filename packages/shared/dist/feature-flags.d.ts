/**
 * Shared feature flag contract used across API, mobile, and admin.
 *
 * Add new flags here when introducing gated features.
 * All flag names use camelCase to match the API JSON response.
 */
export type FeatureFlagKey = 'liveLocation' | 'chat' | 'crownHunt' | 'partnerStats' | 'pushNotifications' | 'socialSharing' | 'externalDataSources' | 'digitalBillboards';
/** Map of every feature flag to its enabled state. */
export type FeatureFlags = Record<FeatureFlagKey, boolean>;
/**
 * Default feature flag values used as a fallback when the API is unavailable
 * and as the initial static response from the API.
 */
export declare const DEFAULT_FEATURE_FLAGS: FeatureFlags;
/** Where the flag values were loaded from. */
export type FeatureFlagSource = 'static' | 'remote';
/** Shape of the `GET /v1/feature-flags` API response. */
export interface FeatureFlagResponse {
    ok: true;
    data: {
        flags: FeatureFlags;
        updatedAt: string;
        source: FeatureFlagSource;
    };
}
//# sourceMappingURL=feature-flags.d.ts.map