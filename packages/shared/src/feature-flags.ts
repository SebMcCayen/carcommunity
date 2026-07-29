/**
 * Shared feature flag contract used across API, mobile, and admin.
 *
 * Add new flags here when introducing gated features.
 * All flag names use camelCase to match the API JSON response.
 */

type FeatureFlagKey =
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
type FeatureFlags = Record<FeatureFlagKey, boolean>;

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

