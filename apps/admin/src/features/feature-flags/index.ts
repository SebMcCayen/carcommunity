/**
 * Feature flags domain module for the admin portal (Phase 13 vertical).
 *
 * The rendered flag list comes from `./contract` — i.e. straight from
 * contracts/features/feature-flags.json — so the operator console can never
 * render fewer flags than the backend honours. It previously derived from a
 * frozen nine-key array in `packages/shared`, which hid `crownHuntSpawn`.
 *
 * Live values are read directly from the `config/featureFlags` Firestore
 * document (authenticated read, rules-gated since Phase 9m), falling back
 * to the contract defaults when the document or a field is absent — the
 * same degradation rule the backend readers use. Toggling goes through
 * the audited `admin-setFeatureFlag` callable (closed key namespace).
 */

import { doc, getDoc } from 'firebase/firestore';
import { getAdminFirestore } from '../../lib/firestore';
import { callAdmin } from '../../lib/callables';
import { buildFeatureFlagRows, type FeatureFlagKey, type FeatureFlagRow } from './contract';

export {
  DEFAULT_FEATURE_FLAGS,
  FEATURE_FLAG_DEFINITIONS,
  FEATURE_FLAG_KEYS,
  buildFeatureFlagRows,
  getFeatureFlagDefault,
  getFeatureFlagDefinition,
  getFeatureFlagRows,
  isFeatureFlagKey,
} from './contract';
export type {
  FeatureFlagDefinition,
  FeatureFlagKey,
  FeatureFlagRow,
  FeatureFlagSensitivity,
} from './contract';

/** Full flag map: every contract key to its effective boolean. */
export type FeatureFlags = Record<FeatureFlagKey, boolean>;

/** Live flag values from config/featureFlags, defaults where unset. */
export async function loadFeatureFlagRows(): Promise<FeatureFlagRow[]> {
  const snapshot = await getDoc(doc(getAdminFirestore(), 'config', 'featureFlags'));
  return buildFeatureFlagRows((snapshot.data() ?? {}) as Record<string, unknown>);
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
