/**
 * Shared feature flag contract used across API, mobile, and admin.
 *
 * Add new flags here when introducing gated features.
 * All flag names use camelCase to match the API JSON response.
 */

export type FeatureFlagKey =
  | 'liveLocation'
  | 'chat'
  | 'crownHunt'
  | 'partners'
  | 'partnerStats'
  | 'pushNotifications'
  | 'socialSharing'
  | 'externalDataSources'
  | 'digitalBillboards';

/** Map of every feature flag to its enabled state. */
export type FeatureFlags = Record<FeatureFlagKey, boolean>;

/**
 * Default feature flag values used as a fallback when the API is unavailable
 * and as the initial static response from the API.
 */
export const DEFAULT_FEATURE_FLAGS: FeatureFlags = {
  liveLocation: true,
  chat: true,
  crownHunt: true,
  partners: true,
  partnerStats: true,
  pushNotifications: true,
  socialSharing: true,
  externalDataSources: true,
  digitalBillboards: true,
};

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
