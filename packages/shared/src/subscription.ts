/**
 * Shared subscription entitlement contract used across API, mobile, and admin.
 *
 * Backend is the source of truth for all subscription and entitlement decisions.
 * Client-side checks are for user experience only.
 *
 * MVP entitlements: none | member_monthly
 * No annual plan, no supporter tier, no multiple membership levels in MVP.
 */

import type { SubscriptionEntitlement } from './users.js';

// ---------------------------------------------------------------------------
// Platform
// ---------------------------------------------------------------------------

export const SUBSCRIPTION_PLATFORMS = ['apple', 'google', 'manual'] as const;
export type SubscriptionPlatform = (typeof SUBSCRIPTION_PLATFORMS)[number];

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

export const SUBSCRIPTION_STATUSES = [
  'inactive',
  'active',
  'grace_period',
  'expired',
  'revoked',
  'cancelled',
] as const;
export type SubscriptionStatus = (typeof SUBSCRIPTION_STATUSES)[number];

// ---------------------------------------------------------------------------
// Route paths
// ---------------------------------------------------------------------------

export const SUBSCRIPTION_ROUTE_PATHS = {
  me: '/v1/subscription/me',
  refreshPlaceholder: '/v1/subscription/refresh-placeholder',
} as const;

export function buildAdminUserSubscriptionPath(userId: string): string {
  return `/v1/admin/users/${userId}/subscription`;
}

// ---------------------------------------------------------------------------
// Contracts
// ---------------------------------------------------------------------------

/**
 * Safe, provider-agnostic subscription summary.
 * Never exposes raw provider tokens or sensitive receipt data.
 */
export interface SubscriptionSourceSummary {
  platform: SubscriptionPlatform;
  status: SubscriptionStatus;
  entitlement: SubscriptionEntitlement;
  startsAt: string | null;
  expiresAt: string | null;
}

/**
 * Response for GET /v1/subscription/me
 * Returns the current effective entitlement and a safe subscription summary.
 */
export interface CurrentEntitlementResponse {
  ok: true;
  data: {
    entitlement: SubscriptionEntitlement;
    subscription: SubscriptionSourceSummary | null;
  };
}

/**
 * Summary of which member features the current entitlement unlocks.
 * Used for display purposes on the client — backend enforces independently.
 */
export interface EntitlementFeatureAccessSummary {
  canViewOtherLiveLocations: boolean;
  canViewEventDetails: boolean;
  canRsvpToEvents: boolean;
}

/**
 * Admin-facing subscription summary for a specific user.
 * Never exposes raw provider tokens or full external identifiers.
 */
export interface AdminUserSubscriptionSummary {
  userId: string;
  entitlement: SubscriptionEntitlement;
  subscription: SubscriptionSourceSummary | null;
  /** True if the user is suspended while still holding an active subscription. */
  isSuspendedWithActiveSubscription: boolean;
}

export interface AdminUserSubscriptionResponse {
  ok: true;
  data: {
    subscription: AdminUserSubscriptionSummary;
  };
}

/**
 * Placeholder response for POST /v1/subscription/refresh-placeholder.
 * This endpoint is not production-ready and returns a safe non-functional response.
 */
export interface SubscriptionRefreshPlaceholderResponse {
  ok: true;
  data: {
    /** Indicates this is a placeholder endpoint only — not for production use. */
    _placeholder: true;
    message: string;
  };
}

// ---------------------------------------------------------------------------
// Helper functions
// ---------------------------------------------------------------------------

/**
 * Returns true if the subscription status grants or continues access.
 * grace_period is included to allow brief continuation after expiry.
 */
export function isSubscriptionActiveStatus(status: SubscriptionStatus): boolean {
  return status === 'active' || status === 'grace_period';
}

/**
 * Returns the effective entitlement from a list of subscription records.
 * Prefers the first record with an active or grace_period status.
 * Returns 'none' if no active record exists.
 */
export function getEffectiveEntitlement(
  records: SubscriptionSourceSummary[],
): SubscriptionEntitlement {
  const activeRecord = records.find((r) => isSubscriptionActiveStatus(r.status));
  return activeRecord?.entitlement ?? 'none';
}
