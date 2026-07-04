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
    reason: z.string().trim().min(1).max(500),
    expiresAt: z.string().datetime().nullable().optional(),
  })
  .strict();

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
    'Expected { targetUid, entitlement: none|member_monthly, reason, expiresAt? }.',
  );

// ---------------------------------------------------------------------------
// Document builder
// ---------------------------------------------------------------------------

export interface EntitlementRecordInput {
  userId: string;
  platform: SubscriptionPlatform;
  status: SubscriptionStatus;
  entitlement: SubscriptionEntitlement;
  /** SHA-256 of the purchase token; null for manual grants. */
  purchaseTokenHash: string | null;
  expiresAt: Date | null;
}

/** subscriptions/{uid} document. Never contains a raw token. */
export function buildSubscriptionDocument(
  input: EntitlementRecordInput,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    userId: input.userId,
    platform: input.platform,
    status: input.status,
    entitlement: input.entitlement,
    purchaseTokenHash: input.purchaseTokenHash,
    expiresAt: input.expiresAt,
    updatedAt: serverTimestamp(),
  };
}
