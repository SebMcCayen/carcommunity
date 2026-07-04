/**
 * Feature flags domain module for the admin portal (Phase 13 vertical).
 *
 * Live values are read directly from the `config/featureFlags` Firestore
 * document (authenticated read, rules-gated since Phase 9m), falling back
 * to the contract defaults when the document or a field is absent — the
 * same degradation rule the backend readers use. Toggling goes through
 * the audited `admin-setFeatureFlag` callable (closed key namespace).
 */

import { doc, getDoc } from 'firebase/firestore';
import { DEFAULT_FEATURE_FLAGS as LEGACY_DEFAULT_FEATURE_FLAGS } from '@carcommunity/shared/feature-flags';
import { getAdminFirestore } from '../../lib/firestore';
import { callAdmin } from '../../lib/callables';

/**
 * Canonical defaults per contracts/features/feature-flags.json: the
 * legacy shared list plus partnerInsightsPassBy (added in Phase 9m; the
 * legacy package is frozen). Default OFF — the 9j privacy gate.
 */
export const DEFAULT_FEATURE_FLAGS = {
  ...LEGACY_DEFAULT_FEATURE_FLAGS,
  partnerInsightsPassBy: false,
} as const;

export type FeatureFlagKey = keyof typeof DEFAULT_FEATURE_FLAGS;
/** Full flag map, including keys the frozen legacy shared type lacks. */
export type FeatureFlags = Record<FeatureFlagKey, boolean>;

export interface FeatureFlagRow {
  key: FeatureFlagKey;
  enabled: boolean;
  /** True when the value comes from Firestore rather than the contract default. */
  overridden: boolean;
}

/** Contract defaults — the offline/fallback view. */
export function getFeatureFlagRows(): FeatureFlagRow[] {
  return (Object.keys(DEFAULT_FEATURE_FLAGS) as FeatureFlagKey[]).map((key) => ({
    key,
    enabled: DEFAULT_FEATURE_FLAGS[key],
    overridden: false,
  }));
}

/** Live flag values from config/featureFlags, defaults where unset. */
export async function loadFeatureFlagRows(): Promise<FeatureFlagRow[]> {
  const snapshot = await getDoc(doc(getAdminFirestore(), 'config', 'featureFlags'));
  const stored = (snapshot.data() ?? {}) as Record<string, unknown>;
  return (Object.keys(DEFAULT_FEATURE_FLAGS) as FeatureFlagKey[]).map((key) => {
    const value = stored[key];
    const overridden = typeof value === 'boolean';
    return {
      key,
      enabled: overridden ? (value as boolean) : DEFAULT_FEATURE_FLAGS[key],
      overridden,
    };
  });
}

export interface SetFeatureFlagResult {
  key: FeatureFlagKey;
  enabled: boolean;
}

/**
 * Toggles one flag via the audited admin.setFeatureFlag callable.
 * Unknown keys are rejected server-side (closed namespace).
 */
export async function setFeatureFlag(
  key: FeatureFlagKey,
  enabled: boolean,
  reason?: string,
): Promise<SetFeatureFlagResult> {
  return callAdmin<SetFeatureFlagResult>('admin-setFeatureFlag', {
    key,
    enabled,
    ...(reason ? { reason } : {}),
  });
}
