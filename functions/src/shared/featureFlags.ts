/**
 * readFeatureFlag — the single flag reader (Phase 9m), replacing the four
 * per-domain copies that grew in 9h–9l. See featureFlags-core.ts for the
 * canonical key list, defaults, and model documentation.
 */

import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { FEATURE_FLAG_DEFAULTS, type FeatureFlagKey } from './featureFlags-core';

/**
 * The raw flag field-map from a single `config/featureFlags` read, or `null`
 * when the document is absent or the read threw. `null` and a missing field are
 * treated identically by [flagFromSnapshot] — both fall back to the contract
 * default — so a caller never has to branch on which happened.
 */
export type FeatureFlagsSnapshot = Record<string, unknown> | null;

/**
 * Reads the `config/featureFlags` document ONCE and returns its field-map (or
 * `null` on absence / read error). Pair with [flagFromSnapshot] to derive
 * several flags from a single Firestore read on a hot path — instead of calling
 * [readFeatureFlag] once per flag, which re-reads the same document each time.
 * The default-on-missing/unreadable behaviour is identical to [readFeatureFlag]:
 * on a read error this logs and returns `null`, and every flag then resolves to
 * its contract default via [flagFromSnapshot].
 */
export async function readFeatureFlagsSnapshot(): Promise<FeatureFlagsSnapshot> {
  try {
    const snap = await db.collection('config').doc('featureFlags').get();
    return snap.data() ?? null;
  } catch (error) {
    logger.warn('Feature flags read failed; using contract defaults', {
      error: String(error),
    });
    return null;
  }
}

/**
 * Derives one flag from a snapshot taken by [readFeatureFlagsSnapshot], applying
 * the EXACT same rule as [readFeatureFlag]: a boolean field wins, anything else
 * (missing document, missing field, or a non-boolean value) falls back to the
 * flag's contract default. Pure — no I/O — so several flags come from one read.
 */
export function flagFromSnapshot(snapshot: FeatureFlagsSnapshot, key: FeatureFlagKey): boolean {
  const value = snapshot?.[key];
  return typeof value === 'boolean' ? value : FEATURE_FLAG_DEFAULTS[key];
}

/**
 * Reads one flag from `config/featureFlags`, falling back to the contract
 * default when the document/field is missing, malformed, or unreadable.
 */
export async function readFeatureFlag(key: FeatureFlagKey): Promise<boolean> {
  return flagFromSnapshot(await readFeatureFlagsSnapshot(), key);
}
