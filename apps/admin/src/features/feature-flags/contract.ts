/**
 * Feature flag registry, read straight from the contract.
 *
 * There is no hand-maintained flag list in this app. The admin portal is the
 * only place an operator can flip a flag, so a list it maintains itself can
 * silently fall behind the backend — and it did: `crownHuntSpawn` was added to
 * the backend and to the contract in Phase 9m/9n but the page derived its rows
 * from a frozen nine-key array in `packages/shared`, so the kill switch for the
 * Kronjakt auto-spawn engine was not rendered at all and could only be turned
 * off by a deploy.
 *
 * `contracts/features/feature-flags.json` is imported directly (bundled at
 * build time), so adding a flag to the contract makes it appear here with no
 * code change. `feature-flags-contract-sync.test.ts` asserts that this set is
 * *equal* to the backend's canonical table — a flag missing on either side
 * fails the build.
 *
 * Note on typing: JSON imports widen string literals, so flag keys are plain
 * strings here rather than a literal union. The namespace stays closed at
 * runtime — `isFeatureFlagKey` guards this side and
 * `admin.setFeatureFlag`'s zod schema rejects unknown keys server-side.
 */

import contractJson from '../../../../../contracts/features/feature-flags.json';

/**
 * How much warning an operator gets before flipping a flag.
 * - `safety`: flipping it changes what members are invited to physically
 *   drive to and stop at. Enabling one requires an explicit confirmation;
 *   disabling one never does — an emergency stop must stay one click.
 * - `privacy`: governs data collected about members.
 * - `standard`: ordinary product surface.
 */
export type FeatureFlagSensitivity = 'safety' | 'privacy' | 'standard';

/** A flag key. Validated against the contract at runtime, not by the type. */
export type FeatureFlagKey = string;

export interface FeatureFlagDefinition {
  key: FeatureFlagKey;
  /** Contract default — the value used when the Firestore field is absent. */
  default: boolean;
  /** Short human-readable name shown to the operator. */
  label: string;
  /** What the flag controls, and what turning it off stops. */
  description: string;
  sensitivity: FeatureFlagSensitivity;
}

const SENSITIVITIES: readonly FeatureFlagSensitivity[] = ['safety', 'privacy', 'standard'];

function isSensitivity(value: unknown): value is FeatureFlagSensitivity {
  return SENSITIVITIES.includes(value as FeatureFlagSensitivity);
}

/**
 * Fails loudly at module load rather than rendering a half-populated page:
 * a malformed contract entry is a build/deploy mistake, and silently
 * dropping the offending flag would recreate the unreachable-switch bug.
 */
function parseDefinition(raw: unknown, index: number): FeatureFlagDefinition {
  const entry = raw as Partial<FeatureFlagDefinition>;
  const where = `contracts/features/feature-flags.json flags[${index}]`;
  if (typeof entry?.key !== 'string' || entry.key.length === 0) {
    throw new Error(`${where}: missing "key".`);
  }
  if (typeof entry.default !== 'boolean') {
    throw new Error(`${where} (${entry.key}): "default" must be a boolean.`);
  }
  if (typeof entry.label !== 'string' || entry.label.trim().length === 0) {
    throw new Error(`${where} (${entry.key}): missing "label".`);
  }
  if (typeof entry.description !== 'string' || entry.description.trim().length === 0) {
    throw new Error(`${where} (${entry.key}): missing "description".`);
  }
  if (!isSensitivity(entry.sensitivity)) {
    throw new Error(
      `${where} (${entry.key}): "sensitivity" must be one of ${SENSITIVITIES.join('|')}.`,
    );
  }
  return {
    key: entry.key,
    default: entry.default,
    label: entry.label,
    description: entry.description,
    sensitivity: entry.sensitivity,
  };
}

/** Every flag the product has, in contract order. */
export const FEATURE_FLAG_DEFINITIONS: readonly FeatureFlagDefinition[] = (() => {
  const flags = (contractJson as { flags?: unknown[] }).flags;
  if (!Array.isArray(flags) || flags.length === 0) {
    throw new Error('contracts/features/feature-flags.json: "flags" must be a non-empty array.');
  }
  const parsed = flags.map(parseDefinition);
  const keys = new Set(parsed.map((flag) => flag.key));
  if (keys.size !== parsed.length) {
    throw new Error('contracts/features/feature-flags.json: duplicate flag key.');
  }
  return parsed;
})();

export const FEATURE_FLAG_KEYS: readonly FeatureFlagKey[] = FEATURE_FLAG_DEFINITIONS.map(
  (flag) => flag.key,
);

const DEFINITIONS_BY_KEY = new Map(FEATURE_FLAG_DEFINITIONS.map((flag) => [flag.key, flag]));

export function isFeatureFlagKey(value: unknown): value is FeatureFlagKey {
  return typeof value === 'string' && DEFINITIONS_BY_KEY.has(value);
}

export function getFeatureFlagDefinition(key: FeatureFlagKey): FeatureFlagDefinition {
  const definition = DEFINITIONS_BY_KEY.get(key);
  if (!definition) throw new Error(`Unknown feature flag key: ${key}`);
  return definition;
}

/** The contract default for one flag. Throws on an unknown key. */
export function getFeatureFlagDefault(key: FeatureFlagKey): boolean {
  return getFeatureFlagDefinition(key).default;
}

/** Contract defaults as a flat map — the offline/fallback view. */
export const DEFAULT_FEATURE_FLAGS: Readonly<Record<FeatureFlagKey, boolean>> = Object.freeze(
  Object.fromEntries(FEATURE_FLAG_DEFINITIONS.map((flag) => [flag.key, flag.default])),
);

export interface FeatureFlagRow extends FeatureFlagDefinition {
  /** Effective value: the Firestore field when set, else the contract default. */
  enabled: boolean;
  /** True when the value comes from Firestore rather than the contract default. */
  overridden: boolean;
}

/**
 * Overlays a stored `config/featureFlags` document onto the contract. A
 * missing or non-boolean field keeps the documented default — flags degrade
 * to their default, never to "off", matching every other reader.
 *
 * Pure: this module never touches Firebase, so the contract-sync test can
 * import it without standing up the SDK.
 */
export function buildFeatureFlagRows(stored: Record<string, unknown>): FeatureFlagRow[] {
  return FEATURE_FLAG_DEFINITIONS.map((definition) => {
    const value = stored[definition.key];
    const overridden = typeof value === 'boolean';
    return {
      ...definition,
      enabled: overridden ? value : definition.default,
      overridden,
    };
  });
}

/** Contract defaults as rows — the offline/fallback view the page paints first. */
export function getFeatureFlagRows(): FeatureFlagRow[] {
  return buildFeatureFlagRows({});
}
