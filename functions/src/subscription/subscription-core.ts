/**
 * Subscription domain — constants, entitlement logic, and builders
 * (Phase 11).
 *
 * Ports packages/shared/src/subscription.ts and the legacy
 * subscription-service invariants to the Firestore model:
 *
 * - `subscriptions/{uid}` — the entitlement record. Written by Cloud
 *   Functions ONLY, after verification (owner read; no client writes,
 *   not even admin clients).
 * - Raw purchase tokens are NEVER stored, logged, or returned — only the
 *   SHA-256 hash (legacy security requirement).
 * - Suspension and deletion always override entitlement (enforced by
 *   shared/access.ts, unchanged here).
 * - Store receipt verification (Apple App Store Server API / Google Play
 *   Developer API) was a PLACEHOLDER in legacy too; the real adapters
 *   land with the end-of-MVP console/credentials setup. Until
 *   `config/subscriptionProviders` enables a provider,
 *   subscription.verify fails closed with failed-precondition. The
 *   entitlement application chain (record + users flag + claim) is fully
 *   implemented and reachable via admin.grantEntitlement (platform
 *   `manual`, which the legacy enum already reserved).
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { createHash } from 'node:crypto';
import { z } from 'zod';

export const SUBSCRIPTION_PLATFORMS = ['apple', 'google', 'manual'] as const;
export type SubscriptionPlatform = (typeof SUBSCRIPTION_PLATFORMS)[number];

export const SUBSCRIPTION_STATUSES = [
  'inactive',
  'active',
  'grace_period',
  'expired',
  'revoked',
  'cancelled',
] as const;
export type SubscriptionStatus = (typeof SUBSCRIPTION_STATUSES)[number];

export const SUBSCRIPTION_ENTITLEMENTS = ['none', 'member_monthly'] as const;
export type SubscriptionEntitlement = (typeof SUBSCRIPTION_ENTITLEMENTS)[number];

/** Canonical tier names. Legacy `member_monthly` maps to Plus. */
export const SUBSCRIPTION_TIERS = ['community', 'plus', 'supporter'] as const;
export type SubscriptionTier = (typeof SUBSCRIPTION_TIERS)[number];

export const PLUS_MONTHLY_PRODUCT_ID = 'plus_monthly' as const;
export const SUPPORTER_MONTHLY_PRODUCT_ID = 'supporter_monthly' as const;

export function subscriptionTierForLegacyEntitlement(
  entitlement: SubscriptionEntitlement,
): SubscriptionTier {
  return entitlement === 'member_monthly' ? 'plus' : 'community';
}

/** Resolves effective access; retained lifecycle tier does not override `none`. */
export function resolveSubscriptionTier(input: {
  entitlement: SubscriptionEntitlement;
  tier?: SubscriptionTier | null;
}): SubscriptionTier {
  if (input.entitlement === 'none') return 'community';
  return input.tier ?? 'plus';
}

export function isPaidSubscriptionTier(tier: SubscriptionTier): boolean {
  return tier === 'plus' || tier === 'supporter';
}

/** Compatibility projection used by the existing activeMember flag and claim. */
export function grantsLegacyActiveMember(input: {
  entitlement: SubscriptionEntitlement;
  status: SubscriptionStatus;
  tier?: SubscriptionTier | null;
}): boolean {
  return (
    input.entitlement === 'member_monthly' &&
    isPaidSubscriptionTier(resolveSubscriptionTier(input)) &&
    isSubscriptionActiveStatus(input.status)
  );
}

/** active and grace_period both grant access (legacy). */
export function isSubscriptionActiveStatus(status: SubscriptionStatus): boolean {
  return status === 'active' || status === 'grace_period';
}

/** SHA-256 hex of a raw purchase token — the only stored representation. */
export function hashPurchaseToken(token: string): string {
  return createHash('sha256').update(token, 'utf8').digest('hex');
}

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

const uidSchema = z.string().trim().min(1).max(128);

const verifySubscriptionInputSchema = z
  .object({
    platform: z.enum(['apple', 'google']),
    purchaseToken: z.string().min(1).max(8192),
  })
  .strict();

const grantEntitlementInputSchema = z
  .object({
    targetUid: uidSchema,
    entitlement: z.enum(SUBSCRIPTION_ENTITLEMENTS),
    tier: z.enum(SUBSCRIPTION_TIERS).optional(),
    reason: z.string().trim().min(1).max(500),
    expiresAt: z.string().datetime().nullable().optional(),
  })
  .strict()
  .superRefine((input, ctx) => {
    const inconsistentCommunityGrant =
      input.entitlement === 'member_monthly' && input.tier === 'community';
    // Revocation preserves the stored tier for lifecycle reporting. A caller
    // therefore cannot select a replacement tier while revoking.
    const inconsistentPaidRevoke = input.entitlement === 'none' && input.tier !== undefined;
    if (inconsistentCommunityGrant || inconsistentPaidRevoke) {
      ctx.addIssue({
        code: 'custom',
        path: ['tier'],
        message: 'Tier is inconsistent with the legacy entitlement.',
      });
    }
  });

export type VerifySubscriptionInput = z.infer<typeof verifySubscriptionInputSchema>;
export type GrantEntitlementInput = z.infer<typeof grantEntitlementInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const parseVerifySubscriptionInput = (d: unknown) =>
  parse(
    verifySubscriptionInputSchema,
    d,
    'Expected { platform: apple|google, purchaseToken: string }.',
  );
export const parseGrantEntitlementInput = (d: unknown) =>
  parse(
    grantEntitlementInputSchema,
    d,
    'Expected { targetUid, entitlement: none|member_monthly, tier?: community|plus|supporter, reason, expiresAt? } with a consistent tier.',
  );

// ---------------------------------------------------------------------------
// Document builder
// ---------------------------------------------------------------------------

export interface EntitlementRecordInput {
  userId: string;
  platform: SubscriptionPlatform;
  status: SubscriptionStatus;
  entitlement: SubscriptionEntitlement;
  /** Optional during rolling compatibility; explicit paid tiers survive lifecycle rewrites. */
  tier?: SubscriptionTier;
  /** SHA-256 of the purchase token; null for manual grants. */
  purchaseTokenHash: string | null;
  /** Optional for legacy callers and nullable when the historical start is unknown. */
  startsAt?: Date | null;
  expiresAt: Date | null;
}

/** subscriptions/{uid} document. Never contains a raw token. */
export function buildSubscriptionDocument(
  input: EntitlementRecordInput,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  if (input.entitlement === 'member_monthly' && input.tier === 'community') {
    throw new Error('member_monthly cannot be persisted with the Community tier.');
  }
  // Persist an explicit historical tier even when entitlement is now `none`.
  // Access readers use resolveSubscriptionTier(), which still resolves that
  // lifecycle record to Community. Missing legacy tiers are materialized from
  // the legacy entitlement when this merge-less writer runs.
  const tier = input.tier ?? subscriptionTierForLegacyEntitlement(input.entitlement);
  return {
    userId: input.userId,
    platform: input.platform,
    status: input.status,
    entitlement: input.entitlement,
    tier,
    purchaseTokenHash: input.purchaseTokenHash,
    startsAt: input.startsAt ?? null,
    expiresAt: input.expiresAt,
    updatedAt: serverTimestamp(),
  };
}
