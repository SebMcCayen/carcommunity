/**
 * Unit tests for the feature flags pure logic (shared/featureFlags-core.ts).
 * No emulators required.
 *
 * The sync tests are the point of this file: the canonical key list /
 * defaults in code must exactly match contracts/features/feature-flags.json,
 * and every per-domain FLAG_KEY/FLAG_DEFAULT constant must agree with the
 * canonical table — so the contract, the reader, and the domain gates can
 * never drift apart silently.
 */

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  FEATURE_FLAG_DEFAULTS,
  FEATURE_FLAG_KEYS,
  isFeatureFlagKey,
  parseSetFeatureFlagInput,
} from '../shared/featureFlags-core';
import {
  BILLBOARDS_FLAG_DEFAULT,
  BILLBOARDS_FLAG_KEY,
} from '../billboards/billboards-core';
import {
  CROWN_HUNT_FLAG_DEFAULT,
  CROWN_HUNT_FLAG_KEY,
} from '../crownHunt/crownhunt-core';
import {
  PASS_BY_FLAG_DEFAULT,
  PASS_BY_FLAG_KEY,
} from '../partnerInsights/insights-core';
import {
  PUSH_NOTIFICATIONS_FLAG_DEFAULT,
  PUSH_NOTIFICATIONS_FLAG_KEY,
} from '../notifications/notifications-core';

const contract = JSON.parse(
  readFileSync(resolve(__dirname, '../../../contracts/features/feature-flags.json'), 'utf8'),
) as { flags: Array<{ key: string; default: boolean }> };

describe('featureFlags-core contract sync', () => {
  it('mirrors contracts/features/feature-flags.json exactly (keys and defaults)', () => {
    const contractDefaults = Object.fromEntries(contract.flags.map((f) => [f.key, f.default]));
    expect({ ...FEATURE_FLAG_DEFAULTS }).toEqual(contractDefaults);
  });

  it('agrees with every per-domain flag constant', () => {
    for (const [key, fallback] of [
      [CROWN_HUNT_FLAG_KEY, CROWN_HUNT_FLAG_DEFAULT],
      [PASS_BY_FLAG_KEY, PASS_BY_FLAG_DEFAULT],
      [BILLBOARDS_FLAG_KEY, BILLBOARDS_FLAG_DEFAULT],
      [PUSH_NOTIFICATIONS_FLAG_KEY, PUSH_NOTIFICATIONS_FLAG_DEFAULT],
    ] as const) {
      expect(isFeatureFlagKey(key)).toBe(true);
      expect(FEATURE_FLAG_DEFAULTS[key]).toBe(fallback);
    }
  });

  it('keeps the 9j privacy gate default OFF', () => {
    expect(FEATURE_FLAG_DEFAULTS.partnerInsightsPassBy).toBe(false);
  });
});

describe('featureFlags-core input validation', () => {
  it('accepts only canonical keys and boolean values', () => {
    expect(parseSetFeatureFlagInput({ key: 'chat', enabled: false }).ok).toBe(true);
    expect(
      parseSetFeatureFlagInput({ key: 'chat', enabled: false, reason: 'Incident 42' }).ok,
    ).toBe(true);
    // Closed namespace: typos can never create phantom flags.
    expect(parseSetFeatureFlagInput({ key: 'chatt', enabled: false }).ok).toBe(false);
    expect(parseSetFeatureFlagInput({ key: 'chat', enabled: 'false' }).ok).toBe(false);
    expect(parseSetFeatureFlagInput({ key: 'chat' }).ok).toBe(false);
    expect(parseSetFeatureFlagInput({ key: 'chat', enabled: true, extra: 1 }).ok).toBe(false);
    expect(
      parseSetFeatureFlagInput({ key: 'chat', enabled: true, reason: 'x'.repeat(501) }).ok,
    ).toBe(false);
    // Prototype pollution names are not keys.
    expect(isFeatureFlagKey('__proto__')).toBe(false);
    expect(isFeatureFlagKey('constructor')).toBe(false);
  });

  it('exposes every canonical key exactly once', () => {
    expect(new Set(FEATURE_FLAG_KEYS).size).toBe(contract.flags.length);
  });
});
