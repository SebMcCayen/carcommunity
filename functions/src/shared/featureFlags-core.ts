/**
 * Feature flags — canonical key list, contract defaults, and input
 * validation (Phase 9m). Pure module — no Firebase Admin SDK imports.
 *
 * The flag state lives in ONE flat Firestore document,
 * `config/featureFlags`, holding a camelCase boolean field per flag key —
 * the shape every flag-gated domain has read since Phase 9h. Keys and
 * defaults mirror contracts/features/feature-flags.json (a unit test keeps
 * the two in sync). Writes go exclusively through the audited
 * `admin.setFeatureFlag` callable; clients get authenticated read access
 * via security rules and fall back to the contract defaults when the
 * document (or a field) is absent — flags degrade to their documented
 * defaults, never to "off". Every other document under `config/` (e.g.
 * `config/partnerInsights`) stays backend-only.
 */

import { z } from 'zod';

/**
 * Canonical flag keys and contract defaults
 * (contracts/features/feature-flags.json). `partnerInsightsPassBy` is the
 * Phase 9j privacy gate: default OFF, and contributions additionally
 * require the user's explicit opt-in.
 *
 * `crownHuntSpawn` is the other default-OFF flag, and for a related reason:
 * it switches on a system that PLACES MAP CONTENT BY ITSELF and invites members
 * to travel to it and stop. Hand-placed Kronjakt points (`crownHunt`) each
 * carry a named admin's safe-location confirmation; an auto-spawned crown
 * cannot, so it stays dark until someone deliberately turns it on AND has
 * approved the areas it may use (`crownSpawnCells`).
 */
export const FEATURE_FLAG_DEFAULTS = {
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
} as const;

export type FeatureFlagKey = keyof typeof FEATURE_FLAG_DEFAULTS;

export const FEATURE_FLAG_KEYS = Object.keys(FEATURE_FLAG_DEFAULTS) as FeatureFlagKey[];

export function isFeatureFlagKey(value: unknown): value is FeatureFlagKey {
  return typeof value === 'string' && Object.hasOwn(FEATURE_FLAG_DEFAULTS, value);
}

export const SET_FLAG_REASON_MAX_LENGTH = 500;

const setFeatureFlagInputSchema = z
  .object({
    key: z.string().refine(isFeatureFlagKey, { message: 'Unknown feature flag key.' }),
    enabled: z.boolean(),
    reason: z.string().trim().min(1).max(SET_FLAG_REASON_MAX_LENGTH).optional(),
  })
  .strict();

export interface SetFeatureFlagInput {
  key: FeatureFlagKey;
  enabled: boolean;
  reason?: string;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseSetFeatureFlagInput(data: unknown): ParseResult<SetFeatureFlagInput> {
  const result = setFeatureFlagInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: `Expected { key: ${FEATURE_FLAG_KEYS.join('|')}, enabled: boolean, reason? }.`,
    };
  }
  return { ok: true, input: result.data as SetFeatureFlagInput };
}
