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
 *
 * `crownHuntPerks` is default OFF for a different reason: it is the Kronjakt
 * SHOP switch — the FIRST member-facing Kronpoäng SINK (buying perks with KP).
 * It stays off until the shop is deliberately turned on; while off, buyPerk
 * rejects and members spend nothing.
 *
 * `crownHuntLiveShareScoring` is default OFF: it switches on the live-share
 * SCORING rule — a crown collected while the member is NOT sharing a live
 * session pays only half its Kronpoäng, while a crown collected during an
 * active live session pays full. It is fail-open: the halving applies ONLY when
 * the backend can CONFIRM the member is not live-sharing (an active session, or
 * any read error, awards full), so a sharer is never wrongly penalised. Gates
 * BOTH the backend multiplier AND the Android UI copy (the instructions line
 * and the popup tip), so the UI never describes a rule that is not in force.
 * While off the multiplier is always 1 and the app shows nothing about it.
 *
 * `reportTicketsBrowser` is default OFF: it switches on the in-app "open
 * tickets" browser on the Report-a-problem page — reading the `openTickets`
 * Firestore mirror of public GitHub issues and letting a member +1 or comment
 * on one (feedback.interactWithIssue). While off, the callable rejects
 * (failed-precondition) so no comment is ever posted to the public repo, and
 * the client draws no ticket list. Stays dark until the browser UI ships and is
 * deliberately turned on.
 *
 * `chatReplies` is default OFF: it switches on inline WhatsApp-style reply-to-
 * message across the chat surfaces (community, convoy, and DMs). While off the
 * backend IGNORES any `replyToMessageId` a client sends — no parent is read, no
 * `replyTo` snapshot is stored, and the Android reply entry point stays hidden —
 * so the feature is dark end-to-end until it is deliberately turned on. It only
 * gates PROCESSING of the optional field; an ordinary message is unaffected.
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
  crownHuntPerks: false,
  crownHuntLiveShareScoring: false,
  reportTicketsBrowser: false,
  chatReplies: false,
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
