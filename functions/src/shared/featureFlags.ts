/**
 * readFeatureFlag — the single flag reader (Phase 9m), replacing the four
 * per-domain copies that grew in 9h–9l. See featureFlags-core.ts for the
 * canonical key list, defaults, and model documentation.
 */

import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { FEATURE_FLAG_DEFAULTS, type FeatureFlagKey } from './featureFlags-core';

/**
 * Reads one flag from `config/featureFlags`, falling back to the contract
 * default when the document/field is missing, malformed, or unreadable.
 */
export async function readFeatureFlag(key: FeatureFlagKey): Promise<boolean> {
  try {
    const snap = await db.collection('config').doc('featureFlags').get();
    const value = snap.data()?.[key];
    return typeof value === 'boolean' ? value : FEATURE_FLAG_DEFAULTS[key];
  } catch (error) {
    logger.warn('Feature flag read failed; using contract default', {
      key,
      error: String(error),
    });
    return FEATURE_FLAG_DEFAULTS[key];
  }
}
