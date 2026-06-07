/**
 * Feature flags domain module for the admin portal.
 *
 * Provides the shared flag definitions and a placeholder list used by the
 * feature-flags admin view.  Editing flags is not implemented yet — the
 * backend must enforce any flag changes.
 */

import {
  DEFAULT_FEATURE_FLAGS,
  type FeatureFlagKey,
  type FeatureFlags,
} from '@carcommunity/shared/feature-flags';

export { DEFAULT_FEATURE_FLAGS };
export type { FeatureFlagKey, FeatureFlags };

export interface FeatureFlagRow {
  key: FeatureFlagKey;
  enabled: boolean;
}

/** Returns an ordered list of all feature flags with their current (static) values. */
export function getFeatureFlagRows(): FeatureFlagRow[] {
  return (Object.keys(DEFAULT_FEATURE_FLAGS) as FeatureFlagKey[]).map((key) => ({
    key,
    enabled: DEFAULT_FEATURE_FLAGS[key],
  }));
}
