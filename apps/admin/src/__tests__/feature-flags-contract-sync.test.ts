/**
 * Feature-flag contract sync — the test whose absence was the real bug.
 *
 * `crownHuntSpawn` shipped to the backend and to the Android client while the
 * admin portal derived its rows from a frozen nine-key array in
 * `packages/shared`. Nothing failed: the kill switch for the Kronjakt
 * auto-spawn engine simply was not on the page, so it could only have been
 * turned off by a deploy.
 *
 * These tests assert SET EQUALITY, in both directions, between:
 *   - contracts/features/feature-flags.json (canonical),
 *   - functions/src/shared/featureFlags-core.ts (what the backend honours),
 *   - apps/android/.../config/FeatureFlags.kt (what the client honours), and
 *   - the rows this app actually renders (`getFeatureFlagRows()`).
 * A flag missing on any side fails, and so does a stale extra one.
 *
 * The backend and Android tables are read as SOURCE TEXT (`?raw`) rather than
 * imported: functions runs its own dependency tree (zod, firebase-admin) that
 * this workspace deliberately does not install, and Kotlin obviously cannot be
 * imported at all. The parsers throw with the offending path when the shape
 * they expect is gone, so a refactor that moves the table fails loudly here
 * instead of quietly asserting nothing.
 */

import { describe, expect, it } from 'vitest';

import contractRaw from '../../../../contracts/features/feature-flags.json?raw';
import backendRaw from '../../../../functions/src/shared/featureFlags-core.ts?raw';
import androidRaw from '../../../../apps/android/app/src/main/java/com/kungsbackacarcommunity/app/config/FeatureFlags.kt?raw';
// The pure registry module the page's rows are built from — imported directly
// so this suite needs no Firebase SDK stand-up.
import { FEATURE_FLAG_DEFINITIONS, getFeatureFlagRows } from '@/features/feature-flags/contract';

const BACKEND_PATH = 'functions/src/shared/featureFlags-core.ts';
const ANDROID_PATH =
  'apps/android/app/src/main/java/com/kungsbackacarcommunity/app/config/FeatureFlags.kt';

type Defaults = Record<string, boolean>;

/** The contract, parsed from its own text — not via the app's typed import. */
function readContractDefaults(): Defaults {
  const parsed = JSON.parse(contractRaw) as {
    flags: Array<{ key: string; default: boolean }>;
  };
  return Object.fromEntries(parsed.flags.map((flag) => [flag.key, flag.default]));
}

/** Isolates the table body a parser expects, or fails with where to look. */
function extractBlock(source: string, pattern: RegExp, where: string, what: string): string {
  const body = pattern.exec(source)?.[1];
  if (!body) {
    throw new Error(
      `Could not find ${what} in ${where}. If the canonical table moved, point this ` +
        'parser at its new home — do not delete the assertion.',
    );
  }
  return body;
}

/** Collects `key -> boolean` pairs, where group 1 is the key and 2 the value. */
function collectFlags(body: string, pattern: RegExp, where: string): Defaults {
  const defaults: Defaults = {};
  for (const match of body.matchAll(pattern)) {
    const key = match[1];
    if (key) defaults[key] = match[2] === 'true';
  }
  if (Object.keys(defaults).length === 0) throw new Error(`No flag entries parsed from ${where}.`);
  return defaults;
}

/** `FEATURE_FLAG_DEFAULTS = { key: boolean, ... }` from the backend source. */
function readBackendDefaults(): Defaults {
  const body = extractBlock(
    backendRaw,
    /export const FEATURE_FLAG_DEFAULTS = \{([\s\S]*?)\} as const;/,
    BACKEND_PATH,
    'the FEATURE_FLAG_DEFAULTS object literal',
  );
  return collectFlags(body, /^\s*(\w+):\s*(true|false),/gm, BACKEND_PATH);
}

/** `KEY("flagKey", default)` enum entries from the Android source. */
function readAndroidDefaults(): Defaults {
  const body = extractBlock(
    androidRaw,
    /enum class FeatureFlag\([\s\S]*?\{([\s\S]*?)\n\}/,
    ANDROID_PATH,
    'the FeatureFlag enum body',
  );
  return collectFlags(body, /\w+\("(\w+)",\s*(true|false)\)/g, ANDROID_PATH);
}

/** What the admin page actually puts on screen, keyed by flag. */
function adminRenderedDefaults(): Defaults {
  return Object.fromEntries(getFeatureFlagRows().map((row) => [row.key, row.default]));
}

const contractDefaults = readContractDefaults();
const backendDefaults = readBackendDefaults();
const androidDefaults = readAndroidDefaults();

describe('feature flags — admin renders exactly the backend set', () => {
  it('renders one row per backend flag, and no extras', () => {
    // toEqual on the full maps: a flag the backend honours but the admin
    // cannot reach fails here, and so does an admin-only phantom flag.
    expect(adminRenderedDefaults()).toEqual(backendDefaults);
  });

  it('renders crownHuntSpawn — the Kronjakt auto-spawn kill switch', () => {
    // Named explicitly: this is the flag that was unreachable, and it is the
    // one an operator is most likely to come looking for in a hurry.
    const row = getFeatureFlagRows().find((candidate) => candidate.key === 'crownHuntSpawn');
    expect(row, 'crownHuntSpawn must be operable from the admin console').toBeDefined();
    expect(row?.sensitivity).toBe('safety');
  });

  it('matches the contract, the backend and the Android client key-for-key', () => {
    expect(contractDefaults).toEqual(backendDefaults);
    expect(androidDefaults).toEqual(backendDefaults);
    expect(adminRenderedDefaults()).toEqual(contractDefaults);
  });
});

describe('feature flags — defaults are not drifting', () => {
  it('keeps crownHuntSpawn OFF everywhere', () => {
    // Making the switch reachable must never make it hot.
    expect(contractDefaults.crownHuntSpawn).toBe(false);
    expect(backendDefaults.crownHuntSpawn).toBe(false);
    expect(androidDefaults.crownHuntSpawn).toBe(false);
    expect(adminRenderedDefaults().crownHuntSpawn).toBe(false);
  });

  it('keeps the 9j privacy gate OFF', () => {
    expect(adminRenderedDefaults().partnerInsightsPassBy).toBe(false);
    expect(backendDefaults.partnerInsightsPassBy).toBe(false);
  });

  it('pins the exact default set, so a flip cannot ride along unnoticed', () => {
    expect(adminRenderedDefaults()).toEqual({
      liveLocation: true,
      chat: true,
      crownHunt: true,
      crownHuntSpawn: false,
      partners: true,
      partnerStats: true,
      pushNotifications: true,
      socialSharing: true,
      externalDataSources: true,
      digitalBillboards: true,
      partnerInsightsPassBy: false,
      crownHuntPerks: false,
      reportTicketsBrowser: false,
      chatReplies: false,
    });
  });
});

describe('feature flags — every flag is explained to the operator', () => {
  it('has a label and a description for each flag', () => {
    for (const definition of FEATURE_FLAG_DEFINITIONS) {
      expect(definition.label.trim().length, `${definition.key} needs a label`).toBeGreaterThan(0);
      // Long enough to say what turning it off stops, not just restate the key.
      expect(
        definition.description.trim().length,
        `${definition.key} needs a description`,
      ).toBeGreaterThan(20);
      expect(definition.label).not.toBe(definition.key);
    }
  });

  it('marks the flags that place map content people drive to as safety-relevant', () => {
    const bySensitivity = (sensitivity: string) =>
      FEATURE_FLAG_DEFINITIONS.filter((flag) => flag.sensitivity === sensitivity).map(
        (flag) => flag.key,
      );
    expect(bySensitivity('safety')).toEqual(['crownHunt', 'crownHuntSpawn']);
    expect(bySensitivity('privacy')).toEqual(['partnerStats', 'partnerInsightsPassBy']);
  });
});
